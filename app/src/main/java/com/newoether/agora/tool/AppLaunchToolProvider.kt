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
 * `open_app`: open an app, the same as tapping its home-screen icon. Discovery — finding
 * a package_name in the first place — lives entirely in [PackageQueryToolProvider]
 * (`list_installed_apps`), since that's a different scope (what exists) from this
 * provider's (act on something already identified); both tools share the same
 * [GenerationContext.packageQueryEnabled] vs [GenerationContext.appLaunchEnabled] split
 * for read vs. act.
 *
 * Not gated: switching the foreground app is what tapping its home-screen icon does too,
 * and isn't destructive or irreversible. (This provider previously also fired an app's
 * static shortcuts, which needed its own confirmation gate — removed for reliability;
 * shortcut XML declarations vary too much across apps to fire consistently.)
 */
class AppLaunchToolProvider(private val app: Application) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.appLaunchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = OPEN_APP,
                description = "Open an app, the same as tapping its home-screen icon.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package_name" to ToolProperty("string", "The app's package name, e.g. \"com.spotify.music\".")
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

        return try {
            launchApp(packageName)
        } catch (e: Exception) {
            DebugLog.e("AppLaunchTool", "open_app failed for $packageName", e)
            err("app_launch_error", e.message)
        }
    }

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
