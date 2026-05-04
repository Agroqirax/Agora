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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class OpenAiErrorResponse(val error: OpenAiError)

@Serializable
internal data class OpenAiError(val message: String, val type: String? = null, val code: String? = null)

class OpenAiProvider : LlmProvider {
    override val name: String = "OpenAI"
    override val defaultBaseUrl: String = "https://api.openai.com/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.openai.com/v1"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = mutableListOf<OpenAiMessage>()
        
        // Add system prompt if present
        if (!config.systemPrompt.isNullOrBlank()) {
            apiMessages.add(
                OpenAiMessage(
                    role = "system",
                    content = listOf(OpenAiContentPart(type = "text", text = config.systemPrompt))
                )
            )
        }

        // Add conversation history
        apiMessages.addAll(limitedPath.flatMap { msg ->
            val entries = mutableListOf<OpenAiMessage>()

            // tool_ messages: assistant turn with tool_calls
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

            // result_ messages carry the tool result
            if (msg.id.startsWith("result_") && msg.toolCall != null) {
                val toolId = "call_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                entries.add(OpenAiMessage(
                    role = "tool",
                    content = listOf(OpenAiContentPart(type = "text", text = msg.toolCall!!.result)),
                    toolCallId = toolId
                ))
                return@flatMap entries
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
                        val mimeType = if (imagePath.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                        parts.add(
                            OpenAiContentPart(
                                type = "image_url",
                                imageUrl = OpenAiImageUrl(url = "data:$mimeType;base64,$base64")
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                }
            }

            if (parts.isEmpty()) {
                parts.add(OpenAiContentPart(type = "text", text = " "))
            }

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
            reasoningEffort = if (modelName.startsWith("o1") || modelName.startsWith("o3")) "medium" else null,
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
            Log.d("AgoraAPI", "[OpenAI] REQ → $baseUrl/chat/completions | model=$modelName | msgs=${apiMessages.size} | thinking=${config.thinkingEnabled} | reasoningEffort=${requestBody.reasoningEffort ?: "none"} | tools=${config.tools?.size ?: 0}")
            Log.d("AgoraAPI", "[OpenAI] BODY: ${requestBodyJson.take(4000)}")
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
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

                            choice?.delta?.let { delta ->
                                delta.reasoningContent?.let { reasoning ->
                                    if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                                        emit(StreamEvent.ThoughtChunk(reasoning))
                                    }
                                }

                                delta.content?.let { content ->
                                    if (content.isNotEmpty()) {
                                        emit(StreamEvent.TextChunk(content))
                                    }
                                }

                                delta.toolCalls?.forEach { tc ->
                                    val existing = if (tc.id != null) pendingToolCalls.values.firstOrNull { it.id == tc.id } else null
                                    val pending = if (existing != null) existing else {
                                        val idx = tc.index ?: pendingToolCalls.size
                                        pendingToolCalls.getOrPut(idx) { PendingToolCall() }
                                    }
                                    if (tc.id != null) pending.id = tc.id
                                    tc.function?.name?.let { pending.name = it }
                                    tc.function?.arguments?.let {
                                        pending.args.append(if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString())
                                    }
                                }
                            }

                            if (choice?.finishReason == "tool_calls" && pendingToolCalls.isNotEmpty()) {
                                val calls = pendingToolCalls.values.filter { it.name.isNotEmpty() }.map {
                                    StreamEvent.ToolCallRequest(it.id, it.name, it.args.toString())
                                }
                                pendingToolCalls.clear()
                                if (calls.size == 1) emit(calls.first())
                                else if (calls.size > 1) emit(StreamEvent.ToolCallsRequest(calls))
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
                }
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error (Code: $responseCode)"
                Log.e("AgoraAPI", "[OpenAI] ERR $responseCode: $errorRaw")
                val errorMessage = try {
                    val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                    "Error ${errorJson.error.code ?: responseCode} (${errorJson.error.type ?: "UNKNOWN"}): ${errorJson.error.message}"
                } catch (e: Exception) {
                    "Error (Code $responseCode): $errorRaw"
                }
                emit(StreamEvent.Error(errorMessage))
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
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "https://api.openai.com/v1"
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
            Log.e("AgoraAPI", "Failed to fetch OpenAI models", e)
            emptyList()
        }
    }
}
