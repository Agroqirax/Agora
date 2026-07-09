package com.newoether.agora.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges a tool running in the generation pipeline (off the UI thread) to the
 * Activity's runtime permission launcher, which can only be invoked from a
 * Composable/Activity. The pending flag tells the UI to launch the system
 * permission request for [permissions]; [resolve] reports back whether it was
 * (at least partially, see [anyGranted]) granted.
 *
 * One instance per permission group — location, calendar, contacts — each with
 * its own manifest permissions and its own pending-request flow, so a prompt
 * for one tool never gets confused with another's.
 */
class RuntimePermissionController(val permissions: Array<String>) {
    private val _pendingRequest = MutableStateFlow<CompletableDeferred<Boolean>?>(null)
    val pendingRequest: StateFlow<CompletableDeferred<Boolean>?> = _pendingRequest.asStateFlow()

    /** True if any of [permissions] is already granted (calendar/contacts have one
     *  permission per group so this is exact; location's fine/coarse pair means
     *  "at least coarse" is enough to proceed). */
    fun anyGranted(context: Context): Boolean =
        permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Requests the system permission dialog only if not already granted. Used when a
     *  setting is toggled on, so re-enabling an already-granted tool is silent. */
    suspend fun requestIfNeeded(context: Context): Boolean {
        if (anyGranted(context)) return true
        return request()
    }

    /** Suspends until the UI reports the permission result. */
    suspend fun request(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _pendingRequest.value = deferred
        return try { deferred.await() } finally {
            if (_pendingRequest.value === deferred) _pendingRequest.value = null
        }
    }

    /** Called by the UI once the system permission dialog has been answered. */
    fun resolve(granted: Boolean) {
        _pendingRequest.value?.complete(granted)
        _pendingRequest.value = null
    }
}
