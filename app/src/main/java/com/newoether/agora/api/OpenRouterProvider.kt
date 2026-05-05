package com.newoether.agora.api

import com.newoether.agora.api.util.StreamingThinkTagParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OpenRouterProvider : BaseOpenAiProvider() {
    override val name: String = "Open Router"
    override val defaultBaseUrl: String = "https://openrouter.ai/api/v1"

    override fun transformSystemPrompt(prompt: String?): String? {
        val sdf = SimpleDateFormat("MMMM d, yyyy, HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }
        val timeInfo = "Current Time: ${sdf.format(Date())} (UTC+8)\n\n"
        return timeInfo + (prompt ?: "")
    }

    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        return request.copy(
            reasoning = if (config.thinkingEnabled) OpenAiReasoning(effort = "high") else null,
            plugins = if (config.googleSearchEnabled) listOf(OpenAiPlugin(id = "web")) else null
        )
    }

    override fun getExtraHeaders(config: ProviderConfig): Map<String, String> = mapOf(
        "HTTP-Referer" to "https://github.com/newo-ether/Agora",
        "X-Title" to "Agora"
    )

    override suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        delta.reasoningDetails?.forEach { detail ->
            if (detail.type == "reasoning.text" || detail.type == "text") {
                detail.text?.let {
                    if (it.isNotEmpty()) {
                        val title = Regex("\\*\\*(.*?)\\*\\*").find(it)?.groupValues?.get(1)
                            ?: Regex("(?m)^#+\\s*(.*)$").find(it)?.groupValues?.get(1)
                        emit(StreamEvent.ThoughtChunk(it, title))
                    }
                }
            }
        }
        delta.reasoningContent?.let {
            if (it.isNotEmpty()) {
                val title = Regex("\\*\\*(.*?)\\*\\*").find(it)?.groupValues?.get(1)
                    ?: Regex("(?m)^#+\\s*(.*)$").find(it)?.groupValues?.get(1)
                emit(StreamEvent.ThoughtChunk(it, title))
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) emit(StreamEvent.TextChunk(content))
        }
    }
}
