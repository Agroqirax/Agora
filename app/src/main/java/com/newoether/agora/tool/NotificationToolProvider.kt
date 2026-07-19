package com.newoether.agora.tool

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.service.ActionResult
import com.newoether.agora.service.AgoraNotificationAccessService
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tool for reading, acting on, dismissing, and creating notifications.
 *
 * Two independent capabilities live in this one provider because they share a single
 * mental model ("notifications on this device") even though they use different platform
 * mechanisms:
 *
 * - **Reading/interacting/dismissing other apps' notifications** ([LIST_NOTIFICATIONS],
 *   [GET_NOTIFICATION], [INTERACT_NOTIFICATION], [DISMISS_NOTIFICATION]) goes through
 *   [AgoraNotificationAccessService], which needs the special "notification access" grant
 *   (not a runtime-permission dialog — same mechanism as [MediaControlToolProvider]).
 * - **Creating Agora's own notifications** ([CREATE_NOTIFICATION]) uses the ordinary
 *   [NotificationManagerCompat] API and only needs the POST_NOTIFICATIONS runtime
 *   permission (Android 13+; auto-granted on older versions). It works even if the user
 *   never grants notification-listener access, since it never needs to *read* anything.
 *
 * [DISMISS_NOTIFICATION] bridges both: it accepts either a `key` (from [LIST_NOTIFICATIONS]/
 * [GET_NOTIFICATION], routed through the listener service) or a `notification_id` (from
 * [CREATE_NOTIFICATION], routed through [NotificationManagerCompat] directly) so dismissing
 * something Agora itself created never requires the heavier listener grant.
 *
 * [INTERACT_NOTIFICATION] and [DISMISS_NOTIFICATION] go through [confirmWrite] first (a
 * no-op when notifications_confirm_enabled is off, or when the source app has been
 * always-allowed — see [confirmWrite]'s doc) since both act on another app's notification
 * on the user's behalf — tapping a reply action or clearing something the user hasn't seen
 * yet is the same trust tier as creating a calendar event. [LIST_NOTIFICATIONS]/
 * [GET_NOTIFICATION] go through the separate, simpler [confirmRead] gate instead — reading
 * notification bodies is its own trust tier (they routinely carry OTPs, message previews,
 * etc.), so it defaults on independently of the interact/dismiss gate. [CREATE_NOTIFICATION]
 * (Agora's own notification, not someone else's) is never gated beyond the platform
 * permission check.
 */
class NotificationToolProvider(private val app: Application) : ToolProvider {

    /** In-app confirmation gate for interact/dismiss. Takes the source app's package name
     *  and display label so the caller can offer a per-app "always allow" alongside the
     *  global one — see [com.newoether.agora.viewmodel.ToolConfirmationController]. Set by the owning
     *  ViewModel. */
    var confirmWrite: (suspend (summary: String, packageName: String, appLabel: String) -> Boolean)? = null

    /** In-app confirmation gate for list/get (reading notification content). Separate from
     *  [confirmWrite] and its own setting, defaulting to *on* since notification bodies are
     *  routinely sensitive. Set by the owning ViewModel. */
    var confirmRead: (suspend (summary: String) -> Boolean)? = null

    /** Opens notification-access settings (for list/get/interact/dismiss-by-key). Set by
     *  the owning ViewModel. Returns the post-open access state, same caveat as
     *  [MediaControlToolProvider.requestPermission]: Android gives no completion callback,
     *  so this reflects whatever is true immediately after opening the settings screen. */
    var requestAccessPermission: (suspend () -> Boolean)? = null

    /** Runtime POST_NOTIFICATIONS permission request (for create_notification on API 33+).
     *  Set by the owning ViewModel. */
    var requestPostPermission: (suspend () -> Boolean)? = null

    /** Maps a friendly id (either caller-supplied or auto-generated) from [CREATE_NOTIFICATION]
     *  to the int id [NotificationManagerCompat] actually uses, so [DISMISS_NOTIFICATION] can
     *  cancel Agora's own notifications without needing listener access. Process-lifetime only
     *  — nothing here is persisted to disk. */
    private val ownNotificationIds = ConcurrentHashMap<String, Int>()
    private val nextOwnId = AtomicInteger(1)

    private val toolNames = setOf(
        LIST_NOTIFICATIONS, GET_NOTIFICATION, INTERACT_NOTIFICATION,
        DISMISS_NOTIFICATION, CREATE_NOTIFICATION, SNOOZE_NOTIFICATION
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.notificationsEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = LIST_NOTIFICATIONS,
                description = "List notifications currently showing on the device from any app (title, short " +
                    "text, which app posted it, and whether it has tappable actions or a reply field). " +
                    "Requires notification access; may prompt the user to enable it.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "app_package" to ToolProperty("string", "Only list notifications from this app's package name (optional)."),
                        "limit" to ToolProperty("integer", "Max number of notifications to return (optional, default 20).")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = GET_NOTIFICATION,
                description = "Get full details for one notification: complete title/text, whether it's ongoing, " +
                    "and the list of actions available (with their index, for use with $INTERACT_NOTIFICATION) " +
                    "including which ones accept a text reply.",
                parameters = ToolParameters(
                    properties = mapOf("key" to ToolProperty("string", "Notification key, from $LIST_NOTIFICATIONS.")),
                    required = listOf("key")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = INTERACT_NOTIFICATION,
                description = "Tap a notification (opens its source app to whatever it points to), or tap one " +
                    "of its action buttons instead. Omit action_index to tap the notification itself. Pass " +
                    "action_index (from $GET_NOTIFICATION) to invoke a specific button instead; pass reply_text " +
                    "as well if that action accepts a text reply (e.g. a messaging app's inline \"Reply\" button). " +
                    "Note: some apps' notifications don't have a tap target at all, or the OS may block the " +
                    "app from actually opening depending on the device — dismiss_notification always works " +
                    "regardless. Asks the user to confirm before acting.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "key" to ToolProperty("string", "Notification key, from $LIST_NOTIFICATIONS."),
                        "action_index" to ToolProperty("integer", "Index of the action to invoke (optional; see $GET_NOTIFICATION)."),
                        "reply_text" to ToolProperty("string", "Text to send, only for actions that accept a reply (optional).")
                    ),
                    required = listOf("key")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = SNOOZE_NOTIFICATION,
                description = "Snooze a notification: the system hides it and automatically re-posts it after " +
                    "the given duration, the same as picking a snooze option from the notification shade. " +
                    "Asks the user to confirm before snoozing.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "key" to ToolProperty("string", "Notification key, from $LIST_NOTIFICATIONS."),
                        "duration_minutes" to ToolProperty("integer", "How long to snooze for, in minutes (optional, default 60).")
                    ),
                    required = listOf("key")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = DISMISS_NOTIFICATION,
                description = "Dismiss (clear) a notification, the same as swiping it away. Pass exactly one of: " +
                    "key (a notification from another app, from $LIST_NOTIFICATIONS) or notification_id (one Agora " +
                    "created itself, from $CREATE_NOTIFICATION). Asks the user to confirm before dismissing.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "key" to ToolProperty("string", "Notification key from $LIST_NOTIFICATIONS (optional)."),
                        "notification_id" to ToolProperty("string", "Id returned by $CREATE_NOTIFICATION (optional).")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = CREATE_NOTIFICATION,
                description = "Post a new notification from Agora — a way to alert the user right now, or leave " +
                    "them a heads-up they'll see later. Tapping it opens Agora. Returns a notification_id that " +
                    "can later be passed to $DISMISS_NOTIFICATION.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Notification title."),
                        "text" to ToolProperty("string", "Notification body text."),
                        "id" to ToolProperty("string", "Custom id to reuse/update this notification later (optional; auto-generated if omitted)."),
                        "priority" to ToolProperty(
                            "string", "How intrusive this should be (optional, default \"default\").",
                            enum = listOf("low", "default", "high")
                        ),
                        "ongoing" to ToolProperty("boolean", "If true, the notification can't be swiped away by the user (optional, default false)."),
                        "silent" to ToolProperty("boolean", "If true, posts without sound/vibration regardless of priority (optional, default false).")
                    ),
                    required = listOf("title", "text")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.notificationsEnabled) return err("disabled", "The notifications tool is disabled in settings.")

        return try {
            when (name) {
                LIST_NOTIFICATIONS -> withAccess { listNotifications(arguments) }
                GET_NOTIFICATION -> withAccess { getNotification(arguments) }
                INTERACT_NOTIFICATION -> withAccess { interactNotification(arguments) }
                DISMISS_NOTIFICATION -> dismissNotification(arguments) // may not need access, see below
                SNOOZE_NOTIFICATION -> withAccess { snoozeNotification(arguments) }
                CREATE_NOTIFICATION -> createNotification(arguments)
                else -> err("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: Exception) {
            DebugLog.e("NotificationTool", "$name failed", e)
            err("notification_error", e.message)
        }
    }

    /** Ensures notification-listener access before running [block]; requests it once if
     *  missing (opens system settings, per-platform caveat noted on [requestAccessPermission]). */
    private suspend fun withAccess(block: suspend () -> String): String {
        if (!AgoraNotificationAccessService.hasAccessGranted(app)) {
            val granted = requestAccessPermission?.invoke() ?: false
            if (!granted || !AgoraNotificationAccessService.hasAccessGranted(app)) {
                return err(
                    "permission_denied",
                    "Notification access isn't granted. Opened the system settings screen — once the user " +
                        "enables notification access for Agora, this will work."
                )
            }
        }
        return block()
    }

    // ── list_notifications ───────────────────────────────────

    private suspend fun listNotifications(arguments: String): String {
        val args = parseToolArgs(arguments)
        val filterPackage = arg(args, "app_package").ifBlank { null }
        val limit = intArg(args, "limit") ?: 20

        val summary = if (filterPackage != null) "Read active notifications from ${appLabel(filterPackage)}"
            else "Read your currently active notifications (all apps)"
        if (confirmRead?.invoke(summary) == false) {
            return err("user_denied", "The user declined to let the assistant read notifications.")
        }

        val sbns = AgoraNotificationAccessService.getActiveNotifications() ?: emptyArray()
        val filtered = sbns
            .filter { filterPackage == null || it.packageName == filterPackage }
            .filter { it.packageName != app.packageName || ownNotificationIds.values.contains(it.id) }
            .sortedByDescending { it.postTime }
            .take(limit.coerceIn(1, 100))

        return buildJsonObject {
            put("type", LIST_NOTIFICATIONS)
            put("count", filtered.size)
            put("notifications", buildJsonArray {
                filtered.forEach { sbn ->
                    add(buildJsonObject {
                        put("key", sbn.key)
                        put("app_package", sbn.packageName)
                        put("app_label", appLabel(sbn.packageName))
                        extraTitle(sbn.notification)?.let { put("title", it) }
                        extraText(sbn.notification)?.let { put("text", truncate(it, 200)) }
                        put("posted_at_epoch_ms", sbn.postTime)
                        put("ongoing", sbn.isOngoing)
                        put("has_actions", !sbn.notification.actions.isNullOrEmpty())
                    })
                }
            })
        }.toString()
    }

    // ── get_notification ─────────────────────────────────────

    private suspend fun getNotification(arguments: String): String {
        val args = parseToolArgs(arguments)
        val key = arg(args, "key").ifBlank { null }
            ?: return err("invalid_argument", "key is required.")

        val sbn = AgoraNotificationAccessService.findByKey(key)
            ?: return err("not_found", "No active notification with that key was found (it may have been dismissed already).")

        val summary = buildString {
            append("Read the full content of a notification from ${appLabel(sbn.packageName)}")
            extraTitle(sbn.notification)?.let { append(" (\"$it\")") }
        }
        if (confirmRead?.invoke(summary) == false) {
            return err("user_denied", "The user declined to let the assistant read this notification.")
        }

        val notification = sbn.notification
        return buildJsonObject {
            put("type", GET_NOTIFICATION)
            put("key", sbn.key)
            put("app_package", sbn.packageName)
            put("app_label", appLabel(sbn.packageName))
            extraTitle(notification)?.let { put("title", it) }
            extraText(notification)?.let { put("text", it) }
            extraSubText(notification)?.let { put("subtext", it) }
            put("posted_at_epoch_ms", sbn.postTime)
            put("ongoing", sbn.isOngoing)
            put("has_tap_action", notification.contentIntent != null)
            put("actions", buildJsonArray {
                notification.actions?.forEachIndexed { index, action ->
                    add(buildJsonObject {
                        put("index", index)
                        put("title", action.title?.toString() ?: "")
                        put("accepts_reply", !action.remoteInputs.isNullOrEmpty())
                    })
                }
            })
        }.toString()
    }

    // ── interact_notification ─────────────────────────────────

    private suspend fun interactNotification(arguments: String): String {
        val args = parseToolArgs(arguments)
        val key = arg(args, "key").ifBlank { null }
            ?: return err("invalid_argument", "key is required.")
        val actionIndex = intArg(args, "action_index")
        val replyText = arg(args, "reply_text").ifBlank { null }

        val sbn = AgoraNotificationAccessService.findByKey(key)
            ?: return err("not_found", "No active notification with that key was found (it may have been dismissed already).")

        val summary = buildString {
            append("Interact with notification from ${appLabel(sbn.packageName)}")
            extraTitle(sbn.notification)?.let { append(" (\"$it\")") }
            if (actionIndex != null) append(", action #$actionIndex")
            if (replyText != null) append(", replying \"${truncate(replyText, 50)}\"")
        }
        if (confirmWrite?.invoke(summary, sbn.packageName, appLabel(sbn.packageName)) == false) {
            return err("user_denied", "The user declined this action.")
        }

        return when (AgoraNotificationAccessService.performAction(key, actionIndex, replyText)) {
            is ActionResult.Sent ->
                buildJsonObject { put("type", INTERACT_NOTIFICATION); put("key", key); put("ok", true) }.toString()
            is ActionResult.NotFound ->
                err("not_found", "The notification is no longer active.")
            is ActionResult.NoIntent ->
                err("no_intent", "This notification (or action) has nothing to trigger.")
            is ActionResult.InvalidAction ->
                err("invalid_argument", "action_index doesn't match any action on this notification.")
            is ActionResult.Canceled ->
                err("canceled", "The action could no longer be performed (it may have expired).")
        }
    }

    // ── dismiss_notification ──────────────────────────────────

    private suspend fun dismissNotification(arguments: String): String {
        val args = parseToolArgs(arguments)
        val key = arg(args, "key").ifBlank { null }
        val ownId = arg(args, "notification_id").ifBlank { null }

        if (key == null && ownId == null) {
            return err("invalid_argument", "Pass either key or notification_id.")
        }
        if (key != null && ownId != null) {
            return err("invalid_argument", "Pass only one of key or notification_id, not both.")
        }

        return if (ownId != null) {
            val intId = ownNotificationIds[ownId]
                ?: return err("not_found", "No notification with that notification_id (or it was already dismissed).")
            val summary = "Dismiss the notification Agora posted (\"$ownId\")"
            if (confirmWrite?.invoke(summary, app.packageName, appLabel(app.packageName)) == false) return err("user_denied", "The user declined to dismiss this.")
            withContext(Dispatchers.IO) { NotificationManagerCompat.from(app).cancel(intId) }
            ownNotificationIds.remove(ownId)
            buildJsonObject { put("type", DISMISS_NOTIFICATION); put("notification_id", ownId); put("ok", true) }.toString()
        } else {
            withAccess {
                val sbn = AgoraNotificationAccessService.findByKey(key!!)
                    ?: return@withAccess err("not_found", "No active notification with that key was found (it may have been dismissed already).")
                val summary = buildString {
                    append("Dismiss notification from ${appLabel(sbn.packageName)}")
                    extraTitle(sbn.notification)?.let { append(" (\"$it\")") }
                }
                if (confirmWrite?.invoke(summary, sbn.packageName, appLabel(sbn.packageName)) == false) return@withAccess err("user_denied", "The user declined to dismiss this.")
                val ok = withContext(Dispatchers.IO) { AgoraNotificationAccessService.cancelNotificationByKey(key) }
                buildJsonObject { put("type", DISMISS_NOTIFICATION); put("key", key); put("ok", ok) }.toString()
            }
        }
    }

    // ── snooze_notification ───────────────────────────────────

    private suspend fun snoozeNotification(arguments: String): String {
        val args = parseToolArgs(arguments)
        val key = arg(args, "key").ifBlank { null }
            ?: return err("invalid_argument", "key is required.")
        val durationMinutes = (intArg(args, "duration_minutes") ?: 60).coerceIn(1, 24 * 60)

        val sbn = AgoraNotificationAccessService.findByKey(key)
            ?: return err("not_found", "No active notification with that key was found (it may have been dismissed already).")

        val summary = buildString {
            append("Snooze notification from ${appLabel(sbn.packageName)} for $durationMinutes minutes")
            extraTitle(sbn.notification)?.let { append(" (\"$it\")") }
        }
        if (confirmWrite?.invoke(summary, sbn.packageName, appLabel(sbn.packageName)) == false) {
            return err("user_denied", "The user declined to snooze this.")
        }

        val ok = withContext(Dispatchers.IO) {
            AgoraNotificationAccessService.snoozeNotificationByKey(key, durationMinutes * 60_000L)
        }
        return buildJsonObject {
            put("type", SNOOZE_NOTIFICATION)
            put("key", key)
            put("duration_minutes", durationMinutes)
            put("ok", ok)
        }.toString()
    }

    // ── create_notification ───────────────────────────────────

    private suspend fun createNotification(arguments: String): String {
        val args = parseToolArgs(arguments)
        val title = arg(args, "title").ifBlank { null }
            ?: return err("invalid_argument", "title is required.")
        val text = arg(args, "text").ifBlank { null }
            ?: return err("invalid_argument", "text is required.")
        val friendlyId = arg(args, "id").ifBlank { "note_${System.currentTimeMillis()}" }
        val priority = arg(args, "priority").ifBlank { "default" }
        val ongoing = boolArg(args, "ongoing") ?: false
        val silent = boolArg(args, "silent") ?: false

        if (priority !in setOf("low", "default", "high")) {
            return err("invalid_argument", "priority must be one of: low, default, high.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val granted = requestPostPermission?.invoke() ?: false
            if (!granted) {
                return err(
                    "permission_denied",
                    "Notification permission isn't granted. Requested it from the user — once they allow " +
                        "notifications for Agora, this will work."
                )
            }
        }

        return withContext(Dispatchers.IO) {
            ensureChannels(app)
            val intId = ownNotificationIds.getOrPut(friendlyId) { nextOwnId.getAndIncrement() }
            val channelId = channelIdFor(priority)
            val contentIntent = PendingIntent.getActivity(
                app, intId,
                Intent(app, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(app, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setPriority(priorityToCompat(priority))
            if (silent) {
                builder.setSilent(true)
            }
            try {
                NotificationManagerCompat.from(app).notify(intId, builder.build())
            } catch (e: SecurityException) {
                return@withContext err("permission_denied", "Notifications aren't permitted for Agora.")
            }
            buildJsonObject {
                put("type", CREATE_NOTIFICATION)
                put("notification_id", friendlyId)
                put("ok", true)
            }.toString()
        }
    }

    private fun ensureChannels(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(NotificationManager::class.java)
        listOf("low", "default", "high").forEach { p ->
            val channel = NotificationChannel(channelIdFor(p), "Agora — ${p.replaceFirstChar { it.uppercase() }}", importanceFor(p)).apply {
                description = "Notifications the assistant creates on request ($p priority)."
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun channelIdFor(priority: String) = "agora_llm_notifications_$priority"

    private fun importanceFor(priority: String): Int = when (priority) {
        "low" -> NotificationManager.IMPORTANCE_LOW
        "high" -> NotificationManager.IMPORTANCE_HIGH
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun priorityToCompat(priority: String): Int = when (priority) {
        "low" -> NotificationCompat.PRIORITY_LOW
        "high" -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun extraTitle(n: Notification): String? = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun extraText(n: Notification): String? = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun extraSubText(n: Notification): String? = n.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    private fun truncate(s: String, max: Int): String = if (s.length > max) s.take(max) + "…" else s

    private fun appLabel(packageName: String): String = try {
        val pm = app.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    private fun intArg(args: Map<String, JsonElement>, key: String): Int? =
        (args[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun boolArg(args: Map<String, JsonElement>, key: String): Boolean? =
        (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "notification")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val LIST_NOTIFICATIONS = "list_notifications"
        const val GET_NOTIFICATION = "get_notification"
        const val INTERACT_NOTIFICATION = "interact_notification"
        const val DISMISS_NOTIFICATION = "dismiss_notification"
        const val SNOOZE_NOTIFICATION = "snooze_notification"
        const val CREATE_NOTIFICATION = "create_notification"
    }
}
