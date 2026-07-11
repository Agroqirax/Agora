package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * Coordinates user confirmation of destructive MCP tool calls.
 *
 * Mirrors [ShellConfirmationController]: owns the pending-confirmation [StateFlow], the
 * per-session trust set, and the suspend/await handshake between the generation
 * pipeline (which asks, only for tools it has determined to be destructive) and the
 * UI (which answers). Non-destructive tools never reach this class at all — they run
 * immediately — and when "confirm MCP tools" is off, [confirm] always returns true.
 */
class McpConfirmationController(private val settings: SettingsRepository) {
    data class PendingMcpToolCall(
        val server: String,
        val toolName: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingMcpToolCall = MutableStateFlow<PendingMcpToolCall?>(null)
    val pendingMcpToolCall: StateFlow<PendingMcpToolCall?> = _pendingMcpToolCall.asStateFlow()

    // Servers the user chose to trust for the rest of this app session.
    private val sessionAllowedServers = Collections.synchronizedSet(mutableSetOf<String>())

    /** Suspends until the user resolves the prompt; returns whether the call may run. */
    suspend fun confirm(server: String, toolName: String, summary: String): Boolean {
        if (!settings.mcpConfirmEnabled.value) return true
        if (sessionAllowedServers.contains(server)) return true
        val deferred = CompletableDeferred<Boolean>()
        _pendingMcpToolCall.value = PendingMcpToolCall(server, toolName, summary, deferred)
        return try { deferred.await() } finally {
            if (_pendingMcpToolCall.value?.deferred === deferred) _pendingMcpToolCall.value = null
        }
    }

    /** Called by the UI to resolve a pending confirmation. */
    fun resolve(allow: Boolean, alwaysAllowServer: Boolean = false) {
        val pending = _pendingMcpToolCall.value ?: return
        if (allow && alwaysAllowServer) sessionAllowedServers.add(pending.server)
        pending.deferred.complete(allow)
        _pendingMcpToolCall.value = null
    }

    fun setEnabled(enabled: Boolean) = settings.setMcpConfirmEnabled(enabled)
}
