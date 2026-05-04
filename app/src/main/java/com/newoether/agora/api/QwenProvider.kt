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
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class QwenProvider : LlmProvider {
    override val name: String = "Qwen"
    override val defaultBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl
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

            if (msg.id.startsWith("tool_")) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val thoughtContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                if (!toolSegs.isNullOrEmpty()) {
                    val toolCalls = toolSegs.map { seg ->
                        val tid = "call_${seg.toolName}_${(seg.toolArgs ?: "{}").hashCode().toUInt().toString(16)}"
                        OpenAiRequestToolCall(
                            id = tid,
                            function = OpenAiRequestFunction(name = seg.toolName ?: "", arguments = seg.toolArgs ?: "{}")
                        )
                    }
                    entries.add(OpenAiMessage(
                        role = "assistant",
                        content = listOf(OpenAiContentPart(type = "text", text = " ")),
                        toolCalls = toolCalls,
                        reasoningContent = thoughtContent?.ifEmpty { null }
                    ))
                } else if (msg.toolCall != null) {
                    val toolId = "call_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                    entries.add(OpenAiMessage(
                        role = "assistant",
                        content = listOf(OpenAiContentPart(type = "text", text = " ")),
                        toolCalls = listOf(OpenAiRequestToolCall(
                            id = toolId,
                            function = OpenAiRequestFunction(name = msg.toolCall!!.toolName, arguments = msg.toolCall!!.arguments)
                        )),
                        reasoningContent = thoughtContent?.ifEmpty { null }
                    ))
                }
                return@flatMap entries
            }

            if (msg.id.startsWith("result_") && msg.toolCall != null) {
                val toolId = "call_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                entries.add(OpenAiMessage(
                    role = "tool",
                    content = listOf(OpenAiContentPart(type = "text", text = msg.toolCall!!.result)),
                    toolCallId = toolId
                ))
                return@flatMap entries
            }

            // Normal message: text only (no images for Qwen)
            val parts = mutableListOf<OpenAiContentPart>()
            parts.add(OpenAiContentPart(type = "text", text = msg.text))
            entries.add(OpenAiMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = parts
            ))
            entries
        })

        val requestBody = OpenAiChatRequest(
            model = config.modelId,
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
            if (config.apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            connection.doOutput = true
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), requestBody)
            Log.d("AgoraAPI", "[Qwen] REQ → $baseUrl/chat/completions | model=$modelName | msgs=${apiMessages.size} | thinking=${config.thinkingEnabled} | tools=${config.tools?.size ?: 0}")
            Log.d("AgoraAPI", "[Qwen] BODY: ${requestBodyJson.take(4000)}")
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var line: String? = null
                var toolCallId = ""
                var toolCallName = ""
                var toolCallArgs = ""
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

                            choice?.delta?.let { delta ->
                                delta.reasoningContent?.let { reasoning ->
                                    if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                                        emit(StreamEvent.ThoughtChunk(reasoning))
                                    }
                                }
                                delta.content?.let { if (it.isNotEmpty()) emit(StreamEvent.TextChunk(it)) }
                                delta.toolCalls?.forEach { tc ->
                                    if (tc.id != null) toolCallId = tc.id
                                    tc.function?.name?.let { toolCallName = it }
                                    tc.function?.arguments?.let { toolCallArgs += if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() }
                                }
                            }

                            if (choice?.finishReason == "tool_calls" && toolCallName.isNotEmpty()) {
                                emit(StreamEvent.ToolCallRequest(toolCallId, toolCallName, toolCallArgs))
                                toolCallId = ""
                                toolCallName = ""
                                toolCallArgs = ""
                            }

                            response.usage?.let { emit(StreamEvent.UsageUpdate(it.totalTokens)) }
                        } catch (e: Exception) {
                            Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                        }
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e("AgoraAPI", "[Qwen] ERR $responseCode: $errorRaw")
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
