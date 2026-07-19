package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generic confirmation gate shared by every tool that needs to pause and ask the user
 * before doing something: shell commands, destructive MCP tool calls, calendar/contacts/
 * alarm writes, notification read/interact, and location reads.
 *
 * Replaces five near-identical hand-written controllers (ShellConfirmationController,
 * McpConfirmationController, LocationConfirmationController, WriteConfirmationController,
 * NotificationWriteConfirmationController) that differed only in whether they had a
 * per-[Key] trust list and what the "always allow" switch flipped. One parameterized
 * implementation here, paired with a single
 * [com.newoether.agora.ui.components.ToolConfirmationDialog] composable, is the whole
 * abstraction now.
 *
 * A controller instance owns:
 *  - a single pending-confirmation [StateFlow] the UI observes to know whether (and what)
 *    to show a dialog for;
 *  - the on/off "confirm before doing this" kill switch, via [confirmEnabled]/[setConfirmEnabled];
 *  - an optional, per-[Key] "always allow this one" trust list, via [isKeyAlwaysAllowed]/
 *    [setKeyAlwaysAllowed] — leave these at their defaults for tools with no meaningful
 *    per-key scoping (location, calendar, contacts, alarms), supply them for tools scoped
 *    to a server or app (shell servers, MCP servers, notification-sending apps).
 *
 * [Key] should be a stable identifier (server ID, package name) — never a display name,
 * since those can collide or change; pair it with a separate human-readable label
 * ([PendingConfirmation.keyLabel]) for what the dialog actually shows.
 */
class ToolConfirmationController<Key>(
    private val confirmEnabled: () -> Boolean,
    private val setConfirmEnabled: (Boolean) -> Unit,
    private val isKeyAlwaysAllowed: (Key) -> Boolean = { false },
    private val setKeyAlwaysAllowed: (Key, Boolean) -> Unit = { _, _ -> },
) {
    data class PendingConfirmation<Key>(
        val summary: String,
        val key: Key? = null,
        val keyLabel: String? = null,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pending = MutableStateFlow<PendingConfirmation<Key>?>(null)
    val pending: StateFlow<PendingConfirmation<Key>?> = _pending.asStateFlow()

    /**
     * Suspends until the user resolves the prompt; returns whether the action may proceed.
     * Short-circuits to `true`, without ever showing a dialog, if confirmation is off
     * globally, or if [key] has been individually always-allowed.
     */
    suspend fun confirm(summary: String, key: Key? = null, keyLabel: String? = null): Boolean {
        if (!confirmEnabled()) return true
        if (key != null && isKeyAlwaysAllowed(key)) return true
        val deferred = CompletableDeferred<Boolean>()
        _pending.value = PendingConfirmation(summary, key, keyLabel, deferred)
        return try {
            deferred.await()
        } finally {
            if (_pending.value?.deferred === deferred) _pending.value = null
        }
    }

    /**
     * Called by the UI to resolve a pending confirmation.
     * @param alwaysAllow turns off confirmation for this tool going forward entirely — the
     *   global kill switch, same effect as toggling it off in Settings.
     * @param alwaysAllowKey allow-lists only the pending confirmation's [PendingConfirmation.key]
     *   for this tool; future prompts for other keys are unaffected. No-ops if this controller
     *   has no key-scoped trust list, or the pending confirmation has no key.
     */
    fun resolve(allow: Boolean, alwaysAllow: Boolean = false, alwaysAllowKey: Boolean = false) {
        val pending = _pending.value ?: return
        if (allow) {
            if (alwaysAllow) setConfirmEnabled(false)
            if (alwaysAllowKey && pending.key != null) setKeyAlwaysAllowed(pending.key, true)
        }
        pending.deferred.complete(allow)
        _pending.value = null
    }
}
