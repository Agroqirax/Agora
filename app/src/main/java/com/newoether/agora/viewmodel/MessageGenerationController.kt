package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.MessageAttachmentCloneSession
import com.newoether.agora.data.cloneAttachmentMeta
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface SendPlacement {
    data class Direct(
        val uiToken: Long,
        val runId: String,
        val inputEffect: RunEffect.PersistAcceptedInput,
    ) : SendPlacement
    data class Queued(val messageId: String) : SendPlacement
    data class QueuedAndDrain(
        val messageId: String,
        val claim: QueuedDrainClaim,
    ) : SendPlacement
    data object RetryAfterRelease : SendPlacement
    data object RetryAfterCompact : SendPlacement

    /**
     * The slot was busy and the caller asked for a direct-only send, so NOTHING was persisted.
     *
     * Distinct from [RetryAfterRelease], which waits for the slot to free up. A direct-only caller
     * must never wait: an automation caller already holds the conversation lock that the current
     * slot owner may be blocked on, so waiting there deadlocks the whole conversation.
     */
    data object Rejected : SendPlacement
}

private data class QueuedDrainClaim(
    val lease: GuidanceBatchLease,
    val inputEffect: RunEffect.PersistAcceptedInput,
) {
    val batch: List<QueuedSend> get() = lease.batch
    val uiToken: Long get() = inputEffect.identity.ownerToken
}

/**
 * Result of delegating one automation (Loop) cycle to the foreground send path.
 *
 * [SlotBusy] means nothing was persisted and nothing generated, so the caller reports a typed busy
 * cycle outcome. It must not fall back to a second headless writer. It is never a partial success:
 * a direct-only send either owns the slot for the whole turn or does not run at all.
 */
internal sealed interface AutomationSendOutcome {
    data object SlotBusy : AutomationSendOutcome

    /** [modelMessageId] is the row this very send created, not a re-derived conversation tail. */
    data class Delivered(val modelMessageId: String) : AutomationSendOutcome
}

/** Dictates whether a send scrolls unconditionally or only while the user is at the bottom. */
internal enum class SendScrollPolicy {
    /** Always request absolute-bottom scroll (manual send, queue drain). */
    FORCE,
    /** Request absolute-bottom scroll only when the viewport is already at the bottom (loop cycle). */
    ATTACHED_ONLY,
}

/**
 * Durable acceptance result returned to the composer.
 *
 * A direct send enters the visible conversation. Its Controller-owned UI commit also requests the
 * scroll; the composer only uses this result to decide whether it may clear the submitted draft.
 * A queued send remains exclusively in the queue banner until a legal boundary starts its fresh
 * normal Send/Run.
 */
sealed interface SendAcceptance {
    val messageId: String
    val conversationId: String

    data class Direct(
        override val messageId: String,
        override val conversationId: String,
    ) : SendAcceptance

    data class Queued(
        override val messageId: String,
        override val conversationId: String,
    ) : SendAcceptance
}

internal fun SendAcceptance.hasDurableAttachmentOwner(): Boolean =
    this is SendAcceptance.Direct

/**
 * Resolves the shared user anchor for regeneration.
 *
 * A normal/edit Run owns its boundary user at sequence 0. A regeneration Run intentionally owns
 * only its assistant branch (plus any later queued interventions), so its anchor is the parent of
 * its earliest ordinary model row. This keeps repeated regeneration under the same user message
 * instead of cloning or progressively nesting user inputs.
 */
internal object RunRegenerationPolicy {
    fun selectBoundaryInput(
        messages: List<MessageEntity>,
        runId: String,
    ): MessageEntity? {
        val runMessages = messages.filter { it.runId == runId }
        val ownedBoundary = runMessages
            .asSequence()
            .filter {
                it.participant == Participant.USER &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            }
            .minWithOrNull(messageOrder)
            ?.takeIf { it.runSequence == 0L }
        if (ownedBoundary != null) return ownedBoundary

        val rootOutput = runMessages
            .asSequence()
            .filter {
                it.participant == Participant.MODEL &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            }
            .minWithOrNull(messageOrder)
            ?: return null
        return messages.firstOrNull {
            it.id == rootOutput.parentId &&
                it.participant == Participant.USER &&
                !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX)
        }
    }

    private val messageOrder =
        compareBy<MessageEntity> {
            it.runSequence.takeIf { sequence -> sequence >= 0L } ?: Long.MAX_VALUE
        }
            .thenBy { it.timestamp }
            .thenBy { it.id }
}

/**
 * Merges Controller-owned optimistic commits into the Room-backed UI snapshot by message ID.
 *
 * Room can publish a just-inserted row before the inserting coroutine reaches its UI commit.
 * Appending in that race creates duplicate in-memory rows (the database remains unique), which
 * projection code can then misread as real Edit/Regenerate siblings.
 */
internal object UiMessageCommitPolicy {
    fun upsert(
        existing: List<ChatMessage>,
        committed: List<ChatMessage>,
    ): List<ChatMessage> {
        if (committed.isEmpty()) return existing.distinctBy { it.id }
        val committedById = committed.associateBy { it.id }
        val emittedIds = hashSetOf<String>()
        return buildList(existing.size + committedById.size) {
            for (message in existing) {
                if (emittedIds.add(message.id)) {
                    add(committedById[message.id] ?: message)
                }
            }
            for (message in committedById.values) {
                if (emittedIds.add(message.id)) add(message)
            }
        }
    }
}

/**
 * Owns the message lifecycle (send / regenerate / edit / delete) and the
 * race-free generation handshake.
 *
 * Generation state is held per-conversation in [ConversationGenerationState]
 * (obtained from [ConversationStateRegistry]); the StateFlows ChatViewModel
 * exposes to the UI are a mirror of whichever conversation is currently open.
 * Synchronous writes to those flows inside the generation coroutines are gated
 * on the open conversation via [ifOpenOn] so a background generation can't
 * clobber the visible conversation's UI.
 */
internal class MessageGenerationController(
    private val viewModelScope: CoroutineScope,
    private val application: Application,
    private val appContext: Context,
    // -- Process-scoped collaborators --
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val registry: ConversationStateRegistry,
    private val generationManagerProvider: () -> GenerationManager,
    private val requestBuilder: GenerationRequestBuilder,
    private val payloadBuilder: MessagePayloadBuilder,
    private val providerRegistry: ProviderRegistry,
    private val localProvider: LocalProvider,
    private val executionCoordinator: ConversationExecutionCoordinator,
    // -- Shared UI state: the SAME instances ChatViewModel exposes -never recreate --
    private val renderStore: ConversationRenderStore,
    private val currentConversationId: MutableStateFlow<String?>,
    private val isNewChatMode: MutableStateFlow<Boolean>,
    private val pendingConversationSettings: MutableStateFlow<ConversationSettings?>,
    private val pendingSystemPromptId: MutableStateFlow<String?>,
    private val currentActiveModel: StateFlow<String>,
    private val messages: StateFlow<List<ChatMessage>>,
    // -- Callbacks into ChatViewModel-owned side effects --
    private val onScrollToMessage: (String?) -> Unit,
    private val onScrollToAbsoluteBottomAfter: (conversationId: String, messageId: String) -> Unit,
    /** Like [onScrollToAbsoluteBottomAfter] but the scroll is suppressed when the viewport is not
     *  already at the bottom. Used by loop cycles so automated messages never steal the user's
     *  scroll position. */
    private val onScrollToAttachedBottomAfter: (conversationId: String, messageId: String) -> Unit,
    /** Fires on every send acceptance (Direct + Queued) regardless of trigger source.
     *  ChatApp wires this to haptics.confirm() so manual send, queue drain, and loop cycle
     *  all produce identical haptic feedback. */
    private val onSendAcceptedEvent: ((conversationId: String, messageId: String) -> Unit)? = null,
    private val onSnackbar: (String) -> Unit,
    private val onSnackbarSuspend: suspend (String) -> Unit,  // sequential emit inside generateTitle
    // Called when sendMessage creates a NEW conversation, so the UI can suppress the
    // conversation-open auto-scroll (the send's own physical-bottom scroll handles it) and
    // avoid a double scroll on the first message of a new chat.
    private val onConversationCreatedBySend: (String) -> Unit = {},
    // Called once when a hidden task/loop execution becomes searchable. The callback
    // only enqueues background work; embedding computation must not run under the send lock.
    // Called after a USER message row is persisted (send / edit), so incremental RAG
    // indexing covers the user's side too -the model reply is indexed at generation end
    // via GenerationManager.onMessagePersisted, and without this hook user messages only
    // ever entered the cache through a manual full re-cache. Enqueues background work only.
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** Covers destructive tree mutation until ChatApp has settled the resulting path. */
    private val onTreeMutationStart: suspend () -> Long? = { null },
    private val onTreeMutationSettling: (requestId: Long?, targetMessageId: String?) -> Unit =
        { _, _ -> },
    private val onTreeMutationFailed: (requestId: Long?) -> Unit = {},
    private val regenerationTransitions: RegenerationTransitionCoordinator,
    private val pauseConversationTasks: suspend (String) -> Unit = {},
) {
    private val generationManager: GenerationManager get() = generationManagerProvider()
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val contextCompactor = ContextCompactor(
        conversations = convRepo,
        settings = settings,
        providers = providerRegistry,
        pauseLoop = pauseConversationTasks,
    )
    private val acceptedInputGraphWriter = AcceptedInputGraphWriter(convRepo)
    private val compactEffectCoordinator = ContextCompactEffectCoordinator()

    private suspend fun compactBeforeBoundaryIfNeeded(
        genId: String,
        modelId: String,
        contextLimit: Int,
        state: ConversationGenerationState,
    ) {
        if (!settings.contextCompactEnabled.value) return
        when (
            val execution = compactEffectCoordinator.executeAutomatic(state) { effect ->
                val compactResult = contextCompactor.compactAutomatic(
                    conversationId = genId,
                    fallbackModel = modelId,
                    contextLimit = contextLimit,
                    compactRunId = effect.compactRunId,
                )
                if (compactResult is CompactResult.Created) {
                    val all = convRepo.getMessagesForConversationSnapshot(genId)
                    val selected = convRepo.restoreBranchSelections(genId)
                    ifOpenOn(genId) {
                        renderStore.replaceGraph(
                            allMessages = all.map { it.toUiChatMessage(appContext) },
                            selectedChildren = selected,
                        )
                    }
                }
                compactResult
            }
        ) {
            is ContextCompactEffectCoordinator.Execution.Settled -> when (
                val result = execution.result
            ) {
                is CompactResult.Failed -> throw IllegalStateException(
                    "Automatic context compact failed: ${result.message}",
                )
                is CompactResult.Created,
                CompactResult.NotNeeded,
                -> Unit
            }
            ContextCompactEffectCoordinator.Execution.Busy -> {
                if (state.stopping.value) {
                    throw CancellationException("Automatic context compact was stopped")
                }
                error("Automatic context compact was not admitted for the active Run")
            }
            ContextCompactEffectCoordinator.Execution.Superseded -> {
                if (state.stopping.value) {
                    throw CancellationException("Automatic context compact was superseded by Stop")
                }
                error("Automatic context compact result was superseded")
            }
        }
    }

    private suspend fun <T> withOptionalLock(
        genId: String,
        alreadyHoldsLock: Boolean,
        block: suspend () -> T,
    ): T = if (alreadyHoldsLock) block()
    else executionCoordinator.withConversationLock(genId, block)

    private fun resolveScrollCallback(policy: SendScrollPolicy): (String, String) -> Unit =
        when (policy) {
            SendScrollPolicy.FORCE -> onScrollToAbsoluteBottomAfter
            SendScrollPolicy.ATTACHED_ONLY -> onScrollToAttachedBottomAfter
        }

    /**
     * Run [block] only if the currently-open conversation is [genId]. Guards synchronous
     * writes to the shared global flows so a background generation (operating on its own
     * private [ConversationGenerationState] flows) cannot clobber the visible conversation's UI.
     */
    private fun ifOpenOn(genId: String, block: () -> Unit) {
        if (currentConversationId.value == genId) block()
    }

    /**
     * Terminalizes a Run whose durable graph was created but whose provider generation could not
     * be started. Cancellation is deliberately handled elsewhere as STOPPED; this path is only
     * for real setup failures.
     */
    private suspend fun failGenerationSetup(
        conversationId: String,
        runId: String,
        modelMessageId: String?,
        uiToken: Long,
        state: ConversationGenerationState,
        error: Exception,
    ) {
        DebugLog.e("AgoraVM", "Failed to start Run $runId", error)
        val errorText = appContext.getString(R.string.failed_to_generate)
        val failedMessage = modelMessageId?.let { id ->
            runCatching {
                convRepo.getMessagesForConversationSnapshot(conversationId)
                    .firstOrNull { it.id == id }
                    ?.toUiChatMessage(appContext)
                    ?.copy(text = errorText, status = MessageStatus.ERROR)
            }.getOrNull()
        }
        if (failedMessage != null) {
            runCatching { convRepo.updateStreamingMessageCheckpoint(failedMessage) }
            state.streamUpdate(uiToken, failedMessage)
            state.streamClear(uiToken)
        }
        runCatching { convRepo.failRun(runId) }
        state.loadingChange(uiToken, false)
        onSnackbar(errorText)
    }

    suspend fun compactManual(request: CompactRequest): CompactResult {
        val conversationId = currentConversationId.value
            ?: return CompactResult.Failed("Open a conversation first")
        val state = registry.getOrCreate(conversationId)
        return when (
            val execution = compactEffectCoordinator.executeManual(state) { effect ->
                executionCoordinator.withConversationLock(conversationId) {
                    if (convRepo.getLiveRun(conversationId) != null) {
                        return@withConversationLock CompactResult.Failed("Conversation is busy")
                    }
                    val result = contextCompactor.compactManual(
                        conversationId = conversationId,
                        request = request,
                        compactRunId = effect.compactRunId,
                    )
                    if (result is CompactResult.Created) {
                        val all = convRepo.getMessagesForConversationSnapshot(conversationId)
                        val selected = convRepo.restoreBranchSelections(conversationId)
                        ifOpenOn(conversationId) {
                            renderStore.replaceGraph(
                                allMessages = all.map { it.toUiChatMessage(appContext) },
                                selectedChildren = selected,
                            )
                        }
                    }
                    result
                }
            }
        ) {
            is ContextCompactEffectCoordinator.Execution.Settled -> execution.result
            ContextCompactEffectCoordinator.Execution.Busy ->
                CompactResult.Failed("Wait for the current generation or context compact to finish")
            ContextCompactEffectCoordinator.Execution.Superseded ->
                CompactResult.Failed("Context compact was interrupted")
        }
    }

    // ==================================
    // deleteMessage
    // ==================================

    /**
     * Deletes one structural message branch. A USER target removes its complete edit subtree; a
     * MODEL target removes its regeneration subtree while retaining the shared boundary USER.
     * ACTIVE and STOPPING both reject deletion; Stop is never an implicit side effect.
     */
    fun deleteMessage(messageId: String): Int {
        val currentId = currentConversationId.value ?: return 0
        val state = registry.getOrCreate(currentId)
        if (state.generating.value) return 0
        val snapshot = renderStore.allMessages
        if (snapshot.none { it.id == messageId }) return 0
        val compactOnly = messageId.startsWith(Constants.COMPACT_MSG_PREFIX)
        val previewIds = if (compactOnly) setOf(messageId) else structuralDescendantIds(snapshot, messageId)

        viewModelScope.launch(Dispatchers.IO) {
            val switchingRequestId = onTreeMutationStart()
            var committed = false
            try {
                state.queueMutationMutex.withLock {
                    // Recheck after the overlay fade and under the same mutex that accepts Send.
                    if (state.generating.value) return@withLock
                    executionCoordinator.withConversationLock(currentId) lock@ {
                        if (convRepo.getLiveRun(currentId) != null) return@lock
                        if (compactOnly) {
                            check(convRepo.removeContextCompact(messageId))
                            val remaining = convRepo.getMessagesForConversationSnapshot(currentId)
                            val selections = convRepo.restoreBranchSelections(currentId)
                            ifOpenOn(currentId) {
                                renderStore.replaceGraph(
                                    allMessages = remaining.map { it.toUiChatMessage(appContext) },
                                    selectedChildren = selections,
                                )
                            }
                            committed = true
                            onTreeMutationSettling(switchingRequestId, remaining.lastOrNull()?.id)
                            return@lock
                        }

                        val runs = convRepo.getRunsForConversationSnapshot(currentId)
                        val allMsgs = convRepo.getMessagesForConversationSnapshot(currentId)
                        val allChatMessages =
                            allMsgs.map { it.toUiChatMessage(appContext) }
                        val previousSelected = convRepo.restoreBranchSelections(currentId)
                        val previousRunSelections =
                            convRepo.restoreRunBranchSelections(currentId)
                        val plan = BranchDeletionPlanner.plan(
                            rootMessageId = messageId,
                            messages = allMsgs,
                            runs = runs,
                            messageSelections = previousSelected,
                            runSelections = previousRunSelections,
                        )
                        val staleList = allMsgs.filter { it.id in plan.deletedMessageIds }
                        val remainingMsgs =
                            allMsgs.filter { it.id !in plan.deletedMessageIds }
                        check(
                            convRepo.deleteMessageSubtree(
                                conversationId = currentId,
                                rootMessageId = messageId,
                                staleMessageIds = plan.deletedMessageIds.toList(),
                                rootRunIdsToDelete = plan.rootRunIdsToDelete.toList(),
                                messageSelections = plan.messageSelections,
                                runSelections = plan.runSelections,
                            )
                        ) { "Message $messageId disappeared during delete" }

                        // Files are external to Room, so remove them only after graph commit.
                        convRepo.deleteMessageFiles(staleList)
                        val remainingChatMessages =
                            remainingMsgs.map { it.toUiChatMessage(appContext) }
                        val remainingPath = ConversationUiState.resolvePath(
                            allMessages = remainingChatMessages,
                            streamingMsg = null,
                            selectedChildren = plan.messageSelections,
                        )
                        val targetAfterDelete = deleteSettlementTargetMessageId(
                            messagesBeforeDelete = allChatMessages,
                            deletedRootMessageId = messageId,
                            remainingPath = remainingPath,
                        )
                        ifOpenOn(currentId) {
                            renderStore.replaceGraph(
                                allMessages = remainingChatMessages,
                                selectedChildren = plan.messageSelections,
                            )
                        }
                        committed = true
                        onTreeMutationSettling(switchingRequestId, targetAfterDelete)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to delete message branch $messageId", e)
            } finally {
                if (!committed) onTreeMutationFailed(switchingRequestId)
            }
        }

        return previewIds.size
    }

    private fun structuralDescendantIds(
        messages: List<ChatMessage>,
        rootMessageId: String,
    ): Set<String> {
        val childrenByParent = messages.groupBy { it.parentId }
        val descendants = linkedSetOf(rootMessageId)
        val pending = ArrayDeque<String>().apply { add(rootMessageId) }
        while (pending.isNotEmpty()) {
            for (child in childrenByParent[pending.removeFirst()].orEmpty()) {
                if (descendants.add(child.id)) pending.add(child.id)
            }
        }
        return descendants
    }

    // ==================================
    // regenerate
    // ==================================

    fun regenerate(messageId: String): Boolean {
        val genId = currentConversationId.value ?: return false
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) =
            requestBuilder.resolveProviderKey(modelId) ?: return false

        // Validate and snapshot the open conversation BEFORE claiming the slot. The generation
        // coroutine may wait behind automation while the user switches to another conversation.
        val visiblePath = messages.value
        val messageToRegenerate = visiblePath.find { it.id == messageId } ?: return false
        if (messageToRegenerate.participant != Participant.MODEL) return false
        val sourceRunId = messageToRegenerate.runId ?: return false
        val targetUserMessageId = messageToRegenerate.parentId ?: return false
        val outputBoundary = visiblePath
            .filter {
                it.runId == sourceRunId &&
                    it.participant == Participant.MODEL &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            .maxWithOrNull(
                compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                    .thenBy { it.timestamp }
                    .thenBy { it.id }
            )
        if (outputBoundary?.id != messageId) return false

        // Regenerate is idle-only by product rule. Enforce it atomically in the state machine in
        // addition to the UI's enabled flag, which can lag during a conversation switch.
        val myUiToken = state.tryAcquireForReplacement() ?: return false
        val transition = regenerationTransitions.begin(
            conversationId = genId,
            oldMessageId = messageId,
            targetUserMessageId = targetUserMessageId,
        ) ?: run {
            state.scope.launch {
                releaseUnlaunchedSlotAndDrain(state, myUiToken)
            }
            return false
        }
        val runId = UUID.randomUUID().toString()

        var graphCommitted = false
        val generationJob = state.launchGenerationJob(myUiToken) generation@ {
            var setupModelMessageId: String? = null
            var runBound = false
            try {
                if (!regenerationTransitions.awaitFade(transition.id)) return@generation
                if (
                    !state.isCurrentToken(myUiToken) ||
                    !regenerationTransitions.isAnimating(transition.id) ||
                    currentConversationId.value != genId
                ) {
                    return@generation
                }
                val myPersistId = state.nextPersistId()
                executionCoordinator.withConversationLock(genId) lock@ {
                if (
                    !state.isCurrentToken(myUiToken) ||
                    !regenerationTransitions.isAnimating(transition.id) ||
                    currentConversationId.value != genId
                ) {
                    return@lock
                }
                val persistedMessages = convRepo.getMessagesForConversationSnapshot(genId)
                val persistedTarget = persistedMessages.find { it.id == messageId } ?: return@lock
                if (persistedTarget.runId != sourceRunId) return@lock
                convRepo.getRun(sourceRunId) ?: return@lock
                val sourceInput =
                    RunRegenerationPolicy.selectBoundaryInput(persistedMessages, sourceRunId)
                        ?: return@lock
                val inputRunId = sourceInput.runId
                val modelMessageId = UUID.randomUUID().toString()
                setupModelMessageId = modelMessageId
                val startTime = maxOf(System.currentTimeMillis(), persistedTarget.timestamp + 1)
                val modelEntity = MessageEntity(
                    id = modelMessageId,
                    conversationId = genId,
                    parentId = sourceInput.id,
                    text = "",
                    thoughts = null,
                    thoughtTitle = null,
                    status = MessageStatus.SENDING,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    modelName = modelId,
                    runId = runId,
                    runSequence = 0,
                )
                val graphCommit = convRepo.createRunWithMessages(
                    RunEntity(
                        id = runId,
                        conversationId = genId,
                        parentRunId = inputRunId,
                        status = RunStatus.ACTIVE,
                        activeSlot = 1,
                        startedAt = startTime,
                        lastCheckpointAt = startTime,
                    ),
                    listOf(modelEntity),
                    messageSelectionUpdates = mapOf(sourceInput.id to modelEntity.id),
                )
                graphCommitted = true
                regenerationTransitions.markCommitted(transition.id)
                runBound = state.tryBindRun(myUiToken, runId)
                if (!runBound) {
                    // Stop can win after Room commits but before the fresh Run is visible to the
                    // slot. No Stop finalizer knows this id, so this coroutine owns termination.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        convRepo.finishStoppedGeneration(emptyList(), runId)
                    }
                    return@lock
                }
                val placeholder = modelEntity.toUiChatMessage(appContext)
                val selectedAfterRegenerate = graphCommit.messageSelections
                // The overlay is installed before the graph projection. An intermediate combine
                // frame can therefore only retain the old path; it can never expose an empty
                // persisted SENDING placeholder.
                state.streamUpdate(myUiToken, placeholder)
                ifOpenOn(genId) {
                    renderStore.commitGraph(
                        committedMessages = listOf(placeholder),
                        selectedChildren = selectedAfterRegenerate,
                        streamingMessage = placeholder,
                    )
                }
                launchGeneration(
                    genId, modelMessageId, startTime,
                    isRegenerate = false, replaceMessageId = null,
                    providerName, modelId, activeKey, myUiToken, myPersistId,
                    state, runId = runId, pass = 0, callerTag = "regenerate"
                )
                }
            } catch (e: CancellationException) {
                // A Room transaction may commit just before cancellation is observed. If the Run
                // was never bound, the normal Stop finalizer cannot discover it.
                if (!runBound) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (convRepo.getRun(runId) != null) {
                            graphCommitted = true
                            regenerationTransitions.markCommitted(transition.id)
                            convRepo.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                failGenerationSetup(
                    conversationId = genId,
                    runId = runId,
                    modelMessageId = setupModelMessageId,
                    uiToken = myUiToken,
                    state = state,
                    error = e,
                )
            } finally {
                if (!graphCommitted) {
                    regenerationTransitions.abort(transition.id)
                }
            }
        }
        if (generationJob == null) {
            regenerationTransitions.abort(transition.id)
        }
        return generationJob != null
    }

    // ==================================
    // launchGeneration
    // ==================================

    /**
     * Shared generation tail called by [sendMessage], [regenerate], and
     * [editMessage]: resolves system prompt + conversation settings, builds
     * [GenerationConfig]/[GenerationContext], and launches the provider stream.
     *
     * All three entry points converge here after their differing branch-setup
     * heads, eliminating copy-pasted prompt-resolution / config-building /
     * callback-wiring code.
     */
    private suspend fun launchGeneration(
        currentId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        providerName: String,
        modelId: String,
        activeKey: String,
        uiToken: Long,
        persistId: Long,
        state: ConversationGenerationState,
        runId: String,
        pass: Int,
        callerTag: String
    ) {
        val requestTrace = com.newoether.agora.api.HttpClient.RequestTrace(
            requestId = modelMessageId,
            origin = callerTag,
        )
        requestTrace.mark(
            "prepare_start",
            "acceptedDelayMs=${(System.currentTimeMillis() - startTime).coerceAtLeast(0L)}",
        )
        val resolved = requestBuilder.buildEffectiveSystemPrompt(currentId, modelId)
        val effectiveSettings = requestBuilder.buildEffectiveConversationSettings(currentId)
        // The already-loaded key is authoritative on the normal path. Only await DataStore during
        // the startup race where the eager StateFlow still exposes its empty default; reading both
        // preference flows on every send unnecessarily lengthened the visible Sending phase.
        val freshKey = activeKey.takeIf { it.isNotBlank() }
            ?: settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
            .orEmpty()
        try {
            val (config, genCtx) = requestBuilder.buildGenerationPair(
                providerName, modelId, freshKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, currentId
            )
            requestTrace.mark("request_config_ready")
            // No global slot: remote generations run concurrently (only the per-conversation
            // lock above serializes same-conversation work); local model work is serialized
            // inside LocalProvider via LocalModelSerializer. Stop therefore releases
            // immediately -nothing is queued behind a held process-wide mutex.
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = isRegenerate,
                replaceMessageId = replaceMessageId,
                modelName = modelId,
                runId = runId,
                pass = pass,
                ownerToken = uiToken,
                config = config,
                ctx = genCtx,
                // The coroutine's own Job -reading state.generationJob here races the caller's
                // assignment (the coroutine can start before `state.generationJob = launch{...`
                // completes and observe the PREVIOUS job).
                generationJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
                callbacks = state.callbacksFor(uiToken, persistId).copy(
                    onToolRoundPersisted = {
                        compactBeforeBoundaryIfNeeded(
                            currentId,
                            modelId,
                            effectiveSettings.contextWindow ?: settings.maxContextWindow.value,
                            state,
                        )
                    },
                ),
                streamScope = state.streamScope,
                requestTrace = requestTrace,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AgoraVM", "Generation failed in $callerTag", e)
            // A pre-stream failure (prompt/config build -e.g. RAG key resolution) would otherwise
            // strand the SENDING placeholder row + streaming overlay until the conversation is
            // reopened. Persist a terminal ERROR row and clear this generation's overlay.
            runCatching {
                val existing = convRepo.getMessagesForConversationSnapshot(currentId)
                    .find { it.id == modelMessageId }
                if (existing != null && existing.status == MessageStatus.SENDING) {
                    convRepo.updateStreamingMessageCheckpoint(
                        ChatMessage(
                            id = existing.id,
                            parentId = existing.parentId,
                            text = "Error: ${e.localizedMessage ?: "Failed to build the request."}",
                            images = existing.images,
                            thoughts = existing.thoughts,
                            thoughtTitle = existing.thoughtTitle,
                            tokenCount = existing.tokenCount,
                            tokenUsage = TokenUsage.fromPersisted(
                                totalTokenCount = existing.tokenCount,
                                inputTokenCount = existing.inputTokenCount,
                                cachedInputTokenCount = existing.cachedInputTokenCount,
                                uncachedInputTokenCount = existing.uncachedInputTokenCount,
                                outputTokenCount = existing.outputTokenCount,
                                reasoningTokenCount = existing.reasoningTokenCount,
                            ),
                            status = MessageStatus.ERROR,
                            participant = existing.participant,
                            timestamp = existing.timestamp,
                            thoughtTimeMs = existing.thoughtTimeMs,
                            modelName = existing.modelName,
                            runId = existing.runId,
                            runSequence = existing.runSequence,
                        )
                    )
                }
                convRepo.failRun(runId)
            }
            state.streamClear(uiToken)
            state.loadingChange(uiToken, false)
        }
    }

    // ==================================
    // editMessage
    // ==================================

    suspend fun editMessage(messageId: String, newText: String): Boolean =
        withContext(Dispatchers.Default) {
            editMessageOffMain(messageId, newText)
        }

    private suspend fun editMessageOffMain(messageId: String, newText: String): Boolean {
        if (newText.isBlank()) return false
        val genId = currentConversationId.value ?: return false
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return false
        val visiblePath = messages.value
        val messageToEdit = visiblePath.find { it.id == messageId } ?: return false
        if (messageToEdit.participant != Participant.USER) return false
        val sourceRunId = messageToEdit.runId ?: return false
        val inputBoundary = visiblePath
            .filter {
                it.runId == sourceRunId &&
                    it.participant == Participant.USER &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            .minWithOrNull(
                compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                    .thenBy { it.timestamp }
                    .thenBy { it.id }
            )
        if (inputBoundary?.id != messageId) return false

        // Edit is idle-only by product rule; enforce it atomically below the UI gate.
        val myUiToken = state.tryAcquireForReplacement() ?: return false
        val runId = UUID.randomUUID().toString()
        val committed = CompletableDeferred<Boolean>()
        val job = state.launchGenerationJob(myUiToken) {
            val myPersistId = state.nextPersistId()
            var setupModelMessageId: String? = null
            var graphCommitted = false
            var runBound = false
            try {
            executionCoordinator.withConversationLock(genId) lock@ {
            val persistedMessages = convRepo.getMessagesForConversationSnapshot(genId)
            val persistedSource = persistedMessages.find { it.id == messageId } ?: return@lock
            if (persistedSource.runId != sourceRunId) return@lock
            val sourceRun = convRepo.getRun(sourceRunId) ?: return@lock
            val newUser = cloneEditedRunInputs(
                sourceInputs = listOf(persistedSource),
                destinationRunId = runId,
                textOverrides = mapOf(persistedSource.id to newText),
            ).single()
            val modelMessageId = UUID.randomUUID().toString()
            setupModelMessageId = modelMessageId
            val startTime = newUser.timestamp + 1
            val modelEntity = MessageEntity(
                id = modelMessageId, conversationId = genId, parentId = newUser.id,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = modelId, runId = runId, runSequence = 1,
            )
            val graphCommit = convRepo.createRunWithMessages(
                RunEntity(
                    id = runId,
                    conversationId = genId,
                    parentRunId = sourceRun.parentRunId,
                    status = RunStatus.ACTIVE,
                    activeSlot = 1,
                    startedAt = newUser.timestamp,
                    lastCheckpointAt = startTime,
                ),
                listOf(newUser, modelEntity),
                messageSelectionUpdates = mapOf(
                    newUser.parentId to newUser.id,
                    newUser.id to modelEntity.id,
                ),
            )
            graphCommitted = true
            runBound = state.tryBindRun(myUiToken, runId)
            if (!runBound) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    convRepo.finishStoppedGeneration(emptyList(), runId)
                }
                committed.complete(true)
                return@lock
            }
            val selectedAfterModelEdit = graphCommit.messageSelections
            runCatching { onUserMessagePersisted(newUser.id, newText) }
                .onFailure { error ->
                    DebugLog.w(
                        "MessageGenerationController",
                        "Failed to enqueue edited-message indexing for ${newUser.id}",
                        error,
                    )
                }
            // Commit the streaming overlay and replacement graph as one render snapshot.
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = newUser.id, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId,
                runId = runId, runSequence = 1,
            )
            state.streamUpdate(myUiToken, placeholder)
            ifOpenOn(genId) {
                renderStore.commitGraph(
                    committedMessages =
                        listOf(newUser.toUiChatMessage(appContext), placeholder),
                    selectedChildren = selectedAfterModelEdit,
                    streamingMessage = placeholder,
                )
                onScrollToMessage(newUser.id)
            }
            if (currentConversationId.value == genId) {
                // The three projection inputs are independent StateFlows. Await the combined
                // visible path so the UI cannot leave edit mode against an intermediate snapshot
                // that still resolves to the source branch.
                combine(messages, currentConversationId) { path, openConversationId ->
                    openConversationId != genId || path.any { it.id == newUser.id }
                }.first { projectedOrClosed -> projectedOrClosed }
            }
            // The durable branch and its UI projection are now committed. Only now may the
            // editor leave edit mode; completing earlier exposes the old branch for a frame.
            committed.complete(true)
            launchGeneration(
                genId, modelMessageId, startTime,
                isRegenerate = false, replaceMessageId = null,
                providerName, modelId, activeKey, myUiToken, myPersistId,
                state, runId = runId, pass = 0, callerTag = "editMessage"
            )
            }
            } catch (e: CancellationException) {
                if (!runBound) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (convRepo.getRun(runId) != null) {
                            graphCommitted = true
                            convRepo.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                failGenerationSetup(
                    conversationId = genId,
                    runId = runId,
                    modelMessageId = setupModelMessageId,
                    uiToken = myUiToken,
                    state = state,
                    error = e,
                )
            } finally {
                if (!committed.isCompleted) committed.complete(graphCommitted)
            }
        }
        if (job == null) return false
        return committed.await()
    }

    // ==================================
    // sendMessage
    // ==================================

    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
        onAccepted: suspend (SendAcceptance) -> Unit = {},
    ): SendAcceptance? = withContext(Dispatchers.Default) {
        sendMessageOffMain(text, images, attachments, onAccepted)
    }

    private suspend fun sendMessageOffMain(
        text: String,
        images: List<String>,
        attachments: List<SelectedAttachment>,
        onAccepted: suspend (SendAcceptance) -> Unit,
    ): SendAcceptance? {
        val selectedModelId = currentActiveModel.value
        // Pre-flight: a blank model fails fast BEFORE creating a new-chat row or enqueueing, so the
        // Send button never swallows a message into a conversation that can't generate.
        if (selectedModelId.isBlank()) {
            onSnackbar(application.getString(R.string.no_model_selected))
            return null
        }
        // Resolve a stable id before claiming the generation slot, but do not publish a new-chat
        // transition yet. Its conversation + Run + message graph commit atomically below; only
        // after the composer acknowledges that durable success may the screen switch and render.
        val wasNewChat = isNewChatMode.value || currentConversationId.value == null
        val genId = if (wasNewChat) {
            UUID.randomUUID().toString()
        } else {
            currentConversationId.value ?: return null
        }
        val newConversation = if (wasNewChat) {
            ChatEntity(
                id = genId,
                title = appContext.getString(R.string.new_chat),
                modelId = selectedModelId,
                systemPromptId = pendingSystemPromptId.value,
            )
        } else {
            null
        }
        return sendInto(
            genId = genId,
            wasNewChat = wasNewChat,
            newConversation = newConversation,
            text = text,
            images = images,
            attachments = attachments,
            modelId = selectedModelId,
            onAccepted = onAccepted,
        )
    }

    /**
     * The durable database commit is authoritative even if a UI acknowledgement is cancelled by
     * Activity teardown. Never turn an already-persisted Send into a setup failure or duplicate it
     * on retry because a presentation callback failed.
     */
    private suspend fun notifySendAccepted(
        acceptance: SendAcceptance,
        onAccepted: suspend (SendAcceptance) -> Unit,
    ) {
        try {
            withContext(kotlinx.coroutines.NonCancellable) {
                onAccepted(acceptance)
            }
            // Feedback belongs to acceptance. A queued guidance send is silent when its row is
            // committed later, so each user action still produces exactly one confirmation.
            onSendAcceptedEvent?.invoke(acceptance.conversationId, acceptance.messageId)
        } catch (error: Exception) {
            DebugLog.e(
                "MessageGenerationController",
                "Failed to acknowledge accepted Send ${acceptance.messageId}",
                error,
            )
        }
    }

    /** Release a claimed slot for which no generation Job was installed, then flush the WHOLE
     * queue into its originating conversation. Installed Jobs release only from their completion
     * hook so `CoroutineSettled` always means actual coroutine completion. */
    private suspend fun releaseUnlaunchedSlotAndDrain(
        state: ConversationGenerationState,
        uiToken: Long,
    ) = withContext(kotlinx.coroutines.NonCancellable) {
        var drainClaim: QueuedDrainClaim? = null
        state.queueMutationMutex.withLock {
            if (state.endGeneration(uiToken)) {
                // Suppression must be checked before the destructive take. A failed boundary
                // deliberately leaves its memory-only guidance queued for a later real boundary.
                if (state.consumeQueueDrainPermission()) {
                    state.claimQueuedSends()?.let { lease ->
                        drainClaim = claimQueuedDrain(state, lease)
                    }
                }
            }
        }
        drainClaim?.let { sendQueuedBatch(state, it) }
    }

    /** Called only while [ConversationGenerationState.queueMutationMutex] is held. */
    private suspend fun claimQueuedDrain(
        state: ConversationGenerationState,
        lease: GuidanceBatchLease,
    ): QueuedDrainClaim? {
        val proposedRunId = UUID.randomUUID().toString()
        val transition = try {
            state.requestSend(
                proposedRunId = proposedRunId,
                effectId = "guidance-$proposedRunId",
                directOnly = false,
                // The queue has already transferred into [lease], so this is now an ordinary
                // accepted-input claim rather than a recursive drain-first decision.
                hasPendingGuidance = false,
            )
        } catch (error: Exception) {
            state.settleGuidanceClaim(lease.id, durable = false)
            throw error
        }
        val inputEffect = transition.effects
            .filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
        if (!transition.accepted || inputEffect == null) {
            state.settleGuidanceClaim(lease.id, durable = false)
            return null
        }
        return QueuedDrainClaim(lease, inputEffect)
    }

    /**
     * Batch drain: persists each queued send as its own user message, chained consecutively onto
     * the conversation leaf, then launches a single generation replying to all of them (providers
     * with strict role alternation see them merged by mergeConsecutiveSameRole). The batch answers
     * with the model of the most recent queued send.
     */

    /** Drain only after the prior Run releases; every accepted batch enters a fresh Run. */
    internal suspend fun drainQueuedAfterGeneration(state: ConversationGenerationState) {
        val claim = state.queueMutationMutex.withLock {
            // Check permission before transferring the pending batch to an in-flight lease.
            if (!state.consumeQueueDrainPermission()) return@withLock null
            state.claimQueuedSends()?.let { lease -> claimQueuedDrain(state, lease) }
        } ?: return
        // Guidance is still memory-only. Its lease enters the normal fresh-Run Send contract.
        sendQueuedBatch(state, claim)
    }

    internal suspend fun drainQueuedAfterStop(state: ConversationGenerationState) =
        drainQueuedAfterGeneration(state)

    private fun sendQueuedBatch(
        state: ConversationGenerationState,
        claim: QueuedDrainClaim,
    ) {
        val batch = claim.batch
        val genId = state.conversationId
        check(claim.inputEffect.identity.conversationId == genId)
        val myUiToken = claim.uiToken
        val runId = claim.inputEffect.identity.runId
        val modelId = batch.last().modelId
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: run {
            state.settleGuidanceClaim(claim.lease.id, durable = false)
            state.deferNextQueueDrain()
            state.scope.launch {
                releaseUnlaunchedSlotAndDrain(state, myUiToken)
            }
            return
        }
        state.loadingChange(myUiToken, true)
        val jobBodyStarted = AtomicBoolean(false)
        val generationJob = state.launchGenerationJob(myUiToken) {
            jobBodyStarted.set(true)
            var setupModelMessageId: String? = null
            var graphCommitted = false
            var runBound = false
            suspend fun reconcileGuidanceOwnership(): Boolean =
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!graphCommitted) {
                        // Room cancellation may surface after an atomic transaction committed.
                        // A fresh guidance Run exists iff that whole input graph exists.
                        graphCommitted = convRepo.getRun(runId) != null
                    }
                    state.settleGuidanceClaim(claim.lease.id, durable = graphCommitted)
                    graphCommitted
                }
            try {
                val myPersistId = state.nextPersistId()
                executionCoordinator.withConversationLock(genId) {
                    val snapshot = convRepo.getMessagesForConversationSnapshot(genId)
                    val selections = convRepo.restoreBranchSelections(genId)
                    val path = ConversationUiState.resolvePath(
                        snapshot.map { it.toUiChatMessage(appContext) },
                        streamingMsg = null,
                        selectedChildren = selections,
                    )
                    var parentId = path.lastOrNull()?.id
                    val start = System.currentTimeMillis()
                    val users = batch.mapIndexed { index, queued ->
                        val entity = MessageEntity(
                            id = queued.id,
                            conversationId = genId,
                            parentId = parentId,
                            text = queued.text,
                            images = queued.preparedImages,
                            thoughts = null,
                            status = MessageStatus.SUCCESS,
                            participant = Participant.USER,
                            timestamp = start + index,
                            attachmentMeta = queued.preparedAttachmentMetaJson,
                            runId = runId,
                            runSequence = index.toLong(),
                            consumedAtPass = 0,
                        )
                        parentId = entity.id
                        entity
                    }
                    val modelMessageId = UUID.randomUUID().toString()
                    setupModelMessageId = modelMessageId
                    val placeholderEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = genId,
                        parentId = users.last().id,
                        text = "",
                        thoughts = null,
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = start + users.size,
                        modelName = modelId,
                        runId = runId,
                        runSequence = users.size.toLong(),
                    )
                    val selectionUpdates = buildMap<String?, String> {
                        users.forEach { put(it.parentId, it.id) }
                        put(users.last().id, modelMessageId)
                    }
                    val graphCommit = convRepo.createRunWithMessages(
                        run = RunEntity(
                            id = runId,
                            conversationId = genId,
                            parentRunId = path.lastOrNull()?.runId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = start,
                            lastCheckpointAt = placeholderEntity.timestamp,
                        ),
                        messages = users + placeholderEntity,
                        messageSelectionUpdates = selectionUpdates,
                    )
                    val committedUsers = graphCommit.messages.dropLast(1)
                    val committedPlaceholder = graphCommit.messages.last()
                    val committedSelections = graphCommit.messageSelections
                    val pass = 0
                    graphCommitted = true
                    check(state.settleGuidanceClaim(claim.lease.id, durable = true))
                    // Echo the same PersistAcceptedInput identity used by ordinary Send. Stop can
                    // win while Room commits; in that order the durable fresh Run is stopped below
                    // and the old STOPPED Run is never reused.
                    runBound = withContext(kotlinx.coroutines.NonCancellable) {
                        state.inputPersisted(claim.inputEffect.identity)
                    }
                    if (!runBound) {
                        // Stop landed while Room was committing the fresh boundary. The rows are
                        // durable, so finish them in place; never requeue and duplicate them.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            convRepo.finishStoppedGeneration(emptyList(), runId)
                        }
                        return@withConversationLock
                    }
                    committedUsers.forEach { user ->
                        if (user.text.isNotBlank()) {
                            runCatching { onUserMessagePersisted(user.id, user.text) }
                                .onFailure { error ->
                                    DebugLog.w(
                                        "MessageGenerationController",
                                        "Failed to enqueue queued-message indexing for ${user.id}",
                                        error,
                                    )
                                }
                        }
                        try {
                            settings.incrementMessagesSent()
                        } catch (error: Exception) {
                            DebugLog.w(
                                "MessageGenerationController",
                                "Failed to increment the queued sent-message counter",
                                error,
                            )
                        }
                    }
                    val placeholder = committedPlaceholder.toUiChatMessage(appContext)
                    state.streamUpdate(myUiToken, placeholder)
                    ifOpenOn(genId) {
                        onScrollToAbsoluteBottomAfter(genId, committedUsers.last().id)
                        renderStore.commitGraph(
                            committedMessages =
                                committedUsers.map { it.toUiChatMessage(appContext) } + placeholder,
                            selectedChildren = committedSelections,
                            streamingMessage = placeholder,
                        )
                    }
                    // Eligibility is checked only after every guidance USER row is durable. The
                    // placeholder remains below a created Compact boundary while provider
                    // canonicalization omits it from the summary/suffix accounting.
                    compactBeforeBoundaryIfNeeded(
                        genId = genId,
                        modelId = modelId,
                        contextLimit = requestBuilder.buildEffectiveConversationSettings(genId)
                            .contextWindow ?: settings.maxContextWindow.value,
                        state = state,
                    )
                    launchGeneration(
                        genId, modelMessageId, committedPlaceholder.timestamp,
                        isRegenerate = false, replaceMessageId = null,
                        providerName, modelId, activeKey, myUiToken, myPersistId,
                        state, runId = runId, pass = pass, callerTag = "guidanceBoundary",
                    )
                }
            } catch (e: CancellationException) {
                val durable = reconcileGuidanceOwnership()
                if (durable && !runBound) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        convRepo.finishStoppedGeneration(emptyList(), runId)
                    }
                }
                throw e
            } catch (e: Exception) {
                val durable = reconcileGuidanceOwnership()
                if (!durable) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        state.inputPersistenceFailed(claim.inputEffect.identity)
                    }
                }
                failGenerationSetup(genId, runId, setupModelMessageId, myUiToken, state, e)
                if (!durable) {
                    // The guidance is still memory-only, so retain it for a later real boundary.
                    // Suppress this generation's completion drain once; otherwise it would consume
                    // the same batch, repeat the same setup failure, emit another snackbar, and
                    // keep the UI in an unbounded generation loop.
                    state.deferNextQueueDrain()
                }
            }
        }
        generationJob?.invokeOnCompletion {
            if (!jobBodyStarted.get()) {
                // Cancellation-before-start cannot reach the body reconciliation path.
                state.settleGuidanceClaim(claim.lease.id, durable = false)
            }
        }
        if (generationJob == null) {
            // The slot was revoked between the claim and lazy-job installation. The guidance has
            // no Room row yet. Requeue before waiting for the Stop barrier, then explicitly retry
            // the handoff: onStopSettled may already have observed an empty queue at this edge.
            state.settleGuidanceClaim(claim.lease.id, durable = false)
            state.scope.launch {
                state.awaitSendAvailable()
                val retryClaim = state.queueMutationMutex.withLock {
                    val retryLease = state.claimQueuedSends() ?: return@withLock null
                    claimQueuedDrain(state, retryLease)
                }
                retryClaim?.let { sendQueuedBatch(state, it) }
            }
        }
    }

    /**
     * Core send into a KNOWN conversation [genId] (never re-reads currentConversationId, so a
     * background send lands in its own conversation). Placement enters the conversation command
     * mailbox: a bound or preparing Run accepts memory-only guidance, Compact waits only for its
     * exact settlement, STOPPING waits for release, and IDLE emits one identified persistence
     * effect before generation launches. The installed Job's completion hook releases the slot and
     * requests queue drain; pre-launch failures release via
     * [releaseUnlaunchedSlotAndDrain].
     */
    private suspend fun sendInto(
        genId: String,
        wasNewChat: Boolean,
        newConversation: ChatEntity?,
        text: String,
        images: List<String>,
        attachments: List<SelectedAttachment>,
        modelId: String,
        onAccepted: suspend (SendAcceptance) -> Unit,
        scrollPolicy: SendScrollPolicy = SendScrollPolicy.FORCE,
        alreadyHoldsLock: Boolean = false,
        directOnly: Boolean = false,
        /** Reports the model row this send created, so an automation caller never has to re-derive
         *  it by scanning the conversation tail (a concurrent branch would win that scan). */
        onModelMessageCreated: ((String) -> Unit)? = null,
        onGenerationJob: ((kotlinx.coroutines.Job?) -> Unit)? = null,
    ): SendAcceptance? {
        val state = registry.getOrCreate(genId)
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return null
        if (providerName == Constants.PROVIDER_LOCAL) {
            val localModelId = modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
            val config = settings.localChatModels.value.find { it.modelId == localModelId }
            if (config == null || !java.io.File(config.localFilePath).exists()) {
                onSnackbar(application.getString(R.string.local_model_not_found))
                return null
            }
        }

        // Expensive media work finishes before the atomic placement decision. The composer does
        // not clear until this function returns, and the placement below does not report success
        // until Room owns every input/file reference.
        val payload = payloadBuilder.buildMessagePayload(application, images, attachments)
        val payloadOwnershipTransferred = AtomicBoolean(false)
        val payloadCleaned = AtomicBoolean(false)
        fun releaseUnownedPreparedPayload() {
            if (!payloadOwnershipTransferred.get() && payloadCleaned.compareAndSet(false, true)) {
                payload.preparedOwnedPaths.forEach { path ->
                    runCatching { java.io.File(path).delete() }
                }
            }
        }

        suspend fun enqueueAcceptedGuidance(runId: String): QueuedSend {
            val queued = QueuedSend(
                id = UUID.randomUUID().toString(),
                text = text,
                modelId = modelId,
                attachments = attachments,
                runId = runId,
                images = images,
                preparedImages = payload.allImages,
                preparedAttachmentMetaJson = payload.attachmentMeta?.let(Json::encodeToString),
                preparedOwnedPaths = payload.preparedOwnedPaths,
            )
            // Guidance acceptance is intentionally memory-only. The current provider pass
            // observes it through hasQueuedSends(), but Room, the selected tree, and LazyColumn
            // cannot expose a bubble before the next durable boundary.
            state.enqueueSend(queued)
            payloadOwnershipTransferred.set(true)
            try {
                notifySendAccepted(
                    acceptance = SendAcceptance.Queued(queued.id, genId),
                    onAccepted = onAccepted,
                )
            } catch (error: Exception) {
                state.removeQueuedSend(queued.id)
                payloadOwnershipTransferred.set(false)
                throw error
            }
            return queued
        }

        var placement: SendPlacement? = null
        val proposedRunId = UUID.randomUUID().toString()
        val sendEffectId = "send-$proposedRunId"
        try {
            while (placement == null) {
                val decision = state.queueMutationMutex.withLock {
                    val pendingQueue = state.queuedSends.value
                    val transition = state.requestSend(
                        proposedRunId = proposedRunId,
                        effectId = sendEffectId,
                        directOnly = directOnly,
                        hasPendingGuidance = pendingQueue.isNotEmpty(),
                    )
                    check(transition.accepted)
                    when (val effect = transition.effects.single()) {
                        is RunEffect.PersistAcceptedInput -> SendPlacement.Direct(
                            uiToken = effect.identity.ownerToken,
                            runId = effect.identity.runId,
                            inputEffect = effect,
                        )
                        is RunEffect.DrainGuidanceFirst -> {
                            // A previously accepted batch may still be waiting for its asynchronous
                            // handoff. Never let a newer Direct send leapfrog the FIFO batch.
                            check(!directOnly)
                            val queued = enqueueAcceptedGuidance(pendingQueue.last().runId)
                            val lease = checkNotNull(state.claimQueuedSends())
                            val claim = claimQueuedDrain(state, lease)
                            if (claim != null) {
                                SendPlacement.QueuedAndDrain(queued.id, claim)
                            } else {
                                SendPlacement.Queued(queued.id)
                            }
                        }
                        is RunEffect.AcceptGuidance -> {
                            // Reducer acceptance is the only placement authority. The guidance is
                            // memory-only, so a concurrent Room terminalization cannot attach it to
                            // the old Run. If settlement already won, immediately hand the FIFO
                            // batch back through a fresh normal Send rather than stranding it.
                            val queued = enqueueAcceptedGuidance(effect.identity.runId)
                            if (!state.generating.value) {
                                val lease = checkNotNull(state.claimQueuedSends())
                                val claim = claimQueuedDrain(state, lease)
                                if (claim != null) {
                                    SendPlacement.QueuedAndDrain(queued.id, claim)
                                } else {
                                    SendPlacement.Queued(queued.id)
                                }
                            } else {
                                SendPlacement.Queued(queued.id)
                            }
                        }
                        is RunEffect.AwaitRunRelease -> SendPlacement.RetryAfterRelease
                        is RunEffect.AwaitCompactSettlement -> SendPlacement.RetryAfterCompact
                        is RunEffect.RejectSendBusy -> SendPlacement.Rejected
                        else -> error(
                            "SendRequested emitted unexpected effect ${effect.javaClass.simpleName}",
                        )
                    }
                }
                if (decision == SendPlacement.RetryAfterRelease) {
                    state.awaitSendAvailable()
                } else if (decision == SendPlacement.RetryAfterCompact) {
                    state.awaitCompactSettled()
                } else {
                    placement = decision
                }
            }
        } catch (error: Exception) {
            releaseUnownedPreparedPayload()
            throw error
        }

        if (placement is SendPlacement.Rejected) {
            releaseUnownedPreparedPayload()
            return null
        }
        if (placement is SendPlacement.Queued) {
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        if (placement is SendPlacement.QueuedAndDrain) {
            sendQueuedBatch(state, placement.claim)
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        val direct = placement as SendPlacement.Direct
        val myUiToken = direct.uiToken
        val runId = direct.runId

        val requestScroll = resolveScrollCallback(scrollPolicy)
        val durableAcceptance = CompletableDeferred<SendAcceptance?>()
        val generationJob = state.launchGenerationJob(myUiToken) generation@ {
            val myPersistId = state.nextPersistId()
            var runBound = false
            var inputGraphCommitted = false
            var setupModelMessageId: String? = null
            var roomProjectionFence: RoomMessageProjectionFence? = null
            try {
                // Skipping the lock here is ONLY sound because the caller that already holds it
                // also joins this job (see sendMessageFromAutomationAwaitingCompletion). If the
                // caller returned before the job finished, the generation would run outside any
                // lock and could interleave with a foreground turn on the same conversation.
                withOptionalLock(genId, alreadyHoldsLock) generationLock@ {
                    val pendingSettings = pendingConversationSettings.value
                    if (pendingSettings != null) {
                        settings.setConversationSettings(genId, pendingSettings)
                        pendingConversationSettings.value = null
                    }
                    val userMessageId = UUID.randomUUID().toString()
                    val modelMessageId = UUID.randomUUID().toString()
                    setupModelMessageId = modelMessageId
                    val graphCommit = acceptedInputGraphWriter.commit(
                        request = AcceptedInputGraphWriter.Request(
                            inputEffect = direct.inputEffect,
                            userMessageId = userMessageId,
                            modelMessageId = modelMessageId,
                            userText = text,
                            images = payload.allImages,
                            attachmentMeta = payload.attachmentMeta?.let(Json::encodeToString),
                            modelId = modelId,
                            userTimestamp = System.currentTimeMillis(),
                            newConversation = newConversation,
                        ),
                        beforeRoomCommit = {
                            if (!wasNewChat) {
                                ifOpenOn(genId) {
                                    roomProjectionFence =
                                        renderStore.beginRoomMessageProjectionFence()
                                }
                            }
                        },
                    )
                    val userEntity = graphCommit.userMessage
                    val modelEntity = graphCommit.modelMessage
                    val startTime = modelEntity.timestamp
                    inputGraphCommitted = true
                    payloadOwnershipTransferred.set(true)

                    // Everything below acknowledges a transaction Room already committed. Finish
                    // it even if Stop/Activity teardown cancels this coroutine at that exact edge.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        runBound = state.inputPersisted(direct.inputEffect.identity)
                        if (text.isNotBlank()) {
                            runCatching { onUserMessagePersisted(userMessageId, text) }
                                .onFailure { error ->
                                    DebugLog.w(
                                        "MessageGenerationController",
                                        "Failed to enqueue user-message indexing for $userMessageId",
                                        error,
                                    )
                                }
                        }
                        try {
                            settings.incrementMessagesSent()
                        } catch (error: Exception) {
                            DebugLog.w(
                                "MessageGenerationController",
                                "Failed to increment the sent-message counter",
                                error,
                            )
                        }
                        val acceptance = SendAcceptance.Direct(userMessageId, genId)
                        notifySendAccepted(acceptance, onAccepted)
                        durableAcceptance.complete(acceptance)
                        runCatching { onModelMessageCreated?.invoke(modelMessageId) }
                            .onFailure { error ->
                                DebugLog.w(
                                    "MessageGenerationController",
                                    "Failed to report created model row $modelMessageId",
                                    error,
                                )
                            }

                        if (wasNewChat) {
                            requestScroll(genId, userMessageId)
                            currentConversationId.value = genId
                            isNewChatMode.value = false
                            onConversationCreatedBySend(genId)
                        }

                        val placeholder = modelEntity.toUiChatMessage(appContext)
                        if (runBound) {
                            state.loadingChange(myUiToken, true)
                            state.streamUpdate(myUiToken, placeholder)
                        }
                        ifOpenOn(genId) {
                            if (!wasNewChat) requestScroll(genId, userMessageId)
                            renderStore.commitGraph(
                                committedMessages = listOf(
                                    userEntity.toUiChatMessage(appContext),
                                    if (runBound) placeholder else placeholder.copy(status = MessageStatus.STOPPED),
                                ),
                                selectedChildren = graphCommit.messageSelections,
                                streamingMessage = if (runBound) placeholder else null,
                                roomProjectionFence = roomProjectionFence,
                            )
                            roomProjectionFence = null
                        }
                        roomProjectionFence?.let(renderStore::releaseRoomMessageProjectionFence)
                        roomProjectionFence = null
                    }

                    if (!runBound) {
                        // Stop won the post-commit bind race. No external finalizer knows this Run
                        // id, so this installed Job owns the one STOPPED transaction.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            convRepo.finishStoppedGeneration(emptyList(), runId)
                        }
                        return@generationLock
                    }
                    if (!wasNewChat) {
                        // The USER row is now durably accepted and visible; Compact, when needed,
                        // runs before this placeholder's provider pass and then continuation
                        // resumes automatically in the same installed generation Job.
                        compactBeforeBoundaryIfNeeded(
                            genId = genId,
                            modelId = modelId,
                            contextLimit = requestBuilder.buildEffectiveConversationSettings(genId)
                                .contextWindow ?: settings.maxContextWindow.value,
                            state = state,
                        )
                    }
                    launchGeneration(
                        genId, modelMessageId, startTime,
                        isRegenerate = false, replaceMessageId = null,
                        providerName, modelId, activeKey, myUiToken, myPersistId,
                        state, runId = runId, pass = 0, callerTag = "sendMessage"
                    )
                    val lastMsg = convRepo.getMessagesForConversationSnapshot(genId)
                        .find { it.id == modelMessageId }
                    if (
                        wasNewChat &&
                        settings.titleGenerationEnabled.value &&
                        kotlinx.coroutines.currentCoroutineContext().isActive &&
                        lastMsg?.status != MessageStatus.ERROR
                    ) {
                        generateTitle(genId)
                    }
                }
            } catch (e: CancellationException) {
                // If cancellation landed inside Room's transaction, it may have committed before
                // surfacing cancellation. Only the unbound edge lacks a Stop finalizer.
                if (!runBound) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (convRepo.getRun(runId) != null) {
                            convRepo.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                if (!inputGraphCommitted) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching {
                            state.inputPersistenceFailed(direct.inputEffect.identity)
                        }
                    }
                }
                failGenerationSetup(
                    conversationId = genId,
                    runId = runId,
                    modelMessageId = setupModelMessageId,
                    uiToken = myUiToken,
                    state = state,
                    error = e,
                )
            } finally {
                roomProjectionFence?.let(renderStore::releaseRoomMessageProjectionFence)
                if (!durableAcceptance.isCompleted) {
                    // No durable user row exists, so the composer/draft must remain untouched.
                    durableAcceptance.complete(null)
                }
                releaseUnownedPreparedPayload()
            }
        }
        onGenerationJob?.invoke(generationJob)
        if (generationJob == null) {
            releaseUnownedPreparedPayload()
            return null
        }
        // Covers cancellation before the coroutine body reaches its own finally block.
        generationJob.invokeOnCompletion {
            releaseUnownedPreparedPayload()
            durableAcceptance.complete(null)
        }
        return durableAcceptance.await()
    }

    /**
     * Entry point for loop cycles on the foreground-open conversation.
     *
     * The coordinator lock is already held by `LoopManager.executeByConversationId`, so neither
     * the setup phase nor the generation job re-acquires it. That is only correct because this
     * function SUSPENDS until the generation job completes: the caller's lease therefore spans the
     * whole turn, exactly as it would for a headless run. Returning early would leave the
     * generation running unlocked and would also report success to the Loop before the cycle
     * actually produced anything.
     *
     * `directOnly` is mandatory here, not an optimization. Both alternatives would need the
     * conversation lock this caller already holds:
     *  - waiting on `generating` deadlocks against a manual send that is itself blocked on the
     *    lock, because that send can only release the slot after acquiring it;
     *  - a queued send is answered by a drain that also takes the lock, so this function would
     *    have no job to join and would have to guess an outcome it never observed.
     * [AutomationSendOutcome.SlotBusy] lets the Loop treat the cycle as not-run instead.
     *
     * [AutomationSendOutcome.Delivered.modelMessageId] is the row this send actually created.
     * Callers must never re-derive it by scanning the conversation tail: a concurrent branch or an
     * older run can win that scan and report a previous turn's answer as this cycle's result.
     *
     * Scrolls only when the viewport is attached at the bottom ([SendScrollPolicy.ATTACHED_ONLY])
     * so automated messages never steal the user's scroll position.
     */
    internal suspend fun sendMessageFromAutomationAwaitingCompletion(
        genId: String,
        text: String,
        modelId: String,
    ): AutomationSendOutcome {
        var generationJob: kotlinx.coroutines.Job? = null
        var createdModelMessageId: String? = null
        val acceptance = sendInto(
            genId = genId,
            wasNewChat = false,
            newConversation = null,
            text = text,
            images = emptyList(),
            attachments = emptyList(),
            modelId = modelId,
            onAccepted = {},
            scrollPolicy = SendScrollPolicy.ATTACHED_ONLY,
            alreadyHoldsLock = true,
            directOnly = true,
            onModelMessageCreated = { createdModelMessageId = it },
            onGenerationJob = { generationJob = it },
        ) ?: return AutomationSendOutcome.SlotBusy
        // directOnly makes Queued unreachable. Assert rather than reporting a cycle this call
        // never actually ran.
        check(acceptance is SendAcceptance.Direct) {
            "A direct-only automation send must never be queued"
        }
        // launchGenerationJob returns null when the slot was revoked between claim and launch (a
        // Stop landing in that window). Nothing generated, so the cycle did not run.
        val job = generationJob ?: return AutomationSendOutcome.SlotBusy
        try {
            job.join()
        } catch (e: CancellationException) {
            // The Loop/Worker lease owns this delegated turn. If its caller is cancelled, do not
            // leave a process-scoped controller job generating outside that released lease.
            job.cancel()
            throw e
        }
        val modelMessageId = createdModelMessageId ?: return AutomationSendOutcome.SlotBusy
        return AutomationSendOutcome.Delivered(modelMessageId)
    }

    // ==================================
    // generateTitle
    // ==================================

    private suspend fun cloneEditedRunInputs(
        sourceInputs: List<MessageEntity>,
        destinationRunId: String,
        textOverrides: Map<String, String> = emptyMap(),
    ): List<MessageEntity> = withContext(Dispatchers.IO) {
        require(sourceInputs.isNotEmpty())
        val attachmentClones = MessageAttachmentCloneSession(
            java.io.File(application.filesDir, "run-inputs")
        )

        try {
            val now = System.currentTimeMillis()
            var parentId = sourceInputs.first().parentId
            sourceInputs.mapIndexed { index, source ->
                val cloned = EditedRunInputFactory.create(
                    source = source,
                    id = UUID.randomUUID().toString(),
                    parentId = parentId,
                    text = textOverrides[source.id] ?: source.text,
                    timestamp = now + index,
                    destinationRunId = destinationRunId,
                    runSequence = index.toLong(),
                    cloneBackingPath = { path ->
                        attachmentClones.cloneBackingPath("edited-run-inputs", path)
                    },
                )
                parentId = cloned.id
                cloned
            }.also { attachmentClones.commit() }
        } catch (e: Exception) {
            attachmentClones.rollback()
            throw e
        }
    }

    internal object EditedRunInputFactory {
        fun create(
            source: MessageEntity,
            id: String,
            parentId: String?,
            text: String,
            timestamp: Long,
            destinationRunId: String,
            runSequence: Long,
            cloneBackingPath: (String) -> String,
        ): MessageEntity {
            val clonedMeta = source.attachmentMeta?.let { raw ->
                cloneAttachmentMeta(raw, cloneBackingPath)
            }
            return source.copy(
                id = id,
                parentId = parentId,
                text = text,
                images = source.images.map(cloneBackingPath),
                status = MessageStatus.SUCCESS,
                timestamp = timestamp,
                attachmentMeta = clonedMeta,
                runId = destinationRunId,
                runSequence = runSequence,
                consumedAtPass = 0,
            )
        }
    }

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            settings.awaitInitialLoad()
            if (settings.titleGenerationNotificationsEnabled.value) {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_generating_title))
            }
            when (titleGenerator.generateAndPersist(conversationId)) {
                is ConversationTitleGenerator.Result.Success -> {
                    if (settings.titleGenerationNotificationsEnabled.value) {
                        onSnackbarSuspend(appContext.getString(R.string.snackbar_title_generated))
                    }
                }
                is ConversationTitleGenerator.Result.Failure -> {
                    if (settings.titleGenerationNotificationsEnabled.value) {
                        onSnackbarSuspend(appContext.getString(R.string.snackbar_title_error))
                    }
                }
            }
        }
    }
}
