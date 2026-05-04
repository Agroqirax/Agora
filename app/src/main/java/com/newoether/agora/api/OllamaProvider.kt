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
            if (msg.id.startsWith("tool_") && msg.toolCall != null) {
                val toolId = "call_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                val argsObj = try { json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                val thinkingContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
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
                return@flatMap entries
            }

            // result_ messages carry the tool result
            if (msg.id.startsWith("result_") && msg.toolCall != null) {
                entries.add(OllamaMessage(
                    role = "user",
                    content = msg.toolCall!!.result
                ))
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
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var line: String? = null
                var inThinkingBlock = false
                var pendingContent = "" // Buffer for handling split tags

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
                                }
                            }

                            // 2. Handle tool calls
                            msg.toolCalls?.firstOrNull()?.let { tc ->
                                val id = tc.id ?: "call_0"
                                val name = tc.function?.name ?: ""
                                val args = tc.function?.arguments?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() } ?: ""
                                if (name.isNotEmpty()) {
                                    emit(StreamEvent.ToolCallRequest(id, name, args))
                                    return@let
                                }
                            }

                            // 3. Handle content and potential <think> tags in content
                            if (msg.content.isNotEmpty()) {
                                pendingContent += msg.content
                                
                                while (pendingContent.isNotEmpty() && currentCoroutineContext().isActive) {
                                    if (!inThinkingBlock) {
                                        val startIdx = pendingContent.indexOf("<think>")
                                        if (startIdx != -1) {
                                            val before = pendingContent.substring(0, startIdx)
                                            if (before.isNotEmpty()) emit(StreamEvent.TextChunk(before))
                                            inThinkingBlock = true
                                            pendingContent = pendingContent.substring(startIdx + 7)
                                        } else {
                                            // Look for partial tag at the end
                                            val lastOpenBracket = pendingContent.lastIndexOf('<')
                                            if (lastOpenBracket != -1 && "<think>".startsWith(pendingContent.substring(lastOpenBracket))) {
                                                // We might have a split tag, emit everything before it
                                                val before = pendingContent.substring(0, lastOpenBracket)
                                                if (before.isNotEmpty()) emit(StreamEvent.TextChunk(before))
                                                pendingContent = pendingContent.substring(lastOpenBracket)
                                                break // Wait for more data
                                            } else {
                                                emit(StreamEvent.TextChunk(pendingContent))
                                                pendingContent = ""
                                            }
                                        }
                                    } else {
                                        val endIdx = pendingContent.indexOf("</think>")
                                        if (endIdx != -1) {
                                            val thought = pendingContent.substring(0, endIdx)
                                            if (thought.isNotEmpty() && config.thinkingEnabled) {
                                                emit(StreamEvent.ThoughtChunk(thought, null))
                                            }
                                            inThinkingBlock = false
                                            pendingContent = pendingContent.substring(endIdx + 8)
                                        } else {
                                            // Look for partial closing tag at the end
                                            val lastOpenBracket = pendingContent.lastIndexOf('<')
                                            if (lastOpenBracket != -1 && "</think>".startsWith(pendingContent.substring(lastOpenBracket))) {
                                                val before = pendingContent.substring(0, lastOpenBracket)
                                                if (before.isNotEmpty() && config.thinkingEnabled) {
                                                    emit(StreamEvent.ThoughtChunk(before, null))
                                                }
                                                pendingContent = pendingContent.substring(lastOpenBracket)
                                                break // Wait for more data
                                            } else {
                                                if (config.thinkingEnabled) {
                                                    emit(StreamEvent.ThoughtChunk(pendingContent, null))
                                                }
                                                pendingContent = ""
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (response.done) {
                            // Flush any remaining content
                            if (pendingContent.isNotEmpty()) {
                                if (inThinkingBlock) {
                                    if (config.thinkingEnabled) emit(StreamEvent.ThoughtChunk(pendingContent, null))
                                } else {
                                    emit(StreamEvent.TextChunk(pendingContent))
                                }
                                pendingContent = ""
                            }
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
                emit(StreamEvent.Error("Error $responseCode: $errorRaw"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
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
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "http://localhost:11434"
            val url = URL("$effectiveBaseUrl/api/tags")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<OllamaTagsResponse>(responseText).models.map { it.name }
        } catch (e: Exception) {
            Log.e("AgoraAPI", "Ollama fetch failed: ${e.message}", e)
            emptyList()
        }
    }
}
