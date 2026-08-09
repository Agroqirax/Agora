package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.ConversationSettings
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
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val compactController = ConversationCompactController(
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        operation = ContextCompactor(
            conversations = convRepo,
            settings = settings,
            providers = providerRegistry,
            pauseLoop = pauseConversationTasks,
        ),
        projectGraph = { conversationId, all, selected ->
            ifOpenOn(conversationId) {
                renderStore.replaceGraph(
                    allMessages = all.map { it.toUiChatMessage(appContext) },
                    selectedChildren = selected,
                )
            }
        },
    )
    private val acceptedInputGraphWriter = AcceptedInputGraphWriter(convRepo)
    private val terminalSettlement = GenerationTerminalSettlementController(
        conversations = convRepo,
        stopFinalizer = GenerationFinalizer(convRepo) { _, _ -> },
        runFinalizationEffects = RunFinalizationEffectCoordinator(),
        failureText = { appContext.getString(R.string.failed_to_generate) },
        toUiMessage = { it.toUiChatMessage(appContext) },
        onSnackbar = onSnackbar,
    )
    private val boundRunGenerationLauncher = BoundRunGenerationLauncher(
        requestBuilder = requestBuilder,
        settings = settings,
        conversations = convRepo,
        generationManagerProvider = generationManagerProvider,
        compactController = compactController,
        terminalSettlement = terminalSettlement,
        toUiMessage = { it.toUiChatMessage(appContext) },
    )
    private val editService = ConversationEditService(
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        inputCloner = EditedRunInputCloner(
            java.io.File(application.filesDir, "run-inputs"),
        ),
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { _, committedMessages, selectedChildren, streamingMessage ->
            renderStore.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
        awaitProjectedPath = { conversationId, messageId ->
            combine(messages, currentConversationId) { path, openConversationId ->
                openConversationId != conversationId || path.any { it.id == messageId }
            }.first { projectedOrClosed -> projectedOrClosed }
        },
        onUserMessagePersisted = onUserMessagePersisted,
        onScrollToMessage = { onScrollToMessage(it) },
    )
    private val queuedGuidanceDrainExecutor = QueuedGuidanceDrainExecutor(
        conversations = convRepo,
        settings = settings,
        requestBuilder = requestBuilder,
        executionCoordinator = executionCoordinator,
        compactController = compactController,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { _, committedMessages, selectedChildren, streamingMessage ->
            renderStore.commitGraph(
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
        onScrollToAbsoluteBottomAfter = onScrollToAbsoluteBottomAfter,
        onUserMessagePersisted = onUserMessagePersisted,
    )
    private val branchMutationService = ConversationBranchMutationService(
        scope = viewModelScope,
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = { currentConversationId.value == it },
        projectGraph = { all, selected ->
            renderStore.replaceGraph(allMessages = all, selectedChildren = selected)
        },
        onMutationStart = onTreeMutationStart,
        onMutationSettling = onTreeMutationSettling,
        onMutationFailed = onTreeMutationFailed,
    )

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

    suspend fun compactManual(request: CompactRequest): CompactResult {
        val conversationId = currentConversationId.value
            ?: return CompactResult.Failed("Open a conversation first")
        return compactController.manual(
            conversationId = conversationId,
            request = request,
            state = registry.getOrCreate(conversationId),
        )
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
        return branchMutationService.delete(
            conversationId = currentId,
            messageId = messageId,
            state = state,
            snapshot = renderStore.allMessages,
        )
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
                queuedGuidanceDrainExecutor.releaseUnlaunchedSlotAndDrain(state, myUiToken)
            }
            return false
        }
        val runId = UUID.randomUUID().toString()

        var graphCommitted = false
        val generationJob = state.launchGenerationJob(myUiToken) generation@ {
            var setupModelMessageId: String? = null
            var runBound = false
            var stopFinalizationClaimed = false
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
                val binding = state.bindPersistedRun(myUiToken, runId)
                runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                if (!runBound) {
                    if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                        stopFinalizationClaimed = true
                        terminalSettlement.settleLateBoundStop(state, binding)
                    } else {
                        // A disposed/replaced runtime cannot accept the durable Run. This is a
                        // recovery-only fallback; the normal Stop race is mailbox-authorized.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            convRepo.finishStoppedGeneration(emptyList(), runId)
                        }
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
                boundRunGenerationLauncher.launch(
                    BoundRunGenerationRequest(
                        conversationId = genId,
                        modelMessageId = modelMessageId,
                        startTime = startTime,
                        isRegenerate = false,
                        replaceMessageId = null,
                        providerName = providerName,
                        modelId = modelId,
                        activeKey = activeKey,
                        uiToken = myUiToken,
                        persistId = myPersistId,
                        runId = runId,
                        pass = 0,
                        callerTag = "regenerate",
                    ),
                    state,
                )
                }
            } catch (e: CancellationException) {
                // A Room transaction may commit just before cancellation is observed. If the Run
                // was never bound, the normal Stop finalizer cannot discover it.
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (convRepo.getRun(runId) != null) {
                            graphCommitted = true
                            regenerationTransitions.markCommitted(transition.id)
                            val binding = state.bindPersistedRun(myUiToken, runId)
                            stopFinalizationClaimed =
                                terminalSettlement.settleCancelledDurableRun(state, binding)
                            if (!stopFinalizationClaimed) {
                                convRepo.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (convRepo.getRun(runId) != null) {
                            graphCommitted = true
                            regenerationTransitions.markCommitted(transition.id)
                            val binding = state.bindPersistedRun(myUiToken, runId)
                            runBound = binding is
                                ConversationGenerationState.RunBindingOutcome.Active
                            if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                                stopFinalizationClaimed = true
                                terminalSettlement.settleLateBoundStop(state, binding)
                            }
                        }
                    }
                }
                terminalSettlement.failGenerationSetup(
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
        return editService.edit(
            ConversationEditRequest(
                conversationId = genId,
                messageId = messageId,
                newText = newText,
                modelId = modelId,
                providerName = providerName,
                activeKey = activeKey,
                visiblePath = messages.value.toList(),
            ),
            state,
        )
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

    internal suspend fun drainQueuedAfterGeneration(state: ConversationGenerationState) {
        queuedGuidanceDrainExecutor.drainAfterSettlement(state)
    }

    internal suspend fun drainQueuedAfterStop(state: ConversationGenerationState) =
        drainQueuedAfterGeneration(state)

    /**
     * Core send into a KNOWN conversation [genId] (never re-reads currentConversationId, so a
     * background send lands in its own conversation). Placement enters the conversation command
     * mailbox: a bound or preparing Run accepts memory-only guidance, Compact waits only for its
     * exact settlement, STOPPING waits for release, and IDLE emits one identified persistence
     * effect before generation launches. The installed Job's completion hook releases the slot and
     * requests queue drain; pre-launch failures release via
     * [QueuedGuidanceDrainExecutor.releaseUnlaunchedSlotAndDrain].
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
                    val transition = state.commands.requestSend(
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
                            val claim = queuedGuidanceDrainExecutor.claimUnderLock(state, lease)
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
                                val claim = queuedGuidanceDrainExecutor.claimUnderLock(state, lease)
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
            queuedGuidanceDrainExecutor.launchClaim(state, placement.claim)
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
            var bindingOutcome: ConversationGenerationState.RunBindingOutcome =
                ConversationGenerationState.RunBindingOutcome.Rejected
            var inputGraphCommitted = false
            val userMessageId = UUID.randomUUID().toString()
            val modelMessageId = UUID.randomUUID().toString()
            val setupModelMessageId: String? = modelMessageId
            var roomProjectionFence: RoomMessageProjectionFence? = null
            suspend fun reconcileCommittedInput(): Boolean =
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!inputGraphCommitted) {
                        inputGraphCommitted = convRepo.getRun(runId) != null
                    }
                    if (!inputGraphCommitted) return@withContext false
                    payloadOwnershipTransferred.set(true)
                    if (
                        bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected
                    ) {
                        bindingOutcome =
                            state.finishInputPersistence(direct.inputEffect.identity)
                        runBound = bindingOutcome is
                            ConversationGenerationState.RunBindingOutcome.Active
                    }
                    if (!durableAcceptance.isCompleted) {
                        val acceptance = SendAcceptance.Direct(userMessageId, genId)
                        notifySendAccepted(acceptance, onAccepted)
                        durableAcceptance.complete(acceptance)
                        runCatching { onModelMessageCreated?.invoke(modelMessageId) }
                        if (wasNewChat) {
                            currentConversationId.value = genId
                            isNewChatMode.value = false
                            onConversationCreatedBySend(genId)
                        }
                    }
                    true
                }
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
                        bindingOutcome = state.finishInputPersistence(direct.inputEffect.identity)
                        runBound = bindingOutcome is
                            ConversationGenerationState.RunBindingOutcome.Active
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
                        val stopping = bindingOutcome as?
                            ConversationGenerationState.RunBindingOutcome.Stopping
                        if (stopping != null) {
                            terminalSettlement.settleLateBoundStop(state, stopping)
                        } else {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                convRepo.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                        return@generationLock
                    }
                    if (!wasNewChat) {
                        // The USER row is now durably accepted and visible; Compact, when needed,
                        // runs before this placeholder's provider pass and then continuation
                        // resumes automatically in the same installed generation Job.
                        compactController.automaticBeforeBoundary(
                            conversationId = genId,
                            fallbackModel = modelId,
                            contextLimit = requestBuilder.buildEffectiveConversationSettings(genId)
                                .contextWindow ?: settings.maxContextWindow.value,
                            state = state,
                        )
                    }
                    boundRunGenerationLauncher.launch(
                        BoundRunGenerationRequest(
                            conversationId = genId,
                            modelMessageId = modelMessageId,
                            startTime = startTime,
                            isRegenerate = false,
                            replaceMessageId = null,
                            providerName = providerName,
                            modelId = modelId,
                            activeKey = activeKey,
                            uiToken = myUiToken,
                            persistId = myPersistId,
                            runId = runId,
                            pass = 0,
                            callerTag = "sendMessage",
                        ),
                        state,
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
                if (
                    !runBound &&
                    bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected
                ) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (reconcileCommittedInput()) {
                            val claimed = terminalSettlement.settleCancelledDurableRun(
                                state,
                                bindingOutcome,
                            )
                            if (!claimed) {
                                convRepo.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                val durable = reconcileCommittedInput()
                if (!durable) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching {
                            state.commands.inputPersistenceFailed(direct.inputEffect.identity)
                        }
                    }
                } else {
                    val stopping = bindingOutcome as?
                        ConversationGenerationState.RunBindingOutcome.Stopping
                    if (stopping != null) {
                        terminalSettlement.settleLateBoundStop(state, stopping)
                    }
                }
                terminalSettlement.failGenerationSetup(
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
