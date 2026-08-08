# Conversation Runtime Refactor Baseline

This document freezes the live architecture and migration boundaries for the incremental
conversation-runtime refactor. It describes the protected product baseline at commit `e650af6`.
It is not a claim that the target single-writer runtime already exists.

The frozen product requirements remain authoritative:

- `agentic-loop-and-generation-requirements.md`
- `context-compact-requirements.md`

## 1. Baseline evidence

- Base release commit: `d05fe4c` (`v2.0.0`).
- Protected product baseline: `e650af6` on local branch
  `codex/incremental-runtime-refactor-20260808`.
- Room schema: version 22, six entities: conversations, runs, messages, embeddings,
  tasks, and loops.
- JVM tests: 697 F-Droid + 697 Play, with zero failures, errors, or skipped tests.
- Required `build.ps1`: passed.
- F-Droid release APK: 56,756,416 bytes, SHA-256
  `AD885301D808FB264776868E23188336129D7BA766F101CB6A50319CE58E7A6B`.
- APK contract: package `com.newoether.agora`, version `2.0.0` (30), minSdk 24,
  targetSdk 36, v2 signature verified.
- No device, process-death, lifecycle, real-network, or background-execution validation is
  included in this evidence.

An exact external recovery snapshot was verified before the protection commit. Local harness,
signing, generated, and private files are intentionally outside the product commit.

## 2. Current lifecycle

```text
startup
  -> ConversationRepository.ensureRunRecovery
  -> Room closes orphaned ACTIVE/STOPPING Runs as STOPPED/PROCESS_RECOVERED
  -> generation and automation scheduling may start

foreground Send
  -> Controller prepares payload
  -> queue mutex decides memory queue vs direct slot
  -> conversation-owned Job
  -> per-conversation execution lease
  -> Room creates ACTIVE Run + USER + MODEL placeholder + selections
  -> optional automatic Compact
  -> GenerationManager owns provider/tool continuation until the Run is terminal
  -> slot release and queued-guidance drain

provider/tool continuation
  -> Provider validates request and owns retry/termination proof for one stream
  -> GenerationManager consumes StreamEvents and checkpoints the placeholder
  -> validated complete tool batch executes sequentially
  -> Room atomically appends request + every authoritative result
  -> next provider pass, or final message/Run transaction

Stop
  -> in-memory phase becomes STOPPING and cancellation handles are revoked
  -> coroutine-settled barrier
  -> durable-finalization barrier
  -> release only after both barriers
  -> queued guidance starts a fresh Run after the stopped Run is terminal

Task/Loop
  -> alarm/WorkManager occurrence fence
  -> automation execution gate
  -> task reservation or loop revision/cycle claim
  -> automation-priority conversation lease
  -> foreground-open Controller bridge or headless TaskExecutionEngine
  -> schedule advances only if the occurrence identity is still current

Compact
  -> serialize through the existing Controller/Task boundary
  -> read selected graph and nearest Compact
  -> keep complete tool rounds in the verbatim suffix
  -> summarize prefix with an ephemeral final USER instruction
  -> re-read assumptions and insert a Compact boundary transaction
  -> never delete original messages
```

## 3. Current state definitions

### 3.1 Durable Run status

| State | Active slot | Legal current transitions | Terminal reason |
| --- | --- | --- | --- |
| `ACTIVE` | 1 | pass with pending input → `ACTIVE`; completed pass → `COMPLETED`; Stop → `STOPPING`; provider failure → `FAILED`; recovery → `STOPPED` | none |
| `STOPPING` | 1 | durable Stop finalization → `STOPPED`; provider failure after Stop → `STOPPED`; recovery → `STOPPED` | none |
| `COMPLETED` | null | late events ignored | `MODEL_COMPLETED` |
| `STOPPED` | null | late events ignored | `USER_STOPPED` or `PROCESS_RECOVERED` |
| `FAILED` | null | late events ignored | provider/setup error reason |

`RunLifecycle.reduce` models part of this table, but it is not the authoritative process
orchestrator. It must be incorporated into or explicitly replaced by the new reducer; a second
long-lived shadow model is forbidden.

### 3.2 In-process slot

`ConversationGenerationState` currently has `IDLE`, `ACTIVE`, and `STOPPING` slot phases.
The slot is fenced by UI/persistence tokens and a bound Run id. These guards remain compatibility
protection until the mailbox has taken authority for the corresponding path.

### 3.3 Target state vocabulary

The target reducer must be able to distinguish at least:

```text
Idle, Preparing, Compacting, Streaming, ExecutingTools,
Continuing, Stopping, Finalizing, Terminal
```

Names may change, but provider pass, tool batch, Compact, Stop barriers, and Run terminalization
must not collapse into Boolean combinations.

## 4. State and side-effect writers

| Current owner | Writes | Current fence | Migration consequence |
| --- | --- | --- | --- |
| `ConversationGenerationState` | slot, Job, overlay, tokens, queue, Stop barriers | conversation + tokens + run id | Preserve until each path moves into the mailbox. |
| `MessageGenerationController` | Send/edit/regenerate graph, queue drain, Compact entry, setup failure, release | conversation + token + run id | First foreground migration seam. |
| `GenerationManager` | stream/checkpoint, tools, continuation, terminal messages/Run, notification | captured run id/pass | Must split one Provider pass from whole-Run finalization. |
| `GenerationFinalizer` | durable Stop finalization/retry | run id | Move behind idempotent `FinalizeRun` effect. |
| `TaskExecutionEngine` | headless Run setup, Compact, generation and terminal cleanup | conversation + run id/pass | Remove duplication only after Task/Loop parity. |
| `LoopManager` | occurrence claim/revision/cycle/schedule | revision + fire time + count | Preserve replay fencing; trigger normal Send contract. |
| `TaskManager`/Workers | reservation, execution conversation, occurrence retry/schedule | task + scheduled time + execution id | Preserve deterministic occurrence identity. |
| Providers | retry, semantic stream termination, normalized events | attempt-local closure | Return one identity-bearing pass outcome. |
| ToolProviders | external side effects and progress/result | tool call metadata | Progress is non-authoritative; result needs effect identity. |
| Room transactions | durable Run/message/selection/Compact/task/loop state | SQL preconditions vary | Remain durable source of truth; consolidate domain boundaries. |

This inventory proves that the current implementation is not yet a process-level single writer.
The execution coordinator serializes the main generation lease, but Stop finalization, callbacks,
and state mutations still have multiple authorities.

## 5. Identity and stale-result policy

Every new asynchronous effect and result must carry:

```text
conversationId, runId, pass, effectId
```

The conversation runtime is the only component allowed to accept a result. It rejects:

- a different conversation or Run;
- an older or unexpected pass;
- an effect id that is not currently expected;
- a duplicate effect completion;
- a command that is illegal in the current state;
- any event after a terminal transition.

Existing UI/persistence tokens and Provider-local parser identity are not substitutes for this
contract. They may coexist only during a bounded adapter migration.

## 6. Resource acquisition order

The live orders that constrain migration are:

1. Direct foreground Send: queue mutex → slot claim; release mutex; Job → conversation lease →
   Room transaction.
2. Queue decision: queue mutex → occasional Run read/repair in Room.
3. Queue drain: queue mutex → origin Run read → slot claim; release mutex; Job → conversation
   lease → Room.
4. Automation bridge: automation conversation lease → Controller direct-only Send → join the
   exact Job. Busy must reject; waiting or queueing here can deadlock with a foreground slot owner
   waiting for the same lease.
5. Task execution: task reservation/task lock → conversation lease → Room/provider/tool work.
6. Loop execution: conversation automation lease → short state mutex/Room claim → generation.
7. Stop: in-memory cancellation and coroutine settlement run independently from durable Room
   finalization; neither alone may release the slot.
8. Exclusive import: close automation admission → cancel/quiesce Workers → wait for active
   executions → import transaction.
9. Tool execution is nested inside the conversation lease. Remote Shell jobs can outlive one
   bounded wait, so timeout is not synonymous with process termination.

Target reducer transitions never suspend. The intended order is:

```text
mailbox command -> pure transition -> one effect -> external/Room work
-> identity-bearing result command -> same mailbox
```

Global gates must not call back into a conversation mailbox while holding an order-inverting lock.

## 7. Durable transaction boundaries

The live DAO already provides useful starting points:

- `createConversationRunWithMessages`
- `createRunWithMessages`
- provider/message checkpoint update
- `appendToolRoundToRun`
- `appendGuidanceBatchAndClaimPass`
- `claimPendingRunInputsAndAppendPlaceholder`
- `finishGeneration`
- `finishStoppedGeneration`
- `insertContextCompactBeforeSuffix`
- `removeContextCompact`
- `deleteMessageSubtree`
- `recoverOrphanedRuns`

Each migrated transaction must document its precondition, durable postcondition, duplicate
behavior, stale-Run conditional update, failure atomicity, selection changes, and attachment
ownership. No Room schema rewrite is planned.

## 8. Graph and protocol invariants

1. Room is the durable source of truth; streaming is an overlay.
2. At most one non-terminal Run exists per conversation.
3. Messages belong to exactly one conversation and Run and have stable Run sequence order.
4. Message and Run selections must reference existing nodes on a valid ancestry.
5. Provider pass, tool round, Run, visible assistant aggregate, and overlay are different bounds.
6. A provider pass ending is not a Run ending.
7. Incomplete, unnamed, duplicate, unsafe, or malformed tool calls never execute.
8. A tool request and all authoritative results form one complete atomic protocol round.
9. Provider success requires semantic termination validation.
10. A continuation may end in a complete tool result; strict Compact requests end in an
    ephemeral USER instruction.
11. Stop preserves generated body and queued guidance.
12. Queue guidance stays memory-only until a legal durable boundary.
13. Stop/error guidance enters a new child Run; it never attaches to a terminal Run.
14. Compact never deletes original messages and never cuts a complete tool round.
15. The nearest Compact boundary wins; deleting it reveals the previous boundary naturally.
16. Overlay and Room projection never render the same assistant message twice.
17. Attachment files are removed only after every message/draft reference is gone.
18. Completion notification is idempotent per Run.
19. Delayed automatic title work cannot overwrite a manual title.
20. No private request content, key, host secret, or tool result enters trace/log fixtures.

## 9. Acceptance matrix

| Requirement | Protected baseline | Required migration proof |
| --- | --- | --- |
| Durable source of truth | Room v22 and recovery barrier | Real in-memory Room integration tests. |
| One live durable Run | unique active-slot index and Run invariants | Concurrent transaction/conditional-update tests. |
| One process writer | not satisfied | Mailbox is sole transition authority. |
| Cross-conversation parallelism | coordinator supports it | Runtime tests with two conversations. |
| Stale/duplicate rejection | partial token/Run/pass guards | universal effect identity tests. |
| Stop two-barrier release | protected and unit-tested | mailbox order-permutation tests. |
| Tool atomicity | transaction and protocol normalization exist | Room failure/reorder/duplicate tests. |
| Queue FIFO and memory ownership | protected unit policies | end-to-end Stop/error/attachment tests. |
| Compact graph safety | graph re-read and unit tests | real Room selected-ancestry tests. |
| Recovery | orphan terminalization exists | deterministic snapshot-to-command tests. |
| Notification/title idempotence | partial application guards | explicit delayed/duplicate effect tests. |
| Privacy-safe trace | not implemented | bounded/redacted trace tests. |

The repository has no `androidTest` source tree. Existing repository tests mock `ChatDao`; real
Room integration coverage is a required addition, not an inferred property of the current green
JVM suite.

## 10. Migration and rollback sequence

Each row is an independent semantic commit and rollback boundary:

1. Pure runtime vocabulary, reducer tests, identity envelope, bounded redacted trace.
2. Ordinary foreground Send enters a real per-conversation mailbox.
3. One Provider pass becomes an isolated runner and closed outcome.
4. Stop and both settlement barriers become mailbox commands.
5. Tool batch execution/commit/continuation becomes effects and result commands.
6. Queued guidance and attachment ownership move through the normal Send contract.
7. Loop and Task reuse the same runtime contract.
8. Manual/automatic Compact become serialized runtime effects.
9. Recovery and Room domain transactions become deterministic/idempotent.
10. Remove the superseded legacy writer for each migrated path.

Old guards are not removed merely because new types compile. They are removed only after the new
runtime owns that path, focused tests pass, the complete unit gate passes at major milestones, and
the diff has been re-reviewed. No push, deployment, publication, or Room compatibility rewrite is
part of this migration.
