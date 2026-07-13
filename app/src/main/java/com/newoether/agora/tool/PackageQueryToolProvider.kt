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
 * Exposes [PackageQueryProvider.listInstalledPackages] as `list_installed_apps`. [provider]
 * is null (or reports unavailable) on the Play flavor, in which case this contributes no
 * tool definitions at all — the model never sees it as an option on that build, rather
 * than seeing it and getting an error every time it's called.
 */
class PackageQueryToolProvider(private val provider: PackageQueryProvider?) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.packageQueryEnabled || provider?.isAvailable() != true) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "list_installed_apps",
                description = "List apps installed on the user's device: package name, app label, version name, and whether it's a system app. Not available on Google Play builds of Agora.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "include_system_apps" to ToolProperty(
                            type = "boolean",
                            description = "Include preinstalled/system apps, not just user-installed ones. Defaults to false."
                        )
                    ),
                    required = emptyList()
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == "list_installed_apps"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.packageQueryEnabled) return err("disabled", "Installed-apps listing is disabled in settings.")
        val p = provider
        if (p == null || !p.isAvailable()) {
            return err("unavailable", "Installed-apps listing isn't available on this build of Agora.")
        }

        val includeSystem = try {
            Json.parseToJsonElement(arguments).jsonObject["include_system_apps"]?.jsonPrimitive?.booleanOrNull ?: false
        } catch (_: Exception) {
            false
        }

        return try {
            val apps = p.listInstalledPackages(includeSystem)
            buildJsonObject {
                put("type", "installed_apps")
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
            DebugLog.e("PackageQueryTool", "list_installed_apps failed", e)
            err("query_error", e.message)
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "installed_apps")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
