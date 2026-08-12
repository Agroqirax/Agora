package com.newoether.agora.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.ui.theme.LocalDarkTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Caches the bundled Mermaid.js source across the whole process — it's ~3MB of minified JS,
 *  read from assets once rather than on every widget composition/recomposition. */
private object MermaidAssetCache {
    @Volatile private var cached: String? = null

    fun get(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: context.assets.open("mermaid/mermaid.min.js")
                .bufferedReader()
                .use { it.readText() }
                .also { cached = it }
        }
}

@Composable
private fun rememberMermaidJsSource(): String {
    val context = LocalContext.current
    return remember { MermaidAssetCache.get(context) }
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Builds Mermaid's `theme: "base"` `themeVariables` from the app's actual Material color roles,
 * the same set already used by [rememberChatWidgetThemeCss] for HTML widgets — `"base"` is Mermaid's
 * only theme that honors custom `themeVariables` fully; the built-in "dark"/"default" themes
 * mostly ignore them and use their own fixed palettes, which is why the diagrams didn't actually
 * match the app before.
 */
@Composable
private fun rememberMermaidThemeVariablesJson(): String {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb().toCssHex()
    val onPrimary = scheme.onPrimary.toArgb().toCssHex()
    val surface = scheme.surface.toArgb().toCssHex()
    val onSurface = scheme.onSurface.toArgb().toCssHex()
    val surfaceVariant = scheme.surfaceVariant.toArgb().toCssHex()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb().toCssHex()
    val outline = scheme.outline.toArgb().toCssHex()
    val error = scheme.error.toArgb().toCssHex()
    val onError = scheme.onError.toArgb().toCssHex()
    val darkMode = LocalDarkTheme.current
    return remember(primary, onPrimary, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, error, onError, darkMode) {
        buildJsonObject {
                put("darkMode", darkMode)
                put("background", surface)
                put("textColor", onSurface)
                put("lineColor", onSurfaceVariant)
                put("edgeLabelBackground", surface)
                put("mainBkg", surfaceVariant)
                // "primary" node/actor/label styling
                put("primaryColor", surfaceVariant)
                put("primaryTextColor", onSurface)
                put("primaryBorderColor", primary)
                // secondary/tertiary reuse the same neutral palette — most diagrams only ever
                // reach "primary" anyway, this just keeps anything that does fall back sane.
                put("secondaryColor", surfaceVariant)
                put("secondaryTextColor", onSurface)
                put("secondaryBorderColor", outline)
                put("tertiaryColor", surfaceVariant)
                put("tertiaryTextColor", onSurface)
                put("tertiaryBorderColor", outline)
                // sequence diagrams
                put("actorBkg", surfaceVariant)
                put("actorBorder", outline)
                put("actorTextColor", onSurface)
                put("actorLineColor", outline)
                put("signalColor", onSurface)
                put("signalTextColor", onSurface)
                put("labelBoxBkgColor", surfaceVariant)
                put("labelBoxBorderColor", outline)
                put("labelTextColor", onSurface)
                put("loopTextColor", onSurface)
                put("activationBorderColor", outline)
                put("activationBkgColor", surfaceVariant)
                put("sequenceNumberColor", onPrimary)
                // notes (flowchart/sequence/class/state)
                put("noteBkgColor", surfaceVariant)
                put("noteTextColor", onSurface)
                put("noteBorderColor", outline)
                // errors
                put("errorBkgColor", error)
                put("errorTextColor", onError)
        }.toString()
    }
}

/**
 * Builds a self-contained HTML document that inlines the bundled Mermaid.js and renders
 * [diagramSource] into an SVG on load. `securityLevel: "strict"` disables Mermaid's own
 * click-handler/script-binding diagram features — model-authored diagram text is untrusted
 * input, same posture as the HTML widget sandbox.
 */
private fun buildMermaidHtml(diagramSource: String, themeVariablesJson: String, jsSource: String): String {
    return """
        <html>
        <head>
        <style>
        html, body { background: transparent; margin: 0; padding: 8px; }
        </style>
        </head>
        <body>
        <div class="mermaid">${escapeHtml(diagramSource)}</div>
        <script>$jsSource</script>
        <script>
          mermaid.initialize({
            startOnLoad: true,
            theme: "base",
            themeVariables: $themeVariablesJson,
            securityLevel: "strict"
          });
        </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun MermaidChatWidgetCard(
    diagramSource: String,
    onExpand: (ExpandedChatWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val jsSource = rememberMermaidJsSource()
    // Follows the app's current Material theme, not the OS dark-mode setting, so it always
    // matches what the widget card itself is rendered against.
    val themeVariablesJson = rememberMermaidThemeVariablesJson()
    val documentHtml = remember(diagramSource, themeVariablesJson, jsSource) {
        buildMermaidHtml(diagramSource, themeVariablesJson, jsSource)
    }
    ChatWidgetCard(
        sourceText = diagramSource,
        documentHtml = documentHtml,
        allowNetwork = false,
        allowJavaScript = true,
        transparentBackground = true,
        onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = false, allowJavaScript = true, transparentBackground = true)) },
        modifier = modifier
    )
}
