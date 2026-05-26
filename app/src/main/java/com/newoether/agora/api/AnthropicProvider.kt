package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.limitContext
import com.newoether.agora.util.Constants
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

        val limitedPath = limitContext(messages, config.maxContextWindow)

        val apiMessages = limitedPath.flatMap { msg ->
            val entries = mutableListOf<AnthropicMessage>()

            // tool_ messages: assistant turn with tool_use blocks from segments
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val toolUseBlocks = toolSegs.map { seg ->
                        val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
                        AnthropicContentPart(
                            type = "tool_use",
                            id = toolId,
                            name = seg.toolName ?: "",
                            input = try { json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject ?: JsonObject(emptyMap()) } catch (_: Exception) { JsonObject(emptyMap()) }
                        )
                    }
                    entries.add(AnthropicMessage(role = "assistant", content = toolUseBlocks))
                } else if (msg.toolCall != null) {
                    val tc = msg.toolCall!!
                    val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
                    entries.add(AnthropicMessage(
                        role = "assistant",
                        content = listOf(AnthropicContentPart(
                            type = "tool_use",
                            id = toolId,
                            name = tc.toolName,
                            input = try { json.parseToJsonElement(tc.arguments) as? JsonObject ?: JsonObject(emptyMap()) } catch (_: Exception) { JsonObject(emptyMap()) }
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the tool_result (handle segments for multi-tool, toolCall for single)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val toolResultBlocks = toolSegs.map { seg ->
                        val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
                        AnthropicContentPart(
                            type = "tool_result",
                            toolUseId = toolId,
                            content = seg.toolResult ?: ""
                        )
                    }
                    entries.add(AnthropicMessage(role = "user", content = toolResultBlocks))
                } else if (msg.toolCall != null) {
                    val tc = msg.toolCall!!
                    val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
                    entries.add(AnthropicMessage(
                        role = "user",
                        content = listOf(AnthropicContentPart(
                            type = "tool_result",
                            toolUseId = toolId,
                            content = tc.result
                        ))
                    ))
                }
                return@flatMap entries
            }

            val parts = mutableListOf<AnthropicContentPart>()

            // Add images
            for (imagePath in msg.images) {
                val encoded = com.newoether.agora.api.util.encodeImageToBase64(imagePath)
                if (encoded != null) {
                    val (mimeType, base64) = encoded
                    parts.add(AnthropicContentPart(
                        type = "image",
                        source = AnthropicImageSource(mediaType = mimeType, data = base64)
                    ))
                }
            }

            // Thinking blocks omitted from history: Anthropic requires a valid signature
            // for every thinking block sent back in a multi-turn request. Signatures are
            // available during streaming but may not survive segment reconstruction, so
            // omitting thinking blocks is safer than risking a 400 signature mismatch.

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

        // Fix tool_use / tool_result pairing before sending to Anthropic.
        // Two classes of problem exist:
        //   1. Orphaned blocks: a tool_use with no following tool_result, or vice versa.
        //      Caused by limitContext truncation or user interrupting a tool round.
        //   2. ID mismatches: a tool_result whose tool_use_id does not match any
        //      tool_use in the preceding assistant message. Caused by legacy data
        //      where result_ messages were persisted without toolCallId.
        //
        // Pass 1 — fix ID mismatches by matching on position within the message.
        // Pass 2 — strip orphaned blocks (tool_use with no next tool_result, and
        //           tool_result with no preceding tool_use).
        val cleanedMessages = apiMessages.toMutableList()

        // Pass 1: fix tool_result IDs
        for (i in 0 until cleanedMessages.size) {
            val msg = cleanedMessages[i]
            if (msg.role != "user" || msg.content.none { it.type == "tool_result" }) continue
            if (i == 0) continue
            val prev = cleanedMessages[i - 1]
            if (prev.role != "assistant") continue
            val useBlocks = prev.content.filter { it.type == "tool_use" }
            if (useBlocks.isEmpty()) continue
            val useIds = useBlocks.mapNotNull { it.id }
            val resultBlocks = msg.content.filter { it.type == "tool_result" }
            val fixed = msg.content.map { part ->
                if (part.type != "tool_result" || part.toolUseId in useIds) return@map part
                val idx = resultBlocks.indexOf(part)
                if (idx in useIds.indices) {
                    val candidate = useIds[idx]
                    val alreadyUsed = resultBlocks.any { it !== part && it.toolUseId == candidate }
                    if (alreadyUsed) part else part.copy(toolUseId = candidate)
                } else part
            }
            if (fixed != msg.content) cleanedMessages[i] = msg.copy(content = fixed)
        }

        // Pass 2: strip orphaned blocks
        var i = 0
        while (i < cleanedMessages.size) {
            val msg = cleanedMessages[i]
            if (msg.role == "assistant" && msg.content.any { it.type == "tool_use" }) {
                val nextHasResult = i + 1 < cleanedMessages.size &&
                    cleanedMessages[i + 1].content.any { it.type == "tool_result" }
                if (!nextHasResult) {
                    val nonTool = msg.content.filter { it.type != "tool_use" }
                    if (nonTool.isEmpty()) { cleanedMessages.removeAt(i); continue }
                    else cleanedMessages[i] = msg.copy(content = nonTool)
                }
            }
            if (msg.role == "user" && msg.content.any { it.type == "tool_result" }) {
                val prevHasUse = i > 0 && cleanedMessages[i - 1].role == "assistant" &&
                    cleanedMessages[i - 1].content.any { it.type == "tool_use" }
                if (!prevHasUse) {
                    val nonResult = msg.content.filter { it.type != "tool_result" }
                    if (nonResult.isEmpty()) { cleanedMessages.removeAt(i); continue }
                    else cleanedMessages[i] = msg.copy(content = nonResult)
                }
            }
            i++
        }

        // Claude thinking logic - all Claude models support thinking except the 3 legacy ones
        val isLegacyClaude = modelName == "claude-3-opus-20240229" ||
            modelName == "claude-3-sonnet-20240229" ||
            modelName == "claude-3-haiku-20240307"
        val thinking = if (config.thinkingEnabled && modelName.startsWith("claude") && !isLegacyClaude) {
            val budget = when (config.thinkingLevel) {
                "low" -> 1024
                "high" -> 8192
                else -> 4096
            }
            AnthropicThinking(budgetTokens = budget)
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
                                val propMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                                    "type" to JsonPrimitive(prop.type),
                                    "description" to JsonPrimitive(prop.description)
                                )
                                if (prop.items != null) {
                                    propMap["items"] = JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive(prop.items.type),
                                            "description" to JsonPrimitive(prop.items.description)
                                        )
                                    )
                                }
                                JsonObject(propMap)
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
            messages = cleanedMessages,
            system = config.systemPrompt,
            thinking = thinking,
            maxTokens = if (thinking != null) maxOf(thinking.budgetTokens + 1024, 4096) else 4096,
            tools = anthropicTools
        )

        try {
            val url = "$baseUrl/messages"
            val headers = mutableMapOf("Content-Type" to "application/json")
            headers["x-api-key"] = config.apiKey
            headers["anthropic-version"] = "2023-06-01"
            val requestBodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
            DebugLog.d("AgoraAPI", "[Anthropic] REQ → $baseUrl/messages | model=$modelName | msgs=${cleanedMessages.size} | thinking=${thinking != null} | tools=${anthropicTools?.size ?: 0}")
            DebugLog.d("AgoraAPI", "[Anthropic] BODY: ${requestBodyJson.take(4000)}")
            val handle = HttpClient.streamPost(url, requestBodyJson, headers)
            try {
                if (handle.code == 200) {
                    var line: String? = null
                    var currentType: String? = null
                    var toolUseId: String? = null
                    var toolUseName: String? = null
                    var toolUseArgs = StringBuilder()
                    var thinkingSignature: String? = null
                    var messageInputTokens = 0

                    while (currentCoroutineContext().isActive) {
                        try {
                            line = handle.readLine()
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
                                    "message_start" -> {
                                        event.message?.usage?.inputTokens?.let { messageInputTokens = it }
                                    }
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
                                            val total = messageInputTokens + (u.outputTokens ?: 0)
                                            emit(StreamEvent.UsageUpdate(total))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
                            }
                        }
                    }
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("Stream cancelled")
                    }
                } else {
                    val errorRaw = handle.errorBody ?: "Unknown error"
                    DebugLog.e("AgoraAPI", "[Anthropic] ERR ${handle.code}: $errorRaw")
                    val errorMessage = try {
                        val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                        "Error ${errorJson.error.code ?: handle.code} (${errorJson.error.type ?: "UNKNOWN"}): ${errorJson.error.message}"
                    } catch (_: Exception) {
                        "Error ${handle.code}: $errorRaw"
                    }
                    emit(StreamEvent.Error(errorMessage))
                }
            } finally { handle.close() }
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
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
            val responseText = HttpClient.fetchModels(
                "$effectiveBaseUrl/models",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
            ) ?: run {
                DebugLog.e("AgoraAPI", "Failed to fetch Anthropic models: empty response")
                return@withContext emptyList()
            }
            json.decodeFromString<AnthropicModelsResponse>(responseText).data.map { it.id }
        } catch (e: Exception) {
            DebugLog.e("AgoraAPI", "Failed to fetch Anthropic models", e)
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
