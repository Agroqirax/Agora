package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.MemoryManager

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RequestTokenUsageAccumulator
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.R
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.api.util.projectToolResultImagesToUserMessage
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.util.Constants
import com.newoether.agora.tool.ImageGenToolProvider
import com.newoether.agora.tool.MemoryToolProvider
import com.newoether.agora.tool.RagToolProvider
import com.newoether.agora.tool.ShellToolProvider
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.WebSearchToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/** Never route a request through an arbitrary fallback provider. */
internal fun <T> requireRegisteredProvider(providers: Map<String, T>, name: String): T =
    requireNotNull(providers[name]) { "Provider is not registered: $name" }

private const val STREAM_UI_UPDATE_INTERVAL_MS = 50L
private const val TOOL_UI_UPDATE_INTERVAL_MS = 50L

/**
 * Throttles durable stream snapshots while allowing lifecycle boundaries to force a write.
 * The first snapshot is always accepted, including when the clock moves backwards.
 */
internal class StreamingCheckpointGate(
    private val intervalMs: Long = 1_000L,
) {
    private var lastCheckpointAt: Long? = null

    init {
        require(intervalMs > 0)
    }

    fun shouldCheckpoint(nowMs: Long, force: Boolean = false): Boolean {
        val previous = lastCheckpointAt
        if (!force && previous != null && nowMs >= previous && nowMs - previous < intervalMs) {
            return false
        }
        lastCheckpointAt = nowMs
        return true
    }
}

/**
 * Returns only reasoning produced since the previous tool-round boundary.
 *
 * A model message keeps the full segment timeline for display, while each synthetic tool row must
 * contain only the protocol blocks that belong to that one round. Reusing every historical thought
 * here makes signatures and reasoning grow quadratically across a long agent run.
 */
internal fun toolRoundThoughtSegments(
    segments: List<MessageSegment>,
    fromIndex: Int,
): List<MessageSegment> {
    val safeStart = fromIndex.coerceIn(0, segments.size)
    return segments.subList(safeStart, segments.size).filter { it.type == "thought" }
}

/**
 * Removes only the strict cumulative thought prefix written by older Agora builds.
 *
 * Legacy tool rows for one run were shaped as `[thought 1, ..., thought N, tool N]`; replaying all
 * rows therefore sent the same signed reasoning over and over. Current rows contain only their own
 * round. This tracker accepts both layouts and strips a prefix only when a later row is a strict
 * extension of the exact thought history already observed. Equal or unrelated content is retained,
 * so this never guesses from text and cannot classify an ordinary answer as protocol data.
 */
internal class ToolRoundHistoryCompactor {
    private val thoughtHistoryByRun = mutableMapOf<String, List<MessageSegment>>()

    fun compact(runId: String, segments: List<MessageSegment>): List<MessageSegment> {
        val thoughts = segments.filter { it.type == "thought" }
        if (thoughts.isEmpty()) return segments

        val history = thoughtHistoryByRun[runId].orEmpty()
        val repeatedPrefixSize = history.size.takeIf { prefixSize ->
            prefixSize > 0 &&
                thoughts.size > prefixSize &&
                thoughts.subList(0, prefixSize) == history
        } ?: 0

        thoughtHistoryByRun[runId] = when {
            repeatedPrefixSize > 0 -> thoughts
            history.isEmpty() -> thoughts
            thoughts == history -> history
            else -> history + thoughts
        }
        if (repeatedPrefixSize == 0) return segments

        var thoughtsToDrop = repeatedPrefixSize
        return segments.filter { segment ->
            if (segment.type == "thought" && thoughtsToDrop > 0) {
                thoughtsToDrop--
                false
            } else {
                true
            }
        }
    }
}

data class GenerationConfig(
    val providerName: String,
    val modelId: String,
    val apiKey: String,
    val effectiveSystemPrompt: String?,
    val maxContextWindow: Int,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val openAiServiceTier: String? = null,
    val baseUrl: String?,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
)

data class GenerationContext(
    val conversationId: String? = null,
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.newoether.agora.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val searchMatchLimit: Int = 10,
    val searchContextWindow: Int = 8,
    val webSearchEnabled: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "duckduckgo",
    val webSearchNumResults: Int = 5,
    val webSearchBaseUrl: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "gpt-image-1",
    val imageGenSize: String = "1024x1024",
    val automationToolsEnabled: Boolean = false,
    /** Workers use WorkManager's foreground execution instead of starting our service. */
    val foregroundServiceManagedExternally: Boolean = false,
    val shellEnabled: Boolean = false,
    val shellDevices: List<com.newoether.agora.data.ShellDeviceConfig> = emptyList(),
    val sandboxEnabled: Boolean = false,
    val sandboxSharedStorageEnabled: Boolean = false,
    val imageTranscriptionEnabled: Boolean = false,
    val imageTranscriptionModel: String? = null,
    val imageTranscriptionBatchSize: Int = 3,
    val imageTranscriptionPrompt: String = com.newoether.agora.data.BuiltInPrompts.IMAGE_TRANSCRIPTION_USER,
    val transcriptionProviderName: String = "",
    val transcriptionModelId: String = "",
    val transcriptionApiKey: String = "",
    val transcriptionBaseUrl: String? = null,
    /** Wall-clock budget for a single tool execution; downgrades a blocking tool from a
     *  permanent generation hang to a recoverable tool error (#49). */
    val toolTimeoutMs: Long = Constants.TOOL_EXECUTION_TIMEOUT_MS
)

internal fun applyUserTemplateToMessages(
    messages: List<ChatMessage>,
    prepend: String?,
    postpend: String?
): List<ChatMessage> {
    if (prepend == null && postpend == null) return messages
    val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    return messages.map { msg ->
        val isToolMessage = msg.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (!isToolMessage && msg.participant == Participant.USER && msg.text.isNotEmpty()) {
            val ts = java.util.Date(msg.timestamp)
            val rp = prepend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            val ra = postpend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            if (rp.isEmpty() && ra.isEmpty()) msg
            else msg.copy(text = rp + msg.text + ra)
        } else msg
    }
}

/**
 * The token-gated UI callbacks a single generation drives. Built once per call by
 * [ConversationGenerationState.callbacksFor], so each generation entry point
 * ([MessageGenerationController]'s send / regenerate / edit) wires the per-conversation
 * ownership tokens in exactly one place instead of re-threading lambdas by hand.
 *
 * Note: the generation-slot lifecycle (generating flag / active-conversation set) is owned by
 * [MessageGenerationController] via [ConversationGenerationState.acquireForSend] /
 * [ConversationGenerationState.tryAcquireForReplacement] / [ConversationGenerationState.endGeneration],
 * NOT by these callbacks — GenerationManager only streams tokens into the message and persists the
 * terminal DB row.
 */
data class GenerationCallbacks(
    val onStreamUpdate: (ChatMessage) -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val onStreamClear: () -> Unit,
    val isLatestPersist: () -> Boolean,
    /** True when the user queued a send behind this generation. The tool loop checks it at
     *  each round boundary and ends the generation there so the queue can flush immediately
     *  (steering) instead of waiting out the entire loop. Headless runs keep the default. */
    val hasQueuedSends: () -> Boolean = { false },
)

class GenerationManager(
    private val app: Application,
    private val conversations: com.newoether.agora.data.repository.ConversationRepository,
    private val memoryManager: MemoryManager,
    private val providers: Map<String, LlmProvider>,
    private val context: android.content.Context,
    private val sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null,
    additionalToolProviders: List<ToolProvider> = emptyList(),
) {
    var onMessagePersisted: ((messageId: String, text: String) -> Unit)? = null

    /** User-confirmation gate for remote shell mutations. Set by the ViewModel.
     *  Returns true to proceed, false to deny. */
    var onConfirmShellCommand: (suspend (server: String, summary: String) -> Boolean)? = null

    private val memoryToolProvider = MemoryToolProvider(memoryManager)
    private val webSearchToolProvider = WebSearchToolProvider()
    private val ragToolProvider = RagToolProvider(conversations)
    private val imageGenToolProvider = ImageGenToolProvider(app)
    private val shellToolProvider = ShellToolProvider(
        sandboxFactory = sandboxFactory,
        imageStore = ToolImageStore(app),
    ).also { stp ->
        // Forward to the ViewModel-provided gate at call time (read the var lazily).
        stp.confirm = { server, summary -> onConfirmShellCommand?.invoke(server, summary) ?: true }
    }
    private val builtInToolProviders: List<ToolProvider> = listOf(
        memoryToolProvider, webSearchToolProvider, ragToolProvider, imageGenToolProvider, shellToolProvider
    )
    private val toolProviders: List<ToolProvider> = builtInToolProviders + additionalToolProviders

    private fun resolveToolPresentationMetadata(name: String): ToolPresentationMetadata? {
        if (name.isBlank()) return null
        for (provider in toolProviders) {
            provider.presentationMetadata(name)?.let { return it }
        }
        return null
    }

    fun buildImageGenTool(ctx: GenerationContext): List<ToolDefinition> =
        imageGenToolProvider.definitions(ctx)

    private val transcriptionManager = TranscriptionManager(providers, conversations, context)

    companion object {
        private val FILE_TOOL_NAMES = setOf(
            "file_read",
            "file_write",
            "file_edit",
            "file_glob",
            "file_grep",
            "view_image",
        )
    }

    private fun getProviderInstance(name: String): LlmProvider =
        requireRegisteredProvider(providers, name)

    // Image/video frame extraction lives in ImageProcessor (single source of truth).
    private val imageProcessor = ImageProcessor(app)

    suspend fun processImages(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = imageProcessor.processImagesAndVideos(uris, sliceConfigs)

    fun buildMemoryTools(ctx: GenerationContext): List<ToolDefinition> =
        memoryToolProvider.definitions(ctx)

    fun buildWebSearchTool(ctx: GenerationContext): List<ToolDefinition> =
        webSearchToolProvider.definitions(ctx)

    fun buildRagTool(ctx: GenerationContext): List<ToolDefinition> =
        ragToolProvider.definitions(ctx)

    fun buildShellTool(ctx: GenerationContext): List<ToolDefinition> {
        val all = shellToolProvider.definitions(ctx)
        return all.filter { it.function.name !in FILE_TOOL_NAMES }
    }

    fun buildFileTool(ctx: GenerationContext): List<ToolDefinition> {
        val all = shellToolProvider.definitions(ctx)
        return all.filter { it.function.name in FILE_TOOL_NAMES }
    }


    /** Semantic message search — delegates to [RagToolProvider], which owns the
     *  embedding-search logic. Kept here as the entry point used by ChatViewModel's
     *  in-app conversation search. */
    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> =
        ragToolProvider.semanticSearch(query, limit, ctx)

    private suspend fun executeTool(
        name: String,
        arguments: String,
        ctx: GenerationContext,
        onEvent: suspend (ToolExecutionEvent) -> Unit,
    ): ToolExecutionResult {
        val completeArguments = arguments.ifBlank { "{}" }
        val argumentsAreCompleteObject = runCatching {
            Json.parseToJsonElement(completeArguments).jsonObject
        }.isSuccess
        if (!argumentsAreCompleteObject) {
            return ToolExecutionResult(
                text = "Error executing tool '$name': arguments are not a complete JSON object",
                isError = true,
            )
        }
        return try {
            for (provider in toolProviders) {
                if (provider.handles(name)) {
                    // Tools run inline on the stream-consuming coroutine, so a tool that blocks
                    // forever hangs the whole generation. withTimeout alone cannot bound a
                    // provider stuck in non-cancellable blocking IO (it only requests
                    // cancellation, then waits for the block to finish) — so the attempt runs
                    // under a detached Job and the deadline waits only on await(), which is
                    // always promptly cancellable. On timeout the attempt is cancelled and
                    // abandoned (it dies at its next suspension point or IO-layer timeout) and
                    // the tool loop continues with an error instead of hanging (#49).
                    val attemptJob = Job()
                    val attempt = CoroutineScope(currentCoroutineContext() + attemptJob).async {
                        var completedResult: ToolExecutionResult? = null
                        provider.executeEvents(name, completeArguments, ctx).collect { event ->
                            if (event is ToolExecutionEvent.Completed) {
                                completedResult = event.result
                            }
                            onEvent(event)
                        }
                        completedResult
                            ?: ToolExecutionResult(
                                text = "Error executing tool '$name': provider ended without a result",
                                isError = true,
                            )
                    }
                    try {
                        return withTimeout(ctx.toolTimeoutMs) { attempt.await() }
                    } finally {
                        attemptJob.cancel()
                    }
                }
            }
            ToolExecutionResult(text = "Unknown tool: $name", isError = true)
        } catch (e: TimeoutCancellationException) {
            // A timeout is a recoverable tool failure, NOT a generation cancellation — return an
            // error string so the model can react, instead of unwinding the whole generation.
            ToolExecutionResult(
                text = "Error executing tool '$name': timed out after ${ctx.toolTimeoutMs}ms",
                isError = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                text = "Error executing tool '$name': ${e.localizedMessage ?: "Unknown error"}",
                isError = true,
            )
        }
    }

    private fun appendBoundedToolOutput(
        current: String?,
        delta: String,
        maxChars: Int = 32 * 1024,
    ): String {
        if (delta.isEmpty()) return current.orEmpty()
        val combined = current.orEmpty() + delta
        return if (combined.length <= maxChars) combined
        else combined.takeLast(maxChars)
    }

    private fun finalToolState(result: String): String {
        if (result.isEmpty()) return com.newoether.agora.model.ToolExecutionStates.EMPTY
        val resultObject = runCatching {
            Json.parseToJsonElement(result).jsonObject
        }.getOrNull()
        val errorCode = (resultObject?.get("error") as? JsonPrimitive)?.content
        if (errorCode == "no_results") {
            return com.newoether.agora.model.ToolExecutionStates.EMPTY
        }
        if (
            result.startsWith("Error", ignoreCase = true) ||
            errorCode != null
        ) {
            return com.newoether.agora.model.ToolExecutionStates.FAILED
        }
        val isBackground = (resultObject?.get("background") as? JsonPrimitive)
            ?.content
            ?.toBooleanStrictOrNull() == true ||
            (
                (resultObject?.get("state") as? JsonPrimitive)
                    ?.content
                    ?.equals("running", ignoreCase = true) == true &&
                    resultObject.get("job_id") != null
                )
        return if (isBackground) {
            com.newoether.agora.model.ToolExecutionStates.BACKGROUND_RUNNING
        } else {
            com.newoether.agora.model.ToolExecutionStates.SUCCEEDED
        }
    }

    private fun applyUserTemplate(messages: List<ChatMessage>, prepend: String?, postpend: String?): List<ChatMessage> {
        return applyUserTemplateToMessages(messages, prepend, postpend)
    }

    private fun appendMergedSegment(target: MutableList<MessageSegment>, segment: MessageSegment) {
        val last = target.lastOrNull()
        val canMerge = last != null &&
            last.type == segment.type &&
            (
                segment.type == "answer" ||
                    (
                        segment.type == "thought" &&
                            last.signature == null &&
                            segment.signature == null
                        )
                )
        if (canMerge) {
            target[target.lastIndex] = last.copy(
                content = last.content + segment.content,
                signature = segment.signature ?: last.signature,
                signatureProvider = segment.signatureProvider ?: last.signatureProvider,
                durationMs = mergeDurationMs(last.durationMs, segment.durationMs)
            )
        } else {
            target.add(segment)
        }
    }

    private fun mergeDurationMs(first: Long?, second: Long?): Long? {
        val merged = (first ?: 0L) + (second ?: 0L)
        return merged.takeIf { it > 0L }
    }

    private fun buildLiveSegments(
        flushed: List<MessageSegment>,
        answerBuf: StringBuilder,
        thoughtBuf: StringBuilder,
        signature: String? = null,
        signatureProvider: String? = null,
        thoughtDurationMs: Long? = null
    ): List<MessageSegment>? {
        val result = flushed.toMutableList()
        if (answerBuf.isNotEmpty()) {
            appendMergedSegment(result, MessageSegment(type = "answer", content = answerBuf.toString()))
        }
        if (thoughtBuf.isNotEmpty()) {
            appendMergedSegment(result, MessageSegment(
                type = "thought",
                content = thoughtBuf.toString(),
                signature = signature,
                signatureProvider = signatureProvider,
                durationMs = thoughtDurationMs
            ))
        }
        return result.ifEmpty { null }
    }

    private suspend fun buildApiPath(
        parentId: String?,
        conversationId: String,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        config: GenerationConfig,
        ctx: GenerationContext,
        loadedMessages: List<MessageEntity>? = null,
    ): Pair<List<ChatMessage>, ProviderConfig> = withContext(Dispatchers.Default) {
        val dbMessages = loadedMessages
            ?: conversations.getMessagesForConversationSnapshot(conversationId)
        val messagesById = dbMessages.associateBy { it.id }
        val pathEntities = mutableListOf<MessageEntity>()
        var currId: String? = parentId
        while (currId != null) {
            val msg = messagesById[currId] ?: break
            pathEntities.add(0, msg)
            currId = msg.parentId
        }
        // Inject each persisted tool protocol row exactly once. A queued intervention may have a
        // result_ ancestor while that same round is also reachable as a side chain of the visible
        // model message; ApiPathAssembler owns that overlap and prevents duplicate replay.
        val expanded = ApiPathAssembler.assemble(pathEntities, dbMessages)
        val toolHistoryCompactor = ToolRoundHistoryCompactor()
        val currentPath = expanded.map {
            val decodedSegments = it.toolCallJson?.let { json ->
                try {
                    Json.decodeFromString<List<MessageSegment>>(json)
                } catch (_: Exception) {
                    null
                }
            }
            val segs = if (
                decodedSegments != null &&
                it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            ) {
                toolHistoryCompactor.compact(it.runId, decodedSegments)
            } else {
                decodedSegments
            }
            val toolCall = segs?.lastOrNull { s -> s.type == "tool" }?.let { s ->
                ToolCallData(
                    toolName = s.toolName ?: "",
                    arguments = s.toolArgs ?: "{}",
                    result = s.toolResult ?: "",
                    signature = s.signature,
                    toolCallId = s.toolCallId,
                    resultImages = s.toolImages,
                    displayName = s.toolDisplayName,
                    resultText = s.toolResultText,
                    structuredResult = s.toolStructuredResult,
                )
            }
            val meta = it.attachmentMeta?.let { json -> try { Json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(json) } catch (_: Exception) { null } }
            val attachmentText = if (meta != null) {
                meta.items.mapNotNull { item ->
                    val content = item.textContent
                    val transcription = item.transcription
                    val includeTranscription = ctx.imageTranscriptionEnabled && transcription != null && transcription.isNotBlank()
                    when {
                        content != null -> {
                            val label = item.fileName ?: "file"
                            "\n\n--- File: $label ---\n$content"
                        }
                        includeTranscription -> {
                            val label = item.fileName ?: "image"
                            "\n\n--- Image Transcription: $label ---\n$transcription"
                        }
                        else -> null
                    }
                }.joinToString("")
            } else ""
            val combinedText = if (attachmentText.isNotBlank()) it.text + attachmentText else it.text
            val hasTranscription = ctx.imageTranscriptionEnabled && meta != null && meta.items.any { item -> !item.transcription.isNullOrBlank() }
            val effectiveImages = if (hasTranscription) emptyList() else it.images
            ChatMessage(
                id = it.id,
                parentId = it.parentId,
                text = combinedText,
                images = effectiveImages,
                thoughts = it.thoughts,
                thoughtTitle = it.thoughtTitle,
                tokenCount = it.tokenCount,
                tokenUsage = TokenUsage.fromPersisted(
                    totalTokenCount = it.tokenCount,
                    inputTokenCount = it.inputTokenCount,
                    cachedInputTokenCount = it.cachedInputTokenCount,
                    uncachedInputTokenCount = it.uncachedInputTokenCount,
                    outputTokenCount = it.outputTokenCount,
                    reasoningTokenCount = it.reasoningTokenCount,
                ),
                status = it.status,
                participant = it.participant,
                timestamp = it.timestamp,
                thoughtTimeMs = it.thoughtTimeMs,
                modelName = it.modelName,
                segments = segs,
                toolCall = toolCall,
                runId = it.runId,
                runSequence = it.runSequence,
                consumedAtPass = it.consumedAtPass,
            )
        }.let(::projectGenerationStatusesForApi)
            .let { path ->
                if (isRegenerate && replaceMessageId != null) {
                    val oldIdx = path.indexOfFirst { it.id == replaceMessageId }
                    if (oldIdx >= 0) path.take(oldIdx) else path
                } else path
            }

        val allTools = toolProviders.flatMap { it.definitions(ctx) }
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = config.effectiveSystemPrompt,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            thinkingEnabled = config.thinkingEnabled,
            thinkingLevel = config.thinkingLevel,
            thinkingBudgetEnabled = config.thinkingBudgetEnabled,
            thinkingBudgetTokens = config.thinkingBudgetTokens,
            openAiServiceTier = config.openAiServiceTier,
            baseUrl = config.baseUrl,
            tools = allTools,
            userPrepend = config.userPrepend,
            userPostpend = config.userPostpend,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        Pair(currentPath, providerConfig)
    }

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        runId: String,
        pass: Int,
        config: GenerationConfig,
        ctx: GenerationContext,
        generationJob: kotlinx.coroutines.Job?,
        callbacks: GenerationCallbacks,
        streamScope: StreamScope? = null,
        requestTrace: com.newoether.agora.api.HttpClient.RequestTrace? = null,
    ) = com.newoether.agora.api.HttpClient.withStreamScope(streamScope, requestTrace) {
        // Bind every provider/tool stream opened by this generation to its coroutine-local
        // StreamScope. Parallel conversations therefore cannot overwrite one another's Stop
        // ownership, while child dispatcher hops inherit the same context element.
        // Destructure into locals so the body below reads exactly as before.
        val (onStreamUpdate, onLoadingChange, onStreamClear, isLatestPersist) = callbacks

        var foregroundLeaseAcquired = false
        // Set when the tool loop ends early because a send was queued behind this generation.
        var interruptedForQueuedSend = false
        var totalText = ""
        var totalThoughts = ""
        var thinkingPlaceholder = ""
        var totalThoughtTitle: String? = null
        var totalTokenCount = 0
        var totalTokenUsage: TokenUsage? = null
        val tokenUsageAccumulator = RequestTokenUsageAccumulator()
        var totalThoughtTimeMs: Long? = null
        var cumulativeThoughtMs: Long = 0
        var currentThoughtStartMs: Long? = null
        var currentThoughtDurationMs: Long = 0
        var currentStatus = MessageStatus.SENDING
        var retryText: String? = null
        val segments = mutableListOf(MessageSegment(type = "answer"))
        val liveToolSegmentIndices = mutableMapOf<String, Int>()
        val generatedImages = mutableListOf<String>()
        var currentAnswerBuf = StringBuilder()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        var currentThoughtSignatureProvider: String? = null
        var parentId: String? = null
        var modelRunSequence = -1L
        var toolPath = emptyList<ChatMessage>()
        var latestTranscriptionSnapshot: ChatMessage? = null
        var transcriptionReturned = false
        val checkpointGate = StreamingCheckpointGate()
        val checkpointWriter = StreamingCheckpointWriter(
            scope = CoroutineScope(currentCoroutineContext()),
            persist = { message ->
                isLatestPersist() &&
                    conversations.updateStreamingMessageCheckpoint(message)
            },
            onFailure = { error ->
                DebugLog.e("AgoraVM", "Failed to persist streaming checkpoint", error)
            },
        )

        suspend fun persistStreamingCheckpoint(message: ChatMessage, force: Boolean = false) {
            if (!isLatestPersist()) return
            val now = System.currentTimeMillis()
            if (!checkpointGate.shouldCheckpoint(now, force)) return
            if (force) checkpointWriter.flush(message) else checkpointWriter.enqueue(message)
        }

        fun adoptIncompleteTranscriptionSnapshot() {
            if (transcriptionReturned) return
            latestTranscriptionSnapshot?.let { snapshot ->
                totalText = snapshot.text
                totalThoughts = snapshot.thoughts.orEmpty()
                totalThoughtTitle = snapshot.thoughtTitle
                totalTokenCount = snapshot.tokenCount
                totalTokenUsage = snapshot.tokenUsage
                totalThoughtTimeMs = snapshot.thoughtTimeMs
                generatedImages.clear()
                generatedImages.addAll(snapshot.images)
                segments.clear()
                segments.addAll(snapshot.segments.orEmpty())
            }
        }

        fun liveThoughtDurationMs(): Long? {
            val liveElapsed = currentThoughtStartMs?.let { System.currentTimeMillis() - it } ?: 0L
            return (currentThoughtDurationMs + liveElapsed).takeIf { it > 0L }
        }

        fun finishCurrentThoughtTiming() {
            val startedAt = currentThoughtStartMs ?: return
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > 0L) {
                cumulativeThoughtMs += elapsed
                currentThoughtDurationMs += elapsed
                totalThoughtTimeMs = cumulativeThoughtMs
            }
            currentThoughtStartMs = null
        }

        try {
            val provider = getProviderInstance(config.providerName)
            onLoadingChange(true)
            // Slot ownership (generating flag / active set) is claimed synchronously by the
            // controller before this coroutine runs — GenerationManager no longer touches it.
            com.newoether.agora.util.CrashReporter.note("generate provider=${config.providerName} regen=$isRegenerate")
            thinkingPlaceholder = context.getString(R.string.thinking_ellipsis)
            val loadedMessages = conversations.getMessagesForConversationSnapshot(conversationId)
            val placeholder = checkNotNull(
                loadedMessages.find { it.id == modelMessageId }
            ) { "Generation placeholder $modelMessageId does not exist" }
            check(placeholder.runId == runId) {
                "Generation placeholder $modelMessageId is not owned by Run $runId"
            }
            check(conversations.getRun(runId)?.currentPass == pass) {
                "Generation pass $pass is not current for Run $runId"
            }
            modelRunSequence = placeholder.runSequence
            parentId = placeholder.parentId
            requestTrace?.mark("generation_state_ready")
            if (!ctx.foregroundServiceManagedExternally) {
                foregroundLeaseAcquired = withContext(Dispatchers.Main) {
                    AgoraForegroundService.acquire(app, modelMessageId)
                }
            }

            // Stage 1: Image Transcription
            var transcriptionPerformed = false
            if (ctx.imageTranscriptionEnabled && ctx.transcriptionModelId.isNotEmpty()) {
                val targets = transcriptionManager.collectTargets(conversationId, parentId)
                if (targets.isNotEmpty()) {
                    val (transcriptionSegments, transcriptionError) = transcriptionManager.transcribe(
                        targets, conversationId,
                        ctx.transcriptionProviderName, ctx.transcriptionModelId,
                        ctx.transcriptionApiKey, ctx.transcriptionBaseUrl,
                        ctx.imageTranscriptionPrompt,
                        generationJob, modelMessageId, startTime
                    ) { snapshot ->
                        latestTranscriptionSnapshot = snapshot
                        onStreamUpdate(snapshot)
                        persistStreamingCheckpoint(snapshot)
                    }
                    transcriptionReturned = true
                    latestTranscriptionSnapshot?.let {
                        // The last chunk may arrive inside the throttle window and then be followed
                        // by a long provider pause, so seal the transcription stage explicitly.
                        persistStreamingCheckpoint(it, force = true)
                    }
                    if (transcriptionSegments.isNotEmpty()) {
                        segments.addAll(0, transcriptionSegments)
                    }
                    if (transcriptionError != null) {
                        totalText = transcriptionError
                        currentStatus = MessageStatus.ERROR
                        transcriptionPerformed = true
                    } else {
                        transcriptionPerformed = true
                    }
                }
            }

            if (currentStatus != MessageStatus.ERROR) {
            val (currentPath, rawProviderConfig) = buildApiPath(
                parentId,
                conversationId,
                isRegenerate,
                replaceMessageId,
                config,
                ctx,
                loadedMessages,
            )
            requestTrace?.mark(
                "api_path_ready",
                "messages=${currentPath.size} tools=${rawProviderConfig.tools.orEmpty().size}",
            )
            val providerConfig = if (transcriptionPerformed) rawProviderConfig.copy(includeImages = false) else rawProviderConfig

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()
            val completedToolCalls = linkedMapOf<String, StreamEvent.ToolCallRequest>()
            var toolRoundSegmentCursor = 0

            var lastEmitMs = 0L
            var firstUiPublishPending = true

            fun modelMessage() = ChatMessage(
                id = modelMessageId, parentId = parentId,
                text = totalText, thoughts = totalThoughts.ifBlank { null },
                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                tokenUsage = totalTokenUsage,
                status = currentStatus, participant = Participant.MODEL,
                timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs,
                modelName = modelName, toolCall = toolCallData,
                images = generatedImages.toList(),
                segments = buildLiveSegments(
                    segments,
                    currentAnswerBuf,
                    currentThoughtBuf,
                    currentThoughtSignature,
                    currentThoughtSignatureProvider,
                    liveThoughtDurationMs()
                ),
                retryText = retryText,
                runId = runId,
                runSequence = modelRunSequence,
            )

            suspend fun publishStreamUpdate(forceCheckpoint: Boolean = false) {
                val snapshot = modelMessage()
                onStreamUpdate(snapshot)
                if (firstUiPublishPending) {
                    firstUiPublishPending = false
                    requestTrace?.mark("first_ui_publish")
                }
                persistStreamingCheckpoint(snapshot, force = forceCheckpoint)
            }

            fun flushAnswerSegment() {
                if (currentAnswerBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(type = "answer", content = currentAnswerBuf.toString()))
                    currentAnswerBuf = StringBuilder()
                }
            }

            fun flushThoughtSegment() {
                finishCurrentThoughtTiming()
                if (currentThoughtBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(
                        type = "thought",
                        content = currentThoughtBuf.toString(),
                        signature = currentThoughtSignature,
                        signatureProvider = currentThoughtSignatureProvider,
                        durationMs = currentThoughtDurationMs.takeIf { it > 0L }
                    ))
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                    currentThoughtSignatureProvider = null
                }
                currentThoughtDurationMs = 0L
            }

            fun updateToolSegment(
                toolCallId: String,
                update: (MessageSegment) -> MessageSegment,
            ): MessageSegment? {
                val index = segments.indexOfLast { it.toolCallId == toolCallId }
                if (index < 0) return null
                return update(segments[index]).also { segments[index] = it }
            }

            fun upsertStreamingToolSegment(
                streamKey: String,
                toolCallId: String?,
                name: String,
                arguments: String,
                signature: String?,
            ): Pair<Int, Boolean> {
                val existingIndex = liveToolSegmentIndices[streamKey]
                if (existingIndex != null) {
                    val existing = segments[existingIndex]
                    val resolvedName = name.ifBlank { existing.toolName.orEmpty() }
                    val metadata = resolveToolPresentationMetadata(resolvedName)
                    segments[existingIndex] = existing.copy(
                        toolName = resolvedName.ifBlank { existing.toolName },
                        toolArgs = arguments,
                        toolCallId = toolCallId ?: existing.toolCallId ?: streamKey,
                        signature = signature ?: existing.signature,
                        signatureProvider = provider.name.takeIf {
                            signature != null || existing.signature != null
                        },
                        toolState = com.newoether.agora.model.ToolExecutionStates.CALLING,
                        toolTarget = metadata?.target ?: existing.toolTarget,
                        toolDisplayName = metadata?.displayName ?: existing.toolDisplayName,
                    )
                    return existingIndex to false
                }

                flushAnswerSegment()
                flushThoughtSegment()
                val index = segments.size
                val metadata = resolveToolPresentationMetadata(name)
                segments += MessageSegment(
                    type = "tool",
                    toolName = name.ifBlank { null },
                    toolArgs = arguments,
                    toolResult = null,
                    toolCallId = toolCallId ?: streamKey,
                    signature = signature,
                    signatureProvider = provider.name.takeIf { signature != null },
                    toolState = com.newoether.agora.model.ToolExecutionStates.CALLING,
                    toolTarget = metadata?.target,
                    toolDisplayName = metadata?.displayName,
                )
                liveToolSegmentIndices[streamKey] = index
                return index to true
            }

            suspend fun executeToolWithLiveSegment(
                name: String,
                arguments: String,
                toolCallId: String,
            ): ToolExecutionResult {
                var lastToolUiEmitMs = 0L
                return executeTool(name, arguments, ctx) { toolEvent ->
                    val changed = when (toolEvent) {
                        is ToolExecutionEvent.OutputDelta -> {
                            updateToolSegment(toolCallId) { segment ->
                                segment.copy(
                                    toolState = com.newoether.agora.model.ToolExecutionStates.RUNNING,
                                    toolProgress = appendBoundedToolOutput(
                                        segment.toolProgress,
                                        toolEvent.text,
                                    ),
                                )
                            }
                            true
                        }
                        is ToolExecutionEvent.TargetResolved -> {
                            updateToolSegment(toolCallId) { segment ->
                                segment.copy(toolTarget = toolEvent.target)
                            }
                            true
                        }
                        is ToolExecutionEvent.Progress -> {
                            updateToolSegment(toolCallId) { segment ->
                                segment.copy(
                                    toolState = com.newoether.agora.model.ToolExecutionStates.RUNNING,
                                )
                            }
                            true
                        }
                        is ToolExecutionEvent.Completed -> false
                    }
                    if (changed) {
                        val now = System.currentTimeMillis()
                        if (now - lastToolUiEmitMs >= TOOL_UI_UPDATE_INTERVAL_MS) {
                            publishStreamUpdate()
                            lastEmitMs = now
                            lastToolUiEmitMs = now
                        }
                    }
                }
            }

            suspend fun executeCompletedToolCalls() {
                if (completedToolCalls.isEmpty()) return
                val calls = completedToolCalls.values.toList()
                completedToolCalls.clear()
                val results = mutableListOf<ToolCallData>()

                for (call in calls) {
                    val index = checkNotNull(liveToolSegmentIndices[call.streamKey]) {
                        "Missing live segment for tool call ${call.streamKey}"
                    }
                    val metadata = resolveToolPresentationMetadata(call.name)
                    segments[index] = segments[index].copy(
                        toolName = call.name,
                        toolArgs = call.arguments,
                        toolCallId = call.id,
                        signature = call.signature,
                        signatureProvider = provider.name.takeIf { call.signature != null },
                        toolState = com.newoether.agora.model.ToolExecutionStates.RUNNING,
                        toolTarget = metadata?.target ?: segments[index].toolTarget,
                        toolDisplayName = metadata?.displayName ?: segments[index].toolDisplayName,
                    )
                    currentStatus = MessageStatus.TOOL_CALLING
                    publishStreamUpdate(forceCheckpoint = true)
                    lastEmitMs = System.currentTimeMillis()

                    val result = executeToolWithLiveSegment(
                        call.name,
                        call.arguments,
                        call.id,
                    )
                    generatedImages.addAll(imageGenToolProvider.drainImages(conversationId))
                    val clipped = result.text.take(Constants.MAX_TOOL_RESULT_LENGTH)
                    val clippedDisplayText = result.displayText
                        ?.take(Constants.MAX_TOOL_RESULT_LENGTH)
                    val clippedStructuredResult = result.structuredContent
                        ?.take(Constants.MAX_TOOL_RESULT_LENGTH)
                    segments[index] = segments[index].copy(
                        toolResult = clipped,
                        toolResultText = clippedDisplayText,
                        toolStructuredResult = clippedStructuredResult,
                        toolState = if (result.isError) {
                            com.newoether.agora.model.ToolExecutionStates.FAILED
                        } else {
                            finalToolState(result.text)
                        },
                        toolImages = result.images,
                    )
                    roundToolSegments.add(segments[index])
                    results += ToolCallData(
                        toolName = call.name,
                        arguments = call.arguments,
                        result = clipped,
                        signature = call.signature,
                        toolCallId = call.id,
                        resultImages = result.images,
                        displayName = segments[index].toolDisplayName,
                        resultText = clippedDisplayText,
                        structuredResult = clippedStructuredResult,
                    )
                    publishStreamUpdate()
                    lastEmitMs = System.currentTimeMillis()
                }

                toolCallData = results.firstOrNull()
                toolCallDataList = results
                currentStatus = MessageStatus.SENDING
                publishStreamUpdate(forceCheckpoint = true)
                lastEmitMs = System.currentTimeMillis()
            }

            suspend fun handleStreamEvent(event: StreamEvent) {
                when (event) {
                    is StreamEvent.TextChunk -> {
                        val answerText = if (currentStatus == MessageStatus.THINKING) event.text.trimStart() else event.text
                        if (currentStatus == MessageStatus.THINKING && answerText.isBlank()) {
                            retryText = null
                            return
                        }
                        if (currentStatus == MessageStatus.THINKING) {
                            flushThoughtSegment()
                        }
                        totalText += answerText
                        currentAnswerBuf.append(answerText)
                        if (answerText.isNotBlank()) {
                            currentStatus = MessageStatus.SENDING
                        }
                        retryText = null
                    }
                    is StreamEvent.ThoughtChunk -> {
                        flushAnswerSegment()
                        currentStatus = MessageStatus.THINKING
                        retryText = null
                        if (currentThoughtStartMs == null) {
                            currentThoughtStartMs = System.currentTimeMillis()
                        }
                        if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        if (event.thought.isNotEmpty()) {
                            currentThoughtBuf.append(event.thought)
                            if (totalThoughts == thinkingPlaceholder) totalThoughts = event.thought
                            else totalThoughts += event.thought
                        }
                        if (event.title != null) totalThoughtTitle = event.title
                        if (event.signature != null) {
                            currentThoughtSignature = event.signature
                            currentThoughtSignatureProvider = provider.name
                        }
                    }
                    is StreamEvent.UsageUpdate -> {
                        tokenUsageAccumulator.observeRequestSnapshot(event.usage)
                        totalTokenUsage = tokenUsageAccumulator.snapshot()
                        totalTokenCount = totalTokenUsage?.totalTokenCount ?: 0
                        if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                            currentStatus = MessageStatus.THINKING
                            if (currentThoughtStartMs == null) {
                                currentThoughtStartMs = System.currentTimeMillis()
                            }
                            if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        }
                    }
                    is StreamEvent.Retrying -> {
                        retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)
                        onStreamUpdate(modelMessage())
                    }
                    is StreamEvent.Error -> {
                        flushThoughtSegment()
                        retryText = null
                        liveToolSegmentIndices.forEach { (streamKey, index) ->
                            if (
                                streamKey !in completedToolCalls &&
                                segments[index].toolResult == null
                            ) {
                                segments[index] = segments[index].copy(
                                    toolState = com.newoether.agora.model.ToolExecutionStates.FAILED,
                                )
                            }
                        }
                        if (
                            toolCallData == null &&
                            toolCallDataList.isEmpty() &&
                            completedToolCalls.isEmpty()
                        ) {
                            totalText = event.message
                            currentStatus = MessageStatus.ERROR
                        }
                    }
                    is StreamEvent.ToolCallUpdate -> {
                        val (_, created) = upsertStreamingToolSegment(
                            streamKey = event.streamKey,
                            toolCallId = event.id,
                            name = event.name,
                            arguments = event.arguments,
                            signature = event.signature,
                        )
                        currentStatus = MessageStatus.TOOL_CALLING
                        retryText = null
                        val now = System.currentTimeMillis()
                        if (created || now - lastEmitMs >= TOOL_UI_UPDATE_INTERVAL_MS) {
                            publishStreamUpdate(forceCheckpoint = created)
                            lastEmitMs = now
                        }
                    }
                    is StreamEvent.ToolCallRequest -> {
                        upsertStreamingToolSegment(
                            streamKey = event.streamKey,
                            toolCallId = event.id,
                            name = event.name,
                            arguments = event.arguments,
                            signature = event.signature,
                        )
                        completedToolCalls[event.streamKey] = event
                        currentStatus = MessageStatus.TOOL_CALLING
                        publishStreamUpdate(forceCheckpoint = true)
                        lastEmitMs = System.currentTimeMillis()
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        event.calls.forEach { call ->
                            upsertStreamingToolSegment(
                                streamKey = call.streamKey,
                                toolCallId = call.id,
                                name = call.name,
                                arguments = call.arguments,
                                signature = call.signature,
                            )
                            completedToolCalls[call.streamKey] = call
                        }
                        currentStatus = MessageStatus.TOOL_CALLING
                        publishStreamUpdate(forceCheckpoint = true)
                        lastEmitMs = System.currentTimeMillis()
                    }
                }

                val now = System.currentTimeMillis()
                val isSignificant = event is StreamEvent.Error
                if (now - lastEmitMs >= STREAM_UI_UPDATE_INTERVAL_MS || isSignificant) {
                    publishStreamUpdate(forceCheckpoint = isSignificant)
                    lastEmitMs = now
                }
            }

            suspend fun collectProviderRequest(
                messages: List<ChatMessage>,
                onFirstEvent: (() -> Unit)? = null,
            ) {
                tokenUsageAccumulator.beginRequest()
                var firstEventPending = onFirstEvent != null
                try {
                    provider.generateResponse(messages, providerConfig).collect { event ->
                        if (firstEventPending) {
                            firstEventPending = false
                            onFirstEvent?.invoke()
                        }
                        handleStreamEvent(event)
                    }
                } finally {
                    tokenUsageAccumulator.finishRequest()
                    totalTokenUsage = tokenUsageAccumulator.snapshot()
                    totalTokenCount = totalTokenUsage?.totalTokenCount ?: totalTokenCount
                }
            }

            val projectedPath = projectToolResultImagesToUserMessage(
                projectAssistantImagesToLatestUserMessage(currentPath, providerConfig.includeImages),
                providerConfig.includeImages,
            )
            val apiPath = applyUserTemplate(projectedPath, config.userPrepend, config.userPostpend)
            requestTrace?.mark("provider_dispatch")
            collectProviderRequest(apiPath) {
                    requestTrace?.mark("first_semantic_event")
            }
            finishCurrentThoughtTiming()
            executeCompletedToolCalls()
            // Publish the final in-memory snapshot without waiting for another Room round trip.
            // The terminal transaction below persists this exact state after fencing the
            // checkpoint writer, while genuine tool lifecycle boundaries remain forced.
            if (generationJob?.isCancelled != true) {
                publishStreamUpdate()
            }

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = toolRoundThoughtSegments(
                    segments = segments,
                    fromIndex = toolRoundSegmentCursor,
                )
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                toolRoundSegmentCursor = segments.size
                val prevLastId = if (toolRound == 1) modelMessageId else toolPath.lastOrNull()?.id
                val toolMsgId = "${Constants.TOOL_MSG_PREFIX}${UUID.randomUUID()}"
                val toolMsgSegs = txedSegments.ifEmpty { null }
                val tcds = toolCallDataList
                val allSegments = toolMsgSegs ?: tcds.map { tc ->
                    MessageSegment(
                        type = "tool",
                        toolName = tc.toolName,
                        toolArgs = tc.arguments,
                        toolResult = tc.result,
                        signature = tc.signature,
                        signatureProvider = provider.name.takeIf { tc.signature != null },
                        toolCallId = tc.toolCallId,
                        toolDisplayName = tc.displayName,
                        toolResultText = tc.resultText,
                        toolStructuredResult = tc.structuredResult,
                        toolImages = tc.resultImages,
                    )
                }
                // Bound the aggregate: a model message row crams every tool round into one
                // toolCallJson column, so many rounds × a clipped 100KB result can still exceed the
                // 2MB CursorWindow. The guard halves the largest results until it fits (#51).
                val allSegmentsJson = MessagePersistenceGuard.encodeSegmentsBounded(allSegments)
                val resultMsgs = tcds.map { tcData ->
                    val rid = "${Constants.RESULT_MSG_PREFIX}${UUID.randomUUID()}"
                    // API-facing message: carry the RAW tool result, matching the persisted row
                    // below. Display formatting (SearchResultFormatter) is applied in the UI
                    // layer only — a localized pretty-print here would mean the model sees
                    // different context in-flight vs after a reload.
                    rid to ChatMessage(
                        id = rid, parentId = toolMsgId,
                        text = tcData.result,
                        images = tcData.resultImages.map { it.path },
                        participant = Participant.USER, status = MessageStatus.SUCCESS,
                        toolCall = tcData,
                        runId = runId,
                    )
                }
                toolPath = toolPath.toMutableList().apply {
                    add(ChatMessage(
                        id = toolMsgId, parentId = prevLastId,
                        text = "", participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS, toolCall = tcds.first(),
                        segments = toolMsgSegs,
                        runId = runId,
                    ))
                    for ((_, msg) in resultMsgs) add(msg)
                }
                val toolRoundTimestamp = System.currentTimeMillis()
                val toolRoundEntities = buildList {
                    add(MessageEntity(
                        id = toolMsgId, conversationId = conversationId, parentId = prevLastId,
                        text = "", thoughts = null, status = MessageStatus.SUCCESS,
                        participant = Participant.MODEL, timestamp = toolRoundTimestamp,
                        modelName = modelName, toolCallJson = allSegmentsJson, runId = runId,
                    ))
                    resultMsgs.forEachIndexed { index, entry ->
                        val (rid, _) = entry
                        add(MessageEntity(
                            id = rid, conversationId = conversationId, parentId = toolMsgId,
                            text = tcds[index].result, thoughts = null, status = MessageStatus.SUCCESS,
                            images = tcds[index].resultImages.map { it.path },
                            participant = Participant.USER, timestamp = toolRoundTimestamp + index + 1,
                            modelName = modelName, runId = runId,
                            toolCallJson = Json.encodeToString(listOf(
                                MessageSegment(
                                    type = "tool",
                                    toolName = tcds[index].toolName,
                                    toolArgs = tcds[index].arguments,
                                    toolResult = tcds[index].result,
                                    signature = tcds[index].signature,
                                    signatureProvider = provider.name.takeIf { tcds[index].signature != null },
                                    toolCallId = tcds[index].toolCallId,
                                    toolDisplayName = tcds[index].displayName,
                                    toolResultText = tcds[index].resultText,
                                    toolStructuredResult = tcds[index].structuredResult,
                                    toolImages = tcds[index].resultImages,
                                )
                            ))
                        ))
                    }
                }
                conversations.appendToolRoundToRun(toolRoundEntities)

                toolCallData = null
                toolCallDataList = emptyList()

                // Steering: a send queued mid-generation is delivered at this round boundary.
                // The round's tool/result rows are already persisted above, so ending here is
                // clean — the slot release drains the queue (each message its own bubble) and
                // the NEXT generation's path continues from these tool results plus the new
                // user turns, instead of making the user wait out the whole tool loop.
                if (callbacks.hasQueuedSends()) {
                    interruptedForQueuedSend = true
                    break
                }

                lastEmitMs = 0L

                val projectedToolPath = projectToolResultImagesToUserMessage(
                    projectAssistantImagesToLatestUserMessage(toolPath, providerConfig.includeImages),
                    providerConfig.includeImages,
                )
                val apiToolPath = applyUserTemplate(projectedToolPath, config.userPrepend, config.userPostpend)
                collectProviderRequest(apiToolPath)
                finishCurrentThoughtTiming()
                executeCompletedToolCalls()
                // Publish the round's final UI state immediately. The next loop boundary or the
                // terminal transaction supplies durability, so blocking here would only duplicate
                // I/O and visibly delay the transition out of generating.
                publishStreamUpdate()
            }

            if (!currentCoroutineContext().isActive) {
                currentStatus = MessageStatus.STOPPED
            }

            if (currentStatus != MessageStatus.ERROR) {
                // A queue-steered interruption is a SUCCESSFUL turn even with no answer text —
                // its value is the persisted tool activity.
                currentStatus = if (totalText.isNotEmpty() || totalThoughts.isNotEmpty() || interruptedForQueuedSend) {
                    MessageStatus.SUCCESS
                } else MessageStatus.ERROR
            }
            if (generationJob?.isCancelled == true && currentStatus != MessageStatus.ERROR) {
                currentStatus = MessageStatus.STOPPED
            }
            } // else { // called buildApiPath when currentStatus == ERROR
        } catch (e: CancellationException) {
            // transcribe() owns its mutable segment list until it returns. If cancellation lands
            // mid-transcription, copy the latest durable/UI snapshot into the terminal accumulator
            // so the final upsert does not overwrite that checkpoint with empty content.
            adoptIncompleteTranscriptionSnapshot()
            segments.indices.forEach { index ->
                val segment = segments[index]
                if (segment.type == "tool" && segment.toolResult == null) {
                    segments[index] = segment.copy(
                        toolState = com.newoether.agora.model.ToolExecutionStates.STOPPED,
                    )
                }
            }
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            adoptIncompleteTranscriptionSnapshot()
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                totalText = "Error: ${e.localizedMessage ?: "An unexpected error occurred."}"
            }
        } finally {
            // Fence the asynchronous checkpoint lane before any terminal transaction. Without
            // this join, an older SENDING snapshot could finish after SUCCESS/STOPPED and revive
            // the exact UI state the terminal write just closed.
            withContext(NonCancellable) {
                checkpointWriter.cancelAndJoin()
            }
            // Critical non-cancellable section: only the terminal DB upsert (and the
            // image drain that feeds it). A stopped/superseded generation MUST still
            // write its final row so it isn't left as SENDING. Everything else — RAG
            // indexing, UI cleanup, foreground release, notifications — is moved below
            // so a Stop returns from here as soon as the row is written, instead of
            // running a heavy non-cancellable tail that held the generation lock/queue.
            if (isLatestPersist()) withContext(NonCancellable) {
                // A cancellation can arrive as ImageGenToolProvider's withContext returns,
                // after the file was queued but before the normal post-tool drain ran.
                generatedImages.addAll(imageGenToolProvider.drainImages(conversationId))
                try {
                    if (isLatestPersist()) {
                        val conversationExists = conversations.getConversation(conversationId) != null
                        if (conversationExists) {
                            finishCurrentThoughtTiming()
                            val finalSegments = buildLiveSegments(
                                segments,
                                currentAnswerBuf,
                                  currentThoughtBuf,
                                  currentThoughtSignature,
                                  currentThoughtSignatureProvider,
                                  currentThoughtDurationMs.takeIf { it > 0L }
                            )
                                ?: segments.toList().ifEmpty { null }
                            // Bound the row's toolCallJson aggregate (#51) and the unbounded answer
                            // text column — together they can exceed the 2MB CursorWindow otherwise.
                            val effectiveParentId = parentId
                            val finalMessage = ChatMessage(
                                id = modelMessageId,
                                parentId = effectiveParentId,
                                text = MessagePersistenceGuard.clipText(totalText),
                                images = generatedImages.toList(),
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle,
                                tokenCount = totalTokenCount,
                                tokenUsage = totalTokenUsage,
                                status = currentStatus,
                                participant = Participant.MODEL,
                                timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs,
                                modelName = modelName,
                                segments = finalSegments,
                                runId = runId,
                                runSequence = modelRunSequence,
                            )
                            val terminalPersisted = when {
                                // Stop always wins over a queued intervention. Otherwise a
                                // cancellation racing with a queued send can strand this Run ACTIVE.
                                currentStatus == MessageStatus.STOPPED ->
                                    conversations.finishGeneration(
                                        finalMessage,
                                        conversationId,
                                        runId,
                                        RunStatus.STOPPED,
                                        RunEndReason.USER_STOPPED,
                                    )
                                // A queue-steered successful pass keeps this Run ACTIVE for the
                                // next pass in the same generation cycle.
                                callbacks.hasQueuedSends() ->
                                    conversations.updateStreamingMessageCheckpoint(finalMessage)
                                currentStatus == MessageStatus.ERROR ->
                                    conversations.finishGeneration(
                                        finalMessage,
                                        conversationId,
                                        runId,
                                        RunStatus.FAILED,
                                        RunEndReason.PROVIDER_ERROR,
                                    )
                                else ->
                                    conversations.finishGeneration(
                                        finalMessage,
                                        conversationId,
                                        runId,
                                        RunStatus.COMPLETED,
                                        RunEndReason.MODEL_COMPLETED,
                                        markConversationUnread = true,
                                    )
                            }
                            if (!terminalPersisted) {
                                DebugLog.e(
                                    "AgoraVM",
                                    "Terminal generation write did not update both message and Run: " +
                                        "message=$modelMessageId run=$runId status=$currentStatus",
                                )
                            }
                            // The last periodic checkpoint was emitted before currentStatus became
                            // terminal. Publish this exact terminal object before onStreamClear():
                            // otherwise streamClear can commit a stale SENDING snapshot after
                            // Room already emitted SUCCESS, leaving the UI in Answering by race.
                            onStreamUpdate(finalMessage)
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraVM", "Failed to persist message to DB", e)
                }
            }
            // Movable tail (cancellable, no suspension points): runs to completion even
            // on cancellation because none of these suspend. Kept OUT of NonCancellable
            // so a heavy RAG-indexing callback or notification can't pin the generation.
            // RAG indexing hook — fire-and-forget; the persist above already committed.
            try {
                if (isLatestPersist() && totalText.isNotBlank()) {
                    onMessagePersisted?.invoke(modelMessageId, totalText)
                }
            } catch (_: Exception) { /* indexing must never break terminal cleanup */ }
            // Terminal UI cleanup. Token-gated at the sink (in ChatViewModel), so they
            // no-op when this generation was stopped/superseded — only the still-current
            // generation resets the loading/streaming/generating-id UI state.
            onStreamClear()
            onLoadingChange(false)
            // The generating flag + active-set are released by the controller's endGeneration()
            // in its finally (which also drains the queue), so the slot lifecycle has a single owner.
            if (foregroundLeaseAcquired) {
                AgoraForegroundService.release(modelMessageId)
            }
            if (!AppForegroundTracker.isInForeground && currentStatus == MessageStatus.SUCCESS && totalText.isNotBlank()) {
                AgoraForegroundService.showCompletionNotification(app, totalText, conversationId)
            }
        }
    }
}
