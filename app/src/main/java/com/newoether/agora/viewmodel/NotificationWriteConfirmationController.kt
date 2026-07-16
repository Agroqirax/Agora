package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Confirmation gate for [com.newoether.agora.tool.NotificationToolProvider]'s
 * `interact_notification`/`dismiss_notification` tools. A variant of
 * [WriteConfirmationController] tailored to notifications' one extra wrinkle: besides the
 * usual "always allow, stop asking" checkbox (which turns off confirmation for every app's
 * notifications, same as [WriteConfirmationController.resolve]), there's a second, narrower
 * "always allow *this app*" option — so someone can leave the global gate on but permanently
 * trust a specific app (e.g. Discord) to skip the prompt from then on, while everything else
 * still asks.
 *
 * [isAppAlwaysAllowed] is consulted before ever showing a prompt, so a trusted app's
 * notifications never interrupt the user at all — not even a flash of the dialog.
 */
class NotificationWriteConfirmationController(
    private val confirmEnabled: () -> Boolean,
    private val setConfirmEnabled: (Boolean) -> Unit,
    private val isAppAlwaysAllowed: (packageName: String) -> Boolean,
    private val setAppAlwaysAllowed: (packageName: String, allowed: Boolean) -> Unit
) {
    data class PendingWrite(
        val summary: String,
        val packageName: String,
        val appLabel: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingWrite = MutableStateFlow<PendingWrite?>(null)
    val pendingWrite: StateFlow<PendingWrite?> = _pendingWrite.asStateFlow()

    /** Suspends until the user resolves the prompt; returns whether the action may proceed.
     *  Short-circuits to true without prompting if confirmation is off globally, or if
     *  [packageName] specifically has been always-allowed. */
    suspend fun confirm(summary: String, packageName: String, appLabel: String): Boolean {
        if (!confirmEnabled()) return true
        if (isAppAlwaysAllowed(packageName)) return true
        val deferred = CompletableDeferred<Boolean>()
        _pendingWrite.value = PendingWrite(summary, packageName, appLabel, deferred)
        return try { deferred.await() } finally {
            if (_pendingWrite.value?.deferred === deferred) _pendingWrite.value = null
        }
    }

    /**
     * Called by the UI to resolve a pending confirmation.
     * @param alwaysAllow turns off confirmation for every app's notifications going forward
     *   (same global kill-switch as [WriteConfirmationController]).
     * @param alwaysAllowApp allow-lists only the specific app this prompt was about; future
     *   prompts for other apps are unaffected.
     */
    fun resolve(allow: Boolean, alwaysAllow: Boolean = false, alwaysAllowApp: Boolean = false) {
        val pending = _pendingWrite.value ?: return
        if (allow) {
            if (alwaysAllow) setConfirmEnabled(false)
            if (alwaysAllowApp) setAppAlwaysAllowed(pending.packageName, true)
        }
        pending.deferred.complete(allow)
        _pendingWrite.value = null
    }
}
