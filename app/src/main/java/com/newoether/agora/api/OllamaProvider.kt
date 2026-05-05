package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.api.util.StreamingThinkTagParser
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true,
    val options: JsonObject? = null,
    val tools: List<ToolDefinition>? = null
)

@Serializable
internal data class OllamaMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
internal data class OllamaStreamResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null
)

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaModelInfo>
)

@Serializable
internal data class OllamaModelInfo(
    val name: String
)

class OllamaProvider : LlmProvider {
    override val name: String = "Ollama"
    override val defaultBaseUrl: String = "http://localhost:11434"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "http://localhost:11434"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = mutableListOf<OllamaMessage>()
        if (!config.systemPrompt.isNullOrBlank()) {
            apiMessages.add(OllamaMessage(role = "system", content = config.systemPrompt))
        }


        apiMessages.addAll(limitedPath.flatMap { msg ->
            val entries = mutableListOf<OllamaMessage>()

            // tool_ messages: assistant turn with tool_calls (and thinking from segments)
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val thinkingContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                if (!toolSegs.isNullOrEmpty()) {
                    val toolCalls = toolSegs.map { seg ->
                        val tid = buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                        val argsObj = try { json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                        OpenAiToolCall(
                            id = tid,
                            type = "function",
                            function = OpenAiFunctionCall(name = seg.toolName ?: "", arguments = argsObj ?: JsonObject(emptyMap()))
                        )
                    }
                    entries.add(OllamaMessage(
                        role = "assistant",
                        content = " ",
                        thinking = thinkingContent?.ifEmpty { null },
                        toolCalls = toolCalls
                    ))
                } else if (msg.toolCall != null) {
                    val toolId = buildToolCallId(msg.toolCall!!.toolName, msg.toolCall!!.arguments)
                    val argsObj = try { json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(OllamaMessage(
                        role = "assistant",
                        content = " ",
                        thinking = thinkingContent?.ifEmpty { null },
                        toolCalls = listOf(OpenAiToolCall(
                            id = toolId,
                            type = "function",
                            function = OpenAiFunctionCall(name = msg.toolCall!!.toolName, arguments = argsObj ?: JsonObject(emptyMap()))
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the tool result(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        entries.add(OllamaMessage(
                            role = "user",
                            content = seg.toolResult ?: ""
                        ))
                    }
                } else if (msg.toolCall != null) {
                    entries.add(OllamaMessage(
                        role = "user",
                        content = msg.toolCall!!.result
                    ))
                }
                return@flatMap entries
            }

            val images = msg.images.mapNotNull { imagePath ->
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                    } else null
                } catch (e: Exception) { null }
            }

            // Normal message: text + images only
            entries.add(OllamaMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = msg.text,
                images = if (images.isNotEmpty()) images else null
            ))
            entries
        })

        val requestBody = OllamaChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            tools = config.tools
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/api/chat")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (config.apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            connection.doOutput = true
            val requestBodyJson = json.encodeToString(OllamaChatRequest.serializer(), requestBody)
            Log.d("AgoraAPI", "[Ollama] REQ → $baseUrl/api/chat | model=${config.modelId} | msgs=${apiMessages.size} | tools=${config.tools?.size ?: 0}")
            Log.d("AgoraAPI", "[Ollama] BODY: ${requestBodyJson.take(4000)}")
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var line: String? = null
                val thinkParser = StreamingThinkTagParser()
                var receivedStructuredThinking = false

                while (currentCoroutineContext().isActive) {
                    try {
                        line = reader.readLine()
                        if (line == null) break
                    } catch (e: java.net.SocketTimeoutException) {
                        if (!currentCoroutineContext().isActive) break
                        continue
                    }
                    try {
                        val response = json.decodeFromString<OllamaStreamResponse>(line)
                        response.message?.let { msg ->
                            // 1. Handle explicit thinking field (Ollama 0.5.4+)
                            msg.thinking?.let { thinking ->
                                if (thinking.isNotEmpty() && config.thinkingEnabled) {
                                    emit(StreamEvent.ThoughtChunk(thinking, null))
                                    receivedStructuredThinking = true
                                }
                            }

                            // 2. Handle tool calls
                            msg.toolCalls?.let { toolCalls ->
                                val calls = toolCalls.mapNotNull { tc ->
                                    val id = tc.id ?: "${Constants.TOOL_CALL_ID_PREFIX}0"
                                    val name = tc.function?.name ?: ""
                                    val args = tc.function?.arguments?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() } ?: ""
                                    if (name.isNotEmpty()) StreamEvent.ToolCallRequest(id, name, args) else null
                                }
                                if (calls.size == 1) emit(calls.first())
                                else if (calls.size > 1) emit(StreamEvent.ToolCallsRequest(calls))
                            }

                            // 3. Handle content: if structured thinking was received, emit content
                            // directly (any <think> tags in content are literal, not semantic).
                            // Otherwise, parse <think> tags as a fallback for older models.
                            if (msg.content.isNotEmpty()) {
                                if (receivedStructuredThinking) {
                                    emit(StreamEvent.TextChunk(msg.content))
                                } else {
                                    thinkParser.feed(
                                        content = msg.content,
                                        thinkingEnabled = config.thinkingEnabled,
                                        onText = { emit(StreamEvent.TextChunk(it)) },
                                        onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                                    )
                                }
                            }
                        }
                        if (response.done) {
                            thinkParser.flush(
                                onText = { emit(StreamEvent.TextChunk(it)) },
                                onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                            )
                            val total = (response.promptEvalCount ?: 0) + (response.evalCount ?: 0)
                            emit(StreamEvent.UsageUpdate(total))
                        }
                    } catch (e: Exception) {
                        Log.e("AgoraAPI", "Parse error: ${e.message}")
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e("AgoraAPI", "[Ollama] ERR $responseCode: $errorRaw")
                val errorMessage = try {
                    val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                    "Error ${errorJson.error.code ?: responseCode} (${errorJson.error.type ?: "UNKNOWN"}): ${errorJson.error.message}"
                } catch (_: Exception) {
                    "Error $responseCode: $errorRaw"
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
                emit(StreamEvent.Error("Error: ${e.localizedMessage}"))
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "http://localhost:11434"
            val url = URL("$effectiveBaseUrl/api/tags")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<OllamaTagsResponse>(responseText).models.map { it.name }
        } catch (e: Exception) {
            Log.e("AgoraAPI", "Ollama fetch failed: ${e.message}", e)
            emptyList()
        }
    }
}
