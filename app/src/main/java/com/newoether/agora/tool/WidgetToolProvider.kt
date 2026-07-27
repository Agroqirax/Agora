package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `render_widget`: render a self-contained HTML/CSS/JS document inline in the chat via a
 * sandboxed WebView. Unlike [ImageGenToolProvider], the widget's payload (the HTML itself)
 * is plain text that already lives in the call's `toolArgs` — this provider only validates
 * the arguments and returns a status; the UI reads the HTML straight out of the segment's
 * `toolArgs`, so no file is written and no result-side data needs to be produced.
 */
class WidgetToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.htmlWidgetsEnabled) return emptyList()
        val themeNote = if (ctx.htmlWidgetsThemeEnabled) {
            " The widget's background is transparent and renders on the user's actual app background, and the following " +
                "Material 3 color scheme is available as CSS custom properties: --md-primary, --md-on-primary, --md-secondary, " +
                "--md-on-secondary, --md-background, --md-on-background, --md-surface, --md-on-surface, --md-surface-variant, " +
                "--md-on-surface-variant, --md-outline, --md-error, --md-on-error (e.g. `color: var(--md-on-surface)`). " +
                "Use these instead of hardcoded colors so the widget matches the user's light/dark theme and stays legible — " +
                "text or controls that assume an opaque white background may be invisible."
        } else {
            " The widget renders on an opaque background (not transparent, no theme color variables available)."
        }
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = RENDER_WIDGET,
                description = "Render an interactive HTML/CSS/JS widget inline in the chat, shown to the user automatically. " +
                    "Use this for visualizations, small interactive tools, mini-games, or UI mockups the user can directly interact with. " +
                    "Provide a complete, self-contained HTML document (inline <style>/<script> only — remote resources such as CDN scripts, " +
                    "web fonts, or remote images are blocked unless the user has enabled network access for widgets in settings)." +
                    themeNote,
                parameters = ToolParameters(
                    properties = mapOf(
                        "html" to ToolProperty("string", "A complete, self-contained HTML document to render."),
                        "title" to ToolProperty("string", "Optional short label shown above the widget.")
                    ),
                    required = listOf("html")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == RENDER_WIDGET

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.htmlWidgetsEnabled) return err("disabled", "The HTML widgets tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        val html = arg(args, "html").ifBlank { null }
            ?: return err("invalid_argument", "html is required.")

        return buildJsonObject {
            put("type", RENDER_WIDGET)
            put("status", "ok")
            put("length", html.length)
        }.toString()
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", RENDER_WIDGET)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val RENDER_WIDGET = "render_widget"
    }
}
