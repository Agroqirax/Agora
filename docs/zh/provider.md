# API 提供商

打开**设置 → 提供商**配置模型端点和凭据。

## 内置提供商

Agora 包含 OpenAI、Anthropic、Google Gemini、DeepSeek、DashScope/通义千问、OpenRouter、Groq、Ollama 和本地模型。模型目录由各提供商独立维护。

## 自定义提供商

自定义端点可选择 OpenAI 兼容、Google 或 Anthropic 协议。请配置与服务器匹配的 Base URL、凭据和协议。同步过程按所选协议执行；无法发现时可手动添加自定义模型。

## 机密

API Key 保存在偏好设置而非 Room 对话数据库中。`SecretCrypto` 通常使用 Android Keystore AES-256-GCM 封装，但旧明文仍可读取，加密失败时会为避免丢失数据而回退为明文。实际目的地由 Base URL 决定，请仔细核对自定义端点。导出时若选择包含机密，它们在归档内部不加密。
