package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.UUID

/** One ordinary Assistant generation that continues from an already-durable graph boundary. */
internal data class StandardGenerationContinuationRequest(
    val conversationId: String,
    val parentMessageId: String,
    val snapshot: GenerationAdmissionSnapshot,
    val alreadyHoldsConversationLock: Boolean = false,
)

internal data class StandardGenerationContinuationLaunch(
    val job: Job,
    val modelMessageId: String,
)

/**
 * Starts a fresh Run and ordinary Assistant placeholder without synthesizing a USER message.
 *
 * This is the standard continuation primitive for any durable protocol boundary. Compact merely
 * chooses the parent boundary; it does not resume an old Run or reuse an old streaming message.
 */
internal class StandardGenerationContinuationLauncher(
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: () -> BoundRunGenerationLauncher,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (
        conversationId: String,
        messages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage,
    ) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun launch(
        request: StandardGenerationContinuationRequest,
        state: ConversationGenerationState,
    ): StandardGenerationContinuationLaunch? {
        val uiToken = state.tryAcquireForReplacement() ?: return null
        val runId = idFactory()
        val modelMessageId = idFactory()
        val generationJob = state.launchGenerationJob(uiToken) generation@{
            var runCreated = false
            var runBound = false
            var stopFinalizationClaimed = false
            try {
                val persistId = state.nextPersistId()
                val launchAtBoundary: suspend () -> Unit = boundary@{
                    if (!state.isCurrentToken(uiToken)) return@boundary
                    val parent = conversations
                        .getMessagesForConversationSnapshot(request.conversationId)
                        .find { it.id == request.parentMessageId }
                        ?: return@boundary
                    val selectedChildren =
                        conversations.restoreBranchSelections(request.conversationId)
                    if (selectedChildren[parent.id] != null) return@boundary
                    val generationSnapshot = request.snapshot.copy(runId = runId)
                    val messageId = modelMessageId
                    val startTime = maxOf(clock(), parent.timestamp + 1)
                    val modelEntity = MessageEntity(
                        id = messageId,
                        conversationId = request.conversationId,
                        parentId = parent.id,
                        text = "",
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = startTime,
                        modelName = generationSnapshot.selectedModelId,
                        runId = runId,
                        runSequence = 0,
                    )
                    val graphCommit = conversations.createRunWithMessages(
                        run = RunEntity(
                            id = runId,
                            conversationId = request.conversationId,
                            parentRunId = parent.runId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = startTime,
                            lastCheckpointAt = startTime,
                        ),
                        messages = listOf(modelEntity),
                        messageSelectionUpdates = mapOf(parent.id to messageId),
                        conversationModelId = generationSnapshot.selectedModelId,
                    )
                    runCreated = true
                    val binding = state.bindPersistedRun(uiToken, runId)
                    runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                    if (!runBound) {
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        } else {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                        return@boundary
                    }

                    val placeholder = toUiMessage(modelEntity)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(request.conversationId)) {
                        projectGraph(
                            request.conversationId,
                            listOf(placeholder),
                            graphCommit.messageSelections,
                            placeholder,
                        )
                    }
                    boundRunGenerationLauncher().launch(
                        BoundRunGenerationRequest(
                            conversationId = request.conversationId,
                            modelMessageId = messageId,
                            startTime = startTime,
                            snapshot = generationSnapshot,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            callerTag = "standardContinuation",
                        ),
                        state,
                    )
                }
                if (request.alreadyHoldsConversationLock) {
                    launchAtBoundary()
                } else {
                    executionCoordinator.withConversationLock(request.conversationId) {
                        launchAtBoundary()
                    }
                }
            } catch (error: CancellationException) {
                if (runCreated && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.bindPersistedRun(uiToken, runId)
                        stopFinalizationClaimed =
                            terminalSettlement.settleCancelledDurableRun(state, binding)
                        if (!stopFinalizationClaimed) {
                            conversations.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                if (runCreated && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.bindPersistedRun(uiToken, runId)
                        runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        }
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId = request.conversationId,
                    runId = runId,
                    modelMessageId = modelMessageId,
                    uiToken = uiToken,
                    state = state,
                    error = error,
                )
            }
        } ?: return null
        return StandardGenerationContinuationLaunch(
            job = generationJob,
            modelMessageId = modelMessageId,
        )
    }
}
