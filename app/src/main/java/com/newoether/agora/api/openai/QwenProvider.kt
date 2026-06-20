package com.newoether.agora.api.openai

class QwenProvider : BaseOpenAiProvider() {
    override val name: String = "Qwen"
    override val defaultBaseUrl: String = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}
