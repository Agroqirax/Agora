package com.newoether.agora.tool

import android.app.Application
import android.content.Intent
import android.provider.AlarmClock
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool for setting alarms and timers on the device via the platform
 * [AlarmClock] intent API. Unlike [CalendarToolProvider]/[ContactsToolProvider] this
 * doesn't touch a content provider or need a dangerous runtime permission — it hands
 * off to whatever clock app is registered to handle these intents (Google Clock,
 * Samsung Clock, etc.), the same mechanism voice assistants use.
 *
 * `set_alarm`/`set_timer` default to `skip_ui = true` so the alarm/timer is created
 * directly without opening the clock app, when the device's clock app supports it;
 * if it doesn't, Android falls back to showing the clock app's editor screen with the
 * fields pre-filled. `dismiss_alarm`/`snooze_alarm` require API 29+ (Android 10).
 *
 * All actions that create/change device state go through [confirmWrite] first, which
 * is a no-op when the user has alarm_confirm_enabled turned off. Reads (`show_alarms`)
 * are never gated.
 */
class AlarmToolProvider(private val app: Application) : ToolProvider {

    /** In-app confirmation gate for set/dismiss/snooze. Set by the owning ViewModel;
     *  null = no gate (proceed). [summary] is a human-readable description of the
     *  action shown in the confirmation dialog. */
    var confirmWrite: (suspend (summary: String) -> Boolean)? = null

    private val toolNames = setOf(
        "set_alarm", "set_timer", "show_alarms", "dismiss_alarm", "snooze_alarm"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.alarmEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "set_alarm",
                description = "Set a device alarm for a specific time of day. Asks the user to confirm before creating.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "hour" to ToolProperty("integer", "Hour in 24-hour format (0-23)."),
                        "minute" to ToolProperty("integer", "Minute (0-59)."),
                        "label" to ToolProperty("string", "Optional label/message shown with the alarm."),
                        "days" to ToolProperty(
                            "array",
                            "Optional list of days to repeat on, using three-letter codes " +
                                "(MON, TUE, WED, THU, FRI, SAT, SUN). Omit for a one-time alarm.",
                            items = ToolProperty("string", "Day code (MON-SUN).")
                        ),
                        "vibrate" to ToolProperty("boolean", "Whether the alarm should vibrate (optional).")
                    ),
                    required = listOf("hour", "minute")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "set_timer",
                description = "Start a device countdown timer. Asks the user to confirm before starting.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "seconds" to ToolProperty("integer", "Timer length in seconds."),
                        "label" to ToolProperty("string", "Optional label/message shown with the timer.")
                    ),
                    required = listOf("seconds")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "show_alarms",
                description = "Open the clock app's alarm list so the user can see what's currently scheduled.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )),
            ToolDefinition(function = ToolFunction(
                name = "dismiss_alarm",
                description = "Dismiss a currently-ringing or next-scheduled alarm. Requires Android 10+. " +
                    "Asks the user to confirm before dismissing.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "label" to ToolProperty("string", "Optional label to match a specific alarm; if omitted, dismisses the next alarm to fire.")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "snooze_alarm",
                description = "Snooze a currently-ringing alarm. Requires Android 10+ and clock-app support. " +
                    "Asks the user to confirm before snoozing.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "minutes" to ToolProperty("integer", "Snooze duration in minutes (optional; uses the clock app's default if omitted).")
                    ),
                    required = emptyList()
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.alarmEnabled) return err("disabled", "The alarm tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        return try {
            when (name) {
                "set_alarm" -> setAlarm(args)
                "set_timer" -> setTimer(args)
                "show_alarms" -> showAlarms()
                "dismiss_alarm" -> dismissAlarm(args)
                "snooze_alarm" -> snoozeAlarm(args)
                else -> err("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: Exception) {
            DebugLog.e("AlarmTool", "$name failed", e)
            err("alarm_error", e.message)
        }
    }

    // ── set_alarm ────────────────────────────────────────────

    private suspend fun setAlarm(args: Map<String, JsonElement>): String {
        val hour = intArg(args, "hour") ?: return err("invalid_argument", "hour is required and must be an integer 0-23.")
        val minute = intArg(args, "minute") ?: return err("invalid_argument", "minute is required and must be an integer 0-59.")
        if (hour !in 0..23 || minute !in 0..59) return err("invalid_argument", "hour must be 0-23 and minute 0-59.")

        val label = arg(args, "label").ifBlank { null }
        val days = stringListArg(args, "days")
        val dayCalendarConstants = days?.mapNotNull { dayCodeToCalendarConstant(it) }
        if (days != null && dayCalendarConstants != null && dayCalendarConstants.size != days.size) {
            return err("invalid_argument", "days must use three-letter codes: MON, TUE, WED, THU, FRI, SAT, SUN.")
        }
        val vibrate = boolArg(args, "vibrate")

        val timeDesc = "%02d:%02d".format(hour, minute)
        val summary = buildString {
            append("Set alarm for $timeDesc")
            if (label != null) append(" (\"$label\")")
            if (!dayCalendarConstants.isNullOrEmpty()) append(" repeating on ${days.joinToString(", ")}")
        }
        if (confirmWrite?.invoke(summary) == false) {
            return err("user_denied", "The user declined to set this alarm.")
        }

        return withContext(Dispatchers.IO) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                vibrate?.let { putExtra(AlarmClock.EXTRA_VIBRATE, it) }
                if (!dayCalendarConstants.isNullOrEmpty()) {
                    putExtra(AlarmClock.EXTRA_DAYS, ArrayList(dayCalendarConstants))
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(app.packageManager) == null) {
                return@withContext err("no_clock_app", "No clock app was found to handle this request.")
            }
            app.startActivity(intent)
            buildJsonObject {
                put("type", "set_alarm")
                put("time", timeDesc)
                put("ok", true)
            }.toString()
        }
    }

    // ── set_timer ────────────────────────────────────────────

    private suspend fun setTimer(args: Map<String, JsonElement>): String {
        val seconds = intArg(args, "seconds") ?: return err("invalid_argument", "seconds is required and must be an integer.")
        if (seconds <= 0) return err("invalid_argument", "seconds must be a positive integer.")
        val label = arg(args, "label").ifBlank { null }

        val summary = buildString {
            append("Start a ${formatDuration(seconds)} timer")
            if (label != null) append(" (\"$label\")")
        }
        if (confirmWrite?.invoke(summary) == false) {
            return err("user_denied", "The user declined to start this timer.")
        }

        return withContext(Dispatchers.IO) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(app.packageManager) == null) {
                return@withContext err("no_clock_app", "No clock app was found to handle this request.")
            }
            app.startActivity(intent)
            buildJsonObject {
                put("type", "set_timer")
                put("seconds", seconds)
                put("ok", true)
            }.toString()
        }
    }

    // ── show_alarms ──────────────────────────────────────────

    private suspend fun showAlarms(): String = withContext(Dispatchers.IO) {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(app.packageManager) == null) {
            return@withContext err("no_clock_app", "No clock app was found to handle this request.")
        }
        app.startActivity(intent)
        buildJsonObject { put("type", "show_alarms"); put("ok", true) }.toString()
    }

    // ── dismiss_alarm ────────────────────────────────────────

    private suspend fun dismissAlarm(args: Map<String, JsonElement>): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return err("unsupported", "Dismissing alarms requires Android 10 or newer.")
        }
        val label = arg(args, "label").ifBlank { null }
        val summary = if (label != null) "Dismiss alarm \"$label\"" else "Dismiss the next scheduled alarm"
        if (confirmWrite?.invoke(summary) == false) {
            return err("user_denied", "The user declined to dismiss this alarm.")
        }

        return withContext(Dispatchers.IO) {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                if (label != null) {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                } else {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(app.packageManager) == null) {
                return@withContext err("no_clock_app", "No clock app was found to handle this request.")
            }
            app.startActivity(intent)
            buildJsonObject { put("type", "dismiss_alarm"); put("ok", true) }.toString()
        }
    }

    // ── snooze_alarm ─────────────────────────────────────────

    private suspend fun snoozeAlarm(args: Map<String, JsonElement>): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return err("unsupported", "Snoozing alarms requires Android 10 or newer.")
        }
        val minutes = intArg(args, "minutes")
        val summary = if (minutes != null) "Snooze alarm for $minutes minute(s)" else "Snooze the ringing alarm"
        if (confirmWrite?.invoke(summary) == false) {
            return err("user_denied", "The user declined to snooze this alarm.")
        }

        return withContext(Dispatchers.IO) {
            val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
                minutes?.let { putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(app.packageManager) == null) {
                return@withContext err("no_clock_app", "No clock app was found to handle this request.")
            }
            app.startActivity(intent)
            buildJsonObject { put("type", "snooze_alarm"); put("ok", true) }.toString()
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun dayCodeToCalendarConstant(code: String): Int? = when (code.trim().uppercase()) {
        "MON" -> java.util.Calendar.MONDAY
        "TUE" -> java.util.Calendar.TUESDAY
        "WED" -> java.util.Calendar.WEDNESDAY
        "THU" -> java.util.Calendar.THURSDAY
        "FRI" -> java.util.Calendar.FRIDAY
        "SAT" -> java.util.Calendar.SATURDAY
        "SUN" -> java.util.Calendar.SUNDAY
        else -> null
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return buildList {
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0 || isEmpty()) add("${s}s")
        }.joinToString(" ")
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    private fun intArg(args: Map<String, JsonElement>, key: String): Int? =
        (args[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun boolArg(args: Map<String, JsonElement>, key: String): Boolean? =
        (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun stringListArg(args: Map<String, JsonElement>, key: String): List<String>? {
        val el = args[key] ?: return null
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "alarm")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
