package com.newoether.agora.tool

import android.app.Application
import android.location.Location
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool that gives the model current conditions + a multi-day forecast for a location,
 * via [Open-Meteo](https://open-meteo.com) — a free, keyless weather API. Also used for
 * geocoding free-text place names ([DEFAULT_GEOCODING_BASE_URL]).
 *
 * The model can pick a location three ways, cheapest/most-specific first:
 *  1. Explicit `latitude`/`longitude` (e.g. it already has coordinates from [LocationToolProvider]).
 *  2. A free-text `location` (city, address, postcode, ...), resolved via Open-Meteo's
 *     geocoding endpoint — no device data touched at all.
 *  3. Nothing at all — auto-detect the device's location via [DeviceLocationSource], the
 *     same fetch mechanics [LocationToolProvider] uses (fine if granted and available,
 *     else coarse), rather than a separate no-permission fallback.
 *
 * [requestPermission] and [confirm] are set by the owning ViewModel and mirror
 * [LocationToolProvider]'s fields one-for-one — they're commonly wired to literally the
 * same callbacks (same Android permission, same in-app share-location prompt, and, within
 * a single model turn, the same *answer*: see [com.newoether.agora.viewmodel.GenerationManager.confirmLocationShared]
 * for why a turn that calls both `get_location` and `get_weather` only prompts once).
 * `null` means "proceed" for [confirm].
 *
 * Open-Meteo happens to be self-hostable (they publish a Docker image), so — mirroring
 * [LocationToolProvider]'s Nominatim override — both endpoints are configurable via
 * [GenerationContext.weatherBaseUrl]/[GenerationContext.weatherGeocodingBaseUrl] rather
 * than hardcoded, even though most users will never touch them.
 */
class WeatherToolProvider(private val app: Application) : ToolProvider {

    var requestPermission: (suspend () -> Boolean)? = null
    var confirm: (suspend () -> Boolean)? = null
    private val deviceLocation = DeviceLocationSource(app)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.weatherEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = GET_WEATHER,
                description = "Get current conditions and a multi-day forecast for a location. Pass either " +
                    "`location` (a free-text place name, address, or postcode) or explicit `latitude`/" +
                    "`longitude`. If none of these are given, it uses the device's current location " +
                    "instead — this requires the user's location permission and may prompt them.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "location" to ToolProperty(
                            type = "string",
                            description = "Free-text place to look up, e.g. \"Paris, France\", \"Tokyo\", " +
                                "\"90210\". Omit to auto-detect the device's location, or pass latitude/" +
                                "longitude directly instead."
                        ),
                        "latitude" to ToolProperty(
                            type = "number",
                            description = "Latitude, if already known. Must be given together with longitude."
                        ),
                        "longitude" to ToolProperty(
                            type = "number",
                            description = "Longitude, if already known. Must be given together with latitude."
                        ),
                        "forecast_days" to ToolProperty(
                            type = "integer",
                            description = "Number of forecast days to include, 1-16. Defaults to 3."
                        ),
                        "units" to ToolProperty(
                            type = "string",
                            description = "`metric` (Celsius, km/h, mm) or `imperial` (Fahrenheit, mph, inch). " +
                                "Defaults to the user's configured preference.",
                            enum = listOf("metric", "imperial")
                        )
                    ),
                    required = emptyList()
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == GET_WEATHER

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.weatherEnabled) return err("disabled", "The weather tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        val argLat = (args["latitude"] as? JsonPrimitive)?.doubleOrNull
        val argLon = (args["longitude"] as? JsonPrimitive)?.doubleOrNull
        val argLocation = (args["location"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val forecastDays = ((args["forecast_days"] as? JsonPrimitive)?.intOrNull ?: 3).coerceIn(1, 16)
        val units = (args["units"] as? JsonPrimitive)?.content?.takeIf { it == "metric" || it == "imperial" }
            ?: ctx.weatherUnits

        return try {
            val resolved = when {
                argLat != null && argLon != null -> ResolvedLocation(argLat, argLon, null)
                argLocation != null -> geocode(argLocation, ctx)
                    ?: return err("location_not_found", "Couldn't find a location matching \"$argLocation\".")
                else -> when (val result = resolveDeviceLocation()) {
                    is DeviceLocationResult.Found -> ResolvedLocation(result.location.latitude, result.location.longitude, null)
                    is DeviceLocationResult.Failed -> return result.errorJson
                }
            }
            fetchForecast(resolved, forecastDays, units, ctx)
        } catch (e: Exception) {
            DebugLog.e("WeatherTool", "$name failed", e)
            err("weather_error", e.message)
        }
    }

    // ── Location resolution ────────────────────────────────────

    private data class ResolvedLocation(val latitude: Double, val longitude: Double, val label: String?)

    private sealed class DeviceLocationResult {
        data class Found(val location: Location) : DeviceLocationResult()
        data class Failed(val errorJson: String) : DeviceLocationResult()
    }

    private suspend fun geocode(query: String, ctx: GenerationContext): ResolvedLocation? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = ctx.weatherGeocodingBaseUrl.ifBlank { DEFAULT_GEOCODING_BASE_URL }
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "${baseUrl.trimEnd('/')}/v1/search?name=$encoded&count=1&language=en&format=json"
            val response = HttpClient.fetchModels(url, USER_AGENT_HEADER) ?: return@withContext null
            val result = Json.parseToJsonElement(response).jsonObject["results"]
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return@withContext null
            val lat = result["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
            val lon = result["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
            val place = result["name"]?.jsonPrimitive?.content
            val admin1 = result["admin1"]?.jsonPrimitive?.content
            val country = result["country"]?.jsonPrimitive?.content
            val label = listOfNotNull(place, admin1, country).distinct().joinToString(", ")
            ResolvedLocation(lat, lon, label.ifBlank { null })
        } catch (e: Exception) {
            DebugLog.e("WeatherTool", "geocode failed", e)
            null
        }
    }

    /** Same permission-request → confirm → fetch flow as [LocationToolProvider.execute],
     *  returning which of permission_denied / user_denied / unavailable applies on failure
     *  rather than a bare null, so the caller can report the specific reason. The fetch
     *  mechanics themselves are shared via [DeviceLocationSource]; this method is just the
     *  weather-tool-specific error mapping around it. */
    private suspend fun resolveDeviceLocation(): DeviceLocationResult {
        if (!deviceLocation.hasAnyPermission()) {
            val granted = requestPermission?.invoke() ?: false
            if (!granted || !deviceLocation.hasAnyPermission()) {
                return DeviceLocationResult.Failed(err("permission_denied", "Location permission was not granted."))
            }
        }

        val allowed = confirm?.invoke() ?: true
        if (!allowed) {
            return DeviceLocationResult.Failed(err("user_denied", "The user declined to share their location."))
        }

        val location = deviceLocation.fetch()
            ?: return DeviceLocationResult.Failed(err("unavailable", "Could not determine the current location."))
        return DeviceLocationResult.Found(location)
    }

    // ── Forecast fetch ─────────────────────────────────────────

    private suspend fun fetchForecast(
        location: ResolvedLocation,
        forecastDays: Int,
        units: String,
        ctx: GenerationContext
    ): String = withContext(Dispatchers.IO) {
        val baseUrl = ctx.weatherBaseUrl.ifBlank { DEFAULT_FORECAST_BASE_URL }
        val tempUnit = if (units == "imperial") "fahrenheit" else "celsius"
        val windUnit = if (units == "imperial") "mph" else "kmh"
        val precipUnit = if (units == "imperial") "inch" else "mm"
        val url = "${baseUrl.trimEnd('/')}/v1/forecast" +
            "?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current_weather=true" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum" +
            "&temperature_unit=$tempUnit&windspeed_unit=$windUnit&precipitation_unit=$precipUnit" +
            "&timezone=auto&forecast_days=$forecastDays"

        val response = HttpClient.fetchModels(url, USER_AGENT_HEADER)
            ?: return@withContext err("network_error", "Couldn't reach the weather service.")

        val root = try {
            Json.parseToJsonElement(response).jsonObject
        } catch (e: Exception) {
            return@withContext err("parse_error", "Couldn't parse the weather response.")
        }

        buildJsonObject {
            put("type", GET_WEATHER)
            location.label?.let { put("location", it) }
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("units", units)
            root["timezone"]?.jsonPrimitive?.content?.let { put("timezone", it) }

            root["current_weather"]?.jsonObject?.let { current ->
                put("current", buildJsonObject {
                    current["temperature"]?.jsonPrimitive?.doubleOrNull?.let { put("temperature", it) }
                    current["windspeed"]?.jsonPrimitive?.doubleOrNull?.let { put("wind_speed", it) }
                    current["winddirection"]?.jsonPrimitive?.doubleOrNull?.let { put("wind_direction_degrees", it) }
                    current["is_day"]?.jsonPrimitive?.intOrNull?.let { put("is_day", it == 1) }
                    current["weathercode"]?.jsonPrimitive?.intOrNull?.let {
                        put("condition", weatherCodeDescription(it))
                    }
                })
            }

            root["daily"]?.jsonObject?.let { daily ->
                val dates = daily["time"]?.jsonArray ?: buildJsonArray {}
                val codes = daily["weathercode"]?.jsonArray
                val maxTemps = daily["temperature_2m_max"]?.jsonArray
                val minTemps = daily["temperature_2m_min"]?.jsonArray
                val precip = daily["precipitation_sum"]?.jsonArray
                put("daily_forecast", buildJsonArray {
                    for (i in dates.indices) {
                        add(buildJsonObject {
                            put("date", dates[i].jsonPrimitive.content)
                            codes?.getOrNull(i)?.jsonPrimitive?.intOrNull?.let { put("condition", weatherCodeDescription(it)) }
                            maxTemps?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.let { put("temperature_max", it) }
                            minTemps?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.let { put("temperature_min", it) }
                            precip?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.let { put("precipitation_sum", it) }
                        })
                    }
                })
            }
        }.toString()
    }

    /** Maps Open-Meteo's WMO weather-interpretation codes to short human descriptions.
     *  See https://open-meteo.com/en/docs (WMO Weather interpretation codes table). */
    private fun weatherCodeDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Depositing rime fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56 -> "Light freezing drizzle"
        57 -> "Dense freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Light freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Slight snow fall"
        73 -> "Moderate snow fall"
        75 -> "Heavy snow fall"
        77 -> "Snow grains"
        80 -> "Slight rain showers"
        81 -> "Moderate rain showers"
        82 -> "Violent rain showers"
        85 -> "Slight snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with slight hail"
        99 -> "Thunderstorm with heavy hail"
        else -> "Unknown"
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", GET_WEATHER)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val GET_WEATHER = "get_weather"
        const val DEFAULT_FORECAST_BASE_URL = "https://api.open-meteo.com"
        const val DEFAULT_GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com"
        private val USER_AGENT_HEADER = mapOf("User-Agent" to "Agora/1.0 (+https://github.com/newo-ether/Agora)")
    }
}
