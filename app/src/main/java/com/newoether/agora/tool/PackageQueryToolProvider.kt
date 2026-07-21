package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One tool, backed by one [PackageQueryProvider]: `list_installed_apps` for device-wide
 * discovery. It reports every field on [InstalledPackageInfo] — package name, app label,
 * version name, and system-app status — for each result, plus an optional `query` to
 * search by label/package name.
 *
 * [provider] is the same, non-flavor-specific implementation on every build (see its doc
 * comment), so unlike before there's no "unavailable on this flavor" case to gate on here.
 */
class PackageQueryToolProvider(
    private val provider: PackageQueryProvider
) : ToolProvider {

    private val toolNames = setOf(LIST_INSTALLED_APPS)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.packageQueryEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = LIST_INSTALLED_APPS,
                description = "List apps installed on the user's device, each with its package name, app " +
                    "label, version name, and whether it's a system app. Only includes apps with a " +
                    "home-screen icon, not every background/system package.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "include_system_apps" to ToolProperty(
                            type = "boolean",
                            description = "Include preinstalled/system apps, not just user-installed ones. Defaults to false."
                        ),
                        "query" to ToolProperty(
                            type = "string",
                            description = "Optional search text to filter results by app label or package name (case-insensitive, substring match)."
                        )
                    ),
                    required = emptyList()
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.packageQueryEnabled) return err(name, "disabled", "The installed-apps tool is disabled in settings.")

        return when (name) {
            LIST_INSTALLED_APPS -> listInstalledApps(arguments)
            else -> err(name, "unknown_tool", "Unknown tool: $name")
        }
    }

    private fun listInstalledApps(arguments: String): String {
        val argsObj = try {
            Json.parseToJsonElement(arguments).jsonObject
        } catch (_: Exception) { null }
        val includeSystem = argsObj?.get("include_system_apps")?.jsonPrimitive?.booleanOrNull ?: false
        val query = argsObj?.get("query")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        return try {
            var apps = provider.listInstalledPackages(includeSystem)
            if (query != null) {
                val needle = query.lowercase()
                apps = apps.filter {
                    it.appLabel.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
                }
            }
            buildJsonObject {
                put("type", LIST_INSTALLED_APPS)
                put("count", apps.size)
                put("apps", buildJsonArray {
                    apps.forEach { app ->
                        addJsonObject {
                            put("package_name", app.packageName)
                            put("app_label", app.appLabel)
                            app.versionName?.let { put("version_name", it) }
                            put("is_system_app", app.isSystemApp)
                        }
                    }
                })
            }.toString()
        } catch (e: Exception) {
            DebugLog.e("PackageQueryTool", "$LIST_INSTALLED_APPS failed", e)
            err(LIST_INSTALLED_APPS, "query_error", e.message)
        }
    }

    private fun err(toolName: String, code: String, message: String?): String = buildJsonObject {
        put("type", toolName)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val LIST_INSTALLED_APPS = "list_installed_apps"
    }
}
