# API Providers

Open **Settings → Providers** to configure model endpoints and credentials.

## Built-in providers

Agora includes configurations for OpenAI, Anthropic, Google Gemini, DeepSeek, DashScope/Qwen, OpenRouter, Groq, Ollama, and Local models. Availability and provider model catalogs can change independently of the app.

## Custom providers

A custom endpoint can use an OpenAI-compatible, Google, or Anthropic protocol. Configure its base URL, credentials, and protocol to match the server. Model synchronization follows the selected protocol; custom model entries can be added manually when discovery is unavailable.

## Secrets

API keys are stored in preferences rather than the Room conversation database. `SecretCrypto` normally applies an Android Keystore AES-256-GCM envelope, but legacy plaintext remains readable and encryption failure deliberately falls back to plaintext rather than losing the value. They are sent only to the configured destination when needed. Base URLs determine the actual server contacted, so verify custom endpoints carefully.

Optional exports can include secrets, but those secrets are unencrypted inside the archive. See [Import & Export](import-export.md) and [Privacy & Security](privacy.md).
