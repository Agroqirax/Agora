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
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * Tool that reports the device's current location to the model. No Google Play
 * Services dependency — uses the platform [LocationManager] directly (GPS/network
 * providers), which keeps the app usable on de-Googled / F-Droid builds.
 *
 * Two independent gates run before any location is read:
 *  - [requestPermission]: triggers the OS runtime-permission dialog when neither
 *    ACCESS_FINE_LOCATION nor ACCESS_COARSE_LOCATION is granted yet.
 *  - [confirm]: the in-app "share your location?" prompt, shown only when the
 *    user has location_confirm_enabled turned on in settings.
 * Both are set by the owning ViewModel; null means "proceed" (no gate).
 *
 * The best-effort street address is sourced from a Nominatim (OpenStreetMap) reverse
 * geocoding endpoint rather than the on-device [android.location.Geocoder], whose backend
 * is absent on many de-Googled ROMs. This is gated by [GenerationContext.locationReverseGeocodeEnabled]
 * and points at [GenerationContext.locationNominatimBaseUrl] (the public OSM instance by default,
 * or a self-hosted/alternate instance the user configures in settings).
 */
class LocationToolProvider(private val app: Application) : ToolProvider {

    var confirm: (suspend () -> Boolean)? = null
    var requestPermission: (suspend () -> Boolean)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.locationEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "get_location",
                description = "Get the user's current geographic location (latitude, longitude, accuracy, and, if enabled, a best-effort street address reverse-geocoded via Nominatim/OpenStreetMap). Prefer the highest-accuracy fix the device can provide. Requires the user's permission and may prompt them.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            ))
        )
    }

    override fun handles(name: String): Boolean = name == "get_location"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.locationEnabled) return err("disabled", "The location tool is disabled in settings.")

        if (!hasAnyLocationPermission()) {
            val granted = requestPermission?.invoke() ?: false
            if (!granted || !hasAnyLocationPermission()) {
                return err("permission_denied", "Location permission was not granted.")
            }
        }

        val allowed = confirm?.invoke() ?: true
        if (!allowed) return err("user_denied", "The user declined to share their location.")

        return try {
            val location = fetchLocation() ?: return err("unavailable", "Could not determine the current location.")
            buildJsonObject {
                put("type", "location")
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                if (location.hasAccuracy()) put("accuracy_meters", location.accuracy.toDouble())
                put("provider", location.provider ?: "unknown")
                put("timestamp", location.time)
                if (ctx.locationReverseGeocodeEnabled) {
                    val baseUrl = ctx.locationNominatimBaseUrl.ifBlank {
                        com.newoether.agora.data.SettingsManager.DEFAULT_NOMINATIM_BASE_URL
                    }
                    reverseGeocode(location.latitude, location.longitude, baseUrl)?.let { put("address", it) }
                }
            }.toString()
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            DebugLog.e("LocationTool", "get_location failed", e)
            err("location_error", e.message)
        }
    }

    private fun hasFine() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarse() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocationPermission() = hasFine() || hasCoarse()

    /** Fine (GPS) when granted and available, else coarse (network). Falls back to the
     *  freshest last-known fix if a live update times out or no provider is enabled. */
    @SuppressLint("MissingPermission") // permission state is checked by the caller before this runs
    private suspend fun fetchLocation(): Location? {
        val lm = app.getSystemService(Application.LOCATION_SERVICE) as? LocationManager ?: return null

        val provider = when {
            hasFine() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        val fresh = provider?.let { withTimeoutOrNull(15_000L) { requestSingleUpdate(lm, it) } }
        return fresh ?: lastKnownAnyProvider(lm)
    }

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

    /** Best-effort street address via Nominatim's `/reverse` endpoint. Network call, run
     *  off the main thread; failures (offline, self-hosted instance down, rate-limited,
     *  malformed response) are silently swallowed since the address is a nice-to-have —
     *  the coordinates are already returned regardless. */
    private suspend fun reverseGeocode(lat: Double, lon: Double, baseUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=18&addressdetails=1"
            // Nominatim's usage policy requires a descriptive User-Agent identifying the app.
            val response = HttpClient.fetchModels(url, mapOf("User-Agent" to "Agora/1.0 (+https://github.com/newo-ether/Agora)"))
                ?: return@withContext null
            (Json.parseToJsonElement(response).jsonObject["display_name"] as? JsonPrimitive)?.content
        } catch (_: Exception) { null }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "location")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
