package com.newoether.agora.api.anthropic

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.api.util.adaptToolRoundsForProvider
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.util.Constants
import kotlinx.coroutines.delay
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
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    val tools: List<AnthropicTool>? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null
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
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    val display: String? = null
)

@Serializable
internal data class AnthropicOutputConfig(
    val effort: String
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
    val text: String? = null,
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

/**
 * Routes Anthropic SSE deltas by protocol content-block identity. A block is exclusively text,
 * thinking, or tool-use, so a field carried by a tool block can never enter the answer channel.
 */
internal class AnthropicStreamEventRouter {
    private data class ToolBlock(
        val streamKey: String,
        val id: String?,
        val name: String,
        val arguments: StringBuilder,
    )

    private val blockTypes = mutableMapOf<Int, String>()
    private val toolBlocks = mutableMapOf<Int, ToolBlock>()
    private val thinkingSignatures = mutableMapOf<Int, String?>()
    private var lastBlockIndex = -1
    private var syntheticIndex = 0
    private var inputTokens = 0

    fun route(event: AnthropicStreamEvent): List<StreamEvent> = buildList {
        when (event.type) {
            "message_start" -> {
                inputTokens = event.message?.usage?.inputTokens ?: inputTokens
            }

            "content_block_start" -> {
                val block = event.contentBlock ?: return@buildList
                val index = indexForStart(event.index)
                blockTypes[index] = block.type
                when (block.type) {
                    "text" -> block.text?.takeIf(String::isNotEmpty)?.let {
                        add(StreamEvent.TextChunk(it))
                    }

                    "thinking" -> {
                        thinkingSignatures[index] = block.signature
                        block.thinking?.takeIf(String::isNotEmpty)?.let {
                            add(StreamEvent.ThoughtChunk(it, signature = block.signature))
                        }
                    }

                    "tool_use" -> {
                        val initialArguments = block.input
                            ?.takeUnless { it.isEmpty() }
                            ?.toString()
                            .orEmpty()
                        val tool = ToolBlock(
                            streamKey = block.id ?: "call_stream_${java.util.UUID.randomUUID()}",
                            id = block.id,
                            name = block.name.orEmpty(),
                            arguments = StringBuilder(initialArguments),
                        )
                        toolBlocks[index] = tool
                        // Tool identity is complete at block start; the UI segment must exist
                        // before the first input_json_delta is written.
                        add(tool.updateEvent())
                    }
                }
            }

            "content_block_delta" -> {
                val index = indexForContinuation(event.index)
                val delta = event.delta ?: return@buildList
                when (delta.type) {
                    "input_json_delta" -> {
                        val tool = toolBlocks[index] ?: return@buildList
                        delta.partialJson?.let(tool.arguments::append)
                        add(tool.updateEvent())
                    }

                    "signature_delta" -> {
                        val signature = delta.signature?.takeIf(String::isNotBlank)
                            ?: return@buildList
                        thinkingSignatures[index] = signature
                        add(StreamEvent.ThoughtChunk(thought = "", signature = signature))
                    }

                    "thinking_delta" -> {
                        delta.thinking?.takeIf(String::isNotEmpty)?.let {
                            add(
                                StreamEvent.ThoughtChunk(
                                    thought = it,
                                    signature = thinkingSignatures[index],
                                )
                            )
                        }
                    }

                    "text_delta" -> {
                        if (blockTypes[index] == "text") {
                            delta.text?.takeIf(String::isNotEmpty)?.let {
                                add(StreamEvent.TextChunk(it))
                            }
                        }
                    }

                    else -> routeUntypedDelta(index, delta, this)
                }
            }

            "content_block_stop" -> {
                val index = indexForContinuation(event.index)
                toolBlocks.remove(index)?.let { tool ->
                    if (tool.name.isNotBlank()) add(tool.completeEvent())
                }
                blockTypes.remove(index)
                thinkingSignatures.remove(index)
            }

            "message_delta" -> event.usage?.let { usage ->
                add(StreamEvent.UsageUpdate(inputTokens + (usage.outputTokens ?: 0)))
            }
        }
    }

    /** Complete only blocks that were actually opened as tool_use if a gateway closes at EOF. */
    fun finish(): List<StreamEvent> = buildList {
        toolBlocks.toSortedMap().values.forEach { tool ->
            if (tool.name.isNotBlank()) add(tool.completeEvent())
        }
        toolBlocks.clear()
        blockTypes.clear()
        thinkingSignatures.clear()
    }

    private fun routeUntypedDelta(
        index: Int,
        delta: AnthropicDelta,
        output: MutableList<StreamEvent>,
    ) {
        when (blockTypes[index]) {
            "tool_use" -> {
                val tool = toolBlocks[index] ?: return
                delta.partialJson?.let(tool.arguments::append)
                output += tool.updateEvent()
            }

            "thinking" -> {
                delta.signature?.takeIf(String::isNotBlank)?.let {
                    thinkingSignatures[index] = it
                }
                (delta.thinking ?: delta.text)?.takeIf(String::isNotEmpty)?.let {
                    output += StreamEvent.ThoughtChunk(
                        thought = it,
                        signature = thinkingSignatures[index],
                    )
                }
            }

            "text" -> delta.text?.takeIf(String::isNotEmpty)?.let {
                output += StreamEvent.TextChunk(it)
            }
        }
    }

    private fun indexForStart(index: Int?): Int {
        val resolved = index ?: syntheticIndex++
        lastBlockIndex = resolved
        return resolved
    }

    private fun indexForContinuation(index: Int?): Int =
        index ?: lastBlockIndex.takeIf { it >= 0 } ?: syntheticIndex++

    private fun ToolBlock.updateEvent() = StreamEvent.ToolCallUpdate(
        streamKey = streamKey,
        id = id,
        name = name,
        arguments = arguments.toString(),
    )

    private fun ToolBlock.completeEvent() = StreamEvent.ToolCallRequest(
        id = id ?: streamKey,
        name = name,
        arguments = arguments.toString(),
        streamKey = streamKey,
    )
}

/** Request-shape generations of the Claude model line. Only the LEGACY sets are enumerated;
 *  anything unmatched (opus-5, sonnet-5, fable, mythos, every future family) is
 *  [CURRENT_ADAPTIVE] and must never receive `budget_tokens` or sampling params (400 on 4.7+). */
internal enum class ClaudeFamily { NO_THINKING, BUDGET_THINKING, TRANSITIONAL_4_6, CURRENT_ADAPTIVE }

internal fun classifyClaudeFamily(modelName: String): ClaudeFamily {
    val m = modelName.lowercase()
    if (!m.startsWith("claude")) return ClaudeFamily.CURRENT_ADAPTIVE
    // 3.0 / 3.5 predate extended thinking entirely.
    if (listOf("claude-3-opus", "claude-3-sonnet", "claude-3-haiku", "claude-3-5-")
            .any { m.startsWith(it) }
    ) return ClaudeFamily.NO_THINKING
    // 4.6: adaptive preferred; deprecated `budget_tokens` still functional (transitional).
    // Checked before the dated-4.x markers so a dated 4.6 id can't fall into the budget list.
    if (m.contains("4-6") || m.contains("4.6")) return ClaudeFamily.TRANSITIONAL_4_6
    // Closed list of budget_tokens generations: 3.7, 4.0 (incl. dated claude-*-4-2025xxxx),
    // 4.1, and the 4.5 tier (opus/sonnet/haiku).
    if (listOf("claude-3-7", "-4-0", "-4-1", "-4-5", "4.0", "4.1", "4.5", "-4-2025")
            .any { m.contains(it) }
    ) return ClaudeFamily.BUDGET_THINKING
    return ClaudeFamily.CURRENT_ADAPTIVE
}

private fun MessageSegment.signatureIsCompatibleWithAnthropic(
    sourceModel: String?,
    targetModel: String,
): Boolean {
    signatureProvider?.let {
        return it.equals(Constants.PROVIDER_ANTHROPIC, ignoreCase = true)
    }
    return sourceModel == null ||
        sourceModel.equals(targetModel, ignoreCase = true) ||
        sourceModel.contains("claude", ignoreCase = true)
}

private fun ChatMessage.isAnthropicToolRoundCompatible(
    targetModel: String,
    signedThinkingRequired: Boolean,
): Boolean {
    if (!signedThinkingRequired) return true
    val thoughts = segments
        ?.filter { it.type == "thought" && it.content.isNotBlank() }
        .orEmpty()
    return thoughts.isNotEmpty() && thoughts.all {
        !it.signature.isNullOrBlank() &&
            it.signatureIsCompatibleWithAnthropic(modelName, targetModel)
    }
}

class AnthropicProvider(
    override val name: String = Constants.PROVIDER_ANTHROPIC,
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1",
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val modelName = config.modelId

        // ── Model-generation classification ─────────────────────────────────
        // The legacy sets are CLOSED lists; every model NOT matched below — including
        // claude-opus-5 / claude-sonnet-5 / fable / mythos and all FUTURE families — is
        // treated as current-generation: adaptive thinking only, and no sampling params.
        // Rationale (API contract): `budget_tokens` and `temperature`/`top_p` are REMOVED
        // from Opus 4.7 onward (sending either returns a hard 400), so an unknown new
        // model must never fall back onto the legacy request shape.
        val family = classifyClaudeFamily(modelName)
        val thinkingBudget = (
            if (config.thinkingBudgetEnabled) config.thinkingBudgetTokens else ThinkingLevels.DefaultBudgetTokens
        ).coerceIn(1024, 128000)
        val thinking = when {
            !config.thinkingEnabled || !modelName.startsWith("claude") -> null
            family == ClaudeFamily.NO_THINKING -> null
            family == ClaudeFamily.BUDGET_THINKING ->
                AnthropicThinking(type = "enabled", budgetTokens = thinkingBudget, display = "summarized")
            // 4.6: adaptive preferred; the deprecated budget form is still functional there,
            // so honor an explicit user-enabled budget as the documented transitional escape hatch.
            family == ClaudeFamily.TRANSITIONAL_4_6 && config.thinkingBudgetEnabled ->
                AnthropicThinking(type = "enabled", budgetTokens = thinkingBudget, display = "summarized")
            else -> AnthropicThinking(type = "adaptive", display = "summarized")
        }
        val outputConfig = if (thinking?.type == "adaptive") {
            AnthropicOutputConfig(effort = ThinkingLevels.anthropicEffort(config.thinkingLevel))
        } else null
        // temperature/top_p are rejected with a 400 on Opus 4.7+ / Sonnet 5 / Fable — only the
        // legacy and transitional families may carry user sampling overrides.
        val allowsSamplingParams = family != ClaudeFamily.CURRENT_ADAPTIVE

        val canonicalPath = prepareMessages(messages, config.maxContextWindow)
        val validatedPath = adaptToolRoundsForProvider(
            messages = canonicalPath,
            providerName = "Anthropic",
        ) { toolMessage ->
            toolMessage.isAnthropicToolRoundCompatible(
                targetModel = modelName,
                signedThinkingRequired = thinking != null,
            )
        }

        // Convert ChatMessages to Anthropic API format.
        // Consecutive result_ messages are batched into a single user message
        // because Anthropic requires all tool_results for a batched assistant
        // tool_use to be in the single immediately-following user message.
        val apiMessages = coalesceAnthropicMessages(buildList {
            var i = 0
            while (i < validatedPath.size) {
                val msg = validatedPath[i]
                when {
                    msg.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                        add(buildAssistantToolUse(msg, modelName))
                        i++
                        // Batch all immediately following result_ messages into one user message
                        if (i < validatedPath.size && validatedPath[i].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                            val resultBlocks = mutableListOf<AnthropicContentPart>()
                            while (i < validatedPath.size && validatedPath[i].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                                resultBlocks.addAll(buildToolResultBlocks(validatedPath[i]))
                                i++
                            }
                            add(AnthropicMessage(role = "user", content = resultBlocks))
                        }
                    }
                    msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                        // Orphan result_ — should not occur after validateToolMessages, but drop defensively
                        i++
                    }
                    else -> {
                        add(buildNormalMessage(if (config.includeImages) msg else msg.copy(images = emptyList())))
                        i++
                    }
                }
            }
        })

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
            messages = apiMessages,
            system = config.systemPrompt,
            thinking = thinking,
            outputConfig = outputConfig,
            // On always-on/adaptive-thinking models max_tokens caps thinking + answer TOGETHER,
            // so the legacy 4096 default truncates mid-answer once the model thinks. Streaming is
            // always on here, so a generous default costs nothing (it is a cap, not a target).
            maxTokens = config.maxTokens ?: when {
                thinking?.budgetTokens != null -> maxOf(thinking.budgetTokens + 1024, 4096)
                thinking?.type == "adaptive" -> 16384
                else -> 4096
            },
            tools = anthropicTools,
            temperature = config.temperature.takeIf { allowsSamplingParams },
            topP = config.topP.takeIf { allowsSamplingParams }
        )

        try {
            requestBody.requireValidWireFormat()
            val url = "$baseUrl/messages"
            val headers = mutableMapOf("Content-Type" to "application/json")
            headers["x-api-key"] = config.apiKey
            headers["anthropic-version"] = "2023-06-01"
            val requestBodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
            requireValidSerializedRequest(
                provider = "Anthropic",
                body = requestBodyJson,
                requiredStringFields = setOf("model"),
                requiredArrayFields = setOf("messages"),
            )
            DebugLog.d("AgoraAPI", "[Anthropic] REQ → $baseUrl/messages | model=$modelName | msgs=${apiMessages.size} | thinking=${thinking != null} | tools=${anthropicTools?.size ?: 0}")
            DebugLog.d("AgoraAPI", "[Anthropic] BODY: ${requestBodyJson.take(4000)}")
            val maxAttempts = 3
            val retryableCodes = setOf(429, 502, 503, 504)
            var attempt = 0
            var done = false

            while (attempt < maxAttempts && !done) {
                attempt++
                val handle = HttpClient.streamPost(url, requestBodyJson, headers)
                try {
                if (handle.code == 200) {
                    done = true
                    var line: String? = null
                    val eventRouter = AnthropicStreamEventRouter()

                    // Tolerate long thinking pauses, but not a silently-dead connection:
                    // 3 consecutive read timeouts (~15 min without a byte) → give up.
                    var consecutiveReadTimeouts = 0
                    while (currentCoroutineContext().isActive) {
                        try {
                            line = handle.readLine()
                            if (line == null) break
                            consecutiveReadTimeouts = 0
                        } catch (e: java.net.SocketTimeoutException) {
                            if (!currentCoroutineContext().isActive) break
                            if (++consecutiveReadTimeouts >= 3) {
                                emit(StreamEvent.Error(GenerationError.Timeout))
                                break
                            }
                            continue
                        }
                        if (line.startsWith("data: ")) {
                            val jsonStr = line.substring(6).trim()
                            try {
                                val event = json.decodeFromString<AnthropicStreamEvent>(jsonStr)
                                eventRouter.route(event).forEach { emit(it) }
                            } catch (e: Exception) {
                                DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
                            }
                        }
                    }
                    eventRouter.finish().forEach { emit(it) }
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("Stream cancelled")
                    }
                } else {
                    val errorRaw = handle.errorBody ?: "Unknown error"
                    DebugLog.e("AgoraAPI", "[Anthropic] ERR ${handle.code}: $errorRaw")

                    if (handle.code in retryableCodes && attempt < maxAttempts) {
                        DebugLog.w("AgoraAPI", "[Anthropic] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${1000 * attempt}ms...")
                        emit(StreamEvent.Retrying(attempt, maxAttempts))
                        delay(1000L * attempt)
                    } else {
                        val genError = try {
                            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                            GenerationError.Api(code = errorJson.error.code ?: handle.code.toString(), type = errorJson.error.type, message = errorJson.error.message)
                        } catch (_: Exception) {
                            GenerationError.Network(statusCode = handle.code, message = errorRaw)
                        }
                        emit(StreamEvent.Error(genError))
                    }
                }
                } finally { handle.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("AgoraAPI", "[Anthropic] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat("Anthropic", e.violations.joinToString())))
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Message conversion helpers ──

    private fun buildAssistantToolUse(msg: ChatMessage, targetModel: String): AnthropicMessage {
        // With thinking enabled, Anthropic requires the assistant turn that carries tool_use to
        // replay its thinking block(s) unchanged (content + signature) — a bare tool_use turn is
        // rejected on the follow-up request. Unsigned thoughts cannot be replayed, so only signed
        // segments are included; when none exist (thinking off) the turn stays tool_use-only.
        val thinkingParts = msg.segments
            ?.filter {
                it.type == "thought" &&
                    it.content.isNotEmpty() &&
                    !it.signature.isNullOrBlank() &&
                    it.signatureIsCompatibleWithAnthropic(msg.modelName, targetModel)
            }
            ?.map { AnthropicContentPart(type = "thinking", thinking = it.content, signature = it.signature) }
            .orEmpty()
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            val blocks = toolSegs.map { seg -> buildToolUseBlock(seg.toolCallId, seg.toolName, seg.toolArgs) }
            return AnthropicMessage(role = "assistant", content = thinkingParts + blocks)
        }
        val tc = msg.toolCall ?: return AnthropicMessage(role = "assistant", content = listOf(
            AnthropicContentPart(type = "text", text = "Continue")
        ))
        val block = buildToolUseBlock(tc.toolCallId, tc.toolName, tc.arguments)
        return AnthropicMessage(role = "assistant", content = thinkingParts + listOf(block))
    }

    private fun buildToolUseBlock(id: String?, name: String?, args: String?): AnthropicContentPart {
        val toolId = id ?: buildToolCallId(name ?: "", args ?: "{}", "tool_")
        val input = try {
            json.parseToJsonElement(args ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) { JsonObject(emptyMap()) }
        return AnthropicContentPart(type = "tool_use", id = toolId, name = name ?: "", input = input)
    }

    private fun buildToolResultBlocks(msg: ChatMessage): List<AnthropicContentPart> {
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            return toolSegs.map { seg ->
                val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
                AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = seg.toolResult ?: "")
            }
        }
        val tc = msg.toolCall ?: return emptyList()
        val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
        return listOf(AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = tc.result))
    }

    private fun buildNormalMessage(msg: ChatMessage): AnthropicMessage {
        val parts = mutableListOf<AnthropicContentPart>()
        val imagePaths = if (msg.participant == Participant.USER) msg.images else emptyList()
        for (imagePath in imagePaths) {
            val encoded = com.newoether.agora.api.util.encodeImageToBase64(imagePath)
            if (encoded != null) {
                val (mimeType, base64) = encoded
                parts.add(AnthropicContentPart(
                    type = "image",
                    source = AnthropicImageSource(mediaType = mimeType, data = base64)
                ))
            }
        }
        // isNotBlank, NOT isNotEmpty: Anthropic rejects a whitespace-only text block with
        // 400 "text content blocks must contain non-whitespace text". Whitespace-only turns
        // are real — a stopped generation that emitted one newline, a tool-only assistant
        // turn, or mergeConsecutiveSameRole joining two blank messages with "\n".
        if (msg.text.isNotBlank()) {
            parts.add(AnthropicContentPart(type = "text", text = msg.text))
        }
        if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = "Continue"))
        val role = if (msg.participant == Participant.USER) "user" else "assistant"
        return AnthropicMessage(role = role, content = parts)
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        // /v1/models is paginated (default page ~20); follow has_more/last_id so accounts
        // with long model lists aren't silently truncated to the first page.
        val all = mutableListOf<String>()
        var afterId: String? = null
        var pages = 0
        while (pages < 10) {
            val url = buildString {
                append(effectiveBaseUrl).append("/models?limit=100")
                afterId?.let { append("&after_id=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            }
            val responseText = HttpClient.fetchModelsResponse(url, headers)
                .requireModelFetchBody()
            val page = decodeModelFetchResponse {
                json.decodeFromString<AnthropicModelsResponse>(responseText)
            }
            all += page.data.map { it.id }
            if (!page.hasMore || page.data.isEmpty()) break
            afterId = page.lastId ?: page.data.last().id
            pages++
        }
        if (all.isEmpty()) throw ModelFetchEmptyResultException()
        all
    }
}

@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)
