# Agora Architecture

This document describes the current repository architecture. It intentionally avoids
line counts and exhaustive file inventories because those became stale faster than the
contracts they were meant to explain.

## 1. System shape

Agora is a single-module Android application built with Kotlin, Jetpack Compose,
coroutines, Room, DataStore, WorkManager, OkHttp, and a small native layer.

```text
Compose UI
   │
   ▼
ChatViewModel ── MessageGenerationController ── ConversationStateRegistry
   │                         │
   │                         ▼
   │                 GenerationManager
   │                    │         │
   │                    │         └── ToolProvider implementations
   │                    ▼
   │               LlmProvider implementations
   │
   ├── ConversationRepository ── Room (v22)
   ├── SettingsRepository ───── DataStore
   └── MemoryManager / attachment files / export and backup files
```

AppContainer owns process-scoped dependencies. Foreground-open automation can bridge into the
controller and join the exact generation Job; headless automation currently shares the same
providers and repositories but retains a parallel orchestration adapter in
`TaskExecutionEngine`. Removing that duplication is a staged migration, not current fact.

The project uses manual dependency injection through `di/AppContainer.kt`. Shared
providers, repositories, automation coordinators, and the local inference engine are
created once per process. A foreground `ChatViewModel` and headless automation reuse
those instances instead of building competing stacks.

## 2. Source layout

| Path | Responsibility |
|---|---|
| `app/src/main/java/.../ui` | Compose screens, message rendering, settings, tasks, media |
| `app/src/main/java/.../viewmodel` | Conversation state, message generation, branching, import/export |
| `app/src/main/java/.../api` | Provider protocol adapters and streaming event model |
| `app/src/main/java/.../tool` | Memory, RAG, web, shell, image, and automation tools |
| `app/src/main/java/.../data` | Room, DataStore, backup, import/export, attachment ownership |
| `app/src/main/java/.../automation` | Tasks, loops, scheduling, execution serialization |
| `app/src/main/java/.../service` | Foreground generation and WorkManager entry points |
| `app/src/main/java/.../sandbox` | Shared sandbox interfaces |
| `app/src/fdroid` | PRoot-backed sandbox implementation |
| `app/src/play` | Play flavor implementation without bundled PRoot binaries |
| `app/src/main/cpp` | llama.cpp chat/embedding JNI and PRoot bridge |
| `thirdparty` | Vendored/submodule native dependencies |
| `build-logic` | Android bytecode compatibility transform |
| `server` | Optional Agora-related server components; not part of the APK runtime |
| `docs` | User-facing MkDocs documentation |

There are two store flavors, `fdroid` and `play`. The application currently targets
Android API 36, supports API 24 and newer, and builds arm64 native artifacts.

## 3. State ownership

### 3.1 Conversation state

`ChatViewModel` is the UI-facing coordinator, but generation state is no longer a
single global mutable slot:

- `ConversationStateRegistry` owns one `ConversationGenerationState` per conversation.
- `MessageGenerationController` accepts sends, edits, regenerations, stops, and queue
  transitions.
- `ConversationGenerationMirror` projects only the selected conversation's live state
  into UI flows.
- `ConversationExecutionCoordinator` serializes the main foreground/background generation lease
  per conversation while allowing different conversations to proceed independently.
- UI/persistence tokens and Run/pass guards reject some stale callbacks.

This is a compatibility architecture, not yet a true single-writer runtime. The controller,
generation manager, Stop finalizer, task engine, and Room transactions all retain lifecycle write
responsibilities. The live ownership table, lock order, acceptance matrix, and staged migration are
frozen in `docs/development/conversation-runtime-refactor-baseline.md`.

Room remains the durable source of truth. The live message is an overlay for the
currently selected branch; it does not become a second durable message graph.

### 3.2 Message tree and branches

Messages form a tree through `parentId`. A conversation stores selected child choices
so the UI can derive one visible path. Tool request/result rows are synthetic graph
nodes and are hidden as standalone chat bubbles.

Branch operations must preserve these invariants:

1. A selected path includes the complete synthetic tool/result closure for each turn,
   including parallel result siblings.
2. Forks copy file-backed attachments into fork-owned storage.
3. Deletion removes a file only after Room confirms that no message or draft still
   references it.
4. Orphan cleanup covers normal message storage and `fork-attachments`.

`ConversationBranchPath`, `ConversationForkShareService`, and
`MessageAttachmentCloneSession` implement these rules.

## 4. Generation pipeline

`GenerationRequestBuilder` prepares provider configuration, context, tools, memory,
attachments, and optional transcription. A Provider owns retry and semantic termination for one
stream. `ProviderPassRunner` collects exactly one such stream and closes it as an identity-bearing
`CompletedText`, `CompletedToolCalls`, `Truncated`, `Failed`, or `Cancelled` outcome. Live events
may update the streaming overlay, but `GenerationManager` accepts the exact expected outcome before
completed tool calls can enter execution. The runner adds fail-closed tool metadata validation; it
does not replace or weaken Provider-specific termination validation and retry. `GenerationManager`
still owns multi-pass Provider/tool continuation and normal Run finalization, while
`GenerationFinalizer` owns durable Stop finalization and its retry path.

The migrated conversation-runtime slice is authoritative for ordinary foreground Send placement,
the in-process generation slot, and Stop barriers. Pure `ConversationCommand`, `RunState`, `RunEffect`, and
`ConversationRuntimeReducer` types decide `Idle`/`Preparing`/`Active`/`Stopping`, coroutine settlement,
durable settlement, and slot release. `ConversationGenerationState` retains the legacy per-
conversation monitor, tokens, Job, streams, and UI flows as an adapter, but its former
`SlotPhase` and Stop-barrier Booleans no longer exist. Every Stop-finalization result echoes
conversation, Run, pass, owner, and effect identity; stale and duplicate results are rejected
before either slot or overlay mutation.

Each process-scoped conversation state now also owns a bounded 64-entry sequential command
mailbox.
Foreground Send enters as `SendRequested`: `Idle` becomes `Preparing` and emits one identified
`PersistAcceptedInput`; the Room transaction returns the exact `InputPersisted` command before the
Run becomes `Active`, while a failed transaction returns `InputPersistenceFailed` and retains
ownership until its coroutine settles. Cancellation before the controller receives the direct
claim emits an exact `SendLaunchAbandoned` command, so an unstarted Send cannot strand the slot.
Active guidance still
uses the legacy in-memory queue executor, but its accept/wait/busy/drain-first placement decision is
made by the reducer.

This is not yet the final mailbox architecture: Provider outcomes are still accepted by the
`GenerationManager` adapter rather than the conversation mailbox, and tools, guidance execution,
Compact, automation, Stop delivery, and recovery have not moved into it. A bounded 256-entry runtime
trace records only sequence, conversation-id digest, Run/pass/effect identity, state/command/effect
types, and timestamp.

```text
accepted send
   │
   ├── optional TRANSCRIBING
   ▼
SENDING
   │
   ├── text delta ───────────────► answer segment
   ├── thought delta ────────────► thought segment / THINKING
   ├── tool-call delta ──────────► one live tool segment / TOOL_CALLING
   │                                  │
   │                                  └── completed request queued
   └── provider stream boundary
                                      │
                                      ▼
                                 execute tool(s)
                                      │
                                      ├── streamed progress/output
                                      └── authoritative final result
                                      │
                         another provider round if needed

terminal: SUCCESS | STOPPED | ERROR
```

The visible message status values are:

`TRANSCRIBING`, `SENDING`, `THINKING`, `TOOL_CALLING`, `SUCCESS`, `STOPPED`, and
`ERROR`.

### 4.1 Streaming contract

All provider adapters normalize their wire protocol into `StreamEvent`:

- `TextChunk`
- `ThoughtChunk`
- `ToolCallUpdate`
- `ToolCallRequest` / `ToolCallsRequest`
- `UsageUpdate`
- `Retrying`
- `Error`

`ToolCallUpdate` contains the accumulated name and arguments known at that point and a
stable `streamKey`. The first delta immediately creates the existing
`Calling tool…` segment. Later deltas update that same segment. There is no separate
pre-tool status.

OpenAI-compatible text responses are also inspected incrementally when tools were
offered. Tagged `<tool_call>` payloads and supported bare JSON tool calls are diverted
into the same streaming tool-call path instead of flashing as answer text or being
lost at end-of-stream.

A completed tool request is executed only after the current provider stream reaches
its boundary. This keeps parsing and execution as separate owners and prevents a
terminal chunk, `[DONE]`, EOF, or parallel tool call from racing the collector.

### 4.2 Tool execution contract

`ToolProvider.executeEvents()` emits:

- `TargetResolved` for the concrete execution target;
- `Progress` or `OutputDelta` for bounded user-visible streaming output;
- exactly one `Completed` value as the model-facing result.

One-shot tools inherit an adapter that emits only `Completed`. Tool segment lifecycle
uses the existing states:

```text
CALLING → RUNNING → SUCCEEDED | EMPTY | FAILED | STOPPED | BACKGROUND_RUNNING
```

The UI is updated at most every 120 ms for ordinary stream content and every 80 ms for
tool-call content, with first and terminal changes emitted immediately. Durable
checkpoints are best-effort and cannot cancel a healthy provider stream.

### 4.3 Stop and terminal ownership

Stopping cancels the active Run and preserves queued guidance. The in-process slot enters
`STOPPING` and remains occupied until both the generation coroutine has unwound and the durable
Stop transaction has succeeded. Either barrier may complete first; only the second releases the
slot. Guidance then enters a fresh Run rather than attaching to the terminal Run. Pending UI
animation work cannot replay after `STOPPED`.

## 5. Rendering and interaction

Compose receives immutable `ChatMessage` snapshots. Streaming markdown uses a
latest-wins two-buffer renderer:

- the current snapshot stays fully visible;
- one incoming snapshot is prepared offscreen;
- updates arriving during the 90 ms fade replace a single pending snapshot;
- promotion and alpha reset happen atomically;
- alpha animates in the graphics layer, so markdown subtrees are not recomposed every
  animation frame;
- a terminal update settles immediately, avoiding a delayed flash after Stop.

The streaming message is rendered once. There is no second tail renderer competing
with the Room-backed list.

Haptics follow interaction meaning:

- direct taps and long presses use discrete feedback where appropriate;
- conversation selection and target-load completion remain separate events;
- generation retains a quiet, low-duty continuous feedback pattern;
- ordinary stream ticks, tool state transitions, and terminal cleanup do not stack
  duplicate pulses on top of it.

## 6. Providers and tools

`LlmProvider` implementations cover OpenAI-compatible endpoints, Anthropic, Gemini,
Ollama, and on-device llama.cpp. Provider-specific parsing stays inside each adapter;
generation code consumes only normalized events. `ProviderPassRunner` is the common one-request
closure boundary: its result carries conversation, owner, Run, durable pass, and effect identity,
and malformed or duplicate completed tool metadata cannot authorize execution.

Tool providers are capability-oriented:

- memory file operations;
- conversation search/RAG;
- web search and fetch;
- remote shell and file operations;
- image generation;
- foreground-only automation creation and control.

Provider signatures are opaque protocol state. A segment records the originating
provider, and signatures must never be replayed into another provider protocol.

## 7. Persistence

Room database version 22 contains six entities:

- `conversations`;
- `runs`;
- `messages`;
- `embeddings`;
- `tasks`;
- `loops`.

Room stores Run ancestry/status/pass state, the conversation graph, selected message and Run
branches, durable streaming checkpoints, automation state, and embedding metadata. The unique
`(conversationId, activeSlot)` index prevents two durable live Runs for one conversation.
Migrations are explicit and schema snapshots are committed under `app/schemas`; v16→v17
introduced Runs, and the current chain continues through v22.

DataStore holds user settings, provider/model configuration, encrypted API-key
references, appearance, generation defaults, tool toggles, backup settings, and
per-conversation overrides.

The filesystem holds processed attachments, fork-owned attachment copies, memory
Markdown files, local models, sandbox files, imports, exports, and backups. File
deletion is intentionally reference-aware because older imports or forks can contain
aliased paths.

Automatic title generation uses compare-and-set against the title observed when work
started. A manual rename or a newer generator always wins.

## 8. Automation

WorkManager and alarms are scheduling entry points. `AutomationExecutionGate` quiesces automation
during destructive import, and `ConversationExecutionCoordinator` gives automation waiters
priority while serializing each conversation. A foreground-open Loop delegates to the controller
through a direct-only call and joins its exact Job; headless Tasks/Loops use
`TaskExecutionEngine`, which reuses process-scoped dependencies but currently duplicates parts of
Run setup, Compact, and finalization.

One-shot schedules preserve explicit past dates so validation can reject them. The
scheduler must not silently reinterpret an expired date as next year.

## 9. Native, sandbox, and remote shell

The native layer exposes:

- on-device chat generation;
- local embeddings;
- the F-Droid PRoot bridge.

The F-Droid sandbox runs commands with concurrent output collection and an actual
wall-clock timeout. The shared glob matcher is implemented without API-26-only
`java.nio.file` APIs so the API 24 minimum remains real.

Remote shell traffic uses the Conch protocol, encrypted payloads, host-key trust, and
streamed tool output. Android-compatible Base64 APIs are used on every supported SDK.

## 10. Data portability and recovery

`.agora` export/import supports selective categories. Third-party importers support
ChatGPT and Claude exports. Auto backup uses WorkManager and configurable retention.

Recovery rules:

- non-terminal messages are checkpointed during generation;
- startup recovery repairs interrupted runs without inventing successful output;
- attachment cleanup occurs only after database ownership changes commit;
- fork cloning rolls back newly created files if graph insertion fails.

## 11. Build and regression gates

Use the repository scripts from the project root:

```powershell
.\build.ps1
```

`build.ps1` is the required release gate because it configures the Android SDK and
runs the repository's expected unit-test/build workflow. Deployment is a separate, explicitly
authorized operation and is not part of the architecture or validation gate.

High-risk changes require focused tests in addition to the full gate:

- provider termination and incremental tool-call parsing;
- generation ownership, queueing, stopping, and checkpointing;
- reducer legality, both Stop-barrier orders, and stale/duplicate effect rejection;
- latest-wins UI buffer behavior;
- branch path closure and attachment cloning/deletion;
- task schedule boundary behavior;
- API-24-compatible glob and process timeout behavior.

## 12. Architectural invariants

The following are review blockers:

1. Never create a second visible status for tool-call assembly; stream into the existing
   `Calling tool…` segment.
2. Never execute an incomplete tool call or discard a completed one merely because the
   provider used `stop`, `[DONE]`, or EOF.
3. Never allow two writers to finalize the same conversation/run identity.
4. Never render both a live tail and its Room counterpart.
5. Never restart an in-flight UI transition for every token; newest content wins.
6. Never block stream collection on attachment preprocessing or synchronous process
   output reads when avoidable.
7. Never delete attachment paths without querying remaining Room references.
8. Never overwrite a manual title with delayed automatic title generation.
9. Never use an Android API above `minSdk` without a guard or compatible implementation.
10. Never put a raw private origin address into client code or committed configuration.
11. Never treat Provider-pass completion as Run completion when a tool or guidance continuation
    remains.
12. Never release a stopped Run before both coroutine and durable finalization barriers settle.
13. Never attach queued guidance to a terminal Run or persist it before a legal boundary.
14. Never accept an asynchronous result for an unexpected Run/pass/effect identity.
15. Never delete original messages during Context Compact or split a complete tool round.
