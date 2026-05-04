package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val thinking: AnthropicThinking? = null,
    val tools: List<AnthropicTool>? = null
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentPart>
)

@Serializable
internal data class AnthropicContentPart(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    val source: AnthropicImageSource? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val message: AnthropicMessageInfo? = null,
    val usage: AnthropicUsage? = null,
    val index: Int? = null
)

@Serializable
internal data class AnthropicDelta(
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val type: String? = null
)

@Serializable
internal data class AnthropicContentBlock(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val thinking: String? = null,
    val signature: String? = null
)

@Serializable
internal data class AnthropicMessageInfo(
    val usage: AnthropicUsage? = null
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null
)

class AnthropicProvider : LlmProvider {
    override val name: String = "Anthropic"
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = limitedPath.flatMap { msg ->
            val entries = mutableListOf<AnthropicMessage>()

            // tool_ messages: assistant turn with tool_use (thinking blocks omitted — signatures unavailable in streaming)
            if (msg.id.startsWith("tool_") && msg.toolCall != null) {
                val toolId = "tool_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                entries.add(AnthropicMessage(
                    role = "assistant",
                    content = listOf(AnthropicContentPart(
                        type = "tool_use",
                        id = toolId,
                        name = msg.toolCall!!.toolName,
                        input = try { json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject ?: JsonObject(emptyMap()) } catch (_: Exception) { JsonObject(emptyMap()) }
                    ))
                ))
                return@flatMap entries
            }

            // result_ messages carry the tool_result
            if (msg.id.startsWith("result_") && msg.toolCall != null) {
                val toolId = "tool_${msg.toolCall!!.toolName}_${msg.toolCall!!.arguments.hashCode().toUInt().toString(16)}"
                entries.add(AnthropicMessage(
                    role = "user",
                    content = listOf(AnthropicContentPart(
                        type = "tool_result",
                        toolUseId = toolId,
                        content = msg.toolCall!!.result
                    ))
                ))
                return@flatMap entries
            }

            val parts = mutableListOf<AnthropicContentPart>()
            val segs = msg.segments


            // Note: thinking blocks from segments are not emitted because Anthropic
            // requires valid signatures which aren't available from streaming responses

            if (msg.text.isNotEmpty()) {
                parts.add(AnthropicContentPart(type = "text", text = msg.text))
            }
            if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = "Continue"))

            val role = when {
                msg.participant == Participant.USER -> "user"
                else -> "assistant"
            }
            entries.add(AnthropicMessage(role = role, content = parts))
            entries
        }

        // Claude thinking logic - all Claude models support thinking except the 3 legacy ones
        val isLegacyClaude = modelName == "claude-3-opus-20240229" ||
            modelName == "claude-3-sonnet-20240229" ||
            modelName == "claude-3-haiku-20240307"
        val thinking = if (config.thinkingEnabled && modelName.startsWith("claude") && !isLegacyClaude) {
            AnthropicThinking(budgetTokens = 1024)
        } else null

        // Convert ToolDefinition to Anthropic format
        val anthropicTools = config.tools?.map { td ->
            AnthropicTool(
                name = td.function.name,
                description = td.function.description,
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(td.function.parameters.type),
                        "properties" to JsonObject(
                            td.function.parameters.properties.mapValues { (_, prop) ->
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive(prop.type),
                                        "description" to JsonPrimitive(prop.description)
                                    )
                                )
                            }
                        ),
                        "required" to kotlinx.serialization.json.JsonArray(
                            td.function.parameters.required.map { JsonPrimitive(it) }
                        )
                    )
                )
            )
        }

        val requestBody = AnthropicRequest(
            model = modelName,
            messages = apiMessages,
            system = config.systemPrompt,
            thinking = thinking,
            maxTokens = if (thinking != null) 2048 else 4096,
            tools = anthropicTools
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/messages")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", config.apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true
            val requestBodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var line: String? = null
                var currentType: String? = null
                var toolUseId: String? = null
                var toolUseName: String? = null
                var toolUseArgs = StringBuilder()
                var thinkingSignature: String? = null

                while (currentCoroutineContext().isActive) {
                    try {
                        line = reader.readLine()
                        if (line == null) break
                    } catch (e: java.net.SocketTimeoutException) {
                        if (!currentCoroutineContext().isActive) break
                        continue
                    }
                    if (line.startsWith("event: ")) {
                        currentType = line.substring(7).trim()
                    } else if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        try {
                            val event = json.decodeFromString<AnthropicStreamEvent>(jsonStr)
                            when (event.type) {
                                "content_block_start" -> {
                                    event.contentBlock?.let { block ->
                                        when (block.type) {
                                            "thinking" -> {
                                                block.signature?.takeIf { it.isNotBlank() }?.let { thinkingSignature = it }
                                            }
                                            "tool_use" -> {
                                                toolUseId = block.id
                                                toolUseName = block.name
                                                toolUseArgs = StringBuilder()
                                            }
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    event.delta?.let { delta ->
                                        when (delta.type) {
                                            "input_json_delta" -> {
                                                delta.partialJson?.let { toolUseArgs.append(it) }
                                            }
                                            else -> {
                                                delta.text?.let { emit(StreamEvent.TextChunk(it)) }
                                                delta.thinking?.let {
                                                    if (delta.signature != null) thinkingSignature = delta.signature
                                                    emit(StreamEvent.ThoughtChunk(it, null, thinkingSignature))
                                                }
                                            }
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (toolUseId != null && toolUseName != null) {
                                        emit(StreamEvent.ToolCallRequest(
                                            toolUseId!!, toolUseName!!, toolUseArgs.toString()
                                        ))
                                        toolUseId = null
                                        toolUseName = null
                                    }
                                    thinkingSignature = null
                                }
                                "message_delta" -> {
                                    event.usage?.let { u ->
                                        val total = (u.inputTokens ?: 0) + (u.outputTokens ?: 0)
                                        emit(StreamEvent.UsageUpdate(total))
                                    }
                                }
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
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e("AgoraAPI", "[Anthropic] ERR $responseCode: $errorRaw")
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
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
            val url = URL("$effectiveBaseUrl/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<AnthropicModelsResponse>(responseText).data.map { it.id }
        } catch (e: Exception) {
            Log.e("AgoraAPI", "Failed to fetch Anthropic models", e)
            emptyList()
        }
    }
}

@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)
