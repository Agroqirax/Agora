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
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private sealed interface SendPlacement {
    data class Direct(val uiToken: Long, val runId: String) : SendPlacement
    data class Queued(val messageId: String) : SendPlacement
    data object RetryAfterRelease : SendPlacement
}

/**
 * Durable acceptance result returned to the composer.
 *
 * A direct send enters the visible conversation. Its Controller-owned UI commit also requests the
 * scroll; the composer only uses this result to decide whether it may clear the submitted draft.
 * A queued send remains exclusively in the queue banner until the next Pass claims it.
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
    // ── Process-scoped collaborators ──
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val registry: ConversationStateRegistry,
    private val generationManagerProvider: () -> GenerationManager,
    private val requestBuilder: GenerationRequestBuilder,
    private val payloadBuilder: MessagePayloadBuilder,
    private val providerRegistry: ProviderRegistry,
    private val localProvider: LocalProvider,
    private val executionCoordinator: ConversationExecutionCoordinator,
    // ── Shared UI state: the SAME instances ChatViewModel exposes — never recreate ──
    private val renderStore: ConversationRenderStore,
    private val currentConversationId: MutableStateFlow<String?>,
    private val isNewChatMode: MutableStateFlow<Boolean>,
    private val pendingConversationSettings: MutableStateFlow<ConversationSettings?>,
    private val pendingSystemPromptId: MutableStateFlow<String?>,
    private val currentActiveModel: StateFlow<String>,
    private val messages: StateFlow<List<ChatMessage>>,
    // ── Callbacks into ChatViewModel-owned side effects ──
    private val onScrollToMessage: (String?) -> Unit,
    private val onScrollToAbsoluteBottomAfter: (conversationId: String, messageId: String) -> Unit,
    private val onSnackbar: (String) -> Unit,
    private val onSnackbarSuspend: suspend (String) -> Unit,  // sequential emit inside generateTitle
    // Called when sendMessage creates a NEW conversation, so the UI can suppress the
    // conversation-open auto-scroll (the send's own physical-bottom scroll handles it) and
    // avoid a double scroll on the first message of a new chat.
    private val onConversationCreatedBySend: () -> Unit = {},
    // Called once when a hidden task/loop execution becomes searchable. The callback
    // only enqueues background work; embedding computation must not run under the send lock.
    // Called after a USER message row is persisted (send / edit), so incremental RAG
    // indexing covers the user's side too — the model reply is indexed at generation end
    // via GenerationManager.onMessagePersisted, and without this hook user messages only
    // ever entered the cache through a manual full re-cache. Enqueues background work only.
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** Covers destructive tree mutation until ChatApp has settled the resulting path. */
    private val onTreeMutationStart: suspend () -> Long? = { null },
    private val onTreeMutationSettling: (requestId: Long?, targetMessageId: String?) -> Unit =
        { _, _ -> },
    private val onTreeMutationFailed: (requestId: Long?) -> Unit = {},
    private val regenerationTransitions: RegenerationTransitionCoordinator,
) {
    private val generationManager: GenerationManager get() = generationManagerProvider()
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)

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

    // ════════════════════════════════════════════════════════════════════
    // deleteMessage
    // ════════════════════════════════════════════════════════════════════

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
        val previewIds = structuralDescendantIds(snapshot, messageId)

        viewModelScope.launch(Dispatchers.IO) {
            val switchingRequestId = onTreeMutationStart()
            var committed = false
            try {
                state.queueMutationMutex.withLock {
                    // Recheck after the overlay fade and under the same mutex that accepts Send.
                    if (state.generating.value) return@withLock
                    executionCoordinator.withConversationLock(currentId) lock@ {
                        if (convRepo.getLiveRun(currentId) != null) return@lock

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

    // ════════════════════════════════════════════════════════════════════
    // regenerate
    // ════════════════════════════════════════════════════════════════════

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
            state.endGeneration(myUiToken)
            return false
        }
        val runId = UUID.randomUUID().toString()

        var graphCommitted = false
        val generationJob = state.launchGenerationJob(myUiToken) generation@ {
            var setupModelMessageId: String? = null
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
                if (!state.tryBindRun(myUiToken, runId)) return@lock
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
                graphCommitted = true
                regenerationTransitions.markCommitted(transition.id)
                launchGeneration(
                    genId, modelMessageId, startTime,
                    isRegenerate = false, replaceMessageId = null,
                    providerName, modelId, activeKey, myUiToken, myPersistId,
                    state, runId = runId, pass = 0, callerTag = "regenerate"
                )
                }
            } catch (e: CancellationException) {
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
                releaseAndDrain(state, myUiToken, genId)
            }
        }
        if (generationJob == null) {
            regenerationTransitions.abort(transition.id)
        }
        return generationJob != null
    }

    // ════════════════════════════════════════════════════════════════════
    // launchGeneration
    // ════════════════════════════════════════════════════════════════════

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
            // immediately — nothing is queued behind a held process-wide mutex.
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = isRegenerate,
                replaceMessageId = replaceMessageId,
                modelName = modelId,
                runId = runId,
                pass = pass,
                config = config,
                ctx = genCtx,
                // The coroutine's own Job — reading state.generationJob here races the caller's
                // assignment (the coroutine can start before `state.generationJob = launch{…}`
                // completes and observe the PREVIOUS job).
                generationJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
                callbacks = state.callbacksFor(uiToken, persistId),
                streamScope = state.streamScope,
                requestTrace = requestTrace,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AgoraVM", "Generation failed in $callerTag", e)
            // A pre-stream failure (prompt/config build — e.g. RAG key resolution) would otherwise
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

    // ════════════════════════════════════════════════════════════════════
    // editMessage
    // ════════════════════════════════════════════════════════════════════

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
        state.bindRun(myUiToken, runId)
        val committed = CompletableDeferred<Boolean>()
        val job = state.launchGenerationJob(myUiToken) {
            val myPersistId = state.nextPersistId()
            var setupModelMessageId: String? = null
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
            val selectedAfterModelEdit = graphCommit.messageSelections
            onUserMessagePersisted(newUser.id, newText)
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
                releaseAndDrain(state, myUiToken, genId)
                if (!committed.isCompleted) committed.complete(false)
            }
        }
        if (job == null) return false
        return committed.await()
    }

    // ════════════════════════════════════════════════════════════════════
    // sendMessage
    // ════════════════════════════════════════════════════════════════════

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
        } catch (error: Exception) {
            DebugLog.e(
                "MessageGenerationController",
                "Failed to acknowledge accepted Send ${acceptance.messageId}",
                error,
            )
        }
    }

    /** Release [uiToken]'s slot and, only if this call actually released it, flush the WHOLE
     *  queue into its originating conversation (never re-reading currentConversationId, so a
     *  message queued in conversation A can't land in B after the user switches chats): every
     *  queued message becomes its own consecutive user bubble and ONE generation answers them. */
    private suspend fun releaseAndDrain(
        state: ConversationGenerationState,
        uiToken: Long,
        genId: String,
    ) {
        var batchToDrain: List<QueuedSend>? = null
        state.queueMutationMutex.withLock {
            if (state.endGeneration(uiToken)) {
                val batch = state.takeQueuedSends()
                if (state.consumeQueueDrainPermission() && batch.isNotEmpty()) {
                    batchToDrain = batch
                }
            }
        }
        batchToDrain?.let { sendQueuedBatch(genId, it) }
    }

    /**
     * Batch drain: persists each queued send as its own user message, chained consecutively onto
     * the conversation leaf, then launches a single generation replying to all of them (providers
     * with strict role alternation see them merged by mergeConsecutiveSameRole). The batch answers
     * with the model of the most recent queued send.
     */
    private fun sendQueuedBatch(genId: String, batch: List<QueuedSend>) {
        val state = registry.getOrCreate(genId)
        val myUiToken = state.acquireForSend() ?: run {
            // Lost the slot race to a manual send that claimed it between release and here —
            // nothing is lost: the batch goes back to the queue head and the winner's own
            // release drains it.
            state.requeueFront(batch)
            return
        }
        val runId = batch.first().runId
        check(batch.all { it.runId == runId }) { "One queue drain cannot span multiple Runs" }
        state.bindRun(myUiToken, runId)
        val modelId = batch.last().modelId
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: run {
            state.scope.launch {
                convRepo.failRun(runId)
                releaseAndDrain(state, myUiToken, genId)
            }
            return
        }
        if (providerName == Constants.PROVIDER_LOCAL) {
            val localModelId = modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
            val config = settings.localChatModels.value.find { it.modelId == localModelId }
            if (config == null || !java.io.File(config.localFilePath).exists()) {
                onSnackbar(application.getString(R.string.local_model_not_found))
                state.scope.launch {
                    convRepo.failRun(runId)
                    releaseAndDrain(state, myUiToken, genId)
                }
                return
            }
        }
        state.loadingChange(myUiToken, true)

        state.launchGenerationJob(myUiToken) {
            var setupModelMessageId: String? = null
            try {
                val myPersistId = state.nextPersistId()
                executionCoordinator.withConversationLock(genId) {
                    val snapshotEntities = convRepo.getMessagesForConversationSnapshot(genId)
                    val messagesById = snapshotEntities.associateBy { it.id }
                    val queuedMessages = batch.map { queued ->
                        checkNotNull(messagesById[queued.id]) {
                            "Persisted intervention ${queued.id} is missing"
                        }
                    }
                    check(queuedMessages.all {
                        it.runId == runId &&
                            it.participant == Participant.USER &&
                            it.consumedAtPass == null
                    }) { "Queue contains a non-pending intervention" }
                    val lastUserMessageId = queuedMessages.last().id
                    val modelMessageId = UUID.randomUUID().toString()
                    setupModelMessageId = modelMessageId
                    val startTime = maxOf(
                        System.currentTimeMillis(),
                        queuedMessages.maxOf { it.timestamp } + 1,
                    )
                    val passCommit = checkNotNull(
                        convRepo.claimPendingRunInputsAndAppendPlaceholder(
                            runId = runId,
                            expectedInputMessageIds = queuedMessages.map { it.id },
                            placeholder = MessageEntity(
                                id = modelMessageId,
                                conversationId = genId,
                                parentId = lastUserMessageId,
                                text = "",
                                thoughts = null,
                                status = MessageStatus.SENDING,
                                participant = Participant.MODEL,
                                timestamp = startTime,
                                modelName = modelId,
                                runId = runId,
                            ),
                        )
                    ) {
                        "Queued intervention batch did not advance Run $runId"
                    }
                    val placeholder = passCommit.placeholder.toUiChatMessage(appContext)
                    val newChildren = passCommit.messageSelections
                    state.streamUpdate(myUiToken, placeholder)
                    ifOpenOn(genId) {
                        renderStore.commitGraph(
                            committedMessages = listOf(placeholder),
                            selectedChildren = newChildren,
                            streamingMessage = placeholder,
                        )
                        onScrollToMessage(lastUserMessageId)
                    }

                    launchGeneration(
                        genId, modelMessageId, startTime,
                        isRegenerate = false, replaceMessageId = null,
                        providerName, modelId, activeKey, myUiToken, myPersistId,
                        state,
                        runId = runId,
                        pass = passCommit.claimedPass.pass,
                        callerTag = "queueDrain",
                    )
                }
            } catch (e: CancellationException) {
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
                releaseAndDrain(state, myUiToken, genId)
            }
        }
    }

    /**
     * Core send into a KNOWN conversation [genId] (never re-reads currentConversationId, so a
     * background send lands in its own conversation). Atomically claims the generation slot
     * via [ConversationGenerationState.acquireForSend]: if a generation is already running (or
     * still winding down after a Stop) the message is enqueued (carrying its full attachment
     * list) and this returns true; otherwise the slot is held, generating is set synchronously,
     * and the generation launches. The finally releases the slot (owner-gated) and batch-drains
     * the queue. Validation failures after the claim release via [releaseAndDrain] too — a plain
     * endGeneration would strand queued sends behind an idle slot until the next manual send.
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

        var placement: SendPlacement? = null
        while (placement == null) {
            val decision = state.queueMutationMutex.withLock {
                val uiToken = state.acquireForSend()
                if (uiToken != null) {
                    val runId = UUID.randomUUID().toString()
                    state.bindRun(uiToken, runId)
                    SendPlacement.Direct(uiToken, runId)
                } else if (state.stopping.value) {
                    SendPlacement.RetryAfterRelease
                } else {
                    val runId = state.currentRunId() ?: return@withLock SendPlacement.RetryAfterRelease
                    val run = convRepo.getRun(runId)
                    if (run == null || run.status != RunStatus.ACTIVE) {
                        if (run?.status == RunStatus.STOPPING) convRepo.finishRunStopped(runId)
                        SendPlacement.RetryAfterRelease
                    } else {
                        val queued = QueuedSend(
                            id = UUID.randomUUID().toString(),
                            text = text,
                            modelId = modelId,
                            attachments = attachments,
                            runId = runId,
                            images = images,
                        )
                        // Publish before the DB append so the ending Pass observes pending work.
                        // Slot release takes this same mutex, so it cannot drain a half-persisted
                        // queue item.
                        state.enqueueSend(queued)
                        try {
                            persistIntervention(genId, runId, queued, payload)
                            notifySendAccepted(
                                acceptance = SendAcceptance.Queued(queued.id, genId),
                                onAccepted = onAccepted,
                            )
                            SendPlacement.Queued(queued.id)
                        } catch (e: Exception) {
                            state.removeQueuedSend(queued.id)
                            val latestRun = convRepo.getRun(runId)
                            if (latestRun == null || latestRun.status != RunStatus.ACTIVE) {
                                SendPlacement.RetryAfterRelease
                            } else {
                                throw e
                            }
                        }
                    }
                }
            }
            if (decision == SendPlacement.RetryAfterRelease) {
                state.generating.filter { generating -> !generating }.first()
            } else {
                placement = decision
            }
        }

        if (placement is SendPlacement.Queued) {
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        val direct = placement as SendPlacement.Direct
        val myUiToken = direct.uiToken
        val runId = direct.runId

        lateinit var modelMessageId: String
        lateinit var userMessageId: String
        var setupModelMessageId: String? = null
        var startTime = 0L
        var roomProjectionFence: RoomMessageProjectionFence? = null
        try {
            executionCoordinator.withConversationLock(genId) {
                val pendingSettings = pendingConversationSettings.value
                if (pendingSettings != null) {
                    settings.setConversationSettings(genId, pendingSettings)
                    pendingConversationSettings.value = null
                }
                val snapshotEntities = convRepo.getMessagesForConversationSnapshot(genId)
                val selectedBeforeSend = convRepo.restoreBranchSelections(genId)
                val path = ConversationUiState.resolvePath(
                    allMessages =
                        snapshotEntities.map { it.toUiChatMessage(appContext) },
                    streamingMsg = null,
                    selectedChildren = selectedBeforeSend,
                )
                val lastMessage = path.lastOrNull()
                userMessageId = UUID.randomUUID().toString()
                val userEntity = MessageEntity(
                    id = userMessageId,
                    conversationId = genId,
                    parentId = lastMessage?.id,
                    text = text,
                    images = payload.allImages,
                    thoughts = null,
                    status = MessageStatus.SUCCESS,
                    participant = Participant.USER,
                    timestamp = System.currentTimeMillis(),
                    attachmentMeta = payload.attachmentMeta?.let(Json::encodeToString),
                    runId = runId,
                    runSequence = 0,
                    consumedAtPass = 0,
                )
                modelMessageId = UUID.randomUUID().toString()
                setupModelMessageId = modelMessageId
                startTime = userEntity.timestamp + 1
                val modelEntity = MessageEntity(
                    id = modelMessageId,
                    conversationId = genId,
                    parentId = userMessageId,
                    text = "",
                    thoughts = null,
                    status = MessageStatus.SENDING,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    modelName = modelId,
                    runId = runId,
                    runSequence = 1,
                )
                val run = RunEntity(
                    id = runId,
                    conversationId = genId,
                    parentRunId = lastMessage?.runId,
                    status = RunStatus.ACTIVE,
                    activeSlot = 1,
                    startedAt = userEntity.timestamp,
                    lastCheckpointAt = startTime,
                )
                val committedMessages = listOf(userEntity, modelEntity)
                val messageSelectionUpdates = mapOf(
                    userEntity.parentId to userEntity.id,
                    userEntity.id to modelEntity.id,
                )
                if (!wasNewChat) {
                    ifOpenOn(genId) {
                        roomProjectionFence = renderStore.beginRoomMessageProjectionFence()
                    }
                }
                val graphCommit = if (newConversation != null) {
                    convRepo.createConversationRunWithMessages(
                        conversation = newConversation,
                        run = run,
                        messages = committedMessages,
                        messageSelectionUpdates = messageSelectionUpdates,
                    )
                } else {
                    convRepo.createRunWithMessages(
                        run = run,
                        messages = committedMessages,
                        messageSelectionUpdates = messageSelectionUpdates,
                    )
                }
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
                    // Usage counters are secondary bookkeeping. A durable graph commit remains a
                    // successful Send even if its aggregate counter cannot be advanced.
                    DebugLog.w(
                        "MessageGenerationController",
                        "Failed to increment the sent-message counter",
                        error,
                    )
                }
                val acceptance = SendAcceptance.Direct(userMessageId, genId)
                notifySendAccepted(acceptance, onAccepted)

                if (wasNewChat) {
                    // Arm the lifecycle entrance before publishing the new conversation id. Room
                    // already owns the durable rows, so publishing the id first can let its first
                    // lazy item compose one frame before the scroll request and permanently miss
                    // the one-shot user-bubble fade.
                    onScrollToAbsoluteBottomAfter(genId, userMessageId)
                    // The composer is already cleared. Publish the new conversation only now, so
                    // its first Room snapshot cannot expose the bubble during media processing.
                    onConversationCreatedBySend()
                    currentConversationId.value = genId
                    isNewChatMode.value = false
                }

                val placeholder = modelEntity.toUiChatMessage(appContext)
                state.loadingChange(myUiToken, true)
                state.streamUpdate(myUiToken, placeholder)
                ifOpenOn(genId) {
                    // Arm the request after durable acceptance/composer clearing, but before the
                    // graph becomes visible. The request actor waits for userMessageId to be
                    // committed, so no scrolling can start early; keeping the request alive
                    // across that first composition also gives the new bubbles their one-shot
                    // lifecycle entrance target.
                    //
                    // Send is the only absolute-bottom request that opts into tween easing. The
                    // ordinary bottom button keeps the actor's default adaptive curve.
                    if (!wasNewChat) {
                        onScrollToAbsoluteBottomAfter(genId, userMessageId)
                    }
                    renderStore.commitGraph(
                        committedMessages =
                            listOf(userEntity.toUiChatMessage(appContext), placeholder),
                        selectedChildren = graphCommit.messageSelections,
                        streamingMessage = placeholder,
                        roomProjectionFence = roomProjectionFence,
                    )
                    roomProjectionFence = null
                }
                roomProjectionFence?.let(renderStore::releaseRoomMessageProjectionFence)
                roomProjectionFence = null
            }
        } catch (e: Exception) {
            roomProjectionFence?.let(renderStore::releaseRoomMessageProjectionFence)
            roomProjectionFence = null
            failGenerationSetup(
                conversationId = genId,
                runId = runId,
                modelMessageId = setupModelMessageId,
                uiToken = myUiToken,
                state = state,
                error = e,
            )
            releaseAndDrain(state, myUiToken, genId)
            return null
        }

        state.launchGenerationJob(myUiToken) {
            val myPersistId = state.nextPersistId()
            try {
                executionCoordinator.withConversationLock(genId) {
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
            } finally {
                releaseAndDrain(state, myUiToken, genId)
            }
        }
        return SendAcceptance.Direct(userMessageId, genId)
    }

    private suspend fun persistIntervention(
        conversationId: String,
        runId: String,
        queued: QueuedSend,
        payload: MessagePayloadBuilder.MessagePayload,
    ) {
        val snapshot = convRepo.getMessagesForConversationSnapshot(conversationId)
        val parentId = snapshot
            .asSequence()
            .filter { it.runId == runId }
            .maxWithOrNull(compareBy<MessageEntity> { it.runSequence }.thenBy { it.id })
            ?.id
        val commit = convRepo.appendPendingInputToRun(
            MessageEntity(
                id = queued.id,
                conversationId = conversationId,
                parentId = parentId,
                text = queued.text,
                images = payload.allImages,
                thoughts = null,
                status = MessageStatus.SUCCESS,
                participant = Participant.USER,
                timestamp = System.currentTimeMillis(),
                attachmentMeta = payload.attachmentMeta?.let(Json::encodeToString),
                runId = runId,
                consumedAtPass = null,
            )
        )
        val message = commit.message
        if (queued.text.isNotBlank()) onUserMessagePersisted(message.id, message.text)
        settings.incrementMessagesSent()
        ifOpenOn(conversationId) {
            renderStore.setSelectedChildren(commit.messageSelections)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // generateTitle
    // ════════════════════════════════════════════════════════════════════

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
