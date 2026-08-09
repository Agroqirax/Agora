package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.MemoryManager

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RequestTokenUsageAccumulator
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.R
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.api.util.projectToolResultImagesToUserMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.ToolExecutionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private const val STREAM_UI_UPDATE_INTERVAL_MS = 50L
private const val TOOL_UI_UPDATE_INTERVAL_MS = 50L

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

    private val toolExecutor = GenerationToolExecutor.createDefault(
        app = app,
        conversations = conversations,
        memoryManager = memoryManager,
        sandboxFactory = sandboxFactory,
        additionalProviders = additionalToolProviders,
        confirmShellCommand = { server, summary ->
            onConfirmShellCommand?.invoke(server, summary) ?: true
        },
    )
    private val providerPassRunner = ProviderPassRunner()
    private val runFinalizationEffects = RunFinalizationEffectCoordinator()
    private val apiPathBuilder = GenerationApiPathBuilder(conversations, toolExecutor)

    fun buildImageGenTool(ctx: GenerationContext): List<ToolDefinition> =
        toolExecutor.imageDefinitions(ctx)

    private val transcriptionManager = TranscriptionManager(providers, conversations, context)

    private fun getProviderInstance(name: String): LlmProvider =
        requireRegisteredProvider(providers, name)

    // Image/video frame extraction lives in ImageProcessor (single source of truth).
    private val imageProcessor = ImageProcessor(app)

    suspend fun processImages(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = imageProcessor.processImagesAndVideos(uris, sliceConfigs)

    fun buildMemoryTools(ctx: GenerationContext): List<ToolDefinition> =
        toolExecutor.memoryDefinitions(ctx)

    fun buildWebSearchTool(ctx: GenerationContext): List<ToolDefinition> =
        toolExecutor.webSearchDefinitions(ctx)

    fun buildRagTool(ctx: GenerationContext): List<ToolDefinition> =
        toolExecutor.ragDefinitions(ctx)

    fun buildShellTool(ctx: GenerationContext): List<ToolDefinition> =
        toolExecutor.shellDefinitions(ctx)

    fun buildFileTool(ctx: GenerationContext): List<ToolDefinition> =
        toolExecutor.fileDefinitions(ctx)


    /** Semantic message search — delegates to the RAG tool provider, which owns the
     *  embedding-search logic. Kept here as the entry point used by ChatViewModel's
     *  in-app conversation search. */
    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> =
        toolExecutor.semanticSearch(query, limit, ctx)

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

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        runId: String,
        pass: Int,
        ownerToken: Long,
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
        var terminalPersisted = false

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
            val (currentPath, rawProviderConfig) = apiPathBuilder.build(
                GenerationApiPathRequest(
                    parentId = parentId,
                    conversationId = conversationId,
                    isRegenerate = isRegenerate,
                    replaceMessageId = replaceMessageId,
                    config = config,
                    context = ctx,
                    loadedMessages = loadedMessages,
                ),
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
            var providerRequestOrdinal = 0
            val toolRoundEffects = ToolRoundEffectCoordinator(callbacks)

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
                    val metadata = toolExecutor.presentationMetadata(resolvedName)
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
                val metadata = toolExecutor.presentationMetadata(name)
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
                batchIdentity: RunEffectIdentity,
                name: String,
                arguments: String,
                toolCallId: String,
            ): AuthorizedToolResult {
                var lastToolUiEmitMs = 0L
                return toolExecutor.execute(
                    AuthorizedToolCall(
                        batchIdentity = batchIdentity,
                        callId = toolCallId,
                        name = name,
                        arguments = arguments,
                        context = ctx,
                    ),
                ) { toolEvent ->
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

            suspend fun executeAcceptedToolBatch() {
                if (completedToolCalls.isEmpty()) return
                val batchEffect = toolRoundEffects.requireBatchEffect()
                val calls = completedToolCalls.values.toList()
                completedToolCalls.clear()
                val results = mutableListOf<ToolCallData>()

                for (call in calls) {
                    val index = checkNotNull(liveToolSegmentIndices[call.streamKey]) {
                        "Missing live segment for tool call ${call.streamKey}"
                    }
                    val metadata = toolExecutor.presentationMetadata(call.name)
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

                    val executedCall = executeToolWithLiveSegment(
                        batchIdentity = batchEffect.identity,
                        name = call.name,
                        arguments = call.arguments,
                        toolCallId = call.id,
                    )
                    check(executedCall.batchIdentity == batchEffect.identity)
                    check(executedCall.callId == call.id)
                    val result = executedCall.result
                    generatedImages.addAll(toolExecutor.drainGeneratedImages(conversationId))
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
                toolRoundEffects.completeBatch(batchEffect.identity)
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
                        currentStatus = MessageStatus.ERROR
                        if (totalText.isBlank()) {
                            totalText = event.message
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
            ): ProviderPassOutcome {
                tokenUsageAccumulator.beginRequest()
                var firstEventPending = onFirstEvent != null
                val proposedIdentity = RunEffectIdentity(
                    conversationId = conversationId,
                    ownerToken = ownerToken,
                    runId = runId,
                    pass = pass,
                    effectId = "provider-$pass-${providerRequestOrdinal++}",
                )
                val startEffect = callbacks.onProviderPassRequested(proposedIdentity)
                    ?.takeIf { it.identity == proposedIdentity }
                    ?: throw CancellationException(
                        "Provider pass ${proposedIdentity.effectId} is no longer authorized",
                    )
                try {
                    return providerPassRunner.run(
                        identity = startEffect.identity,
                        provider = provider,
                        messages = messages,
                        config = providerConfig,
                    ) { event ->
                        if (firstEventPending) {
                            firstEventPending = false
                            onFirstEvent?.invoke()
                        }
                        handleStreamEvent(event)
                    }
                } catch (error: Exception) {
                    // ProviderPassRunner normally closes cancellation/error into an outcome. This
                    // path covers a consumer failure before it can do so, ensuring the mailbox
                    // never retains a phantom Running pass.
                    callbacks.onProviderPassCompleted(
                        startEffect.identity,
                        ProviderPassResult.FAILED,
                    )
                    throw error
                } finally {
                    tokenUsageAccumulator.finishRequest()
                    totalTokenUsage = tokenUsageAccumulator.snapshot()
                    totalTokenCount = totalTokenUsage?.totalTokenCount ?: totalTokenCount
                }
            }

            suspend fun acceptProviderPass(outcome: ProviderPassOutcome) {
                val result = when (outcome) {
                    is ProviderPassOutcome.CompletedText -> ProviderPassResult.COMPLETED_TEXT
                    is ProviderPassOutcome.CompletedToolCalls ->
                        ProviderPassResult.COMPLETED_TOOL_CALLS
                    is ProviderPassOutcome.Truncated -> ProviderPassResult.TRUNCATED
                    is ProviderPassOutcome.Failed -> ProviderPassResult.FAILED
                    is ProviderPassOutcome.Cancelled -> ProviderPassResult.CANCELLED
                }
                callbacks.onProviderPassCompleted(outcome.identity, result)
                    ?.takeIf { it.identity == outcome.identity && it.result == result }
                    ?: throw CancellationException(
                        "Provider pass ${outcome.identity.effectId} outcome is no longer current",
                    )
                when (outcome) {
                    is ProviderPassOutcome.CompletedText -> Unit
                    is ProviderPassOutcome.CompletedToolCalls -> {
                        check(completedToolCalls.isEmpty()) {
                            "A Provider pass cannot overlap an unconsumed tool batch"
                        }
                        toolRoundEffects.acceptValidatedBatch(outcome.identity)
                        outcome.calls.forEach { call ->
                            completedToolCalls[call.streamKey] = call
                        }
                    }
                    is ProviderPassOutcome.Truncated,
                    is ProviderPassOutcome.Failed,
                    -> check(currentStatus == MessageStatus.ERROR) {
                        "A failed Provider pass must publish its error before closing"
                    }
                    is ProviderPassOutcome.Cancelled -> throw CancellationException(
                        "Provider pass ${outcome.identity.effectId} was cancelled",
                    )
                }
            }

            val projectedPath = projectToolResultImagesToUserMessage(
                projectAssistantImagesToLatestUserMessage(currentPath, providerConfig.includeImages),
                providerConfig.includeImages,
            )
            val apiPath = applyUserTemplate(projectedPath, config.userPrepend, config.userPostpend)
            requestTrace?.mark("provider_dispatch")
            acceptProviderPass(collectProviderRequest(apiPath) {
                requestTrace?.mark("first_semantic_event")
            })
            finishCurrentThoughtTiming()
            if (currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
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
                toolRoundEffects.commitRound { commitIdentity ->
                    conversations.appendToolRoundToRun(
                        messages = toolRoundEntities,
                        expectedPass = commitIdentity.pass,
                    )
                }
                callbacks.onToolRoundPersisted()
                toolPath = apiPathBuilder.build(
                    GenerationApiPathRequest(
                        parentId = resultMsgs.last().first,
                        conversationId = conversationId,
                        isRegenerate = false,
                        replaceMessageId = null,
                        config = config,
                        context = ctx,
                    ),
                ).messages

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
                acceptProviderPass(collectProviderRequest(apiToolPath))
                finishCurrentThoughtTiming()
                if (currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
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
            // The mailbox, rather than a mutable token check in this finally block, chooses the
            // one terminal effect that may write Room. A concurrent Stop wins by entering
            // Stopping first; a natural completion wins by entering Finalizing first.
            withContext(NonCancellable) {
                // A cancellation can arrive as ImageGenToolProvider's withContext returns,
                // after the file was queued but before the normal post-tool drain ran.
                generatedImages.addAll(toolExecutor.drainGeneratedImages(conversationId))
                try {
                    val conversationExists = conversations.getConversation(conversationId) != null
                    if (conversationExists) {
                        finishCurrentThoughtTiming()
                        val finalSegments = buildLiveSegments(
                            segments,
                            currentAnswerBuf,
                            currentThoughtBuf,
                            currentThoughtSignature,
                            currentThoughtSignatureProvider,
                            currentThoughtDurationMs.takeIf { it > 0L },
                        ) ?: segments.toList().ifEmpty { null }
                        // Bound the row's toolCallJson aggregate (#51) and the unbounded answer
                        // text column — together they can exceed the 2MB CursorWindow otherwise.
                        val finalMessage = ChatMessage(
                            id = modelMessageId,
                            parentId = parentId,
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
                        val terminalDisposition = generationTerminalDisposition(
                            messageStatus = currentStatus,
                            hasPendingGuidance = callbacks.hasQueuedSends(),
                        )
                        val finalizationIdentity = RunEffectIdentity(
                            conversationId = conversationId,
                            ownerToken = ownerToken,
                            runId = runId,
                            pass = pass,
                            effectId = "finalize-$runId-$pass",
                        )
                        val finalizationEffect = callbacks.onRunFinalizationRequested(
                            finalizationIdentity,
                            terminalDisposition.runStatus,
                            terminalDisposition.endReason,
                            terminalDisposition.markConversationUnread,
                        )?.takeIf { effect ->
                            effect.identity == finalizationIdentity &&
                                effect.status == terminalDisposition.runStatus &&
                                effect.reason == terminalDisposition.endReason &&
                                effect.markConversationUnread ==
                                terminalDisposition.markConversationUnread
                        }
                        if (finalizationEffect != null) {
                            val result = runFinalizationEffects.execute(finalizationEffect) { effect ->
                                conversations.finishGeneration(
                                    finalMessage,
                                    effect.identity.conversationId,
                                    effect.identity.runId,
                                    effect.status,
                                    effect.reason,
                                    markConversationUnread = effect.markConversationUnread,
                                )
                            }
                            val durableSuccess =
                                result is RunFinalizationEffectCoordinator.Result.Succeeded
                            terminalPersisted =
                                callbacks.onRunFinalizationCompleted(
                                    finalizationEffect.identity,
                                    durableSuccess,
                                ) && durableSuccess
                            // Keep the exact final snapshot as the overlay even when Room failed.
                            // It remains non-authoritative, but gives a later explicit Stop the
                            // complete content to persist instead of an older SENDING checkpoint.
                            onStreamUpdate(finalMessage)
                            if (!terminalPersisted) {
                                val failure =
                                    (result as? RunFinalizationEffectCoordinator.Result.Failed)
                                        ?.lastFailure
                                val message =
                                    "Terminal generation effect failed after ${result.attempts} attempts: " +
                                        "message=$modelMessageId run=$runId status=$currentStatus"
                                if (failure != null) DebugLog.e("AgoraVM", message, failure)
                                else DebugLog.e("AgoraVM", message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraVM", "Failed to execute terminal generation effect", e)
                }
            }
            // Movable tail (cancellable, no suspension points): runs to completion even
            // on cancellation because none of these suspend. Kept OUT of NonCancellable
            // so a heavy RAG-indexing callback or notification can't pin the generation.
            // RAG indexing hook — fire-and-forget; the persist above already committed.
            try {
                if (terminalPersisted && totalText.isNotBlank()) {
                    onMessagePersisted?.invoke(modelMessageId, totalText)
                }
            } catch (_: Exception) { /* indexing must never break terminal cleanup */ }
            // Terminal UI cleanup. Token-gated at the sink (in ChatViewModel), so they
            // no-op when this generation was stopped/superseded — only the still-current
            // generation resets the loading/streaming/generating-id UI state.
            if (terminalPersisted) {
                onStreamClear()
                onLoadingChange(false)
            }
            // The installed Job's completion hook reports CoroutineSettled through the mailbox;
            // only its accepted release clears the active set and requests queue drain.
            if (foregroundLeaseAcquired) {
                AgoraForegroundService.release(modelMessageId)
            }
            // A queued user intervention ends this provider pass but not the generation cycle.
            // The controller immediately drains that queue into the next pass. Notify only after
            // the final pass, otherwise accepting a steering message produces a false "response
            // ready" notification while Agora is still generating its actual answer.
            val generationCycleComplete =
                currentStatus == MessageStatus.SUCCESS &&
                    !interruptedForQueuedSend &&
                    !callbacks.hasQueuedSends()
            if (
                terminalPersisted &&
                !AppForegroundTracker.isInForeground &&
                generationCycleComplete &&
                totalText.isNotBlank()
            ) {
                AgoraForegroundService.showCompletionNotification(app, totalText, conversationId)
            }
        }
    }
}
