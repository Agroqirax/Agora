package com.newoether.agora.tool

import android.app.Application
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tool for turning the device's rear-camera flash unit ("torch") on/off, via the
 * platform [CameraManager.setTorchMode] API added in Marshmallow. This call is
 * available to any app *without* the CAMERA permission, as long as no other app
 * currently has the camera open — unlike [MediaControlToolProvider] there's no
 * special access grant to request either, so this provider has no `requestPermission`
 * gate and no [confirmWrite]-style prompt: flipping a flashlight is exactly as
 * transient and low-stakes as toggling media playback.
 *
 * Exposes two tools: [get_torch_state][GET_TORCH_STATE] to read whether the torch is
 * currently on, and [set_torch][SET_TORCH] to turn it on/off/toggle it, mirroring
 * [MediaControlToolProvider]'s "one action parameter" shape rather than separate
 * on/off tools.
 *
 * Current torch state is tracked via a registered [CameraManager.TorchCallback] rather
 * than assumed, since Android (or another app, or the user via a quick-settings tile)
 * can change it out from under us at any time; the callback fires immediately with the
 * live state for every known camera as soon as it's registered.
 */
class TorchToolProvider(private val app: Application) : ToolProvider {

    private val toolNames = setOf(GET_TORCH_STATE, SET_TORCH)

    private val cameraManager: CameraManager? by lazy {
        app.getSystemService(Application.CAMERA_SERVICE) as? CameraManager
    }

    /** The rear-facing camera ID with a flash unit, resolved lazily and cached — most
     *  devices only have one, and re-enumerating on every tool call is wasted work. */
    private val torchCameraId: String? by lazy { findTorchCameraId() }

    // Updated by the registered TorchCallback; null until we've heard from the platform.
    @Volatile private var lastKnownTorchOn: Boolean? = null
    private val callbackRegistered = AtomicBoolean(false)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.torchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = GET_TORCH_STATE,
                description = "Check whether the device's flashlight (torch) is currently on or off, " +
                    "and whether the device has one at all. No parameters.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )),
            ToolDefinition(function = ToolFunction(
                name = SET_TORCH,
                description = "Turn the device's flashlight (torch) on or off. Pick the desired state via " +
                    "the `action` parameter.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "action" to ToolProperty(
                            type = "string",
                            description = "`on` turns the torch on; `off` turns it off; `toggle` flips " +
                                "whatever its current state is — use this when you aren't sure whether " +
                                "it's currently on or off.",
                            enum = listOf("on", "off", "toggle")
                        )
                    ),
                    required = listOf("action")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.torchEnabled) return err("disabled", "The torch tool is disabled in settings.")

        val cameraId = torchCameraId
            ?: return err("no_flash", "This device doesn't have a flashlight/torch.")

        ensureCallbackRegistered()

        return try {
            when (name) {
                GET_TORCH_STATE -> getTorchState(cameraId)
                SET_TORCH -> setTorch(cameraId, arguments)
                else -> err("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: CameraAccessException) {
            // Most commonly: another app (a camera app, a third-party flashlight app)
            // currently has the camera open, so the torch can't be toggled right now.
            err("camera_busy", "The camera is currently in use by another app, so the torch can't be controlled right now.")
        } catch (e: Exception) {
            DebugLog.e("TorchTool", "$name failed", e)
            err("torch_error", e.message)
        }
    }

    private suspend fun getTorchState(cameraId: String): String = withContext(Dispatchers.IO) {
        buildJsonObject {
            put("type", GET_TORCH_STATE)
            put("available", true)
            put("is_on", lastKnownTorchOn ?: false)
        }.toString()
    }

    private suspend fun setTorch(cameraId: String, arguments: String): String = withContext(Dispatchers.IO) {
        val args = parseToolArgs(arguments)
        val action = args["action"]?.let { (it as? JsonPrimitive)?.content }
            ?: return@withContext err("invalid_argument", "action is required.")

        val cm = cameraManager ?: return@withContext err("unavailable", "Camera service is unavailable.")
        val targetOn = when (action) {
            "on" -> true
            "off" -> false
            "toggle" -> !(lastKnownTorchOn ?: false)
            else -> return@withContext err(
                "invalid_argument",
                "Unknown action \"$action\". Must be one of: on, off, toggle."
            )
        }

        cm.setTorchMode(cameraId, targetOn)
        // setTorchMode is fire-and-forget; the TorchCallback below will confirm the
        // change, but report the requested state immediately rather than racing it.
        lastKnownTorchOn = targetOn

        buildJsonObject {
            put("type", SET_TORCH)
            put("action", action)
            put("is_on", targetOn)
            put("ok", true)
        }.toString()
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    /** Finds the first camera that reports a flash unit, preferring a rear-facing one
     *  (front-facing flash units are rare but do exist on some devices). */
    private fun findTorchCameraId(): String? {
        val cm = cameraManager ?: return null
        return try {
            val ids = cm.cameraIdList
            val withFlash = ids.filter { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        } catch (e: Exception) {
            DebugLog.e("TorchTool", "Failed to enumerate cameras", e)
            null
        }
    }

    /** Registers a [CameraManager.TorchCallback] once, lazily, so [lastKnownTorchOn]
     *  reflects reality even if the torch was toggled by something other than this
     *  tool (a quick-settings tile, another app, the OS). Registering fires the
     *  callback immediately with the current state, so we don't need a separate
     *  synchronous "read state" path. */
    private fun ensureCallbackRegistered() {
        if (!callbackRegistered.compareAndSet(false, true)) return
        val cm = cameraManager ?: return
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId) lastKnownTorchOn = enabled
                }
                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == torchCameraId) lastKnownTorchOn = false
                }
            }, null)
        } catch (e: Exception) {
            DebugLog.e("TorchTool", "Failed to register torch callback", e)
            callbackRegistered.set(false)
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "torch")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val GET_TORCH_STATE = "get_torch_state"
        const val SET_TORCH = "set_torch"
    }
}
