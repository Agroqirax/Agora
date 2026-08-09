package com.newoether.agora.viewmodel

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Publishes one accepted Send without turning presentation callback failure into Send failure. */
internal class SendAcceptanceNotifier(
    private val onAcceptedEvent: ((conversationId: String, messageId: String) -> Unit)?,
) {
    suspend fun notify(
        acceptance: SendAcceptance,
        onAccepted: suspend (SendAcceptance) -> Unit,
    ) {
        try {
            withContext(NonCancellable) { onAccepted(acceptance) }
            onAcceptedEvent?.invoke(acceptance.conversationId, acceptance.messageId)
        } catch (error: Exception) {
            DebugLog.e(
                "SendAcceptanceNotifier",
                "Failed to acknowledge accepted Send ${acceptance.messageId}",
                error,
            )
        }
    }
}
