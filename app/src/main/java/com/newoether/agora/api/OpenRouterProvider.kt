package com.newoether.agora.api


import android.util.Log
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class OpenRouterProvider : LlmProvider {
    override val name: String = "Open Router"
    override val defaultBaseUrl: String = "https://openrouter.ai/api/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl
        val modelName = config.modelId

        val limitedMessages = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else messages

        val apiMessages = mutableListOf<OpenAiMessage>()

        // System prompt + time injection
        val sdf = SimpleDateFormat("MMMM d, yyyy, HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }
        val timeInfo = "Current Time: ${sdf.format(Date())} (UTC+8)\n\n"
        val systemPrompt = timeInfo + (config.systemPrompt ?: "")
        apiMessages.add(OpenAiMessage(role = "system", content = listOf(OpenAiContentPart(type = "text", text = systemPrompt))))

        // Messages with image support
        for (msg in limitedMessages) {
            // tool_ messages: assistant turn with tool_calls
            if (msg.id.startsWith("tool_") && msg.toolCall != null) {
                val toolId = "call_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                val thoughtContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                apiMessages.add(OpenAiMessage(
                    role = "assistant",
                    content = listOf(OpenAiContentPart(type = "text", text = " ")),
                    toolCalls = listOf(OpenAiRequestToolCall(
                        id = toolId,
                        function = OpenAiRequestFunction(name = msg.toolCall!!.toolName, arguments = msg.toolCall!!.arguments)
                    )),
                    reasoningContent = thoughtContent?.ifEmpty { null }
                ))
                continue
            }

            // result_ messages carry the tool result
            if (msg.id.startsWith("result_") && msg.toolCall != null) {
                val toolId = "call_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                apiMessages.add(OpenAiMessage(
                    role = "tool",
                    content = listOf(OpenAiContentPart(type = "text", text = msg.toolCall!!.result)),
                    toolCallId = toolId
                ))
                continue
            }

            // Normal message: text + images only
            val parts = mutableListOf<OpenAiContentPart>()
            if (msg.text.isNotEmpty()) {
                parts.add(OpenAiContentPart(type = "text", text = msg.text))
            }
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
            if (parts.isEmpty()) parts.add(OpenAiContentPart(type = "text", text = ""))
            apiMessages.add(OpenAiMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = parts
            ))
        }

        val requestBody = OpenAiChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            reasoning = if (config.thinkingEnabled) OpenAiReasoning(effort = "high") else null,
            plugins = if (config.googleSearchEnabled) listOf(OpenAiPlugin(id = "web")) else null,
            tools = config.tools
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/chat/completions")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (config.apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            connection.setRequestProperty("HTTP-Referer", "https://github.com/newo-ether/Agora")
            connection.setRequestProperty("X-Title", "Agora")
            connection.doOutput = true
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), requestBody)
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var line: String? = null
                while (currentCoroutineContext().isActive) {
                    try {
                        line = reader.readLine()
                        if (line == null) break
                    } catch (e: java.net.SocketTimeoutException) {
                        if (!currentCoroutineContext().isActive) break
                        continue
                    }
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        if (jsonStr == "[DONE]") break
                        try {
                            val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                            val choice = response.choices?.firstOrNull()

                            var toolCallId = ""
                            var toolCallName = ""
                            var toolCallArgs = ""

                            choice?.delta?.let { delta ->
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
                                delta.content?.let {
                                    if (it.isNotEmpty()) emit(StreamEvent.TextChunk(it))
                                }
                                delta.toolCalls?.forEach { tc ->
                                    if (tc.id != null) toolCallId = tc.id
                                    tc.function?.name?.let { toolCallName = it }
                                    tc.function?.arguments?.let { toolCallArgs += if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() }
                                }
                            }

                            if (choice?.finishReason == "tool_calls" && toolCallName.isNotEmpty()) {
                                emit(StreamEvent.ToolCallRequest(toolCallId, toolCallName, toolCallArgs))
                            }

                            response.usage?.let {
                                emit(StreamEvent.UsageUpdate(
                                    it.totalTokens,
                                    it.completionTokensDetails?.reasoningTokens ?: 0
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                        }
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error (Code: $responseCode)"
                Log.e("AgoraAPI", "[OpenRouter] ERR $responseCode: $errorRaw")
                emit(StreamEvent.Error("Error $responseCode: $errorRaw"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error("Request timed out. The server took too long to respond."))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error("Connection refused. Please check your internet connection or if the service is available."))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error("Network error: Unable to reach the server. Please check your internet connection."))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error("Error: ${e.localizedMessage ?: "An unexpected error occurred."}"))
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val url = URL("${baseUrl?.trimEnd('/') ?: defaultBaseUrl}/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<OpenAiModelListResponse>(responseText).data.map { it.id }.sorted()
        } catch (e: Exception) { emptyList() }
    }
}
