package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     * [onFinalized] runs after the persist attempt (success or not) — used to release the STOPPED
     * streaming overlay now that Room owns the terminal row.
     */
    fun launchStopFinalization(
        scope: CoroutineScope,
        conversationId: String?,
        runId: String?,
        messages: List<ChatMessage>,
        onFinalized: () -> Unit = {},
    ): Job? {
        if (conversationId == null) return null
        val distinct = messages.distinctBy { it.id }
        if (distinct.isEmpty() && runId == null) return null
        return scope.launch {
            try {
                if (convRepo.getConversation(conversationId) == null) return@launch
                convRepo.finishStoppedGeneration(distinct, runId)
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to persist stopped generation", e)
            } finally {
                onFinalized()
            }
            // RAG is outside the Stop critical path and owns its own eligibility gate.
            distinct.forEach { message ->
                if (message.text.isNotBlank()) onIndexMessageForRag(message.id, message.text)
            }
        }
    }
}
