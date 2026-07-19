package com.newoether.agora.tool

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
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
 * `open_app`: open an app, or one of its static shortcuts, in one call — omitting
 * shortcut_id opens the app itself, passing it (from [PackageQueryToolProvider]'s
 * `get_app_info`) fires that shortcut instead. Same optional-parameter shape as
 * [NotificationToolProvider]'s `interact_notification` (omit action_index to tap the
 * notification itself, pass it to invoke a specific action). Discovery — finding a
 * package_name or a shortcut_id in the first place — lives entirely in
 * [PackageQueryToolProvider] (`list_installed_apps`/`get_app_info`), since that's a
 * different scope (what exists) from this provider's (act on something already
 * identified); both tools share the same [GenerationContext.packageQueryEnabled] vs
 * [GenerationContext.appLaunchEnabled] split for read vs. act.
 *
 * Opening the app itself isn't gated: switching the foreground app is what tapping its
 * home-screen icon does too, and isn't destructive or irreversible. Firing a *shortcut*
 * is different — some static shortcuts trigger the target app's own action immediately
 * (e.g. "call last number") rather than just opening a screen — so that path still goes
 * through [confirmWrite], the same as [CalendarToolProvider]/[ContactsToolProvider].
 *
 * See [AppShortcuts]'s doc comment for why only *static* (manifest-declared) shortcuts
 * are reachable here, and [PlayPackageQueryProvider]'s for how package visibility works
 * without QUERY_ALL_PACKAGES on the Play flavor.
 */
class AppLaunchToolProvider(private val app: Application) : ToolProvider {

    /** In-app confirmation gate for firing a shortcut (not for a plain app launch — see
     *  class doc). Set by the owning ViewModel; null = no gate (proceed). [summary] is a
     *  human-readable description of the action shown in the confirmation dialog. */
    var confirmWrite: (suspend (summary: String) -> Boolean)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.appLaunchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = OPEN_APP,
                description = "Open an app, the same as tapping its home-screen icon. Pass shortcut_id " +
                    "(from get_app_info) to fire one of its static shortcuts instead of opening the app plainly.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package_name" to ToolProperty("string", "The app's package name, e.g. \"com.spotify.music\"."),
                        "shortcut_id" to ToolProperty(
                            "string",
                            "Optional. A shortcut_id from get_app_info — fires that shortcut instead of opening the app plainly."
                        )
                    ),
                    required = listOf("package_name")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == OPEN_APP

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.appLaunchEnabled) return err("disabled", "The app-launch tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        val packageName = arg(args, "package_name").ifBlank { null }
            ?: return err("invalid_argument", "package_name is required.")
        val shortcutId = arg(args, "shortcut_id").ifBlank { null }

        return try {
            if (shortcutId == null) launchApp(packageName) else openShortcut(packageName, shortcutId)
        } catch (e: Exception) {
            DebugLog.e("AppLaunchTool", "open_app failed for $packageName", e)
            err("app_launch_error", e.message)
        }
    }

    // ── plain launch — ungated, see class doc ───────────────

    private suspend fun launchApp(packageName: String): String = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return@withContext err(
                "not_launchable",
                "\"$packageName\" isn't installed, isn't visible to Agora, or has no launcher entry point."
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        buildJsonObject {
            put("type", OPEN_APP)
            put("package_name", packageName)
            appLabelOrNull(pm, packageName)?.let { put("app_label", it) }
            put("ok", true)
        }.toString()
    }

    // ── shortcut — gated, see class doc ──────────────────────

    private suspend fun openShortcut(packageName: String, shortcutId: String): String {
        val shortcuts = try {
            withContext(Dispatchers.IO) { AppShortcuts.readStatic(app.packageManager, packageName) }
        } catch (e: PackageManager.NameNotFoundException) {
            return err("not_found", "\"$packageName\" isn't installed or isn't visible to Agora.")
        }
        val shortcut = shortcuts.find { it.id == shortcutId }
            ?: return err("not_found", "No shortcut \"$shortcutId\" on \"$packageName\". Check get_app_info.")
        if (!shortcut.enabled) {
            return err("disabled", shortcut.disabledMessage ?: "This shortcut is currently disabled.")
        }
        if (shortcut.intents.isEmpty()) {
            return err("no_intent", "This shortcut doesn't declare an action to run.")
        }

        if (confirmWrite?.invoke("Open \"${shortcut.shortLabel}\" (from $packageName)") == false) {
            return err("user_denied", "The user declined to open this shortcut.")
        }

        return withContext(Dispatchers.IO) {
            val pm = app.packageManager
            val intents = shortcut.intents.map { it.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }
            val target = intents.last()
            if (target.resolveActivity(pm) == null) {
                return@withContext err(
                    "not_resolvable",
                    "This shortcut's target activity can't be resolved (app may need an update)."
                )
            }
            if (intents.size > 1) {
                app.startActivities(intents.toTypedArray())
            } else {
                app.startActivity(target)
            }
            buildJsonObject {
                put("type", OPEN_APP)
                put("package_name", packageName)
                put("shortcut_id", shortcutId)
                put("label", shortcut.shortLabel)
                put("ok", true)
            }.toString()
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun appLabelOrNull(pm: PackageManager, packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        null
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", OPEN_APP)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val OPEN_APP = "open_app"
    }
}
