# How Agora Works — A Complete Guide

## 1. Architecture at a Glance

```
┌─────────────────────────────────────────────────┐
│  UI Layer (Compose)                              │
│  ChatApp → MessageList → MessageItem             │
│          → ChatBottomBar                         │
│          → SettingsScreen                        │
├─────────────────────────────────────────────────┤
│  ViewModel Layer                                 │
│  ChatViewModel (state, orchestration)            │
│  GenerationManager (LLM calls, tool loops)       │
├─────────────────────────────────────────────────┤
│  API Layer (7 providers)                         │
│  LlmProvider interface → Flow<StreamEvent>       │
│  BaseOpenAiProvider (template for 4 providers)   │
├─────────────────────────────────────────────────┤
│  Data Layer                                      │
│  Room DB (conversations + messages)              │
│  DataStore (settings, API keys, model list)      │
│  Filesystem (memory .md files)                   │
└─────────────────────────────────────────────────┘
```

**MVVM with a single ViewModel.** `ChatViewModel` owns all app state. It's created via `ChatViewModelFactory` which injects `ChatDao`, `SettingsManager`, and `MemoryManager`. The UI observes `StateFlow`s.

---

## 2. Data Layer

### 2a. Room Database (`ChatDatabase.kt`)

Two tables:

**`conversations` table (`ChatEntity`)**

| Column | Type | Purpose |
|---|---|---|
| `id` | String (PK) | UUID |
| `title` | String | Display name |
| `lastUpdated` | Long | Sort order |
| `selectedBranchesJson` | String? | JSON map of `parentId → chosenChildId` for branch selection |
| `systemPromptId` | String? | Which system prompt is active for this conversation |
| `modelId` | String? | Which model is active |

**`messages` table (`MessageEntity`)**

| Column | Type | Purpose |
|---|---|---|
| `id` | String (PK) | UUID (with prefixes `tool_`/`result_` for synthetic messages) |
| `conversationId` | String (FK) | Parent conversation |
| `parentId` | String? | Parent message — forms the **tree** structure |
| `text` | String | Message body |
| `images` | List\<String\> | Local file paths to processed images |
| `thoughts` | String? | Aggregated thinking/reasoning text |
| `thoughtTitle` | String? | Title extracted from thinking (e.g. `**Analysis**`) |
| `tokenCount` | Int | Total tokens used (from API usage metadata) |
| `status` | Enum | SENDING → THINKING → SUCCESS / STOPPED / ERROR |
| `participant` | Enum | USER or MODEL |
| `timestamp` | Long | Creation time |
| `thoughtTimeMs` | Long? | How long the model spent thinking |
| `modelName` | String? | Which model generated this |
| `toolCallJson` | String? | JSON of `MessageSegment` list (thought + tool segments) |

Key detail: messages form a **tree**, not a linear list. Each message has a `parentId` pointer. When you edit or regenerate, a new sibling is created under the same parent. The "selected path" is determined by `_selectedChildren` which maps `parentId → chosenChildId`.

The database is at version 9, with 7 incremental migrations (v2→v3 through v8→v9). `MessageConverters` handles type conversion for `Participant`, `MessageStatus`, and `List<String>` (with backward compatibility for old `|||` delimiter format).

### 2b. DataStore Settings (`SettingsManager.kt`)

Persists to `settings` DataStore preferences file. Stores:

- `selectedModel` — default model (e.g. `"gemini-1.5-flash"`)
- `availableModels` — JSON map `{ "Google": ["Google:gemini-2.5-flash", ...], "OpenAI": ["OpenAI:gpt-4", ...] }`
- `enabledModels` — which models the user has toggled on
- `modelAliases` — custom display names for models
- `apiKeys` — list of `ApiKeyEntry` (id, name, key, provider)
- `activeApiKeyIds` — which key is selected per provider `{ "Google": "key-uuid", ... }`
- `systemPrompts` — list of `SystemPromptEntry` (id, title, content)
- `activeSystemPromptId` — global default system prompt
- `maxContextWindow` — how many messages to send (default 20)
- `codeExecutionEnabled` / `googleSearchEnabled` / `thinkingEnabled` — bool toggles
- `providerBaseUrls` — custom base URLs per provider (for proxies, Ollama, etc.)
- `titleGenerationEnabled` / `titleGenerationModel` — auto-title settings

All reads use `Flow`; all writes use `dataStore.edit {}`. The ViewModel calls `.stateIn(viewModelScope)` on each flow to create hot `StateFlow`s.

### 2c. Memory Manager (`MemoryManager.kt`)

A file-based "memory" store that the model can manipulate via tool calls:

- **Active memory** (`active_memory.md`) — prepended to every API call's system prompt
- **Saved files** (`memory_db/*.md`) — individual markdown files the model can create, read, edit, delete

Thread-safe via `@Synchronized`. Path traversal protected by canonical path checks.

---

## 3. The ViewModel Layer

### 3a. ChatViewModel (`ChatViewModel.kt`, ~886 lines)

This is the central orchestrator. Its responsibilities:

**Conversation management:**
- `createNewChat()` — clears state, switches to "new chat" mode
- `selectConversation(id)` — loads a conversation, restoring branch selections from `selectedBranchesJson`
- `deleteConversation(id)`, `renameConversation(id, title)`
- `generateTitle(conversationId)` — fires a separate tiny API call (title generator prompt) and updates the conversation title

**Message sending (3 entry points):**
- `sendMessage(text, images)` — new message in conversation
- `regenerate(messageId)` — replace a model response with a new one
- `editMessage(messageId, newText)` — edit a user message, creating a branch

All three follow the same pattern:

1. Compute IDs, create placeholder model message in `_allMessages`
2. Set `_streamingMessage` = placeholder (UI shows streaming bubble)
3. Launch `GenerationManager.generate()` in `generationScope` (IO dispatcher)
4. Pass callbacks: `onStreamUpdate`, `onLoadingChange`, `onGeneratingIdChange`, `onStreamClear`

**Branch switching:**
- `switchBranch(parentId, direction)` — cycle through sibling messages at the same tree level
- Updates `_selectedChildren` which the `messages` StateFlow reads

**The `messages` StateFlow** (lines 156-200) is the heart of the app. It `combine`s three flows:

1. `_allMessages` — all messages in the current conversation (from Room DB Flow)
2. `_streamingMessage` — the currently-streaming message (null when idle)
3. `_selectedChildren` — branch selection map

It walks the tree from the root (parentId=null), following `_selectedChildren` to pick which child to follow at each level. The streaming message **overlays** its corresponding DB message. Synthetic `tool_`/`result_` messages are hidden from the display path. Result: a linear list of `ChatMessage` for the UI.

**Settings delegation** (lines 336-416): ~15 thin wrapper methods that delegate to `SettingsManager`.

**Model syncing** (`fetchAvailableModels`): iterates all 7 providers, calls `provider.fetchModels()`, saves results to DataStore.

### 3b. GenerationManager (`GenerationManager.kt`, ~590 lines)

This is the engine that actually talks to LLMs. Separated from ChatViewModel in the refactoring.

**`generate()` function** — the main pipeline:

```
1. Build message path from DB (walking parentId chain)
2. If regenerating: trim path to exclude the message being replaced
3. Create ProviderConfig with model, system prompt, tools, toggles
4. Call provider.generateResponse(currentPath, config) → Flow<StreamEvent>
5. Collect events:
   - TextChunk → accumulate into totalText
   - ThoughtChunk → accumulate into currentThoughtBuf + totalThoughts
   - ToolCallRequest / ToolCallsRequest → execute tool locally, add to segments
   - UsageUpdate → track token count
   - Error → set error status
6. After each event: call onStreamUpdate() with live ChatMessage (UI updates in real-time)
7. If tool calls were made: enter multi-tool loop (up to 5 rounds)
   - Persist tool_ and result_ messages to DB
   - Call provider.generateResponse() again with updated path
8. In finally: persist final message to DB, call onStreamClear(), stop foreground service
```

**Memory tools** (`buildMemoryTools()`): defines 6 tools the model can call:

- `list_memory_files` — list all .md files in the memory database
- `read_memory_file` — read one or more files by name
- `create_memory_file` — create a new .md file with content
- `edit_memory_file` — edit content and/or rename an existing file
- `delete_memory_file` — delete a file
- `update_active_memory` — replace, append, or prepend to the active memory context

**`executeTool()`**: Parses JSON arguments, dispatches to `MemoryManager` methods.

**Image processing** (`processImages()`): Decodes URIs from the photo picker, resizes to max ~1024px, re-compresses to JPEG at 80% quality, saves to internal storage.

**`buildLiveSegments()`**: Merges flushed thought/tool segments with the current unflushed thought buffer — prevents losing the last thought chunk on persistence.

---

## 4. The API Provider Layer

### 4a. Interface & Types (`LlmProvider.kt`)

```kotlin
interface LlmProvider {
    val name: String
    val defaultBaseUrl: String
    fun generateResponse(messages: List<ChatMessage>, config: ProviderConfig): Flow<StreamEvent>
    suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String>
}
```

**`StreamEvent`** sealed class — the universal output format:

- `TextChunk(text)` — a piece of the model's response
- `ThoughtChunk(thought, title?, signature?)` — a piece of reasoning/thinking
- `ToolCallRequest(id, name, arguments)` — single tool call
- `ToolCallsRequest(calls)` — multiple tool calls in one response
- `UsageUpdate(tokenCount, thoughtsTokenCount)` — token usage metadata
- `Error(message)` — error

**Key design**: All 7 providers produce the same `Flow<StreamEvent>`, so `GenerationManager` only deals with one event type.

### 4b. BaseOpenAiProvider (Template Method Pattern)

Four providers (OpenAI, DeepSeek, Qwen, OpenRouter) share this base class. It handles:

- Building the `OpenAiChatRequest` with common fields (`model`, `messages`, `stream`, `tools`)
- Opening HTTP connection, writing JSON body
- Reading SSE lines (`data: {...}`), parsing JSON, dispatching to `parseDeltaContent()`
- Accumulating tool calls in `pendingToolCalls` map (deduplicates by index)
- Emitting `StreamEvent.ToolCallRequest` / `ToolCallsRequest` on `finish_reason=tool_calls`
- Emitting `UsageUpdate` when usage data arrives
- **Flushing `thinkParser`** after the SSE loop ends (fix for buffered `<think>` content)

Subclasses override:

- `parseDeltaContent()` — how to extract text/thought from a delta
- `customizeRequest()` — add provider-specific fields (reasoning, plugins)
- `getExtraHeaders()` — provider-specific HTTP headers
- `transformSystemPrompt()` — modify system prompt before sending

### 4c. Individual Providers

| Provider | Base | Key Differences |
|---|---|---|
| **OpenAI** | BaseOpenAiProvider | `reasoningEffort = "medium"` for o1/o3; parses `reasoningContent` |
| **DeepSeek** | BaseOpenAiProvider | Uses `thinkParser.feed()` on `delta.content` for `<think>` tags |
| **Qwen** | BaseOpenAiProvider | DashScope base URL; parses `reasoningContent` |
| **OpenRouter** | BaseOpenAiProvider | Timestamp in system prompt; `reasoning` effort field; `reasoningDetails` array; web `plugins`; referer/title headers |
| **Anthropic** | Direct `LlmProvider` | Custom SSE protocol (event:/data: lines); `content_block_start/delta/stop` events; thinking via `budget_tokens`; tool_use/tool_result blocks |
| **Gemini** | Direct `LlmProvider` | Google's `streamGenerateContent` SSE; inline base64 images; `thought` flag; code execution + Google Search as Google-specific tools; different thinking config for Gemini 2.5 vs 3 |
| **Ollama** | Direct `LlmProvider` | Local server; `/api/chat` endpoint; custom `thinking` field; `/api/tags` for model list |

### 4d. Message Conversion (`MessageConverter.kt`)

`convertToOpenAiMessages()` transforms the internal `ChatMessage` tree into OpenAI-format API messages:

- **`tool_` messages** → `assistant` role with `tool_calls` array (one per tool segment) + `reasoning_content` from thought segments
- **`result_` messages** → `tool` role with `tool_call_id` matching the tool
- **Normal messages** → text + base64-encoded images
- Tool call IDs are SHA-256 hashes of `toolName:arguments` — deterministic so multi-turn conversations maintain matching IDs

Anthropic and Gemini have their own conversion logic inline.

### 4e. Streaming Think Tag Parser (`StreamingThinkTagParser.kt`)

A stateful buffer-based parser that handles `<think>...</think>` tags in streaming content. Since SSE chunks can split a tag across multiple events (`<thi` + `nk>`), the parser:

1. Looks for `<think>` opening tag in accumulated buffer
2. Once inside, looks for `</think>` closing tag
3. If a partial tag is found at the end (e.g., `</thi`), buffers it for the next `feed()` call
4. `flush()` emits any remaining buffered content (called when stream ends)

Used by: BaseOpenAiProvider (for DeepSeekProvider, OpenRouterProvider), OllamaProvider.

---

## 5. The UI Layer

### 5a. MainActivity (`MainActivity.kt`)

Entry point. Handles:

- Splash screen setup
- Notification channel creation + permission request
- Database version check (shows error dialog if stored version > current version)
- Creates `MemoryManager`, `SettingsManager`, and `ChatDatabase`
- Sets up the Compose content tree: `AgoraTheme` → `MainNavigation`

### 5b. ChatApp composable (`MainActivity.kt`, line 538)

The main screen with:

- **ModalNavigationDrawer** — chat history sidebar with new chat button, conversation list (long-press for rename/delete/generate title), settings button
- **Scaffold** — top app bar (title, menu icon, system prompt selector, new chat button)
- **AnimatedContent** — switches between "New Chat" welcome screen and `MessageList`
- **MessageList** — LazyColumn of chat bubbles
- **ChatBottomBar** — text input, image picker, model selector, tool toggles, send/stop button
- **Full-screen image viewer** — pinch-to-zoom, pan, double-tap, fling, rubber-band physics
- **Settings overlay** — slides in from right with scrim

### 5c. MessageList (`MessageList.kt`)

A `LazyColumn` rendering each visible message as a `MessageItem`. Computes context window boundaries for the "context rollout" visualization (messages outside the context window are dimmed). Manages extra bottom padding for scroll anchoring during streaming.

### 5d. MessageItem (`MessageItem.kt`, ~1000 lines)

Three visual modes:

- **USER**: Right-aligned bubble with text, images, copy/edit/info actions, branch switcher
- **MODEL**: Left-aligned with thought blocks (expandable), tool call blocks (expandable), markdown rendering with inline LaTeX, status indicators (SENDING spinner, THINKING pulsing dots, STOPPED badge, ERROR banner), context rollout dimming
- **ERROR**: Center error banner

Supports inline editing of user messages (which triggers `editMessage()`), branch switching arrows, and streaming content with debounced re-rendering.

### 5e. ChatBottomBar (`ChatBottomBar.kt`)

The input area at the bottom. Features:

- Expandable text field with custom scrollbar
- Image picker using `PickMultipleVisualMedia` (Android Photo Picker)
- Model selector dropdown
- Tools menu: Code Execution, Web Search, Thinking toggles
- Send FAB (pulsing when loading) / Stop button

### 5f. SettingsScreen (`SettingsScreen.kt`, ~1100 lines)

Three tabs via `HorizontalPager`:

- **General**: API key CRUD, provider base URLs, active provider selector, system prompt CRUD, context window slider, context rollout toggle, title generation toggle + model
- **Models**: Default model selector, sync button, expandable per-provider model lists with enable/disable checkboxes and alias editing
- **Memory**: Active memory editor textarea, saved memories list with view/edit/delete, new file creator

---

## 6. Key Data Flows

### Sending a message (end-to-end):

```
1. User types text + attaches images, taps Send
2. ChatBottomBar calls viewModel.sendMessage(text, images)
3. ChatViewModel:
   a. If new chat: creates ChatEntity in Room
   b. Creates user MessageEntity in Room (status=SUCCESS)
   c. Creates placeholder model MessageEntity (status=SENDING)
   d. Sets _streamingMessage = placeholder
   e. Sets _isLoading = true
   f. Launches generationScope { generationManager.generate(...) }
4. GenerationManager:
   a. Builds message path from DB (walking parentId chain)
   b. Builds memory tools
   c. Starts foreground service
   d. Calls provider.generateResponse(path, config)
   e. Collects TextChunk, ThoughtChunk, etc.
   f. After each event: onStreamUpdate(updatedMsg) → ViewModel updates _streamingMessage → UI recomposes
   g. If tool calls: executes locally, persists tool_/result_ to DB, loops (up to 5 rounds)
   h. In finally: persists final message to DB, onStreamClear() → _streamingMessage = null
5. UI: MessageList observes messages StateFlow, shows streaming bubble with real-time text
6. When done: DB Flow emits new message → _allMessages updates → messages recomputes path → UI shows final message
```

### Branching (message tree):

```
Root (null)
├── User Msg A (id=1, parentId=null)
│   ├── Model Response X (id=2, parentId=1)     ← selectedChildren[1] = 2
│   └── Model Response Y (id=3, parentId=1)     ← regenerate created this sibling
├── User Msg B (id=4, parentId=2)               ← follows selected path
│   └── Model Response Z (id=5, parentId=4)
```

`_selectedChildren` = `{ "1": "2", "4": "5" }` walks → [1, 2, 4, 5]
Switch branch at parentId=1 → `{ "1": "3", "4": "5" }` walks → [1, 3]

### Tool call flow:

```
1. Model's response includes a tool_call (e.g. "read_memory_file")
2. Provider emits ToolCallRequest(id, "read_memory_file", {"name":"notes.md"})
3. GenerationManager:
   a. executeTool("read_memory_file", args) → calls memoryManager.readFile("notes.md")
   b. Creates MessageSegment(type="tool", toolName=..., toolResult=...)
   c. Adds to segments list (visible in UI as tool call block)
4. After stream ends: tool_ message persisted to DB, result_ message persisted
5. Multi-tool loop: re-calls provider.generateResponse() with updated path including tool results
6. Model sees tool result and continues its response
```

---

## 7. Key Design Decisions

- **No HTTP library** — raw `HttpURLConnection` for all providers. Simple but means manual timeout/error handling in every provider.
- **No repository layer** — ViewModel talks directly to DAO and SettingsManager. The `GenerationManager` is a step toward separation but isn't a full repository.
- **Single ViewModel** — the refactoring extracted `GenerationManager` and `SettingsManager`, but `ChatViewModel` still holds most state. No `SettingsViewModel` or per-screen ViewModels.
- **Message tree, not list** — the `parentId` + `_selectedChildren` approach enables branching conversations (different responses to the same prompt) without data duplication.
- **Tool calls are local** — the model can only manipulate its memory files. No code execution sandbox, no web access (except via Google Search in Gemini/OpenRouter).
- **SSE streaming everywhere** — all providers use Server-Sent Events. The app reads lines in a `while(readLine)` loop on an IO dispatcher, parsing `data: {...}` JSON.
- **Model IDs are prefixed** — format is `ProviderName:model-id` (e.g. `OpenAI:gpt-4`). The prefix determines which provider class and API key to use.
