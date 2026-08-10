package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

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
    suspend fun automaticBeforeBoundary(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
    ): String? {
        if (!operation.automaticNeeded(conversationId, contextLimit, config)) {
            return null
        }
        var startedMessageId: String? = null
        suspend fun publishGraph() {
            startedMessageId = projectCurrentGraph(
                conversationId = conversationId,
                expectedMessageId = null,
                alreadyStartedMessageId = startedMessageId,
            )
        }
        when (
            val execution = effectCoordinator.executeAutomatic(state) { effect ->
                operation.compactAutomatic(
                    conversationId = conversationId,
                    contextLimit = contextLimit,
                    config = config,
                    identity = effect.identity,
                    compactRunId = effect.compactRunId,
                    onSummaryChunk = { chunk ->
                        state.appendCompactPreview(effect.identity, chunk)
                    },
                    onGraphChanged = { publishGraph() },
                ).also { result ->
                    if (result.hasDurableMessage()) publishGraph()
                }
            }
        ) {
            is ContextCompactEffectCoordinator.Execution.Settled -> when (
                val result = execution.result
            ) {
                // Auto Compact is a single best-effort opportunity before the hard-cap rollout.
                // Its failure must not fail the already-admitted ordinary generation. Any durable
                // failed capsule remains visible, while request projection ignores it.
                is CompactResult.Failed -> return null
                is CompactResult.Created -> return result.messageId
                CompactResult.NotNeeded -> return null
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
        return null
    }

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
    ): Boolean {
        if (!operation.automaticNeeded(conversationId, contextLimit, config)) return false
        val rowStarted = CompletableDeferred<Boolean>()
        val job = state.scope.launch {
            var startedMessageId: String? = null
            suspend fun publishGraph() {
                startedMessageId = projectCurrentGraph(
                    conversationId = conversationId,
                    expectedMessageId = null,
                    alreadyStartedMessageId = startedMessageId,
                )
                if (startedMessageId != null) rowStarted.complete(true)
            }
            try {
                effectCoordinator.executeManual(state) { effect ->
                    if (conversations.getLiveRun(conversationId) != null) {
                        return@executeManual CompactResult.NotNeeded
                    }
                    operation.compactBeforeSend(
                        conversationId = conversationId,
                        contextLimit = contextLimit,
                        config = config,
                        identity = effect.identity,
                        compactRunId = effect.compactRunId,
                        onSummaryChunk = { chunk ->
                            state.appendCompactPreview(effect.identity, chunk)
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
                rowStarted.complete(false)
            }
        }
        job.invokeOnCompletion { rowStarted.complete(false) }
        return rowStarted.await()
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
            val execution = effectCoordinator.executeManual(state) { effect ->
                if (conversations.getLiveRun(conversationId) != null) {
                    return@executeManual CompactResult.Failed("Conversation is busy")
                }
                operation.compactManual(
                    conversationId = conversationId,
                    request = request,
                    identity = effect.identity,
                    compactRunId = effect.compactRunId,
                    onSummaryChunk = { chunk ->
                        state.appendCompactPreview(effect.identity, chunk)
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
