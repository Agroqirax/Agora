package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.StreamingThinkTagParser
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.prepareMessages
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
     */
    protected abstract suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    )

    // -- Template method --

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl

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
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), request)
            DebugLog.d("AgoraAPI", "[$name] REQ → $baseUrl/chat/completions | model=${config.modelId} | msgs=${apiMessages.size} | tools=${config.tools?.size ?: 0}")

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotEmpty()) headers["Authorization"] = "Bearer ${config.apiKey}"
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = 3
            val retryableCodes = setOf(401, 429, 502, 503, 504)
            var attempt = 0
            var done = false

            while (attempt < maxAttempts && !done) {
                attempt++
                val handle = HttpClient.streamPost("$baseUrl/chat/completions", requestBodyJson, headers)
                try {
                if (handle.code == 200) {
                    done = true
                    val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()

                    while (currentCoroutineContext().isActive) {
                        val line: String?
                        try {
                            line = handle.readLine()
                            if (line == null) break
                        } catch (e: SocketTimeoutException) {
                            if (!currentCoroutineContext().isActive) break
                            continue
                        }

                        if (!line.startsWith("data: ")) continue
                        val jsonStr = line.substring(6).trim()
                        if (jsonStr == "[DONE]") break

                        try {
                            val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                            val choice = response.choices?.firstOrNull()

                            choice?.delta?.let { delta ->
                                parseDeltaContent(delta, config, thinkParser) { emit(it) }

                                delta.toolCalls?.forEach { tc ->
                                    val existing = if (tc.id != null) pendingToolCalls.values.firstOrNull { it.id == tc.id } else null
                                    val pending = if (existing != null) existing else {
                                        val idx = tc.index ?: pendingToolCalls.size
                                        pendingToolCalls.getOrPut(idx) { PendingToolCall() }
                                    }
                                    if (tc.id != null) pending.id = tc.id
                                    tc.function?.name?.let { pending.name = it }
                                    tc.function?.arguments?.let {
                                        pending.args.append(if (it is JsonPrimitive) it.content else it.toString())
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
                                emit(
                                    StreamEvent.UsageUpdate(
                                        tokenCount = usage.totalTokens,
                                        thoughtsTokenCount = usage.completionTokensDetails?.reasoningTokens ?: 0
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
                        }
                    }

                    thinkParser.flush(
                        onText = { emit(StreamEvent.TextChunk(it)) },
                        onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                    )

                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("Stream cancelled")
                    }
                } else {
                    val errorRaw = handle.errorBody ?: "Unknown error"
                    DebugLog.e("AgoraAPI", "[$name] ERR ${handle.code}: $errorRaw")

                    if (handle.code in retryableCodes && attempt < maxAttempts) {
                        DebugLog.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${1000 * attempt}ms...")
                        emit(StreamEvent.Retrying(attempt, maxAttempts))
                        delay(1000L * attempt)
                    } else {
                        val errorMessage = try {
                            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                            "Error ${errorJson.error.code ?: handle.code} (${errorJson.error.type ?: "UNKNOWN"}): ${errorJson.error.message}"
                        } catch (_: Exception) {
                            "Error ${handle.code}: $errorRaw"
                        }
                        emit(StreamEvent.Error(errorMessage))
                    }
                }
                } finally { handle.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error("Request timed out. The server took too long to respond."))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error("Connection refused. Please check your internet connection or if the service is available."))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error("Network error: Unable to reach the server. Please check your internet connection."))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error("Error: ${e.localizedMessage ?: "An unexpected error occurred."}"))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
            val responseText = HttpClient.fetchModels(
                "$effectiveBaseUrl/models",
                mapOf("Authorization" to "Bearer $apiKey")
            ) ?: run {
                DebugLog.e("AgoraAPI", "Failed to fetch $name models: empty response")
                return@withContext emptyList()
            }
            json.decodeFromString<OpenAiModelListResponse>(responseText)
                .data.map { it.id }.sorted()
        } catch (e: Exception) {
            DebugLog.e("AgoraAPI", "Failed to fetch $name models", e)
            emptyList()
        }
    }
}
