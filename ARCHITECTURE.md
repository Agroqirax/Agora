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
   ├── ConversationRepository ── Room (v19)
   ├── SettingsRepository ───── DataStore
   └── MemoryManager / attachment files / export and backup files

AppContainer owns process-scoped dependencies and also exposes the same generation
stack to TaskExecutionEngine for background tasks and loops.
```

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
- `ConversationExecutionCoordinator` prevents foreground and background execution from
  writing the same conversation concurrently.
- UI ownership tokens and run sequence numbers reject stale callbacks from an older run.

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
attachments, and optional transcription. `GenerationManager` owns one provider/tool
round. `GenerationFinalizer` performs terminal persistence and cleanup.

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

Stopping cancels the active run and settles the newest content directly. Pending UI
animation work cannot replay after `STOPPED`. Final persistence is guarded by the run
identity so an older run cannot overwrite a newer branch, retry, or queued send.

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
generation code consumes only normalized events.

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

Room database version 19 contains five entities:

- `conversations`;
- `messages`;
- `embeddings`;
- `tasks`;
- `loops`.

Room stores the conversation graph, selected branches, durable streaming checkpoints,
automation state, and embedding metadata. Migrations are explicit and schema snapshots
are committed under `app/schemas`.

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

Tasks and loops run through `TaskExecutionEngine`, which reuses the process-scoped
provider registry and generation dependencies. WorkManager and alarms are scheduling
entry points; `AutomationExecutionGate` quiesces automation during destructive import,
and `ConversationExecutionCoordinator` serializes execution per conversation.

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
.\deploy.ps1
```

`build.ps1` is the required release gate because it configures the Android SDK and
runs the repository's expected build/test workflow. Both `fdroid` and `play` variants
must remain lint-clean, even when only one flavor is packaged for a particular release.

High-risk changes require focused tests in addition to the full gate:

- provider termination and incremental tool-call parsing;
- generation ownership, queueing, stopping, and checkpointing;
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
