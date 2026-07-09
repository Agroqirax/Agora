package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates user confirmation before the location tool reads and shares the
 * device's current location with the model. Mirrors [ShellConfirmationController]'s
 * suspend/await handshake between the generation pipeline (which asks) and the UI
 * (which answers), simplified since there's only one location source (no per-server
 * trust set).
 */
class LocationConfirmationController(private val settings: SettingsRepository) {
    data class PendingLocationRequest(val deferred: CompletableDeferred<Boolean>)

    private val _pendingLocationRequest = MutableStateFlow<PendingLocationRequest?>(null)
    val pendingLocationRequest: StateFlow<PendingLocationRequest?> = _pendingLocationRequest.asStateFlow()

    /** Suspends until the user resolves the prompt; returns whether the location may be read. */
    suspend fun confirm(): Boolean {
        if (!settings.locationConfirmEnabled.value) return true
        val deferred = CompletableDeferred<Boolean>()
        _pendingLocationRequest.value = PendingLocationRequest(deferred)
        return try { deferred.await() } finally {
            if (_pendingLocationRequest.value?.deferred === deferred) _pendingLocationRequest.value = null
        }
    }

    /** Called by the UI to resolve a pending confirmation. */
    fun resolve(allow: Boolean) {
        val pending = _pendingLocationRequest.value ?: return
        pending.deferred.complete(allow)
        _pendingLocationRequest.value = null
    }
}
