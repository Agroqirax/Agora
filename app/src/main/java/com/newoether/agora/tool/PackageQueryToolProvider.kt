package com.newoether.agora.tool

import android.app.Application
import android.content.pm.PackageManager
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Two tools sharing one toggle and one [PackageQueryProvider]: `list_installed_apps` for
 * device-wide discovery, `get_app_info` for everything about one already-identified app —
 * the same list/detail split [NotificationToolProvider] uses (list_notifications vs
 * get_notification). `list_installed_apps` only returns package_name/app_label, so
 * scanning the whole device stays cheap; version, system-app status, and static shortcuts
 * (see [AppShortcuts]) are all per-package lookups that only happen for a package the
 * model actually asked about.
 *
 * [provider] is null (or reports unavailable) only if flavor detection itself failed, in
 * which case this contributes no tool definitions at all. On a normal build, one of the
 * two flavor implementations is always present: fdroid returns every installed package,
 * Play returns apps with a launcher icon (see [PackageQueryProvider]'s doc comment for why
 * that split exists) — `get_app_info` is scoped the same way per flavor, since it goes
 * through the same [PackageQueryProvider.getPackageInfo].
 */
class PackageQueryToolProvider(
    private val provider: PackageQueryProvider?,
    private val app: Application
) : ToolProvider {

    private val toolNames = setOf(LIST_INSTALLED_APPS, GET_APP_INFO)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.packageQueryEnabled || provider?.isAvailable() != true) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = LIST_INSTALLED_APPS,
                description = "List apps installed on the user's device: package name and app label. Use " +
                    "$GET_APP_INFO for version, system-app status, or shortcuts for a specific app. On some " +
                    "builds of Agora this only includes apps with a home-screen icon, not every " +
                    "background/system package.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "include_system_apps" to ToolProperty(
                            type = "boolean",
                            description = "Include preinstalled/system apps, not just user-installed ones. Defaults to false."
                        )
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = GET_APP_INFO,
                description = "Get details for one app: version name, whether it's a system app, and its " +
                    "static/fixed shortcuts (e.g. a messaging app's \"New conversation\") — not dynamic or " +
                    "recently-used shortcuts, which aren't accessible to other apps on Android. Pass a " +
                    "shortcut_id from here to $OPEN_APP to fire it.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "package_name" to ToolProperty("string", "The app's package name, from $LIST_INSTALLED_APPS.")
                    ),
                    required = listOf("package_name")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.packageQueryEnabled) return err(name, "disabled", "The installed-apps tool is disabled in settings.")
        val p = provider
        if (p == null || !p.isAvailable()) {
            return err(name, "unavailable", "The installed-apps tool isn't available on this build of Agora.")
        }

        return when (name) {
            LIST_INSTALLED_APPS -> listInstalledApps(p, arguments)
            GET_APP_INFO -> getAppInfo(p, arguments)
            else -> err(name, "unknown_tool", "Unknown tool: $name")
        }
    }

    private fun listInstalledApps(p: PackageQueryProvider, arguments: String): String {
        val includeSystem = try {
            Json.parseToJsonElement(arguments).jsonObject["include_system_apps"]?.jsonPrimitive?.booleanOrNull ?: false
        } catch (_: Exception) { false }

        return try {
            val apps = p.listInstalledPackages(includeSystem)
            buildJsonObject {
                put("type", LIST_INSTALLED_APPS)
                put("count", apps.size)
                put("apps", buildJsonArray {
                    apps.forEach { app ->
                        addJsonObject {
                            put("package_name", app.packageName)
                            put("app_label", app.appLabel)
                        }
                    }
                })
            }.toString()
        } catch (e: Exception) {
            DebugLog.e("PackageQueryTool", "$LIST_INSTALLED_APPS failed", e)
            err(LIST_INSTALLED_APPS, "query_error", e.message)
        }
    }

    private suspend fun getAppInfo(p: PackageQueryProvider, arguments: String): String = withContext(Dispatchers.IO) {
        val packageName = try {
            Json.parseToJsonElement(arguments).jsonObject["package_name"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        if (packageName.isNullOrBlank()) {
            return@withContext err(GET_APP_INFO, "invalid_argument", "package_name is required.")
        }

        val info = p.getPackageInfo(packageName)
            ?: return@withContext err(GET_APP_INFO, "not_found", "\"$packageName\" isn't installed or isn't visible to Agora.")

        val shortcuts = try {
            AppShortcuts.readStatic(app.packageManager, packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("PackageQueryTool", "$GET_APP_INFO shortcut lookup failed for $packageName", e)
            emptyList()
        }

        buildJsonObject {
            put("type", GET_APP_INFO)
            put("package_name", info.packageName)
            put("app_label", info.appLabel)
            info.versionName?.let { put("version_name", it) }
            put("is_system_app", info.isSystemApp)
            put("shortcuts", buildJsonArray {
                shortcuts.forEach { s ->
                    addJsonObject {
                        put("shortcut_id", s.id)
                        put("label", s.shortLabel)
                        s.longLabel?.let { put("long_label", it) }
                        put("enabled", s.enabled)
                        if (!s.enabled) s.disabledMessage?.let { put("disabled_message", it) }
                    }
                }
            })
        }.toString()
    }

    private fun err(toolName: String, code: String, message: String?): String = buildJsonObject {
        put("type", toolName)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val LIST_INSTALLED_APPS = "list_installed_apps"
        const val GET_APP_INFO = "get_app_info"
        private const val OPEN_APP = "open_app"
    }
}
