package com.newoether.agora.viewmodel

import com.newoether.agora.api.LocalModelSerializer
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.applyNearestContextCompact
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.api.util.splitContextForCompactRetention
import com.newoether.agora.api.util.stripEmptyTurns
import com.newoether.agora.api.util.validateToolMessages
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class CompactRequest(
    val model: String,
    val prompt: String,
    val retainLogicalMessages: Int,
    val replaceMessageId: String? = null,
)

private data class CompactProviderAccess(
    val providerName: String,
    val apiKey: String,
    val baseUrl: String?,
    val provider: com.newoether.agora.api.LlmProvider?,
    val configured: Boolean,
    val generationContext: GenerationContext,
    val userPrepend: String?,
    val userPostpend: String?,
)

/**
 * A compact model is generating a new summary, so its history needs a real terminal user request.
 * The older prefix often ends with an assistant answer after the retained suffix is removed. Sending
 * that prefix directly violates OpenAI-compatible, Anthropic, and Gemini generation boundaries.
 * This instruction exists only in the compact request and is never persisted into the conversation.
 */
internal fun buildCompactSummaryInput(prefix: List<ChatMessage>): List<ChatMessage> =
    prefix + ChatMessage(
        id = "ephemeral_summary_request_${UUID.randomUUID()}",
        text = "Summarize the conversation context above according to the system instructions. Return only the summary.",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
    )

/** Provider-equivalent split input without role coalescing away durable graph ids. */
internal fun compactSplitMessages(messages: List<ChatMessage>): List<ChatMessage> =
    stripEmptyTurns(
        validateToolMessages(
            projectGenerationStatusesForApi(messages.distinctBy(ChatMessage::id))
        )
    )

/** Excludes a Recompact target and everything after it from that replacement request. */
internal fun selectedContextBeforeReplacement(
    selectedPath: List<ChatMessage>,
    replacementMessageId: String?,
): List<ChatMessage> = replacementMessageId?.let { targetId ->
    selectedPath.takeWhile { it.id != targetId }
} ?: selectedPath

/**
 * Applies the same canonical, protocol-atomic suffix rollout used by ordinary Provider requests.
 *
 * Keep-recent is deliberately absent here: it controls only the verbatim suffix persisted after
 * the summary and must never filter the context seen by the Compact Provider.
 */
internal fun buildRolledCompactInput(
    context: List<ChatMessage>,
    systemPrompt: String,
    contextWindow: Int,
): List<ChatMessage> {
    val summaryRequest = buildCompactSummaryInput(emptyList()).single()
    val messageBudget = (
        contextWindow.coerceAtLeast(1) -
            ContextTokenEstimator.estimateFixed(systemPrompt, emptyList()) -
            ContextTokenEstimator.estimate(listOf(summaryRequest))
        ).coerceAtLeast(1)
    val rolledContext = prepareMessages(
        messages = applyNearestContextCompact(context),
        contextTokenBudget = messageBudget,
    )
    return buildCompactSummaryInput(rolledContext)
}

/**
 * Provider adapters canonicalize requests once more. Give that pass enough room for the context
 * already selected above so it cannot prefer the synthetic summary instruction and discard the
 * oversized newest protocol unit that the standard rollout intentionally retained.
 */
internal fun compactProviderMessageBudget(
    input: List<ChatMessage>,
    systemPrompt: String,
    contextWindow: Int,
): Int = maxOf(
    (
        contextWindow.coerceAtLeast(1) -
            ContextTokenEstimator.estimateFixed(systemPrompt, emptyList())
        ).coerceAtLeast(1),
    ContextTokenEstimator.estimate(input).coerceAtLeast(1),
)

internal data class CompactAppendBoundary(
    val parentMessageId: String,
    val childMessageId: String?,
)

internal fun resolveCompactAppendBoundary(
    selectedPath: List<ChatMessage>,
    compactablePath: List<ChatMessage>,
    entitiesById: Map<String, MessageEntity>,
): CompactAppendBoundary? {
    var parentId = compactablePath.lastOrNull()?.id ?: return null
    val compactableIds = compactablePath.mapTo(hashSetOf(), ChatMessage::id)
    var parentIndex = selectedPath.indexOfLast { it.id == parentId }
    // Tool/result protocol side-chain rows are intentionally absent from the visible UI path.
    if (parentIndex < 0) return CompactAppendBoundary(parentId, childMessageId = null)
    // Failed/stopped Compact rows are invisible to Provider context but remain ordinary durable
    // graph nodes. A later Compact must append after them rather than reorder the visible history.
    while (parentIndex + 1 < selectedPath.size) {
        val next = selectedPath[parentIndex + 1]
        if (!next.isContextCompact() || next.isSuccessfulContextCompact()) break
        if (entitiesById[next.id]?.parentId != parentId) break
        parentId = next.id
        parentIndex++
    }
    val childId = selectedPath.getOrNull(parentIndex + 1)
        ?.takeIf { it.id !in compactableIds }
        ?.takeIf { entitiesById[it.id]?.parentId == parentId }
        ?.id
    return CompactAppendBoundary(parentId, childId)
}

internal fun buildPersistedCompactText(
    summary: String,
    retainedMessages: List<ChatMessage>,
): String = buildString {
    append(summary.trim())
    if (retainedMessages.isEmpty()) return@buildString
    append("\n\n--- Recent messages (verbatim) ---")
    retainedMessages.forEach { message ->
        append("\n\n")
        when {
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                val calls = message.segments.orEmpty().filter { it.type == "tool" }
                if (calls.isEmpty()) {
                    append("[Assistant tool request]\n")
                    append(message.text)
                } else {
                    calls.forEachIndexed { index, call ->
                        if (index > 0) append("\n\n")
                        append("[Assistant tool request: ")
                        append(call.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                        append("]\n")
                        append(call.toolArgs.orEmpty())
                    }
                }
            }
            message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                val results = message.segments.orEmpty().filter { it.type == "tool" }
                if (results.isEmpty()) {
                    append("[Tool result]\n")
                    append(message.text)
                } else {
                    results.forEachIndexed { index, result ->
                        if (index > 0) append("\n\n")
                        append("[Tool result: ")
                        append(result.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                        append("]\n")
                        append(result.toolResult.orEmpty())
                    }
                }
            }
            message.participant == Participant.USER -> {
                append("[User]\n")
                append(message.text)
            }
            else -> {
                append("[Assistant]\n")
                append(message.text)
            }
        }
        if (message.images.isNotEmpty()) {
            append("\n[Attached images: ")
            append(message.images.size)
            append(']')
        }
    }
}

sealed interface CompactResult {
    data class Created(val messageId: String) : CompactResult
    data object NotNeeded : CompactResult
    data class Failed(
        val message: String,
        val messageId: String? = null,
    ) : CompactResult
}

/** Narrow operation port used by the application-level Compact effect executor. */
internal interface ContextCompactOperation {
    suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean

    suspend fun compactAutomatic(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit = {},
        onGraphChanged: suspend () -> Unit = {},
    ): CompactResult

    suspend fun compactBeforeSend(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit = {},
        onGraphChanged: suspend () -> Unit = {},
    ): CompactResult

    suspend fun compactManual(
        conversationId: String,
        request: CompactRequest,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit = {},
        onGraphChanged: suspend () -> Unit = {},
    ): CompactResult
}

internal fun automaticCompactNeeded(
    entities: List<MessageEntity>,
    selectedChildren: Map<String?, String>,
    contextLimit: Int,
    retainLogicalMessages: Int,
    includeStoredTranscriptions: Boolean = false,
    fixedTokenCost: Int = 0,
    userPrepend: String? = null,
    userPostpend: String? = null,
): Boolean {
    val selectedPath = ConversationUiState.resolvePath(
        allMessages = entities.map { it.toUiChatMessage { text -> text } },
        streamingMsg = null,
        selectedChildren = selectedChildren,
    )
    val entitiesById = entities.associateBy(MessageEntity::id)
    return automaticCompactNeeded(
        path = ApiPathAssembler.assemble(
            selectedPath.mapNotNull { entitiesById[it.id] },
            entities,
        ).let { projectProviderMessages(it, includeStoredTranscriptions) },
        contextLimit = contextLimit,
        retainLogicalMessages = retainLogicalMessages,
        fixedTokenCost = fixedTokenCost,
        userPrepend = userPrepend,
        userPostpend = userPostpend,
    )
}

internal fun automaticCompactNeeded(
    path: List<ChatMessage>,
    contextLimit: Int,
    retainLogicalMessages: Int,
    fixedTokenCost: Int = 0,
    userPrepend: String? = null,
    userPostpend: String? = null,
): Boolean {
    if (path.isEmpty() || retainLogicalMessages < 0) return false
    val semanticPath = path.filterNot { it.isContextCompact() && !it.isSuccessfulContextCompact() }
    val nearest = semanticPath.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    val compactablePath = compactSplitMessages(
        semanticPath.drop(nearest.coerceAtLeast(-1) + 1),
    )
    val split = splitContextForCompactRetention(compactablePath, retainLogicalMessages)
    return split.prefix.isNotEmpty() &&
        contextWindowUsage(
            projectGenerationInputMessages(
                messages = semanticPath,
                includeImages = true,
                userPrepend = userPrepend,
                userPostpend = userPostpend,
            ),
            contextLimit.coerceAtLeast(1),
            fixedTokenCost = fixedTokenCost,
        ).estimatedTokenCount >=
        contextLimit.coerceAtLeast(1)
}

/** Non-destructive context compaction. Original messages remain in the graph. */
internal class ContextCompactor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
    private val pauseLoop: suspend (String) -> Unit,
    private val providerPassRunner: ProviderPassRunner = ProviderPassRunner(),
) : ContextCompactOperation {
    override suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean =
        config.enabled && automaticCompactNeeded(
            conversations.getMessagesForConversationSnapshot(conversationId),
            conversations.restoreBranchSelections(conversationId),
            contextLimit,
            config.request.retainLogicalMessages,
            config.generationContext.imageTranscriptionEnabled,
            config.fixedTokenCost,
            config.userPrepend,
            config.userPostpend,
        )

    override suspend fun compactAutomatic(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
        onGraphChanged: suspend () -> Unit,
    ): CompactResult {
        if (!config.enabled) return CompactResult.NotNeeded
        return compact(
            conversationId = conversationId,
            request = config.request,
            threshold = contextLimit.coerceAtLeast(1),
            fixedTokenCost = config.fixedTokenCost,
            providerAccess = CompactProviderAccess(
                providerName = config.providerName,
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                provider = config.provider,
                configured = config.configured,
                generationContext = config.generationContext,
                userPrepend = config.userPrepend,
                userPostpend = config.userPostpend,
            ),
            compactRunId = compactRunId,
            automaticIdentity = identity,
            onSummaryChunk = onSummaryChunk,
            onGraphChanged = onGraphChanged,
        )
    }

    override suspend fun compactBeforeSend(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
        onGraphChanged: suspend () -> Unit,
    ): CompactResult {
        if (!config.enabled) return CompactResult.NotNeeded
        return compact(
            conversationId = conversationId,
            request = config.request,
            threshold = contextLimit.coerceAtLeast(1),
            fixedTokenCost = config.fixedTokenCost,
            providerAccess = CompactProviderAccess(
                providerName = config.providerName,
                apiKey = config.apiKey,
                baseUrl = config.baseUrl,
                provider = config.provider,
                configured = config.configured,
                generationContext = config.generationContext,
                userPrepend = config.userPrepend,
                userPostpend = config.userPostpend,
            ),
            compactRunId = compactRunId,
            manualIdentity = identity,
            onSummaryChunk = onSummaryChunk,
            onGraphChanged = onGraphChanged,
        )
    }

    override suspend fun compactManual(
        conversationId: String,
        request: CompactRequest,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
        onGraphChanged: suspend () -> Unit,
    ): CompactResult = compact(
        conversationId,
        request,
        threshold = null,
        fixedTokenCost = 0,
        compactRunId = compactRunId,
        manualIdentity = identity,
        onSummaryChunk = onSummaryChunk,
        onGraphChanged = onGraphChanged,
    )

    private suspend fun compact(
        conversationId: String,
        request: CompactRequest,
        threshold: Int?,
        fixedTokenCost: Int,
        providerAccess: CompactProviderAccess? = null,
        compactRunId: String,
        automaticIdentity: RunEffectIdentity? = null,
        manualIdentity: RunEffectIdentity? = null,
        onSummaryChunk: (String) -> Unit,
        onGraphChanged: suspend () -> Unit,
    ): CompactResult {
        require(compactRunId.isNotBlank())
        if (request.model.isBlank()) return CompactResult.Failed("Select a compact model")
        if (request.prompt.isBlank()) return CompactResult.Failed("Compact prompt cannot be empty")
        if (request.retainLogicalMessages < 0) return CompactResult.Failed("Retained messages cannot be negative")

        val entities = conversations.getMessagesForConversationSnapshot(conversationId)
        val selected = conversations.restoreBranchSelections(conversationId)
        val selectedPath = ConversationUiState.resolvePath(
            allMessages = entities.map { it.toUiChatMessage { text -> text } },
            streamingMsg = null,
            selectedChildren = selected,
        )
        val entitiesById = entities.associateBy(MessageEntity::id)
        val replacement = request.replaceMessageId?.let { messageId ->
            val targetIndex = selectedPath.indexOfFirst { it.id == messageId }
            if (targetIndex < 0) {
                return CompactResult.Failed("Compact message is not on the selected branch")
            }
            entitiesById[messageId]
                ?.takeIf { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) }
                ?.takeIf {
                    it.status in setOf(
                        MessageStatus.SUCCESS,
                        MessageStatus.ERROR,
                        MessageStatus.STOPPED,
                    )
                }
                ?: return CompactResult.Failed("Compact message is not ready to recompact")
        }
        val selectedScope = selectedContextBeforeReplacement(
            selectedPath = selectedPath,
            replacementMessageId = replacement?.id,
        )
        val selectedEntities = selectedScope.mapNotNull { entitiesById[it.id] }
        val path = projectProviderMessages(
            entities = ApiPathAssembler.assemble(selectedEntities, entities),
            includeStoredTranscriptions = providerAccess?.generationContext
                ?.imageTranscriptionEnabled
                ?: settings.imageTranscriptionEnabled.value,
        )
        if (path.isEmpty()) return CompactResult.NotNeeded
        val semanticPath = path.filterNot {
            it.isContextCompact() && !it.isSuccessfulContextCompact()
        }
        val nearest = semanticPath.indexOfLast(ChatMessage::isSuccessfulContextCompact)
        val activePath = semanticPath.drop(nearest.coerceAtLeast(-1) + 1)
        val compactablePath = compactSplitMessages(activePath)
        val split = splitContextForCompactRetention(
            compactablePath,
            request.retainLogicalMessages,
        )
        val activeUsage = contextWindowUsage(
            projectGenerationInputMessages(
                messages = semanticPath,
                includeImages = true,
                userPrepend = providerAccess?.userPrepend,
                userPostpend = providerAccess?.userPostpend,
            ),
            threshold ?: Int.MAX_VALUE,
            fixedTokenCost = fixedTokenCost,
        )
        if (
            threshold != null &&
            activeUsage.estimatedTokenCount < threshold
        ) return CompactResult.NotNeeded
        if (threshold != null && split.prefix.isEmpty()) return CompactResult.NotNeeded

        val appendBoundary = if (replacement == null) {
            resolveCompactAppendBoundary(
                selectedPath,
                compactablePath,
                entitiesById,
            ) ?: return CompactResult.NotNeeded
        } else {
            CompactAppendBoundary(
                parentMessageId = replacement.parentId
                    ?: return CompactResult.Failed("Compact boundary disappeared"),
                childMessageId = null,
            )
        }
        val appendParentId = appendBoundary.parentMessageId
        val graphChildId = appendBoundary.childMessageId

        val completeContext = projectGenerationInputMessages(
            messages = semanticPath,
            includeImages = true,
            userPrepend = providerAccess?.userPrepend,
            userPostpend = providerAccess?.userPostpend,
        )
        val compactWindow = (threshold ?: settings.maxContextWindow.value).coerceAtLeast(1)
        val compactInput = buildRolledCompactInput(
            context = completeContext,
            systemPrompt = request.prompt,
            contextWindow = compactWindow,
        )
        if (compactInput.dropLast(1).isEmpty()) return CompactResult.NotNeeded

        val source = entitiesById[appendParentId]
            ?: return CompactResult.Failed("Compact boundary disappeared")
        val sourceRun = conversations.getRun(source.runId)
            ?: return CompactResult.Failed("Compact source run disappeared")
        val compactId = replacement?.id ?: Constants.COMPACT_MSG_PREFIX + UUID.randomUUID()
        val runId = automaticIdentity?.runId ?: replacement?.runId ?: compactRunId
        val startedAt = System.currentTimeMillis()
        val compactRun = RunEntity(
            id = runId,
            conversationId = conversationId,
            parentRunId = sourceRun.id,
            status = RunStatus.ACTIVE,
            activeSlot = 1,
            startedAt = startedAt,
            lastCheckpointAt = startedAt,
        )
        val compactMessage = replacement?.copy(
            text = "",
            status = MessageStatus.SENDING,
            modelName = request.model,
        ) ?: MessageEntity(
            id = compactId,
            conversationId = conversationId,
            parentId = appendParentId,
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            timestamp = startedAt,
            modelName = request.model,
            runId = runId,
            runSequence = 0,
        )
        val repairedSelections = selected.toMutableMap().apply {
            put(appendParentId, compactId)
            if (graphChildId != null) put(compactId, graphChildId)
        }
        val durableCompactMessage = if (replacement != null) {
            conversations.beginRecompactContextCompact(
                messageId = replacement.id,
                modelName = request.model,
                expectedSelections = selected,
            )
        } else if (automaticIdentity != null) {
            conversations.beginAutomaticContextCompact(
                message = compactMessage,
                childMessageId = graphChildId,
                expectedPass = automaticIdentity.pass,
                expectedSelections = selected,
                selections = repairedSelections,
                at = startedAt,
            )
        } else {
            conversations.beginManualContextCompact(
                run = compactRun,
                message = compactMessage,
                expectedSelections = selected,
                selections = repairedSelections,
                at = startedAt,
            )
        }
        suspend fun publishGraph() {
            try {
                onGraphChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Room is authoritative. A transient UI projection failure must not abandon the
                // already-created Compact row or turn a successful Provider pass into failure.
            }
        }

        val summary = StringBuilder()
        val checkpointWriter = StreamingCheckpointWriter(
            scope = CoroutineScope(currentCoroutineContext()),
            persist = { checkpoint ->
                if (replacement != null) {
                    conversations.updateRecompactCheckpoint(
                        messageId = compactId,
                        text = checkpoint.text,
                    )
                } else {
                    conversations.updateContextCompactCheckpoint(
                        messageId = compactId,
                        runId = runId,
                        expectedPass = automaticIdentity?.pass,
                        text = checkpoint.text,
                    )
                }
            },
            onFailure = { error ->
                com.newoether.agora.util.DebugLog.e(
                    "AgoraVM",
                    "Failed to persist Context Compact streaming checkpoint",
                    error,
                )
            },
        )
        var checkpointWriterClosed = false
        suspend fun closeCheckpointWriter() {
            if (!checkpointWriterClosed) {
                checkpointWriterClosed = true
                checkpointWriter.cancelAndJoin()
            }
        }
        suspend fun settleFailure(reason: String): CompactResult.Failed {
            closeCheckpointWriter()
            val partial = summary.toString().trim()
            val failureText = if (partial.isEmpty()) {
                "Compact failed: $reason"
            } else {
                "$partial\n\n[Compact failed: $reason]"
            }
            if (replacement != null) {
                conversations.settleRecompactMessage(
                    messageId = compactId,
                    text = failureText,
                    status = MessageStatus.ERROR,
                )
            } else if (automaticIdentity != null) {
                conversations.settleAutomaticContextCompact(
                    messageId = compactId,
                    runId = runId,
                    expectedPass = automaticIdentity.pass,
                    text = failureText,
                    status = MessageStatus.ERROR,
                )
            } else {
                conversations.settleManualContextCompact(
                    messageId = compactId,
                    runId = runId,
                    text = failureText,
                    messageStatus = MessageStatus.ERROR,
                    runStatus = RunStatus.FAILED,
                    reason = RunEndReason.PROVIDER_ERROR,
                )
            }
            publishGraph()
            return CompactResult.Failed(reason, compactId)
        }

        suspend fun settleCancellation() {
            closeCheckpointWriter()
            val partial = summary.toString().trim()
            val stoppedText = partial.ifEmpty { "Context compact was interrupted." }
            if (replacement != null) {
                conversations.settleRecompactMessage(
                    messageId = compactId,
                    text = stoppedText,
                    status = MessageStatus.STOPPED,
                )
            } else if (automaticIdentity != null) {
                // A concurrent Stop owns atomic terminalization of the active Run and every
                // SENDING row. This conditional update only applies when no Stop won the race.
                conversations.settleAutomaticContextCompact(
                    messageId = compactId,
                    runId = runId,
                    expectedPass = automaticIdentity.pass,
                    text = stoppedText,
                    status = MessageStatus.ERROR,
                )
            } else {
                conversations.settleManualContextCompact(
                    messageId = compactId,
                    runId = runId,
                    text = stoppedText,
                    messageStatus = MessageStatus.STOPPED,
                    runStatus = RunStatus.STOPPED,
                    reason = RunEndReason.USER_STOPPED,
                )
            }
            publishGraph()
        }

        return try {
            publishGraph()
            val providerName = providerAccess?.providerName
                ?: providers.providerForModel(request.model)
            val key = providerAccess?.apiKey
                ?: settings.awaitActiveKey(providerName).orEmpty()
            val providerConfigured = providerAccess?.configured
                ?: providers.isConfigured(providerName, key)
            if (!providerConfigured) {
                return settleFailure("The selected compact model is not configured")
            }
            val provider = providerAccess?.provider ?: providers.getInstanceOrNull(providerName)
                ?: return settleFailure("The selected compact provider is unavailable")

            val config = ProviderConfig(
                apiKey = key,
                modelId = ModelId.parse(providers.canonicalModelId(request.model)).modelName,
                systemPrompt = request.prompt,
                maxContextWindow = compactProviderMessageBudget(
                    input = compactInput,
                    systemPrompt = request.prompt,
                    contextWindow = compactWindow,
                ),
                thinkingEnabled = false,
                baseUrl = providerAccess?.baseUrl ?: providers.getEffectiveBaseUrl(providerName),
            )
            val effectIdentity = automaticIdentity ?: checkNotNull(manualIdentity)
            val providerIdentity = effectIdentity.copy(
                effectId = "${effectIdentity.effectId}:provider",
            )
            suspend fun collectResponse(): ProviderPassOutcome = providerPassRunner.run(
                identity = providerIdentity,
                provider = provider,
                messages = compactInput,
                config = config,
            ) { event ->
                if (event is StreamEvent.TextChunk) {
                    summary.append(event.text)
                    onSummaryChunk(event.text)
                    checkpointWriter.enqueue(
                        durableCompactMessage.toUiChatMessage { it }.copy(
                            text = summary.toString(),
                        )
                    )
                }
            }
            pauseLoop(conversationId)
            val outcome = if (providerName == Constants.PROVIDER_LOCAL) {
                LocalModelSerializer.mutex.withLock {
                    withContext(Dispatchers.IO) { collectResponse() }
                }
            } else collectResponse()
            when (outcome) {
                is ProviderPassOutcome.CompletedText -> Unit
                is ProviderPassOutcome.CompletedToolCalls ->
                    return settleFailure("Compact model returned tool calls")
                is ProviderPassOutcome.Truncated ->
                    return settleFailure(outcome.error.userMessage())
                is ProviderPassOutcome.Failed ->
                    return settleFailure(outcome.error.userMessage())
                is ProviderPassOutcome.Cancelled ->
                    throw CancellationException("Compact provider pass was cancelled")
            }
            val summaryText = summary.toString().trim()
            if (summaryText.isBlank()) {
                return settleFailure("Compact model returned an empty summary")
            }
            val persistedText = buildPersistedCompactText(summaryText, split.retained)
            closeCheckpointWriter()
            val settled = if (replacement != null) {
                conversations.settleRecompactMessage(
                    messageId = compactId,
                    text = persistedText,
                    status = MessageStatus.SUCCESS,
                )
            } else if (automaticIdentity != null) {
                conversations.settleAutomaticContextCompact(
                    messageId = compactId,
                    runId = runId,
                    expectedPass = automaticIdentity.pass,
                    text = persistedText,
                    status = MessageStatus.SUCCESS,
                )
            } else {
                conversations.settleManualContextCompact(
                    messageId = compactId,
                    runId = runId,
                    text = persistedText,
                    messageStatus = MessageStatus.SUCCESS,
                    runStatus = RunStatus.COMPLETED,
                    reason = RunEndReason.MODEL_COMPLETED,
                )
            }
            publishGraph()
            if (settled) {
                CompactResult.Created(compactId)
            } else {
                CompactResult.Failed("Context compact result was superseded", compactId)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { settleCancellation() }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) {
                settleFailure(error.message?.takeIf(String::isNotBlank) ?: "Context compact failed")
            }
        }
    }
}
