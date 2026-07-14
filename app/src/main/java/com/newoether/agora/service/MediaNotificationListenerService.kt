package com.newoether.agora.service

import android.service.notification.NotificationListenerService

/**
 * Deliberately does nothing with notifications — it exists solely so this app can be
 * registered as an enabled notification listener, which is the permission
 * [android.media.session.MediaSessionManager.getActiveSessions] requires of its caller.
 * Agora never reads notification content through this service; it's only ever used as
 * the listener-component handle passed into `getActiveSessions()` by
 * [com.newoether.agora.tool.MediaControlToolProvider].
 *
 * Kept intentionally free of `onNotificationPosted`/`onNotificationRemoved` overrides:
 * the base class no-ops both, and there's nothing here that should ever inspect,
 * store, or forward notification content.
 */
class MediaNotificationListenerService : NotificationListenerService()
