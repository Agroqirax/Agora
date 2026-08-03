package com.newoether.agora.api.openai

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.StreamingThinkTagParser
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseOpenAiProvider : LlmProvider {

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // -- Override points --

    /**
     * Modify the outgoing request before serialization (e.g. add reasoning_effort, plugins).
     * The default implementation returns the request unchanged.
     */
    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest = request

    /**
     * Extra HTTP headers to include in the POST to /chat/completions.
     */
    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()

    /**
     * Transform the system prompt before it is sent. Default: pass-through.
     */
    protected open fun transformSystemPrompt(prompt: String?): String? = prompt

    /**
     * Parse the delta from one SSE event and emit TextChunk / ThoughtChunk events.
     * The base class handles tool_calls accumulation, finish_reason emission, and usage
     * emission automatically.
     *
     * The default implementation covers the common OpenAI-compatible shape: a separate
     * `reasoning_content` field (gated on [ProviderConfig.thinkingEnabled]) plus `content`.
     * Providers with a different reasoning representation (e.g. OpenRouter's
     * `reasoning_details`) override this.
     */
    protected open suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        delta.reasoningContent?.let { reasoning ->
            if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                emit(StreamEvent.ThoughtChunk(reasoning))
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) {
                if (parseInlineThinkTags) {
                    thinkParser.feed(
                        content = content,
                        thinkingEnabled = config.thinkingEnabled,
                        onText = { emit(StreamEvent.TextChunk(it)) },
                        onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                    )
                } else {
                    emit(StreamEvent.TextChunk(content))
                }
            }
        }
    }

    /**
     * When true, inline `<think>…</think>` in the content stream is parsed into thought chunks.
     * Enabled only for self-hosted OpenAI-compatible servers (llama.cpp/vLLM render reasoning
     * inline via the chat template); official cloud endpoints keep content literal so an answer
     * that MENTIONS a think tag is never misclassified.
     */
    protected open val parseInlineThinkTags: Boolean = false

    protected open val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    protected open val retryMissingV1BaseUrl: Boolean = false

    protected open fun retryDelayMillis(statusCode: Int, attempt: Int): Long = 1000L * attempt

    /**
     * OpenAI normally follows `finish_reason` with an optional usage-only event and `[DONE]`.
     * Compatible gateways sometimes omit `[DONE]` or keep the HTTP connection alive. Once the
     * semantic terminal event arrives, accept that tail only for this bounded window.
     */
    protected open val terminalSseGraceMillis: Long = 1_000L

    // -- Template method --

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(baseUrl, "chat/completions")

        val validatedMessages = prepareMessages(messages, config.maxContextWindow)

        val apiMessages = convertToOpenAiMessages(
            messages = validatedMessages,
            systemPrompt = transformSystemPrompt(config.systemPrompt),
            includeImages = config.includeImages
        )

        var request = OpenAiChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            tools = config.tools,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        request = customizeRequest(request, config)

        val thinkParser = StreamingThinkTagParser()

        try {
            request.requireValidWireFormat(name)
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), request)
            requireValidSerializedRequest(
                provider = name,
                body = requestBodyJson,
                requiredStringFields = setOf("model"),
                requiredArrayFields = setOf("messages"),
            )
            DebugLog.d("AgoraAPI", "[$name] REQ -> ${endpointUrls.first()} | model=${config.modelId} | msgs=${apiMessages.size} | tools=${config.tools?.size ?: 0}")

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = 3
            var attempt = 0
            var finished = false

            while (attempt < maxAttempts && !finished) {
                attempt++
                var endpointIndex = 0
                var retryScheduled = false

                while (endpointIndex < endpointUrls.size && !finished && !retryScheduled) {
                    val endpointUrl = endpointUrls[endpointIndex]
                    val handle = HttpClient.streamPost(endpointUrl, requestBodyJson, headers)
                    try {
                        if (handle.code == 200) {
                            consumeSuccessfulStream(handle, config, thinkParser) { emit(it) }
                            finished = true
                        } else {
                            val errorRaw = handle.errorBody ?: "Unknown error"
                            val hasV1Fallback = endpointIndex + 1 < endpointUrls.size
                            if (hasV1Fallback) {
                                DebugLog.w("AgoraAPI", "[$name] ${handle.code} at $endpointUrl, retrying with ${endpointUrls[endpointIndex + 1]}")
                                endpointIndex++
                                continue
                            }

                            DebugLog.e("AgoraAPI", "[$name] ERR ${handle.code} at $endpointUrl: $errorRaw")

                            if (handle.code in retryableStatusCodes && attempt < maxAttempts) {
                                val retryDelayMs = retryDelayMillis(handle.code, attempt)
                                DebugLog.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                                emit(StreamEvent.Retrying(attempt, maxAttempts))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                emit(StreamEvent.Error(buildGenerationError(handle.code, errorRaw, endpointUrls)))
                                finished = true
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("AgoraAPI", "[$name] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat(name, e.violations.joinToString())))
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun consumeSuccessfulStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
        // Accumulate answer content so that, if the server emits tool calls as content text rather
        // than as structured delta.tool_calls (#33 path B), we can recover them at stream end.
        val contentBuf = StringBuilder()
        val emitAndAccumulate: suspend (StreamEvent) -> Unit = { event ->
            if (event is StreamEvent.TextChunk) contentBuf.append(event.text)
            emit(event)
        }
        var structuredToolCallsEmitted = false
        var textToolCallsEmitted = false
        val textToolParser = config.tools
            ?.takeIf { it.isNotEmpty() }
            ?.let { StreamingTextToolCallParser() }
        var terminalDeadlineNanos: Long? = null

        suspend fun emitPendingStructuredToolCalls() {
            if (pendingToolCalls.isEmpty()) return
            val pending = pendingToolCalls.values.toList()
            pendingToolCalls.clear()
            val calls = pending
                .filter { it.name.isNotEmpty() }
                .map {
                    StreamEvent.ToolCallRequest(
                        id = it.id.ifBlank { it.streamKey },
                        name = it.name,
                        arguments = it.args.toString(),
                        streamKey = it.streamKey,
                    )
                }
            if (calls.isEmpty()) {
                emit(
                    StreamEvent.Error(
                        GenerationError.SseParse(
                            rawLine = "tool_calls",
                            cause = "Provider ended before the tool name was complete",
                        )
                    )
                )
                return
            }
            structuredToolCallsEmitted = true
            if (calls.size == 1) emit(calls.first())
            else emit(StreamEvent.ToolCallsRequest(calls))
        }

        suspend fun emitFilteredText(text: String) {
            parseDeltaContent(
                delta = OpenAiDelta(content = text),
                config = config,
                thinkParser = thinkParser,
                emit = emitAndAccumulate,
            )
        }

        suspend fun emitTextToolUpdate(snapshot: StreamingTextToolCallParser.Snapshot) {
            emit(
                StreamEvent.ToolCallUpdate(
                    streamKey = snapshot.streamKey,
                    id = null,
                    name = snapshot.name,
                    arguments = snapshot.arguments,
                )
            )
        }

        suspend fun emitCompletedTextTool(call: StreamingTextToolCallParser.CompletedCall) {
            textToolCallsEmitted = true
            emit(
                StreamEvent.ToolCallRequest(
                    id = syntheticToolCallId(),
                    name = call.name,
                    arguments = call.arguments,
                    streamKey = call.streamKey,
                )
            )
        }

        suspend fun emitMalformedTextTool(cause: String) {
            emit(
                StreamEvent.Error(
                    GenerationError.SseParse(
                        rawLine = "tool_call",
                        cause = cause,
                    )
                )
            )
        }

        suspend fun parseDeltaWithStreamingTextTools(delta: OpenAiDelta) {
            val content = delta.content
            if (textToolParser == null || content.isNullOrEmpty()) {
                parseDeltaContent(delta, config, thinkParser, emitAndAccumulate)
                return
            }

            // Emit provider-specific reasoning fields once, then route only content through the
            // text-tool parser so split tags never flash as answer text.
            parseDeltaContent(
                delta.copy(content = null),
                config,
                thinkParser,
                emitAndAccumulate,
            )
            textToolParser.feed(
                content = content,
                onText = { emitFilteredText(it) },
                onUpdate = { emitTextToolUpdate(it) },
                onComplete = { emitCompletedTextTool(it) },
                onMalformed = { emitMalformedTextTool(it) },
            )
        }

        // Read timeouts are tolerated for long thinking pauses (read timeout = 5 min), but a
        // silently-dead connection (NAT drop with no RST) must not hang forever: give up after
        // 3 consecutive timeouts (~15 min without a single byte).
        var consecutiveReadTimeouts = 0
        while (currentCoroutineContext().isActive) {
            terminalDeadlineNanos?.let { deadline ->
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                val remainingMillis =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                        .coerceAtLeast(1L)
                handle.setReadTimeoutMillis(remainingMillis)
            }
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                // A semantic terminal event already completed the model response. This timeout
                // only closes its optional usage/[DONE] grace tail and is therefore success.
                if (terminalDeadlineNanos != null) break
                if (++consecutiveReadTimeouts >= 3) {
                    emit(StreamEvent.Error(GenerationError.Timeout))
                    break
                }
                continue
            } ?: break
            consecutiveReadTimeouts = 0

            if (!line.startsWith("data: ")) continue
            val jsonStr = line.substring(6).trim()
            if (jsonStr == "[DONE]") {
                emitPendingStructuredToolCalls()
                break
            }

            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                val choice = response.choices?.firstOrNull()

                choice?.delta?.let { delta ->
                    parseDeltaWithStreamingTextTools(delta)

                    delta.toolCalls?.forEach { tc ->
                        val existingEntry = tc.id?.let { id ->
                            pendingToolCalls.entries.firstOrNull { it.value.id == id }
                        }
                        val index = tc.index
                            ?: existingEntry?.key
                            ?: pendingToolCalls.keys.singleOrNull()
                            ?: pendingToolCalls.size
                        val pending = pendingToolCalls.getOrPut(index) { PendingToolCall() }
                        if (tc.id != null) pending.id = tc.id
                        tc.function?.name?.let { if (it.isNotEmpty()) pending.name = it }
                        tc.function?.arguments?.let {
                            pending.args.append(if (it is JsonPrimitive) it.content else it.toString())
                        }
                        emit(
                            StreamEvent.ToolCallUpdate(
                                streamKey = pending.streamKey,
                                id = pending.id.ifBlank { null },
                                name = pending.name,
                                arguments = pending.args.toString(),
                            )
                        )
                    }
                }

                if (!choice?.finishReason.isNullOrBlank()) {
                    // Several OpenAI-compatible gateways return "stop" (or close directly)
                    // after streaming a perfectly valid structured tool call. The accumulated
                    // call is authoritative; never discard it based on the terminal spelling.
                    emitPendingStructuredToolCalls()
                }

                response.usage?.let { usage ->
                    emit(
                        StreamEvent.UsageUpdate(
                            tokenCount = usage.totalTokens,
                            thoughtsTokenCount = usage.completionTokensDetails?.reasoningTokens ?: 0
                        )
                    )
                }

                if (!choice?.finishReason.isNullOrBlank() && terminalDeadlineNanos == null) {
                    terminalDeadlineNanos =
                        System.nanoTime() +
                            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                terminalSseGraceMillis.coerceAtLeast(1L)
                            )
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
            }
        }

        // EOF without [DONE]/finish_reason is common on self-hosted compatible endpoints. If a
        // structured call started, complete that same live segment instead of silently ending.
        emitPendingStructuredToolCalls()

        textToolParser?.flush(
            onText = { emitFilteredText(it) },
            onUpdate = { emitTextToolUpdate(it) },
            onComplete = { emitCompletedTextTool(it) },
            onMalformed = { emitMalformedTextTool(it) },
        )

        thinkParser.flush(
            onText = { emitAndAccumulate(StreamEvent.TextChunk(it)) },
            onThought = { emitAndAccumulate(StreamEvent.ThoughtChunk(it)) },
            thinkingEnabled = config.thinkingEnabled
        )

        // Fallback (#33 path B): some OpenAI-compatible servers (llama.cpp et al.) finish with
        // finish_reason == "stop" and put the tool call in the content text instead of the
        // structured delta.tool_calls field. If we never saw structured tool calls but tools were
        // offered, parse them out of the accumulated content so the generation enters the
        // tool-call phase instead of just printing the JSON as an answer. Brings these servers to
        // parity with Ollama, which reads its structured tool_calls field directly.
        if (
            !structuredToolCallsEmitted &&
            !textToolCallsEmitted &&
            !config.tools.isNullOrEmpty()
        ) {
            val parsed = ToolCallTextParser.parse(contentBuf.toString())
            if (parsed.size == 1) {
                emit(StreamEvent.ToolCallRequest(syntheticToolCallId(), parsed[0].name, parsed[0].arguments))
            } else if (parsed.size > 1) {
                emit(StreamEvent.ToolCallsRequest(parsed.map {
                    StreamEvent.ToolCallRequest(syntheticToolCallId(), it.name, it.arguments)
                }))
            }
        }

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Stream cancelled")
        }
    }

    private fun endpointCandidates(baseUrl: String, path: String): List<String> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val primary = "$normalizedBaseUrl/$cleanPath"
        if (!retryMissingV1BaseUrl || normalizedBaseUrl.isBlank() ||
            BaseUrlResolver.hasVersionSegment(normalizedBaseUrl)
        ) {
            return listOf(primary)
        }
        return listOf(primary, "$normalizedBaseUrl/v1/$cleanPath")
    }

    /** Synthetic id for tool calls recovered from content text (#33 path B), where the server
     *  provides no id. Unique per call so the result can still be paired back to the request. */
    private fun syntheticToolCallId(): String =
        "call_text_${java.util.UUID.randomUUID()}"

    private fun buildGenerationError(
        statusCode: Int,
        errorRaw: String,
        endpointUrls: List<String>
    ): GenerationError {
        val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
            "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
        } else {
            ""
        }
        return try {
            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
            GenerationError.Api(
                code = errorJson.error.code ?: statusCode.toString(),
                type = errorJson.error.type,
                message = errorJson.error.message + endpointHint
            )
        } catch (_: Exception) {
            GenerationError.Network(statusCode = statusCode, message = errorRaw + endpointHint)
        }
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
            val endpointUrls = endpointCandidates(effectiveBaseUrl, "models")
            val headers = authHeaders(apiKey)
            var lastParseError: Exception? = null

            for ((index, endpointUrl) in endpointUrls.withIndex()) {
                val responseText = HttpClient.fetchModels(endpointUrl, headers)
                if (responseText == null) {
                    if (index < endpointUrls.lastIndex) {
                        DebugLog.w("AgoraAPI", "Failed to fetch $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    }
                    continue
                }

                try {
                    return@withContext json.decodeFromString<OpenAiModelListResponse>(responseText)
                        .data.map { it.id }.sorted()
                } catch (e: Exception) {
                    lastParseError = e
                    if (index < endpointUrls.lastIndex) {
                        DebugLog.w("AgoraAPI", "Failed to parse $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    }
                }
            }

            if (lastParseError != null) {
                DebugLog.e("AgoraAPI", "Failed to parse $name models", lastParseError)
            } else {
                DebugLog.e("AgoraAPI", "Failed to fetch $name models: empty response")
            }
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("AgoraAPI", "Failed to fetch $name models", e)
            emptyList()
        }
    }
}
