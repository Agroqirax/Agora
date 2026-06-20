package com.newoether.agora.api.openai

class DeepSeekProvider : BaseOpenAiProvider() {
    override val name: String = "DeepSeek"
    override val defaultBaseUrl: String = "https://api.deepseek.com"
    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}
