package com.newoether.agora.api

import android.content.Context
import android.util.Log
import com.newoether.agora.api.util.StreamingThinkTagParser
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class LocalProvider(
    private val context: Context,
    private val settingsManager: SettingsManager
) : LlmProvider {

    companion object {
        private const val TAG = "LocalProvider"
    }

    override val name: String = "Local"
    override val defaultBaseUrl: String = ""

    private var currentEngine: LlamaChatEngine? = null
    private val engineLock = Mutex()

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val chatModels = settingsManager.localChatModels.first()
        val modelConfig = chatModels.find { it.modelId == config.modelId }
        if (modelConfig == null) {
            emit(StreamEvent.Error("Local model not found: ${config.modelId}"))
            return@flow
        }

        val engine = ensureEngineLoaded(modelConfig)
        if (engine == null) {
            emit(StreamEvent.Error("Failed to load model: ${modelConfig.alias}"))
            return@flow
        }

        // Convert messages to chat template messages
        val templateMessages = buildTemplateMessages(messages, config.systemPrompt)

        // Apply chat template to get prompt string
        val prompt = engine.applyTemplate(templateMessages, addAss = true)
        if (prompt == null) {
            emit(StreamEvent.Error("Failed to format prompt with chat template"))
            return@flow
        }

        Log.d(TAG, "Generated prompt: ${prompt.take(200)}...")

        // Generate tokens with think tag parsing
        var totalTokens = 0
        val thinkParser = StreamingThinkTagParser()
        try {
            engine.generate(
                prompt = prompt,
                temperature = modelConfig.temperature,
                topP = modelConfig.topP,
                maxTokens = modelConfig.maxTokens
            ).collect { token ->
                if (!coroutineContext.isActive) {
                    engine.cancel()
                    return@collect
                }
                totalTokens++
                thinkParser.feed(
                    content = token,
                    thinkingEnabled = config.thinkingEnabled,
                    onText = { emit(StreamEvent.TextChunk(it)) },
                    onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                )
            }
            thinkParser.flush(
                onText = { emit(StreamEvent.TextChunk(it)) },
                onThought = { emit(StreamEvent.ThoughtChunk(it)) }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            engine.cancel()
            emit(StreamEvent.Error("Generation cancelled"))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            emit(StreamEvent.Error("Generation failed: ${e.message}"))
            return@flow
        }

        emit(StreamEvent.UsageUpdate(totalTokens))
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureEngineLoaded(model: com.newoether.agora.data.LocalChatModelConfig): LlamaChatEngine? {
        return engineLock.withLock {
            val existing = currentEngine
            if (existing != null && existing.modelPath == model.localFilePath) {
                existing.resetContext()
                existing
            } else {
                existing?.close()
                val engine = LlamaChatEngine(model.localFilePath, model.nCtx)
                if (engine.load()) {
                    currentEngine = engine
                    engine
                } else {
                    null
                }
            }
        }
    }

    private fun buildTemplateMessages(
        messages: List<ChatMessage>,
        systemPrompt: String?
    ): List<ChatTemplateMessage> {
        val result = mutableListOf<ChatTemplateMessage>()

        if (!systemPrompt.isNullOrBlank()) {
            result.add(ChatTemplateMessage(role = "system", content = systemPrompt))
        }

        for (msg in messages) {
            if (msg.participant == Participant.ERROR) continue

            // Tool call messages: treat as assistant
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        result.add(ChatTemplateMessage(
                            role = "assistant",
                            content = "Tool call: ${seg.toolName}\nArguments: ${seg.toolArgs}"
                        ))
                    }
                } else if (msg.toolCall != null) {
                    result.add(ChatTemplateMessage(
                        role = "assistant",
                        content = "Tool call: ${msg.toolCall.toolName}\nArguments: ${msg.toolCall.arguments}"
                    ))
                }
                continue
            }

            // Tool result messages: treat as user (tool results)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        result.add(ChatTemplateMessage(
                            role = "user",
                            content = "Tool result: ${seg.toolResult ?: ""}"
                        ))
                    }
                } else if (msg.toolCall != null) {
                    result.add(ChatTemplateMessage(
                        role = "user",
                        content = "Tool result: ${msg.toolCall.result}"
                    ))
                }
                continue
            }

            // Normal messages
            val role = when (msg.participant) {
                Participant.USER -> "user"
                Participant.MODEL -> "assistant"
                Participant.ERROR -> "user"
            }

            result.add(ChatTemplateMessage(role = role, content = msg.text))
        }

        return result
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> {
        return settingsManager.localChatModels.first().map { it.modelId }
    }

    fun close() {
        currentEngine?.close()
        currentEngine = null
    }
}
