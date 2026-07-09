package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates user confirmation before a tool performs a state-changing (create/update/
 * delete) action against on-device data — calendar events, contacts, etc.
 *
 * Mirrors [ShellConfirmationController]'s suspend/await handshake between the generation
 * pipeline (which asks, carrying a human-readable [PendingWrite.summary] of what it's about
 * to do) and the UI (which answers). Reads are never gated through this controller — per-tool
 * providers only call [confirm] from their create/update/delete paths.
 *
 * One instance per tool category (calendar, contacts) so each has its own on/off setting
 * and its own pending-prompt flow.
 */
class WriteConfirmationController(private val confirmEnabled: () -> Boolean, private val setConfirmEnabled: (Boolean) -> Unit) {
    data class PendingWrite(val summary: String, val deferred: CompletableDeferred<Boolean>)

    private val _pendingWrite = MutableStateFlow<PendingWrite?>(null)
    val pendingWrite: StateFlow<PendingWrite?> = _pendingWrite.asStateFlow()

    /** Suspends until the user resolves the prompt; returns whether the write may proceed. */
    suspend fun confirm(summary: String): Boolean {
        if (!confirmEnabled()) return true
        val deferred = CompletableDeferred<Boolean>()
        _pendingWrite.value = PendingWrite(summary, deferred)
        return try { deferred.await() } finally {
            if (_pendingWrite.value?.deferred === deferred) _pendingWrite.value = null
        }
    }

    /** Called by the UI to resolve a pending confirmation. If [alwaysAllow] is set on an
     *  approval, future writes of this type are no longer confirmed (flips the setting off) —
     *  the user can always turn confirmation back on later from Settings. */
    fun resolve(allow: Boolean, alwaysAllow: Boolean = false) {
        val pending = _pendingWrite.value ?: return
        if (allow && alwaysAllow) setConfirmEnabled(false)
        pending.deferred.complete(allow)
        _pendingWrite.value = null
    }
}
