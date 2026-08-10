package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class StandardCompactLaunch(
    val messageId: String,
    val job: Job,
)

/**
 * Executes only mailbox-authorized Compact work and publishes the resulting durable graph.
 *
 * It owns no Run state or long-lived resource. The existing effect coordinator returns every
 * asynchronous result through the same conversation mailbox before this component returns.
 */
internal class ConversationCompactController(
    private val conversations: ConversationRepository,
    private val operation: ContextCompactOperation,
    private val effectCoordinator: ContextCompactEffectCoordinator =
        ContextCompactEffectCoordinator(),
    private val projectGraph: (
        conversationId: String,
        messages: List<MessageEntity>,
        selectedChildren: Map<String?, String>,
    ) -> Unit,
    private val onCompactStarted: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
) {
    suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean = operation.automaticNeeded(conversationId, contextLimit, config)

    /**
     * Starts an ordinary manual-style Compact generation from IDLE and returns as soon as its
     * durable capsule has been projected. The caller then invokes the unchanged send path; because
     * this same generation slot is occupied, the accepted input enters the ordinary FIFO queue.
     */
    suspend fun startAutomaticBeforeSend(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
    ): Boolean = startAutomaticStandard(
        conversationId = conversationId,
        contextLimit = contextLimit,
        config = config,
        state = state,
    ) != null

    suspend fun startAutomaticStandard(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
    ): StandardCompactLaunch? {
        if (!operation.automaticNeeded(conversationId, contextLimit, config)) return null
        val rowStarted = CompletableDeferred<String?>()
        val job = state.scope.launch {
            var startedMessageId: String? = null
            suspend fun publishGraph() {
                startedMessageId = projectCurrentGraph(
                    conversationId = conversationId,
                    expectedMessageId = null,
                    alreadyStartedMessageId = startedMessageId,
                )
                if (startedMessageId != null) rowStarted.complete(startedMessageId)
            }
            try {
                effectCoordinator.execute(state) { effect ->
                    if (conversations.getLiveRun(conversationId) != null) {
                        return@execute CompactResult.NotNeeded
                    }
                    operation.compactBeforeSend(
                        conversationId = conversationId,
                        contextLimit = contextLimit,
                        config = config,
                        identity = effect.identity,
                        compactRunId = effect.compactRunId,
                        onSummaryUpdate = { snapshot ->
                            state.updateCompactPreview(effect.identity, snapshot)
                        },
                        onGraphChanged = { publishGraph() },
                    ).also { result ->
                        if (result.hasDurableMessage()) publishGraph()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Automatic pre-send Compact is best effort. If no row became durable, the caller
                // continues through the ordinary direct send path.
            } finally {
                rowStarted.complete(null)
            }
        }
        job.invokeOnCompletion { rowStarted.complete(null) }
        val messageId = try {
            rowStarted.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                job.cancel(cancelled)
                job.join()
            }
            throw cancelled
        }
        return messageId?.let { StandardCompactLaunch(it, job) }
    }

    suspend fun manual(
        conversationId: String,
        request: CompactRequest,
        state: ConversationGenerationState,
    ): CompactResult {
        var startedMessageId: String? = null
        suspend fun publishGraph() {
            startedMessageId = projectCurrentGraph(
                conversationId = conversationId,
                expectedMessageId = request.replaceMessageId,
                alreadyStartedMessageId = startedMessageId,
            )
        }
        return when (
            val execution = effectCoordinator.execute(state) { effect ->
                if (conversations.getLiveRun(conversationId) != null) {
                    return@execute CompactResult.Failed("Conversation is busy")
                }
                operation.compactManual(
                    conversationId = conversationId,
                    request = request,
                    identity = effect.identity,
                    compactRunId = effect.compactRunId,
                    onSummaryUpdate = { snapshot ->
                        state.updateCompactPreview(effect.identity, snapshot)
                    },
                    onGraphChanged = { publishGraph() },
                ).also { result ->
                    if (result.hasDurableMessage()) publishGraph()
                }
            }
        ) {
            is ContextCompactEffectCoordinator.Execution.Settled -> execution.result
            ContextCompactEffectCoordinator.Execution.Busy ->
                CompactResult.Failed("Wait for the current generation to finish")
            ContextCompactEffectCoordinator.Execution.Superseded ->
                CompactResult.Failed("Context compact was interrupted")
        }
    }

    private suspend fun projectCurrentGraph(
        conversationId: String,
        expectedMessageId: String?,
        alreadyStartedMessageId: String?,
    ): String? {
        val messages = conversations.getMessagesForConversationSnapshot(conversationId)
        projectGraph(
            conversationId,
            messages,
            conversations.restoreBranchSelections(conversationId),
        )
        if (alreadyStartedMessageId != null) return alreadyStartedMessageId

        val inFlightStatuses = setOf(MessageStatus.SENDING, MessageStatus.THINKING)
        val started = expectedMessageId
            ?.let { id ->
                messages.firstOrNull { message ->
                    message.id == id && message.status in inFlightStatuses
                }
            }
            ?: messages.lastOrNull { message ->
                message.id.startsWith(Constants.COMPACT_MSG_PREFIX) &&
                    message.status in inFlightStatuses
            }
        started?.let { onCompactStarted(conversationId, it.id) }
        return started?.id
    }
}

private fun CompactResult.hasDurableMessage(): Boolean = when (this) {
    is CompactResult.Created -> true
    is CompactResult.Failed -> messageId != null
    CompactResult.NotNeeded -> false
}
