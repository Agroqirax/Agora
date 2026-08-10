package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageStatus

internal data class GenerationCompletionEffectsRequest(
    val terminalPersisted: Boolean,
    val status: MessageStatus,
    val text: String,
    val conversationId: String,
    val modelMessageId: String,
    val foregroundLeaseAcquired: Boolean,
)

internal data class GenerationCompletionEffectsCallbacks(
    val onMessagePersisted: ((messageId: String, text: String) -> Unit)?,
    val onStreamClear: () -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val hasQueuedSends: () -> Boolean,
)

internal fun GenerationCallbacks.completionEffectsCallbacks(
    onMessagePersisted: ((messageId: String, text: String) -> Unit)?,
) = GenerationCompletionEffectsCallbacks(
    onMessagePersisted = onMessagePersisted,
    onStreamClear = onStreamClear,
    onLoadingChange = onLoadingChange,
    hasQueuedSends = hasQueuedSends,
)

/** Executes post-finalization presentation/resource effects without owning Run-state authority. */
internal class GenerationCompletionEffectsExecutor(
    private val isAppInForeground: () -> Boolean,
    private val releaseForegroundLease: (modelMessageId: String) -> Unit,
    private val notify: (text: String, conversationId: String) -> Unit,
) {
    fun execute(
        request: GenerationCompletionEffectsRequest,
        callbacks: GenerationCompletionEffectsCallbacks,
    ) {
        try {
            if (request.terminalPersisted && request.text.isNotBlank()) {
                callbacks.onMessagePersisted?.invoke(request.modelMessageId, request.text)
            }
        } catch (_: Exception) {
            // Indexing is non-authoritative and must never break terminal cleanup.
        }
        if (request.terminalPersisted) {
            callbacks.onStreamClear()
            callbacks.onLoadingChange(false)
        }
        if (request.foregroundLeaseAcquired) {
            releaseForegroundLease(request.modelMessageId)
        }

        // A queued intervention is a separate pending generation. Avoid notifying for the Run
        // immediately before it while the next generation is about to start.
        val shouldNotify =
            request.status == MessageStatus.SUCCESS && !callbacks.hasQueuedSends()
        if (
            request.terminalPersisted &&
            !isAppInForeground() &&
            shouldNotify &&
            request.text.isNotBlank()
        ) {
            notify(request.text, request.conversationId)
        }
    }
}
