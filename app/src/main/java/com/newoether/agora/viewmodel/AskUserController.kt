package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Suspend-and-resume handshake for the ask_user tool: lets the model pause generation and
 * ask the human a question, then resumes with their answer.
 *
 * Deliberately not built on [ToolConfirmationController] — that class is Boolean-in/
 * Boolean-out with an "always allow" kill switch and a per-key trust list, none of which
 * apply here: each question is unique content (not a repeatable permission), the payload
 * is a free-text [String], and "dismissed without answering" is a distinct outcome from
 * `true`/`false`, not one of them.
 */
class AskUserController {
    data class PendingQuestion(
        val question: String,
        val options: List<String> = emptyList(),
        val deferred: CompletableDeferred<String?>
    )

    private val _pending = MutableStateFlow<PendingQuestion?>(null)
    val pending: StateFlow<PendingQuestion?> = _pending.asStateFlow()

    /** Suspends until the user answers or dismisses; null means dismissed without answering. */
    suspend fun ask(question: String, options: List<String> = emptyList()): String? {
        val deferred = CompletableDeferred<String?>()
        _pending.value = PendingQuestion(question, options, deferred)
        return try {
            deferred.await()
        } finally {
            if (_pending.value?.deferred === deferred) _pending.value = null
        }
    }

    /** Called by the UI. [answer] is null/blank on dismiss; a tapped option or typed text otherwise. */
    fun resolve(answer: String?) {
        _pending.value?.deferred?.complete(answer?.takeIf { it.isNotBlank() })
    }
}
