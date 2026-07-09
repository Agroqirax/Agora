package com.newoether.agora.tool

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
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
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tool for reading and writing device calendar events via the platform
 * [CalendarContract] content provider. No Google Play Services dependency — this
 * talks to whatever calendar provider/accounts are registered on the device
 * (local, Google, Fastmail/CalDAV via DAVx5, etc.), consistent with the location
 * tool's no-Play-Services approach.
 *
 * Reads (`list_calendars`, `get_calendar_events`) are never gated beyond the
 * runtime permission check — once the user has granted calendar access, the
 * model can look. Writes (`create_calendar_event`, `update_calendar_event`,
 * `delete_calendar_event`) additionally go through [confirmWrite], which is a
 * no-op when the user has calendar_confirm_enabled turned off.
 */
class CalendarToolProvider(private val app: Application) : ToolProvider {

    /** In-app confirmation gate for create/update/delete. Set by the owning ViewModel;
     *  null = no gate (proceed). [summary] is a human-readable description of the write
     *  shown in the confirmation dialog. */
    var confirmWrite: (suspend (summary: String) -> Boolean)? = null

    /** Runtime permission gate (READ_CALENDAR / WRITE_CALENDAR). Set by the owning
     *  ViewModel; triggers the system permission dialog and returns whether granted. */
    var requestPermission: (suspend () -> Boolean)? = null

    private val resolver get() = app.contentResolver

    private val toolNames = setOf(
        "list_calendars", "get_calendar_events",
        "create_calendar_event", "update_calendar_event", "delete_calendar_event"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.calendarEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "list_calendars",
                description = "List the calendars available on this device (id, name, account, and whether events can be created on it).",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )),
            ToolDefinition(function = ToolFunction(
                name = "get_calendar_events",
                description = "Get calendar events in a time range. Recurring events are expanded into individual occurrences.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "start_time" to ToolProperty("string", "Start of the range, ISO-8601 (e.g. 2026-07-08T00:00:00Z)."),
                        "end_time" to ToolProperty("string", "End of the range, ISO-8601."),
                        "query" to ToolProperty("string", "Optional substring to filter by event title."),
                        "calendar_id" to ToolProperty("string", "Optional calendar id to restrict the search to (see list_calendars).")
                    ),
                    required = listOf("start_time", "end_time")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "create_calendar_event",
                description = "Create a new calendar event. Asks the user to confirm before creating.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Event title."),
                        "start_time" to ToolProperty("string", "Start time, ISO-8601."),
                        "end_time" to ToolProperty("string", "End time, ISO-8601. Required unless all_day is true."),
                        "all_day" to ToolProperty("boolean", "Whether this is an all-day event (optional, default false)."),
                        "location" to ToolProperty("string", "Event location (optional)."),
                        "description" to ToolProperty("string", "Event notes/description (optional)."),
                        "calendar_id" to ToolProperty("string", "Calendar to create the event on (optional, defaults to the device's primary writable calendar).")
                    ),
                    required = listOf("title", "start_time")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "update_calendar_event",
                description = "Update fields of an existing calendar event. Only provided fields are changed. Asks the user to confirm before applying.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "event_id" to ToolProperty("string", "Id of the event to update (see get_calendar_events)."),
                        "title" to ToolProperty("string", "New title (optional)."),
                        "start_time" to ToolProperty("string", "New start time, ISO-8601 (optional)."),
                        "end_time" to ToolProperty("string", "New end time, ISO-8601 (optional)."),
                        "location" to ToolProperty("string", "New location (optional)."),
                        "description" to ToolProperty("string", "New notes/description (optional).")
                    ),
                    required = listOf("event_id")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "delete_calendar_event",
                description = "Delete a calendar event. Asks the user to confirm before deleting.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "event_id" to ToolProperty("string", "Id of the event to delete (see get_calendar_events).")
                    ),
                    required = listOf("event_id")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.calendarEnabled) return err("disabled", "The calendar tool is disabled in settings.")

        if (!hasCalendarPermission()) {
            val granted = requestPermission?.invoke() ?: false
            if (!granted || !hasCalendarPermission()) {
                return err("permission_denied", "Calendar permission was not granted.")
            }
        }

        val args = parseToolArgs(arguments)
        return try {
            when (name) {
                "list_calendars" -> listCalendars()
                "get_calendar_events" -> getEvents(args)
                "create_calendar_event" -> createEvent(args)
                "update_calendar_event" -> updateEvent(args)
                "delete_calendar_event" -> deleteEvent(args)
                else -> err("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            DebugLog.e("CalendarTool", "$name failed", e)
            err("calendar_error", e.message)
        }
    }

    private fun hasCalendarPermission() = hasPermission(android.Manifest.permission.READ_CALENDAR)
    private fun hasWritePermission() = hasPermission(android.Manifest.permission.WRITE_CALENDAR)

    private fun hasPermission(perm: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(app, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── list_calendars ──────────────────────────────────────

    private suspend fun listCalendars(): String = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val calendars = mutableListOf<kotlinx.serialization.json.JsonObject>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val accessLevel = c.getInt(4)
                calendars.add(buildJsonObject {
                    put("id", c.getLong(0).toString())
                    put("name", c.getString(1) ?: "")
                    put("account", c.getString(2) ?: "")
                    put("is_primary", c.getInt(3) == 1)
                    put("writable", accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                })
            }
        }
        buildJsonObject {
            put("type", "list_calendars")
            putJsonArray("calendars") { calendars.forEach { add(it) } }
        }.toString()
    }

    /** Primary/first writable calendar, used when create_calendar_event omits calendar_id. */
    private fun defaultWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, selectionArgs, null)?.use { c ->
            var firstId: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (firstId == null) firstId = id
                if (c.getInt(1) == 1) return id
            }
            return firstId
        }
        return null
    }

    // ── get_calendar_events ─────────────────────────────────

    private suspend fun getEvents(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val startMs = parseInstant(arg(args, "start_time"))
            ?: return@withContext err("invalid_argument", "start_time must be a valid ISO-8601 date-time.")
        val endMs = parseInstant(arg(args, "end_time"))
            ?: return@withContext err("invalid_argument", "end_time must be a valid ISO-8601 date-time.")
        val query = arg(args, "query").lowercase()
        val calendarId = arg(args, "calendar_id").toLongOrNull()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_ID
        )
        val selection = calendarId?.let { "${CalendarContract.Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }

        val events = mutableListOf<kotlinx.serialization.json.JsonObject>()
        resolver.query(builder.build(), projection, selection, selectionArgs, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1) ?: ""
                if (query.isNotBlank() && !title.lowercase().contains(query)) continue
                events.add(buildJsonObject {
                    put("event_id", c.getLong(0).toString())
                    put("title", title)
                    put("start_time", isoUtc(c.getLong(2)))
                    put("end_time", isoUtc(c.getLong(3)))
                    put("all_day", c.getInt(4) == 1)
                    put("location", c.getString(5) ?: "")
                    put("description", c.getString(6) ?: "")
                    put("calendar_id", c.getLong(7).toString())
                })
            }
        }
        buildJsonObject {
            put("type", "get_calendar_events")
            putJsonArray("events") { events.forEach { add(it) } }
        }.toString()
    }

    // ── create_calendar_event ───────────────────────────────

    private suspend fun createEvent(args: Map<String, JsonElement>): String {
        val title = arg(args, "title")
        if (title.isBlank()) return err("invalid_argument", "title is required")
        val allDay = arg(args, "all_day").equals("true", ignoreCase = true)
        val startMs = parseInstant(arg(args, "start_time"))
            ?: return err("invalid_argument", "start_time must be a valid ISO-8601 date-time.")
        val endMs = if (allDay) startMs else {
            parseInstant(arg(args, "end_time"))
                ?: return err("invalid_argument", "end_time is required (unless all_day) and must be ISO-8601.")
        }
        val location = arg(args, "location")
        val description = arg(args, "description")
        val calendarId = arg(args, "calendar_id").toLongOrNull()
            ?: withContext(Dispatchers.IO) { defaultWritableCalendarId() }
            ?: return err("no_writable_calendar", "No writable calendar is available on this device.")

        if (!hasWritePermission()) return err("permission_denied", "Write-calendar permission was not granted.")

        val range = if (allDay) "on ${isoUtc(startMs)}"
            else "from ${isoUtc(startMs)} to ${isoUtc(endMs)}"
        if (confirmWrite?.invoke("Create \"$title\" $range") == false) {
            return err("user_denied", "The user declined to create this event.")
        }

        return withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID())
                if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@withContext err("write_failed", "Could not create the event.")
            buildJsonObject {
                put("type", "create_calendar_event")
                put("event_id", ContentUris.parseId(uri).toString())
                put("ok", true)
            }.toString()
        }
    }

    // ── update_calendar_event ───────────────────────────────

    private suspend fun updateEvent(args: Map<String, JsonElement>): String {
        val eventId = arg(args, "event_id").toLongOrNull()
            ?: return err("invalid_argument", "event_id is required and must be numeric.")

        val current = fetchEventSummary(eventId) ?: return err("not_found", "No event with id $eventId.")

        val newTitle = arg(args, "title").ifBlank { null }
        val newStartMs = arg(args, "start_time").ifBlank { null }?.let {
            parseInstant(it) ?: return err("invalid_argument", "start_time must be a valid ISO-8601 date-time.")
        }
        val newEndMs = arg(args, "end_time").ifBlank { null }?.let {
            parseInstant(it) ?: return err("invalid_argument", "end_time must be a valid ISO-8601 date-time.")
        }
        val newLocation = if (args.containsKey("location")) arg(args, "location") else null
        val newDescription = if (args.containsKey("description")) arg(args, "description") else null

        val changes = buildList {
            if (newTitle != null) add("title -> \"$newTitle\"")
            if (newStartMs != null) add("start -> ${isoUtc(newStartMs)}")
            if (newEndMs != null) add("end -> ${isoUtc(newEndMs)}")
            if (newLocation != null) add("location -> \"$newLocation\"")
            if (newDescription != null) add("description updated")
        }
        if (changes.isEmpty()) return err("invalid_argument", "No fields to update were provided.")
        if (!hasWritePermission()) return err("permission_denied", "Write-calendar permission was not granted.")

        if (confirmWrite?.invoke("Update \"${current.first}\": ${changes.joinToString(", ")}") == false) {
            return err("user_denied", "The user declined to update this event.")
        }

        return withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                newTitle?.let { put(CalendarContract.Events.TITLE, it) }
                newStartMs?.let { put(CalendarContract.Events.DTSTART, it) }
                newEndMs?.let { put(CalendarContract.Events.DTEND, it) }
                newLocation?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                newDescription?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = resolver.update(uri, values, null, null)
            if (rows == 0) return@withContext err("write_failed", "Could not update the event.")
            buildJsonObject { put("type", "update_calendar_event"); put("event_id", eventId.toString()); put("ok", true) }.toString()
        }
    }

    // ── delete_calendar_event ───────────────────────────────

    private suspend fun deleteEvent(args: Map<String, JsonElement>): String {
        val eventId = arg(args, "event_id").toLongOrNull()
            ?: return err("invalid_argument", "event_id is required and must be numeric.")
        val current = fetchEventSummary(eventId) ?: return err("not_found", "No event with id $eventId.")
        if (!hasWritePermission()) return err("permission_denied", "Write-calendar permission was not granted.")

        if (confirmWrite?.invoke("Delete \"${current.first}\" on ${current.second}") == false) {
            return err("user_denied", "The user declined to delete this event.")
        }

        return withContext(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = resolver.delete(uri, null, null)
            if (rows == 0) return@withContext err("write_failed", "Could not delete the event.")
            buildJsonObject { put("type", "delete_calendar_event"); put("event_id", eventId.toString()); put("ok", true) }.toString()
        }
    }

    /** Title + human-readable start date, fetched before an update/delete so the
     *  confirmation dialog shows what's actually being changed, not a bare id. */
    private suspend fun fetchEventSummary(eventId: Long): Pair<String, String>? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val title = c.getString(0) ?: "(untitled)"
                val start = c.getLong(1)
                return@withContext title to isoUtc(start)
            }
        }
        null
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    /** Formats an epoch-millis timestamp as a UTC ISO-8601 instant, e.g. "2026-07-08T09:00:00Z".
     *  Uses java.text.SimpleDateFormat rather than java.time (minSdk 24 predates java.time,
     *  which needs API 26+ or core library desugaring — neither is set up in this module). */
    private fun isoUtc(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    /** Accepts full ISO-8601 UTC instants ("...Z", with or without milliseconds) or bare
     *  local date-times with no zone (assumed device-local timezone), matching what a model
     *  is likely to send back after seeing [isoUtc]'s output from get_calendar_events. */
    private fun parseInstant(value: String): Long? {
        if (value.isBlank()) return null
        val utcPatterns = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        for (pattern in utcPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.isLenient = false
                return sdf.parse(value)?.time
            } catch (_: Exception) { /* try next pattern */ }
        }
        val localPatterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")
        for (pattern in localPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getDefault()
                sdf.isLenient = false
                return sdf.parse(value)?.time
            } catch (_: Exception) { /* try next pattern */ }
        }
        return null
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "calendar")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
