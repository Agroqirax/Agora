package com.newoether.agora.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.newoether.agora.util.DebugLog

/**
 * Notification-listener that backs [com.newoether.agora.tool.NotificationToolProvider]'s
 * `list_notifications`/`get_notification`/`interact_notification`/`dismiss_notification`
 * tools. Unlike [MediaNotificationListenerService] — which is a deliberate no-op stub —
 * this service exists specifically to let the model read notification content on demand,
 * so it must actually be trusted with it.
 *
 * Design choices that keep this from becoming a persistent surveillance surface:
 * - No `onNotificationPosted`/`onNotificationRemoved` override does anything with the
 *   payload; both are left as the base class's no-ops. Nothing is cached, logged, or
 *   forwarded as notifications arrive — content is only ever read the moment a tool call
 *   asks for it, via [getActiveNotifications] which queries the live system list.
 * - The instance handle is only used for [getActiveNotifications]/[cancelNotificationByKey]/
 *   [performAction]; there is no field anywhere that accumulates notification history.
 * - This is a special access grant (Settings > Apps > Special app access > Notification
 *   access), separate from [MediaNotificationListenerService]'s grant, so a user who wants
 *   media control but not notification reading (or vice versa) can grant one without the
 *   other.
 */
class AgoraNotificationAccessService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: AgoraNotificationAccessService? = null

        /** True once the system has bound and connected this listener — i.e. the user has
         *  granted notification access *and* the service is currently alive. Prefer
         *  [hasAccessGranted] to check the permission grant itself; this reflects whether a
         *  call right now would actually succeed. */
        fun isConnected(): Boolean = instance != null

        /** Live snapshot of every currently-posted notification the system will hand us,
         *  or null if the listener isn't connected (access not granted, or not yet bound). */
        fun getActiveNotifications(): Array<StatusBarNotification>? =
            try { instance?.activeNotifications } catch (e: SecurityException) { null }

        /** Finds one active notification by its [StatusBarNotification.getKey]. */
        fun findByKey(key: String): StatusBarNotification? =
            getActiveNotifications()?.firstOrNull { it.key == key }

        /** Dismisses a notification by key, the same as swiping it away. Returns false if
         *  the listener isn't connected or the key no longer matches anything live. */
        fun cancelNotificationByKey(key: String): Boolean {
            val svc = instance ?: return false
            return try {
                svc.cancelNotification(key)
                true
            } catch (e: SecurityException) {
                DebugLog.e("NotificationAccess", "cancelNotification failed", e)
                false
            }
        }

        /**
         * Fires the notification's main tap target ([Notification.contentIntent]) or, if
         * [actionIndex] is given, the corresponding entry in [Notification.actions] — this
         * is what taps that button. [replyText] is only used when the target action declares
         * [android.app.RemoteInput] (e.g. an inline "Reply" action); it's bundled into the
         * intent the same way the system keyboard would on a real reply.
         *
         * On API 34+ this passes [android.app.ActivityOptions] with background-activity-start
         * explicitly allowed for this one send: without it, `PendingIntent.send()` from a
         * background service (which is what a notification listener is, from the system's
         * point of view) reports success but the target Activity is silently blocked from
         * actually appearing, on stock Android's background-activity-launch restrictions.
         * Below API 34 there's no equivalent opt-in, so this is best-effort there.
         */
        fun performAction(key: String, actionIndex: Int?, replyText: String?): ActionResult {
            val sbn = findByKey(key) ?: return ActionResult.NotFound
            val notification = sbn.notification

            val pendingIntent: PendingIntent
            val remoteInputs: Array<RemoteInput>?
            if (actionIndex == null) {
                pendingIntent = notification.contentIntent
                    ?: return ActionResult.NoIntent
                remoteInputs = null
            } else {
                val action = notification.actions?.getOrNull(actionIndex)
                    ?: return ActionResult.InvalidAction
                pendingIntent = action.actionIntent ?: return ActionResult.NoIntent
                remoteInputs = action.remoteInputs
            }

            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
            } else null

            return try {
                if (!remoteInputs.isNullOrEmpty() && replyText != null) {
                    val fillIntent = Intent()
                    val resultsBundle = android.os.Bundle()
                    for (ri in remoteInputs) resultsBundle.putCharSequence(ri.resultKey, replyText)
                    RemoteInput.addResultsToIntent(remoteInputs, fillIntent, resultsBundle)
                    pendingIntent.send(instance, 0, fillIntent, null, null, null, options)
                } else {
                    pendingIntent.send(instance, 0, null, null, null, null, options)
                }
                ActionResult.Sent
            } catch (e: PendingIntent.CanceledException) {
                ActionResult.Canceled
            } catch (e: SecurityException) {
                ActionResult.Canceled
            }
        }

        /** Snoozes a notification for [durationMs] — the system hides it and re-posts it
         *  automatically once the duration elapses, the same as picking a snooze option from
         *  the system notification shade. Returns false if the listener isn't connected. */
        fun snoozeNotificationByKey(key: String, durationMs: Long): Boolean {
            val svc = instance ?: return false
            return try {
                svc.snoozeNotification(key, durationMs)
                true
            } catch (e: SecurityException) {
                DebugLog.e("NotificationAccess", "snoozeNotification failed", e)
                false
            }
        }

        /** True if this app is currently an enabled notification listener for this
         *  specific service (a device can have several listener services registered). */
        fun hasAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) &&
                isConnected()

        /** Opens the system "notification access" settings screen so the user can grant
         *  (or revoke) access for this specific listener component. Mirrors
         *  [com.newoether.agora.tool.MediaControlToolProvider.openNotificationAccessSettings]. */
        fun openNotificationAccessSettings(context: Context) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                        ComponentName(context, AgoraNotificationAccessService::class.java).flattenToString()
                    )
                }
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** Result of [AgoraNotificationAccessService.performAction]. */
sealed class ActionResult {
    data object Sent : ActionResult()
    data object NotFound : ActionResult()
    data object NoIntent : ActionResult()
    data object InvalidAction : ActionResult()
    data object Canceled : ActionResult()
}
