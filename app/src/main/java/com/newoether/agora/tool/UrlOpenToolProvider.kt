package com.newoether.agora.tool

import android.app.Application
import android.content.Intent
import android.net.Uri
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
 * `open_url`: open a URL in the user's browser (or whichever app handles it), the same as
 * tapping a link. Not gated: this is the same trust boundary as [AppLaunchToolProvider]'s
 * `open_app` — switching the foreground app to show a link isn't destructive or irreversible.
 */
class UrlOpenToolProvider(private val app: Application) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.urlOpenEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = OPEN_URL,
                description = "Open a URL in the browser, the same as tapping a link.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The URL to open, e.g. \"https://example.com\".")
                    ),
                    required = listOf("url")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == OPEN_URL

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.urlOpenEnabled) return err("disabled", "The open-url tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        val url = arg(args, "url").ifBlank { null }
            ?: return err("invalid_argument", "url is required.")

        return try {
            openUrl(url)
        } catch (e: Exception) {
            DebugLog.e("UrlOpenTool", "open_url failed for $url", e)
            err("url_open_error", e.message)
        }
    }

    private suspend fun openUrl(url: String): String = withContext(Dispatchers.IO) {
        val uri = try {
            Uri.parse(url).takeIf { it.scheme != null } ?: Uri.parse("https://$url")
        } catch (e: Exception) {
            return@withContext err("invalid_argument", "\"$url\" isn't a valid URL.")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(app.packageManager) == null) {
            return@withContext err("not_openable", "No app found to open \"$url\".")
        }
        app.startActivity(intent)
        buildJsonObject {
            put("type", OPEN_URL)
            put("url", uri.toString())
            put("ok", true)
        }.toString()
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", OPEN_URL)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val OPEN_URL = "open_url"
    }
}
