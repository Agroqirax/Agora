package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class DeepSeekProvider : LlmProvider {
    override val name: String = "DeepSeek"
    override val defaultBaseUrl: String = "https://api.deepseek.com"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.deepseek.com"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = convertToOpenAiMessages(
            messages = limitedPath,
            systemPrompt = config.systemPrompt,
            includeImages = true
        )

        val requestBody = OpenAiChatRequest(
            model = modelName,
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
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), requestBody)
            Log.d("AgoraAPI", "[DeepSeek] REQ → $baseUrl/chat/completions | model=$modelName | msgs=${apiMessages.size} | thinking=${config.thinkingEnabled} | tools=${config.tools?.size ?: 0}")
            Log.d("AgoraAPI", "[DeepSeek] BODY: ${requestBodyJson.take(4000)}")
            connection.outputStream.bufferedWriter().use {
                it.write(requestBodyJson)
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var inThinkingBlock = false
                var pendingBuffer = ""
                val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()

                var line = reader.readLine()
                while (line != null && currentCoroutineContext().isActive) {
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        if (jsonStr == "[DONE]") break

                        try {
                            val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                            val choice = response.choices?.firstOrNull()
                            val delta = choice?.delta

                            delta?.reasoningContent?.let { reasoning ->
                                if (reasoning.isNotEmpty()) {
                                    emit(StreamEvent.ThoughtChunk(reasoning, null))
                                }
                            }

                            delta?.content?.let { content ->
                                if (content.isNotEmpty()) {
                                    var remaining = pendingBuffer + content
                                    pendingBuffer = ""
                                    while (remaining.isNotEmpty() && currentCoroutineContext().isActive) {
                                        if (!inThinkingBlock) {
                                            val startIdx = remaining.indexOf("<think>")
                                            if (startIdx != -1) {
                                                val before = remaining.substring(0, startIdx)
                                                if (before.isNotEmpty()) emit(StreamEvent.TextChunk(before))
                                                inThinkingBlock = true
                                                remaining = remaining.substring(startIdx + 7)
                                            } else {
                                                val partialIdx = remaining.indexOf('<')
                                                if (partialIdx > 0) {
                                                    emit(StreamEvent.TextChunk(remaining.substring(0, partialIdx)))
                                                    pendingBuffer = remaining.substring(partialIdx)
                                                } else {
                                                    emit(StreamEvent.TextChunk(remaining))
                                                }
                                                remaining = ""
                                            }
                                        } else {
                                            val endIdx = remaining.indexOf("</think>")
                                            if (endIdx != -1) {
                                                val thought = remaining.substring(0, endIdx)
                                                if (thought.isNotEmpty() && config.thinkingEnabled) {
                                                    emit(StreamEvent.ThoughtChunk(thought, null))
                                                }
                                                inThinkingBlock = false
                                                remaining = remaining.substring(endIdx + 8)
                                            } else {
                                                val partialIdx = remaining.indexOf('<')
                                                if (partialIdx > 0) {
                                                    val thoughtPart = remaining.substring(0, partialIdx)
                                                    if (thoughtPart.isNotEmpty() && config.thinkingEnabled) {
                                                        emit(StreamEvent.ThoughtChunk(thoughtPart, null))
                                                    }
                                                    pendingBuffer = remaining.substring(partialIdx)
                                                } else {
                                                    if (config.thinkingEnabled) {
                                                        emit(StreamEvent.ThoughtChunk(remaining, null))
                                                    }
                                                }
                                                remaining = ""
                                            }
                                        }
                                    }
                                }
                            }

                            delta?.toolCalls?.forEach { tc ->
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

                    try {
                        line = reader.readLine()
                    } catch (e: SocketTimeoutException) {
                        if (!currentCoroutineContext().isActive) break
                        continue
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e("AgoraAPI", "[DeepSeek] ERR $responseCode: $errorRaw")
                emit(StreamEvent.Error("Error $responseCode: $errorRaw"))
            }
        } catch (e: CancellationException) {
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
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
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
            Log.e("AgoraAPI", "Failed to fetch DeepSeek models", e)
            emptyList()
        }
    }
}
