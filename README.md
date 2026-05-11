<div align="center">
  <img src="app/src/main/assets/agora_transparent_large.png" alt="Agora Logo" width="120" />

  # Agora
  
  **Access powerful models without the guardrails of corporate silos.**

  [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-blue.svg)](https://kotlinlang.org/)
</div>

---

Official LLM apps are often heavily restricted, wrapping capable AI models in sanitized, linear, and limited user interfaces. **Agora is different.**

Agora is a fully open-source, BYOK (Bring Your Own Key) Android client designed for power users who want raw, unrestricted access to frontier models. Built natively with Jetpack Compose, it brings desktop-class agentic capabilities to your mobile device, emphasizing user control, privacy, and architectural flexibility.

## Why Agora?

- **No Middlemen:** You connect directly to the API provider. There are no intermediary servers, no hidden telemetry, and no corporate tracking logging your conversations. Your chat history lives locally on your device.
- **Non-Linear Thought:** Human conversation isn't a straight line, and AI interactions shouldn't be either. Agora uses a tree-structured database that allows you to edit past messages, regenerate responses, and seamlessly explore alternative conversation branches without losing your original context.
- **Agentic Workflows:** Native support for tool calling, web search, code execution, memory management, and on-device LLM inference — all directly in the chat interface.

## Features

### Multi-Provider Access
- **8 AI providers** in one app: OpenAI, Anthropic, Google Gemini, DeepSeek, Qwen (DashScope), OpenRouter, Ollama, and on-device Local (GGUF via llama.cpp)
- **BYOK:** Bring your own API keys for every provider — no subscriptions, no middlemen
- Per-provider base URL override for proxies and self-hosted endpoints
- **Multiple API keys per provider** with named aliases for easy rotation

### Agentic Capabilities
- **Tool Calling:** Multi-round tool execution loop — the model can search the web, read/write memory files, search past conversations, and more
- **Web Search:** Built-in Brave, Serper, Tavily, and SearXNG integration. The model decides when to search and the app executes it client-side
- **Code Execution:** Gemini code execution support for running and testing code inline
- **Active Memory:** Persistent context carried across conversations — the model can store and recall information between chats
- **Saved Memory Files:** Model-created and managed markdown files for long-term knowledge storage

### Thinking & Reasoning
- Deep support for reasoning models across providers (OpenAI o1/o3, Anthropic extended thinking, Gemini thinking, DeepSeek-R1, Qwen QwQ, OpenRouter reasoning)
- Streaming think-tag parser renders thought chains in real-time with collapsible UI
- Thinking duration tracking for performance analysis

### On-Device Intelligence
- **Local LLM inference** via llama.cpp — run GGUF models entirely on-device with no network
- **Local embeddings** for on-device semantic search (RAG) over your conversation history
- **Ollama** provider for self-hosted models on your local network

### Knowledge Management
- **RAG-powered semantic search** across all past conversations using cosine similarity
- Configurable similarity threshold and embedding model selection (remote or local)
- **Context window management** with real-time token counting and configurable sliding window
- Visual context rollout indicator shows which messages are in/out of window

### Customization
- **System prompt templates** with multi-tab editor (system prompt + user prepend/postpend)
- Variable substitution: `{sent_time}`, `{sent_date}`, and future-proof variable system
- Per-conversation model and system prompt switching on the fly
- Per-message model selection from the chat bottom bar
- **Auto title generation** for conversations

### UI & UX
- Modern Material 3 design built entirely in Jetpack Compose
- **Non-linear branching:** Edit any past message and branch into alternative conversation paths
- Real-time streaming with precise message anchoring and animated auto-scrolling
- Immersive gesture-driven image viewer
- Markdown rendering with syntax highlighting, LaTeX math, and code block support
- Image, video, and file attachment support with thumbnails
- English and Chinese (中文) language support

## Screenshots

<div align="center">
  <img src="assets/screenshot.jpg" alt="Agora Chat Screen" width="350"/>
</div>

## Getting Started

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (Ladybug or newer recommended)
- Android SDK 34+
- A valid API key from a supported provider (Google Gemini, OpenAI, Anthropic, etc.)

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/newo-ether/Agora.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Build and run the app on an emulator or a physical Android device.

### Configuration

1. Launch Agora on your device.
2. Open **Settings** from the bottom navigation bar.
3. Select a **Provider** and add your **API Key**.
4. Browse and enable models under **Models** → "Sync from All Providers."
5. Customize system prompts, context limits, web search, and memory settings to your preference.

### Running Local Models

1. Place a GGUF model file on your device.
2. In Settings → Provider → Local, tap "Import GGUF Model" and select the file.
3. Configure context size, temperature, and other parameters.
4. Select your local model from the chat model picker.

## Tech Stack

Agora is built for performance and maintainability using modern Android architecture:
- **Language:** [Kotlin](https://kotlinlang.org/)
- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)
- **Architecture:** MVVM (Model-View-ViewModel)
- **Concurrency:** Kotlin Coroutines & Flow
- **Local Storage:** [Room Database](https://developer.android.com/training/data-storage/room) with tree-structured message schema & DataStore Preferences
- **Networking:** Native `HttpURLConnection` with Server-Sent Events (SSE) streaming, OkHttp for Ollama
- **Serialization:** `kotlinx.serialization`
- **Native:** llama.cpp via Android NDK (CMake) for on-device LLM inference and embeddings
- **Image Loading:** Coil
- **Markdown:** Multiplatform Markdown Renderer M3
- **Math:** JLaTeXMath-Android

## Contributing

Contributions are welcome! If you'd like to help improve Agora, please feel free to fork the repository, submit pull requests, or open an issue to discuss new features or bug fixes.

## License

This project is open-source and available under the [MIT License](LICENSE).
