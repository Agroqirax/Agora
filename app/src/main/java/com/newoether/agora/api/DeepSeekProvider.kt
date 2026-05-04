package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class DeepSeekProvider : LlmProvider {
    override val name: String = "DeepSeek"
    override val defaultBaseUrl: String = "https://api.deepseek.com"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.deepseek.com"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = mutableListOf<OpenAiMessage>()
        if (!config.systemPrompt.isNullOrBlank()) {
            apiMessages.add(OpenAiMessage(role = "system", content = listOf(OpenAiContentPart(type = "text", text = config.systemPrompt))))
        }

        apiMessages.addAll(limitedPath.flatMap { msg ->
            val entries = mutableListOf<OpenAiMessage>()

            // tool_ messages: assistant turn with tool_calls (+ reasoning from thought segments)
            if (msg.id.startsWith("tool_") && msg.toolCall != null) {
                val tc = msg.toolCall!!
                val toolId = "call_${tc.toolName}_${tc.arguments.hashCode().toUInt().toString(16)}"
                val thoughtContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                entries.add(OpenAiMessage(
                    role = "assistant",
                    content = listOf(OpenAiContentPart(type = "text", text = " ")),
                    toolCalls = listOf(OpenAiRequestToolCall(
                        id = toolId,
                        function = OpenAiRequestFunction(name = tc.toolName, arguments = tc.arguments)
                    )),
                    reasoningContent = thoughtContent?.ifEmpty { null }
                ))
                return@flatMap entries
            }

            // result_ messages: tool result
            if (msg.id.startsWith("result_") && msg.toolCall != null) {
                val tc = msg.toolCall!!
                val toolId = "call_${tc.toolName}_${tc.arguments.hashCode().toUInt().toString(16)}"
                entries.add(OpenAiMessage(
                    role = "tool",
                    content = listOf(OpenAiContentPart(type = "text", text = tc.result)),
                    toolCallId = toolId
                ))
                return@flatMap entries
            }

            // Normal message: text + images only
            val parts = mutableListOf<OpenAiContentPart>()
            if (msg.text.isNotEmpty()) parts.add(OpenAiContentPart(type = "text", text = msg.text))
            for (imagePath in msg.images) {
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        parts.add(OpenAiContentPart(type = "image_url", imageUrl = OpenAiImageUrl(url = "data:image/jpeg;base64,$base64")))
                    }
                } catch (e: Exception) {
                    Log.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                }
            }
            if (parts.isEmpty()) parts.add(OpenAiContentPart(type = "text", text = " "))
            entries.add(OpenAiMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = parts
            ))
            entries
        })

        val requestBody = OpenAiChatRequest(
            model = modelName,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            tools = config.tools
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/chat/completions")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), requestBody)
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var inThinkingBlock = false
                var toolCallId = ""
                var toolCallName = ""
                var toolCallArgs = ""

                var line = reader.readLine()
                while (line != null && currentCoroutineContext().isActive) {
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        if (jsonStr == "[DONE]") break

                        try {
                            val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                            val choice = response.choices?.firstOrNull()
                            val delta = choice?.delta

                            delta?.reasoningContent?.let { reasoning ->
                                if (reasoning.isNotEmpty()) {
                                    emit(StreamEvent.ThoughtChunk(reasoning, null))
                                }
                            }

                            delta?.content?.let { content ->
                                if (content.isNotEmpty()) {
                                    var remaining = content
                                    while (remaining.isNotEmpty() && currentCoroutineContext().isActive) {
                                        if (!inThinkingBlock) {
                                            val startIdx = remaining.indexOf("<think>")
                                            if (startIdx != -1) {
                                                val before = remaining.substring(0, startIdx)
                                                if (before.isNotEmpty()) emit(StreamEvent.TextChunk(before))
                                                inThinkingBlock = true
                                                remaining = remaining.substring(startIdx + 7)
                                            } else {
                                                emit(StreamEvent.TextChunk(remaining))
                                                remaining = ""
                                            }
                                        } else {
                                            val endIdx = remaining.indexOf("</think>")
                                            if (endIdx != -1) {
                                                val thought = remaining.substring(0, endIdx)
                                                if (thought.isNotEmpty() && config.thinkingEnabled) {
                                                    emit(StreamEvent.ThoughtChunk(thought, null))
                                                }
                                                inThinkingBlock = false
                                                remaining = remaining.substring(endIdx + 8)
                                            } else {
                                                if (config.thinkingEnabled) {
                                                    emit(StreamEvent.ThoughtChunk(remaining, null))
                                                }
                                                remaining = ""
                                            }
                                        }
                                    }
                                }
                            }

                            delta?.toolCalls?.forEach { tc ->
                                if (tc.id != null) toolCallId = tc.id
                                tc.function?.name?.let { toolCallName = it }
                                tc.function?.arguments?.let {
                                    toolCallArgs += if (it is JsonPrimitive) it.content else it.toString()
                                }
                            }
                            if (choice?.finishReason == "tool_calls" && toolCallName.isNotEmpty()) {
                                emit(StreamEvent.ToolCallRequest(toolCallId, toolCallName, toolCallArgs))
                            }

                            response.usage?.let { usage ->
                                emit(StreamEvent.UsageUpdate(
                                    tokenCount = usage.totalTokens,
                                    thoughtsTokenCount = usage.completionTokensDetails?.reasoningTokens ?: 0
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                        }
                    }

                    try {
                        line = reader.readLine()
                    } catch (e: SocketTimeoutException) {
                        if (!currentCoroutineContext().isActive) break
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e("AgoraAPI", "[DeepSeek] ERR $responseCode: $errorRaw")
                emit(StreamEvent.Error("Error $responseCode: $errorRaw"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error("Error: ${e.localizedMessage}"))
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
            val url = URL("$effectiveBaseUrl/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<OpenAiModelListResponse>(responseText).data.map { it.id }.sorted()
        } catch (e: Exception) {
            Log.e("AgoraAPI", "Failed to fetch DeepSeek models", e)
            emptyList()
        }
    }
}
