package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persists terminal (STOPPED) message state to the DB after a generation is stopped. Kept separate
 * from per-conversation [ConversationGenerationState] (which owns no repos) so it can delegate
 * finalization without holding repository references.
 *
 * Runs on the supplied conversation-owned scope; the stopped conversation id comes from
 * [ConversationGenerationState.StopResult], NOT from the live `currentConversationId`, so a stop
 * triggered after the user switched conversations still persists to the ORIGINAL conversation.
 */
class GenerationFinalizer(
    private val convRepo: ConversationRepository,
    private val onIndexMessageForRag: (messageId: String, text: String) -> Unit,
) {
    /**
     * Persist [messages] as STOPPED into [conversationId] on [scope]. Returns the launched job
     * (or null if nothing to persist). The caller may chain a subsequent generation onto this job.
     * [onFinalized] reports whether Room reached a terminal state. Failure must not release the
     * in-memory slot because the database's unique live-Run slot is still unavailable.
     */
    fun launchStopFinalization(
        scope: CoroutineScope,
        conversationId: String?,
        runId: String?,
        messages: List<ChatMessage>,
        onFinalized: (success: Boolean) -> Unit = {},
    ): Job? {
        if (conversationId == null) return null
        val distinct = messages.distinctBy { it.id }
        if (distinct.isEmpty() && runId == null) return null
        return scope.launch {
            var finalized = false
            var lastFailure: Exception? = null
            val retryDelaysMs = longArrayOf(0L, 40L, 120L)
            for (retryDelayMs in retryDelaysMs) {
                if (retryDelayMs > 0L) delay(retryDelayMs)
                try {
                    if (runId != null) convRepo.requestRunStop(runId)
                    finalized = convRepo.finishStoppedGeneration(distinct, runId)
                    if (finalized) break
                } catch (e: Exception) {
                    lastFailure = e
                }
            }
            if (!finalized) {
                val message =
                    "Failed to persist stopped generation after ${retryDelaysMs.size} attempts"
                if (lastFailure != null) DebugLog.e("AgoraVM", message, lastFailure)
                else DebugLog.e("AgoraVM", message)
            }
            onFinalized(finalized)
            // RAG is outside the Stop critical path and owns its own eligibility gate.
            if (finalized) {
                distinct.forEach { message ->
                    if (message.text.isNotBlank()) onIndexMessageForRag(message.id, message.text)
                }
            }
        }
    }
}
