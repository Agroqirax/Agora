package com.newoether.agora.tool

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The device-location-fetch mechanics shared by [LocationToolProvider] and
 * [WeatherToolProvider]'s device-location auto-detect path: same GPS-then-network-then-
 * last-known strategy, same 15-second timeout, same [LocationListener] boilerplate. This
 * used to be ~60 lines duplicated verbatim in both files (including the diverging
 * comment on [WeatherToolProvider] explaining the duplication was deliberate); past a
 * certain size that tradeoff stops being worth it, so it's a single small class both tools
 * hold an instance of and delegate to.
 *
 * Deliberately narrow: this owns *only* the permission check + fetch mechanics, not the
 * `confirm`/`requestPermission` gating (each tool wires those to the ViewModel itself) or
 * error-code mapping (e.g. [WeatherToolProvider]'s richer `DeviceLocationResult` sealed
 * class stays there — it's a presentation concern, not a fetch-mechanics one).
 */
class DeviceLocationSource(private val app: Application) {

    fun hasAnyPermission(): Boolean = hasFine() || hasCoarse()

    /** Fine (GPS) when granted and available, else coarse (network). Falls back to the
     *  freshest last-known fix if a live update times out or no provider is enabled.
     *  Returns null if permission was never checked by the caller and turns out to be
     *  missing, or if no provider yields a fix at all. */
    @SuppressLint("MissingPermission") // permission state is the caller's responsibility
    suspend fun fetch(): Location? {
        val lm = app.getSystemService(Application.LOCATION_SERVICE) as? LocationManager ?: return null

        val provider = when {
            hasFine() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        val fresh = provider?.let { withTimeoutOrNull(15_000L) { requestSingleUpdate(lm, it) } }
        return fresh ?: lastKnownAnyProvider(lm)
    }

    private fun hasFine() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarse() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnownAnyProvider(lm: LocationManager): Location? {
        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { hasFine() },
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Deprecated in Java, part of the LocationListener interface")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
