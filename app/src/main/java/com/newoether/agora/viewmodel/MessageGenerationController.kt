package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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

    data class Direct(override val messageId: String) : SendAcceptance
    data class Queued(override val messageId: String) : SendAcceptance
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
class MessageGenerationController(
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
    private val allMessages: MutableStateFlow<List<ChatMessage>>,
    private val selectedChildren: MutableStateFlow<Map<String?, String>>,
    private val currentConversationId: MutableStateFlow<String?>,
    private val isNewChatMode: MutableStateFlow<Boolean>,
    private val pendingConversationSettings: MutableStateFlow<ConversationSettings?>,
    private val pendingSystemPromptId: MutableStateFlow<String?>,
    private val currentActiveModel: StateFlow<String>,
    private val messages: StateFlow<List<ChatMessage>>,
    // ── Callbacks into ChatViewModel-owned side effects ──
    private val onScrollToMessage: (String?) -> Unit,
    private val onSnackbar: (String) -> Unit,
    private val onSnackbarSuspend: suspend (String) -> Unit,  // sequential emit inside generateTitle
    private val onPersistSelectedChildren: suspend (String, Map<String?, String>) -> Unit,
    // Called when sendMessage creates a NEW conversation, so the UI can suppress the
    // conversation-open auto-scroll (the send's own scroll-to-message handles it) and
    // avoid a double scroll on the first message of a new chat.
    private val onConversationCreatedBySend: () -> Unit = {},
    // Called once when a hidden task/loop execution becomes searchable. The callback
    // only enqueues background work; embedding computation must not run under the send lock.
    private val onConversationGraduated: (String) -> Unit = {},
    // Called after a USER message row is persisted (send / edit), so incremental RAG
    // indexing covers the user's side too — the model reply is indexed at generation end
    // via GenerationManager.onMessagePersisted, and without this hook user messages only
    // ever entered the cache through a manual full re-cache. Enqueues background work only.
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** Covers destructive tree mutation until ChatApp has settled the resulting path. */
    private val onTreeMutationStart: suspend () -> Unit = {},
    private val onTreeMutationSettling: (targetMessageId: String?) -> Unit = {},
    private val onTreeMutationFailed: () -> Unit = {},
) {
    private val generationManager: GenerationManager get() = generationManagerProvider()

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
                    ?.toChatMessage()
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
        val snapshot = allMessages.value
        if (snapshot.none { it.id == messageId }) return 0
        val previewIds = structuralDescendantIds(snapshot, messageId)

        viewModelScope.launch(Dispatchers.IO) {
            onTreeMutationStart()
            var committed = false
            try {
                state.queueMutationMutex.withLock {
                    // Recheck after the overlay fade and under the same mutex that accepts Send.
                    if (state.generating.value) return@withLock
                    executionCoordinator.withConversationLock(currentId) lock@ {
                        if (convRepo.getLiveRun(currentId) != null) return@lock

                        val runs = convRepo.getRunsForConversationSnapshot(currentId)
                        val allMsgs = convRepo.getMessagesForConversationSnapshot(currentId)
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
                        val remainingChatMessages = remainingMsgs.map { it.toChatMessage() }
                        val targetAfterDelete = ConversationUiState.resolvePath(
                            allMessages = remainingChatMessages,
                            streamingMsg = null,
                            selectedChildren = plan.messageSelections,
                        ).lastOrNull { it.participant == Participant.USER }?.id
                        ifOpenOn(currentId) {
                            allMessages.value = remainingChatMessages
                            selectedChildren.value = plan.messageSelections
                        }
                        committed = true
                        onTreeMutationSettling(targetAfterDelete)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to delete message branch $messageId", e)
            } finally {
                if (!committed) onTreeMutationFailed()
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

    fun regenerate(messageId: String) {
        val genId = currentConversationId.value ?: return
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return

        // Validate and snapshot the open conversation BEFORE claiming the slot. The generation
        // coroutine may wait behind automation while the user switches to another conversation.
        val visiblePath = messages.value
        val messageToRegenerate = visiblePath.find { it.id == messageId } ?: return
        if (messageToRegenerate.participant != Participant.MODEL) return
        val sourceRunId = messageToRegenerate.runId ?: return
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
        if (outputBoundary?.id != messageId) return

        // Regenerate is idle-only by product rule. Enforce it atomically in the state machine in
        // addition to the UI's enabled flag, which can lag during a conversation switch.
        val myUiToken = state.tryAcquireForReplacement() ?: return
        val runId = UUID.randomUUID().toString()
        state.bindRun(myUiToken, runId)

        state.generationJob = state.scope.launch {
            val myPersistId = state.nextPersistId()
            var setupModelMessageId: String? = null
            try {
                executionCoordinator.withConversationLock(genId) lock@ {
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
                convRepo.createRunWithMessages(
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
                )
                val placeholder = modelEntity.toChatMessage()
                val selectedAfterRegenerate =
                    convRepo.restoreBranchSelections(genId).toMutableMap().apply {
                    put(sourceInput.id, modelEntity.id)
                }.toMap()
                convRepo.selectRunBranch(
                    conversationId = genId,
                    parentRunId = inputRunId,
                    runId = runId,
                    messageSelections = selectedAfterRegenerate,
                )
                ifOpenOn(genId) {
                    allMessages.update { existing ->
                        UiMessageCommitPolicy.upsert(existing, listOf(placeholder))
                    }
                    selectedChildren.value = selectedAfterRegenerate
                    onScrollToMessage(sourceInput.id)
                }
                state.streamUpdate(myUiToken, placeholder)
                convRepo.getConversation(genId)?.let { conv ->
                    convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                }
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
                releaseAndDrain(state, myUiToken, genId)
            }
        }
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
        val resolved = requestBuilder.buildEffectiveSystemPrompt(currentId, modelId)
        val effectiveSettings = requestBuilder.buildEffectiveConversationSettings(currentId)
        // Re-resolve the key against on-disk settings here (the suspend convergence
        // point for all entry paths). The synchronous [activeKey] resolved by the
        // callers can be blank if DataStore had not finished loading when Send was
        // tapped, which would build the request with an empty key → 401.
        val freshKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() } ?: activeKey
        try {
            val (config, genCtx) = requestBuilder.buildGenerationPair(
                providerName, modelId, freshKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, currentId
            )
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
                streamScope = state.streamScope
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

    fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank()) return
        val genId = currentConversationId.value ?: return
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return
        val visiblePath = messages.value
        val messageToEdit = visiblePath.find { it.id == messageId } ?: return
        if (messageToEdit.participant != Participant.USER) return
        val sourceRunId = messageToEdit.runId ?: return
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
        if (inputBoundary?.id != messageId) return

        // Edit is idle-only by product rule; enforce it atomically below the UI gate.
        val myUiToken = state.tryAcquireForReplacement() ?: return
        val runId = UUID.randomUUID().toString()
        state.bindRun(myUiToken, runId)
        state.generationJob = state.scope.launch {
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
            convRepo.createRunWithMessages(
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
            )
            val selectedAfterModelEdit =
                convRepo.restoreBranchSelections(genId).toMutableMap().apply {
                    put(newUser.parentId, newUser.id)
                    put(newUser.id, modelMessageId)
                }.toMap()
            convRepo.selectRunBranch(
                conversationId = genId,
                parentRunId = sourceRun.parentRunId,
                runId = runId,
                messageSelections = selectedAfterModelEdit,
            )
            onUserMessagePersisted(newUser.id, newText)
            convRepo.getConversation(genId)?.let { conv ->
                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
            }
            // Set streamingMessage BEFORE allMessages so the combine never
            // evaluates with stale allMessages data but no streaming overlay.
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = newUser.id, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId,
                runId = runId, runSequence = 1,
            )
            state.streamUpdate(myUiToken, placeholder)
            ifOpenOn(genId) {
                allMessages.update {
                    UiMessageCommitPolicy.upsert(
                        existing = it,
                        committed = listOf(newUser.toChatMessage(), placeholder),
                    )
                }
                selectedChildren.value = selectedAfterModelEdit
                onScrollToMessage(newUser.id)
            }
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
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // sendMessage
    // ════════════════════════════════════════════════════════════════════

    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
    ): SendAcceptance? {
        val selectedModelId = currentActiveModel.value
        // Pre-flight: a blank model fails fast BEFORE creating a new-chat row or enqueueing, so the
        // Send button never swallows a message into a conversation that can't generate.
        if (selectedModelId.isBlank()) {
            onSnackbar(application.getString(R.string.no_model_selected))
            return null
        }
        // G9: resolve the conversation id on the calling thread BEFORE launching the generation
        // coroutine, so the registry keys the new generation on the correct conversation even if
        // the user switches chats before the coroutine runs. The new-conversation row is a fast DB
        // insert; doing it here closes the race where genId was unknown on the calling thread.
        val wasNewChat = isNewChatMode.value || currentConversationId.value == null
        if (wasNewChat) {
            val newId = UUID.randomUUID().toString()
            convRepo.upsertConversation(ChatEntity(
                id = newId,
                title = appContext.getString(R.string.new_chat),
                modelId = selectedModelId,
                systemPromptId = pendingSystemPromptId.value
            ))
            // Suppress the conversation-open auto-scroll BEFORE the id change triggers it.
            onConversationCreatedBySend()
            currentConversationId.value = newId
            isNewChatMode.value = false
        }
        val genId = currentConversationId.value ?: return null
        return sendInto(genId, wasNewChat, text, images, attachments, selectedModelId)
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

        state.generationJob = state.scope.launch {
            var setupModelMessageId: String? = null
            try {
                val myPersistId = state.nextPersistId()
                executionCoordinator.withConversationLock(genId) {
                    if (convRepo.graduateConversation(genId)) {
                        onConversationGraduated(genId)
                    }
                    val snapshotEntities = convRepo.getMessagesForConversationSnapshot(genId)
                    val selectedBeforeSend = convRepo.restoreBranchSelections(genId)
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
                    val newChildren = selectedBeforeSend.toMutableMap()
                    val claimedPass = checkNotNull(convRepo.claimPendingRunInputs(runId)) {
                        "Queued intervention batch did not advance Run $runId"
                    }
                    val lastUserMessageId = queuedMessages.last().id
                    val modelMessageId = UUID.randomUUID().toString()
                    setupModelMessageId = modelMessageId
                    val startTime = maxOf(
                        System.currentTimeMillis(),
                        queuedMessages.maxOf { it.timestamp } + 1,
                    )
                    val insertedPlaceholder = convRepo.appendMessageToRun(MessageEntity(
                        id = modelMessageId, conversationId = genId, parentId = lastUserMessageId,
                        text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL,
                        timestamp = startTime, modelName = modelId, runId = runId,
                    ))
                    convRepo.getConversation(genId)?.let { conv ->
                        convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                    }
                    val placeholder = ChatMessage(
                        id = modelMessageId, parentId = lastUserMessageId, text = "", participant = Participant.MODEL,
                        status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId,
                        runId = runId, runSequence = insertedPlaceholder.runSequence,
                    )
                    state.streamUpdate(myUiToken, placeholder)
                    ifOpenOn(genId) {
                        allMessages.update {
                            UiMessageCommitPolicy.upsert(it, listOf(placeholder))
                        }
                    }
                    newChildren[lastUserMessageId] = modelMessageId
                    onPersistSelectedChildren(genId, newChildren)
                    ifOpenOn(genId) { selectedChildren.value = newChildren }
                    ifOpenOn(genId) { onScrollToMessage(lastUserMessageId) }

                    launchGeneration(
                        genId, modelMessageId, startTime,
                        isRegenerate = false, replaceMessageId = null,
                        providerName, modelId, activeKey, myUiToken, myPersistId,
                        state, runId = runId, pass = claimedPass.pass, callerTag = "queueDrain"
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
        text: String,
        images: List<String>,
        attachments: List<SelectedAttachment>,
        modelId: String,
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
            return SendAcceptance.Queued(placement.messageId)
        }
        val direct = placement as SendPlacement.Direct
        val myUiToken = direct.uiToken
        val runId = direct.runId
        state.loadingChange(myUiToken, true)

        lateinit var modelMessageId: String
        lateinit var userMessageId: String
        var setupModelMessageId: String? = null
        var startTime = 0L
        try {
            executionCoordinator.withConversationLock(genId) {
                if (convRepo.graduateConversation(genId)) {
                    onConversationGraduated(genId)
                }
                val pendingSettings = pendingConversationSettings.value
                if (pendingSettings != null) {
                    settings.setConversationSettings(genId, pendingSettings)
                    pendingConversationSettings.value = null
                }
                val snapshotEntities = convRepo.getMessagesForConversationSnapshot(genId)
                val selectedBeforeSend = convRepo.restoreBranchSelections(genId)
                val path = ConversationUiState.resolvePath(
                    allMessages = snapshotEntities.map { it.toChatMessage() },
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
                convRepo.createRunWithMessages(
                    RunEntity(
                        id = runId,
                        conversationId = genId,
                        parentRunId = lastMessage?.runId,
                        status = RunStatus.ACTIVE,
                        activeSlot = 1,
                        startedAt = userEntity.timestamp,
                        lastCheckpointAt = startTime,
                    ),
                    listOf(userEntity, modelEntity),
                )
                convRepo.selectRunBranch(genId, lastMessage?.runId, runId)
                if (text.isNotBlank()) onUserMessagePersisted(userMessageId, text)
                settings.incrementMessagesSent()
                convRepo.getConversation(genId)?.let { conv ->
                    convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                }
                val placeholder = modelEntity.toChatMessage()
                state.streamUpdate(myUiToken, placeholder)
                ifOpenOn(genId) {
                    allMessages.update { existing ->
                        UiMessageCommitPolicy.upsert(
                            existing = existing,
                            committed = listOf(userEntity.toChatMessage(), placeholder),
                        )
                    }
                }
                val newChildren = selectedBeforeSend.toMutableMap().apply {
                    put(userEntity.parentId, userEntity.id)
                    put(userEntity.id, modelEntity.id)
                }
                onPersistSelectedChildren(genId, newChildren)
                ifOpenOn(genId) {
                    selectedChildren.value = newChildren
                    // The bubble commit owns its scroll request. A callback issued later by the
                    // composer's coroutine can be lost when new-chat UI leaves composition.
                    onScrollToMessage(userMessageId)
                }
            }
        } catch (e: Exception) {
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

        state.generationJob = state.scope.launch {
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
        return SendAcceptance.Direct(userMessageId)
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
        val message = convRepo.appendMessageToRun(
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
        if (queued.text.isNotBlank()) onUserMessagePersisted(message.id, message.text)
        settings.incrementMessagesSent()
        convRepo.getConversation(conversationId)?.let { conversation ->
            convRepo.upsertConversation(conversation.copy(lastUpdated = System.currentTimeMillis()))
        }
        val updatedBranches = convRepo.restoreBranchSelections(conversationId).toMutableMap().apply {
            put(message.parentId, message.id)
        }
        onPersistSelectedChildren(conversationId, updatedBranches)
        ifOpenOn(conversationId) {
            selectedChildren.value = updatedBranches
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
        val destinationDir = File(application.filesDir, "run-inputs")
        check(destinationDir.exists() || destinationDir.mkdirs()) {
            "Cannot create Run input directory"
        }
        val copiedBySource = mutableMapOf<String, String>()
        val createdFiles = mutableListOf<File>()

        fun cloneBackingPath(path: String): String {
            copiedBySource[path]?.let { return it }
            val source = File(path)
            if (!source.isFile) return path
            val suffix = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            val destination = File(destinationDir, "${UUID.randomUUID()}$suffix")
            source.copyTo(destination, overwrite = false)
            createdFiles += destination
            return destination.absolutePath.also { copiedBySource[path] = it }
        }

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
                    cloneBackingPath = ::cloneBackingPath,
                )
                parentId = cloned.id
                cloned
            }
        } catch (e: Exception) {
            createdFiles.forEach { runCatching { it.delete() } }
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
                val meta = Json.decodeFromString<AttachmentMeta>(raw)
                Json.encodeToString(
                    meta.copy(
                        items = meta.items.map { item ->
                            val uri = item.originalUri
                            if (uri != null && uri.startsWith("file://")) {
                                item.copy(
                                    originalUri =
                                        "file://${cloneBackingPath(uri.removePrefix("file://"))}"
                                )
                            } else {
                                item
                            }
                        }
                    )
                )
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

    private fun MessageEntity.toChatMessage() = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        images = images,
        thoughts = thoughts,
        thoughtTitle = thoughtTitle,
        tokenCount = tokenCount,
        status = status,
        participant = participant,
        timestamp = timestamp,
        thoughtTimeMs = thoughtTimeMs,
        modelName = modelName,
        attachmentMeta = attachmentMeta?.let { raw ->
            runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull()
        },
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = consumedAtPass,
    )

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            onSnackbarSuspend(appContext.getString(R.string.snackbar_generating_title))
            val conversation = convRepo.getConversation(conversationId) ?: return@launch
            // Resolve the TARGET conversation's own path — not messages.value, which
            // is the currently-open conversation. Otherwise a long-press "regenerate
            // title" on a background conversation would summarize the active one.
            val entities = convRepo.getMessagesForConversationSnapshot(conversationId)
            val path = ConversationUiState.resolvePath(
                allMessages = entities.map {
                    ChatMessage(
                        id = it.id,
                        parentId = it.parentId,
                        text = it.text,
                        participant = it.participant,
                        timestamp = it.timestamp,
                        status = it.status,
                        modelName = it.modelName,
                        runId = it.runId,
                        runSequence = it.runSequence,
                        consumedAtPass = it.consumedAtPass,
                    )
                },
                streamingMsg = null,
                selectedChildren = emptyMap()
            )
            val firstUserMsg = path.firstOrNull { it.participant == Participant.USER } ?: return@launch
            val firstModelMsg = path
                .filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .firstOrNull()

            val titleModelId = settings.titleGenerationModel.value
            val modelIdWithPrefix = if (!titleModelId.isNullOrBlank()) titleModelId else (conversation.modelId ?: firstModelMsg?.modelName ?: settings.selectedModel.value)
            val modelId = ModelId.parse(modelIdWithPrefix).modelName
            val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelIdWithPrefix) ?: return@launch

            val summaryText = if (firstModelMsg != null) {
                "User: ${firstUserMsg.text}\nAssistant: ${firstModelMsg.text.take(500)}"
            } else {
                firstUserMsg.text
            }

            val titlePrompt = listOf(
                ChatMessage(
                    text = "Generate a short title (5 words maximum) for this conversation:\n\n$summaryText\n\nRespond with ONLY the title text, no quotes, no punctuation, no explanation.",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS
                )
            )

            val provider = providerRegistry.getInstance(providerName)
            val config = ProviderConfig(
                apiKey = activeKey,
                modelId = modelId,
                systemPrompt = settings.titleGenerationPrompt.value.ifBlank { BuiltInPrompts.TITLE_GENERATION_SYSTEM },
                maxContextWindow = 1,
                thinkingEnabled = false,
                baseUrl = providerRegistry.getEffectiveBaseUrl(providerName)
            )

            var title = ""
            try {
                // Title generation is a real provider call. Remote title gen runs without any
                // global slot (it's cheap and independent); local title gen takes the shared
                // LocalModelSerializer mutex so it can't load a model alongside an in-flight
                // chat/embedding turn (OOM). The mutex is fair and cancellable, so a Stop or a
                // new send isn't blocked behind it.
                if (providerName == Constants.PROVIDER_LOCAL) {
                    com.newoether.agora.api.LocalModelSerializer.mutex.withLock {
                        withContext(Dispatchers.IO) {
                            provider.generateResponse(titlePrompt, config).collect { event ->
                                if (event is StreamEvent.TextChunk) title += event.text
                                else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                            }
                        }
                        // Intentionally do NOT releaseEngine() here. Title generation runs right
                        // after the first message of a new conversation, on the same model the
                        // user is actively chatting with; LocalProvider.ensureEngineLoaded reuses
                        // the already-loaded engine. Releasing here would force the next message
                        // to re-load a multi-GB model onto a possibly-fragmented native heap,
                        // which is the leading suspect for the "second message OOM" crash (#53,
                        // 31 OOM reports) — the text path itself is crash-safe. Keeping the
                        // engine session-scoped (released only on model switch / RAG / process
                        // death) eliminates that reload churn. This is an OOM-probability
                        // reduction, NOT a claimed #53 root-case fix (that needs a logcat).
                    }
                } else {
                    provider.generateResponse(titlePrompt, config).collect { event ->
                        if (event is StreamEvent.TextChunk) title += event.text
                        else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Title generation failed for provider=$providerName model=$modelId", e)
                return@launch
            }

            title = title.trim().replace("\n", " ").take(60)
            if (title.isNotBlank()) {
                convRepo.getConversation(conversationId)?.let { existing ->
                    convRepo.upsertConversation(existing.copy(title = title))
                }
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_generated))
            } else {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_error))
            }
        }
    }
}
