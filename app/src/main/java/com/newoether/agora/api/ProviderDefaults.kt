package com.newoether.agora.api

/**
 * Single source of truth for default API base URLs.
 *
 * - [OPENAI_BASE_URL] is the OpenAI-compatible default reused as the fallback for
 *   embedding / image clients that have no per-provider override configured.
 * - [embeddingBaseUrl] maps an embedding *provider name* to its default base URL.
 *   Note these are embedding providers (Mistral / Voyage / SiliconFlow are
 *   embedding-only and have no chat [LlmProvider] implementation), so their
 *   defaults cannot come from provider classes and are owned here instead.
 */
object ProviderDefaults {
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"

    fun embeddingBaseUrl(provider: String): String = when (provider.lowercase()) {
        "openai" -> OPENAI_BASE_URL
        "mistral" -> "https://api.mistral.ai/v1"
        "deepseek" -> "https://api.deepseek.com/v1"
        "qwen" -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        "open router", "openrouter" -> "https://openrouter.ai/api/v1"
        "voyage ai", "voyage" -> "https://api.voyageai.com/v1"
        "siliconflow" -> "https://api.siliconflow.cn/v1"
        "ollama" -> "http://localhost:11434/v1"
        else -> OPENAI_BASE_URL
    }
}
