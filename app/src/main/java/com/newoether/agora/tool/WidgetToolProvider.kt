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
            " The app already provides Material 3 styling. Use the following CSS variables: such as: --md-primary, --md-on-primary, --md-secondary, --md-on-secondary, --md-background, --md-on-background, --md-surface, --md-on-surface, --md-surface-variant, --md-on-surface-variant, --md-outline, --md-error, --md-on-error. Prefer these variables over hardcoded colors."
        } else {
            " The widget is shown on an opaque background. No theme CSS variables are available."
        }
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = RENDER_WIDGET,
                description =
                    "Display an interactive HTML widget directly in the conversation. " +
                    "Use it whenever the user would benefit from interacting with a visualization, UI, game, calculator, editor, or other small web component. " +

                    "IMPORTANT: The widget is shown automatically after this tool is called. " +
                    "Do NOT paste or explain the HTML/CSS/JavaScript in your response, and do NOT tell the user to copy or run the code themselves. " +
                    "After the tool call, simply continue the conversation normally or briefly explain how to use the widget. " +

                    "Provide only an HTML fragment (elements plus optional inline <style> and <script>). " +
                    "Do not include <html>, <head>, or <body>. " +

                    "Assume the app already provides the page layout and Material 3 styling. " +
                    "Avoid global CSS, body/html styling, viewport sizing (100vh, fixed heights, etc.), or custom layout unless the widget genuinely requires it. " +
                    "Let the content size naturally." +
                    themeNote,
                parameters = ToolParameters(
                    properties = mapOf(
                        "html" to ToolProperty("string", "The widget's HTML content — a fragment, not a full document (no <html>/<head>/<body>)."),
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
            put("message", "The widget was rendered successfully and is already visible to the user. Do not paste, repeat, or describe the HTML/CSS/JS source in your reply.")
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
