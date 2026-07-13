package com.newoether.agora.tool

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reports live device state to the model in one shot: battery, ringer mode, network
 * connectivity, free storage, and basic device/locale facts. Deliberately a single
 * combined tool rather than one-per-field — none of these individually cost enough (no
 * network round-trip, no heavy computation, no per-field permission) to justify a
 * separate tool call and separate confirmation/registration overhead each.
 *
 * Everything here reads from APIs that require no runtime permission and no special
 * declared-use justification (battery/ringer/storage/build info are permission-free;
 * ACCESS_NETWORK_STATE is a normal, install-time-only permission) — so unlike
 * [LocationToolProvider] or the calendar/contacts tools, this has no confirm/permission
 * gate and no per-user settings beyond a single enable toggle.
 */
class DeviceInfoToolProvider(private val app: Application) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.deviceInfoEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "get_device_info",
                description = "Get the current state of the user's device: battery level/charging state/charge method, ringer mode (normal/vibrate/silent), network connectivity type, free/total internal storage, current time and timezone, and basic device facts (model, manufacturer, Android version, locale). No parameters.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            ))
        )
    }

    override fun handles(name: String): Boolean = name == "get_device_info"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.deviceInfoEnabled) return err("disabled", "The device info tool is disabled in settings.")
        return try {
            buildJsonObject {
                put("type", "device_info")
                putBattery(this)
                putRingerMode(this)
                putNetwork(this)
                putStorage(this)
                putDeviceFacts(this)
            }.toString()
        } catch (e: Exception) {
            DebugLog.e("DeviceInfoTool", "get_device_info failed", e)
            err("device_info_error", e.message)
        }
    }

    private fun putBattery(obj: kotlinx.serialization.json.JsonObjectBuilder) {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = app.getSystemService(Application.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: sticky?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && scale > 0) (l * 100) / scale else null
            }
        level?.let { obj.put("battery_level_percent", it) }

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        obj.put("battery_charging", isCharging)
        obj.put("battery_status", when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        })

        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        obj.put("battery_charge_method", when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        })

        sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let {
            obj.put("battery_temperature_celsius", it / 10.0)
        }
    }

    private fun putRingerMode(obj: kotlinx.serialization.json.JsonObjectBuilder) {
        val am = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager
        obj.put("ringer_mode", when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown"
        })
    }

    private fun putNetwork(obj: kotlinx.serialization.json.JsonObjectBuilder) {
        val cm = app.getSystemService(Application.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        obj.put("network_type", type)
        obj.put("network_connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    private fun putStorage(obj: kotlinx.serialization.json.JsonObjectBuilder) {
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            obj.put("storage_free_bytes", stat.availableBytes)
            obj.put("storage_total_bytes", stat.totalBytes)
        } catch (_: Exception) {
            // Some restricted environments (rare) can't stat this path — omit rather than fail the whole tool.
        }
    }

    private fun putDeviceFacts(obj: kotlinx.serialization.json.JsonObjectBuilder) {
        obj.put("device_model", Build.MODEL ?: "unknown")
        obj.put("device_manufacturer", Build.MANUFACTURER ?: "unknown")
        obj.put("android_version", Build.VERSION.RELEASE ?: "unknown")
        obj.put("android_sdk_int", Build.VERSION.SDK_INT)
        obj.put("locale", Locale.getDefault().toLanguageTag())
        val tz = TimeZone.getDefault()
        obj.put("timezone", tz.id)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = tz }
        obj.put("current_time", sdf.format(java.util.Date()))
        obj.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "device_info")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
