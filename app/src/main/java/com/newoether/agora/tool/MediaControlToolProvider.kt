package com.newoether.agora.tool

import android.app.Application
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.service.AgoraNotificationAccessService
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool for reading and controlling whatever is currently playing media on the device
 * (music, podcasts, video audio, etc.), via the platform [MediaSessionManager]/
 * [MediaController] APIs — the same mechanism the lock screen and Bluetooth
 * headset buttons use, so it works with essentially any app that publishes a media
 * session (Spotify, YouTube Music, podcast apps, browsers playing video, ...).
 *
 * Exposes exactly two tools: [get_playback_state][GET_PLAYBACK_STATE] to read state, and
 * [control_media_playback][CONTROL_MEDIA_PLAYBACK] to act on it, with the specific transport
 * action (play/pause/toggle/next/previous/stop) passed as an `action` parameter rather than
 * one tool per action — fewer, more composable tools for the model to choose between.
 *
 * Unlike [AlarmToolProvider], this needs "notification access" (a special access
 * grant toggled in system Settings, not a runtime permission dialog) because
 * [MediaSessionManager.getActiveSessions] requires the calling app to be an enabled
 * notification listener — see [AgoraNotificationAccessService], which this tool shares
 * with [NotificationToolProvider] so there's only one grant to make. [requestPermission]
 * is set by the owning ViewModel to open that settings screen; since Android gives no
 * callback for when the user finishes there, a request always returns whatever the
 * live status is immediately after opening (usually still ungranted) — the *next*
 * tool call after the user actually flips the toggle will succeed.
 *
 * No [confirmWrite]-style gate: transport controls (play/pause/skip) are transient
 * and low-stakes, the same trust level this app extends to volume-like device state
 * rather than to persistent writes like creating a calendar event or contact.
 */
class MediaControlToolProvider(private val app: Application) : ToolProvider {

    /** Opens the notification-listener settings screen. Set by the owning ViewModel.
     *  Always returns the post-open permission state (see class doc). */
    var requestPermission: (suspend () -> Boolean)? = null

    private val toolNames = setOf(GET_PLAYBACK_STATE, CONTROL_MEDIA_PLAYBACK)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.mediaControlEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = GET_PLAYBACK_STATE,
                description = "Get the currently playing (or last active) media: app, track title, artist, album, " +
                    "whether it's playing or paused, and position/duration in milliseconds if available. " +
                    "Requires the user to have granted notification access; may prompt them to enable it.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )),
            ToolDefinition(function = ToolFunction(
                name = CONTROL_MEDIA_PLAYBACK,
                description = "Control playback on the active media session (whatever music/podcast/video app is " +
                    "currently playing or was last active). Pick the specific transport action via the " +
                    "`action` parameter rather than calling separate tools.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty(
                            type = "string",
                            description = "Which transport action to perform: " +
                                "`play` resumes/starts playback; " +
                                "`pause` pauses playback; " +
                                "`play_pause` toggles play/pause — use this when you aren't sure whether it's " +
                                "currently playing or paused; " +
                                "`next` skips to the next track; " +
                                "`previous` goes back to the previous track (or restarts the current one, " +
                                "depending on the app); " +
                                "`stop` stops playback.",
                            enum = listOf("play", "pause", "play_pause", "next", "previous", "stop")
                        )
                    ),
                    required = listOf("action")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.mediaControlEnabled) return err("disabled", "The media control tool is disabled in settings.")

        if (!hasNotificationAccess()) {
            val granted = requestPermission?.invoke() ?: false
            if (!granted || !hasNotificationAccess()) {
                return err(
                    "permission_denied",
                    "Notification access isn't granted. Opened the system settings screen — once the user " +
                        "enables notification access for this app, media control will work."
                )
            }
        }

        return try {
            when (name) {
                GET_PLAYBACK_STATE -> getPlaybackState()
                CONTROL_MEDIA_PLAYBACK -> controlMediaPlayback(arguments)
                else -> err("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            DebugLog.e("MediaControlTool", "$name failed", e)
            err("media_error", e.message)
        }
    }

    // ── get_playback_state ───────────────────────────────────

    private suspend fun getPlaybackState(): String = withContext(Dispatchers.IO) {
        val controller = activeController()
            ?: return@withContext buildJsonObject {
                put("type", GET_PLAYBACK_STATE)
                put("active", false)
            }.toString()

        val metadata = controller.metadata
        val playbackState = controller.playbackState
        buildJsonObject {
            put("type", GET_PLAYBACK_STATE)
            put("active", true)
            put("app_package", controller.packageName)
            put("app_label", appLabel(controller.packageName))
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.let { put("title", it) }
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.let { put("artist", it) }
            metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)?.let { put("album", it) }
            val durationMs = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L
            if (durationMs > 0) put("duration_ms", durationMs)
            playbackState?.position?.let { if (it >= 0) put("position_ms", it) }
            put("is_playing", playbackState?.state == PlaybackState.STATE_PLAYING)
            put("state", playbackStateLabel(playbackState?.state))
        }.toString()
    }

    // ── control_media_playback ────────────────────────────────

    private suspend fun controlMediaPlayback(arguments: String): String {
        val args = parseToolArgs(arguments)
        val action = args["action"]?.let { (it as? JsonPrimitive)?.content }
            ?: return err("invalid_argument", "action is required.")
        return when (action) {
            "play" -> sendAction(action) { it.transportControls.play() }
            "pause" -> sendAction(action) { it.transportControls.pause() }
            "play_pause" -> togglePlayPause()
            "next" -> sendAction(action) { it.transportControls.skipToNext() }
            "previous" -> sendAction(action) { it.transportControls.skipToPrevious() }
            "stop" -> sendAction(action) { it.transportControls.stop() }
            else -> err(
                "invalid_argument",
                "Unknown action \"$action\". Must be one of: play, pause, play_pause, next, previous, stop."
            )
        }
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    /** Reports the requested `action` alongside the concrete `effect` it produced, since
     *  [togglePlayPause] resolves to "played" or "paused" depending on the session's
     *  state at call time — the UI and the model both want to know which one actually
     *  happened, not just which action was requested. */
    private suspend fun sendAction(action: String, transport: (MediaController) -> Unit): String =
        withContext(Dispatchers.IO) {
            val controller = activeController()
                ?: return@withContext err("no_active_session", "No app with active media playback was found.")
            transport(controller)
            buildJsonObject {
                put("type", CONTROL_MEDIA_PLAYBACK)
                put("action", action)
                put("effect", effectFor(action))
                put("app_package", controller.packageName)
                put("ok", true)
            }.toString()
        }

    /** [togglePlayPause] resolves based on the current playback state rather than
     *  always calling one transport method, since [MediaController.TransportControls]
     *  has no single "toggle" call and blindly calling play() on an already-playing
     *  session is a silent no-op some apps also treat as a seek-to-start. */
    private suspend fun togglePlayPause(): String = withContext(Dispatchers.IO) {
        val controller = activeController()
            ?: return@withContext err("no_active_session", "No app with active media playback was found.")
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
        buildJsonObject {
            put("type", CONTROL_MEDIA_PLAYBACK)
            put("action", "play_pause")
            put("effect", if (isPlaying) "paused" else "played")
            put("app_package", controller.packageName)
            put("ok", true)
        }.toString()
    }

    private fun effectFor(action: String): String = when (action) {
        "play" -> "played"
        "pause" -> "paused"
        "next" -> "skipped_next"
        "previous" -> "skipped_previous"
        "stop" -> "stopped"
        else -> action
    }

    /** Picks the most relevant active session: the first one actually in a "playing"
     *  state, falling back to the first session at all (e.g. paused-but-recent). Order
     *  of [MediaSessionManager.getActiveSessions] is itself already relevance-sorted by
     *  the platform (most recently active first). */
    private fun activeController(): MediaController? {
        val sessions = try {
            mediaSessionManager().getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            return null
        }
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    private fun mediaSessionManager() =
        app.getSystemService(Application.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private fun listenerComponent() = ComponentName(app, AgoraNotificationAccessService::class.java)

    private fun hasNotificationAccess(): Boolean = AgoraNotificationAccessService.hasAccessGranted(app)

    private fun appLabel(packageName: String): String = try {
        val pm = app.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun playbackStateLabel(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_NONE, null -> "none"
        else -> "unknown"
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "media_control")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val GET_PLAYBACK_STATE = "get_playback_state"
        const val CONTROL_MEDIA_PLAYBACK = "control_media_playback"
    }
}
