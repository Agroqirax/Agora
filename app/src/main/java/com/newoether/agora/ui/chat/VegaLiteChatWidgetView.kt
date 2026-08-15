package com.newoether.agora.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Caches the bundled Vega/Vega-Lite/vega-embed sources across the whole process, same rationale
 *  as [MermaidAssetCache] — read from assets once rather than on every widget composition. */
private object VegaAssetCache {
    @Volatile private var cachedVega: String? = null
    @Volatile private var cachedVegaLite: String? = null
    @Volatile private var cachedVegaEmbed: String? = null

    fun vega(context: Context): String =
        cachedVega ?: synchronized(this) {
            cachedVega ?: context.assets.open("vega/vega.min.js")
                .bufferedReader().use { it.readText() }.also { cachedVega = it }
        }

    fun vegaLite(context: Context): String =
        cachedVegaLite ?: synchronized(this) {
            cachedVegaLite ?: context.assets.open("vega/vega-lite.min.js")
                .bufferedReader().use { it.readText() }.also { cachedVegaLite = it }
        }

    fun vegaEmbed(context: Context): String =
        cachedVegaEmbed ?: synchronized(this) {
            cachedVegaEmbed ?: context.assets.open("vega/vega-embed.min.js")
                .bufferedReader().use { it.readText() }.also { cachedVegaEmbed = it }
        }
}

@Composable
private fun rememberVegaJsSources(): Triple<String, String, String> {
    val context = LocalContext.current
    return remember {
        Triple(VegaAssetCache.vega(context), VegaAssetCache.vegaLite(context), VegaAssetCache.vegaEmbed(context))
    }
}

/**
 * Builds Vega-Lite's `config` object from the app's actual Material color roles — the same
 * palette [rememberMermaidThemeVariablesJson] draws from — so charts always match the current
 * light/dark theme instead of Vega-Lite's own fixed default palette.
 */
@Composable
private fun rememberVegaLiteThemeConfigJson(): String {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb().toCssHex()
    val onSurface = scheme.onSurface.toArgb().toCssHex()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb().toCssHex()
    val outline = scheme.outline.toArgb().toCssHex()
    val secondary = scheme.secondary.toArgb().toCssHex()
    val tertiary = scheme.tertiary.toArgb().toCssHex()
    val error = scheme.error.toArgb().toCssHex()
    return remember(primary, onSurface, onSurfaceVariant, outline, secondary, tertiary, error) {
        buildJsonObject {
            put("background", "transparent")
            put("title", buildJsonObject { put("color", onSurface) })
            put("axis", buildJsonObject {
                put("domainColor", outline)
                put("gridColor", onSurfaceVariant)
                put("gridOpacity", 0.3)
                put("tickColor", outline)
                put("labelColor", onSurfaceVariant)
                put("titleColor", onSurface)
            })
            put("legend", buildJsonObject {
                put("labelColor", onSurfaceVariant)
                put("titleColor", onSurface)
            })
            put("header", buildJsonObject { put("labelColor", onSurfaceVariant); put("titleColor", onSurface) })
            put("range", buildJsonObject {
                put("category", buildJsonArray {
                    add(JsonPrimitive(primary))
                    add(JsonPrimitive(secondary))
                    add(JsonPrimitive(tertiary))
                    add(JsonPrimitive(error))
                    add(JsonPrimitive(outline))
                })
            })
            put("mark", buildJsonObject { put("color", primary) })
        }.toString()
    }
}

/**
 * CSS for the HTML form controls vega-embed renders for a spec's `bind`ings (sliders, dropdowns,
 * checkboxes, radio groups) — these are plain `<input>`/`<select>`/`<label>` elements outside the
 * chart's own SVG, so [rememberVegaLiteThemeConfigJson]'s Vega-Lite `config` (which only styles
 * marks/axes/legends) never touches them. Left unstyled they render with the browser's default
 * light-mode form-control chrome, same problem [rememberChatWidgetThemeCss] fixes for HTML widgets —
 * this mirrors that same input/select/range/checkbox styling against the app's Material colors.
 */
@Composable
private fun rememberVegaLiteControlsCss(): String {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb().toCssHex()
    val onSurface = scheme.onSurface.toArgb().toCssHex()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb().toCssHex()
    val surfaceVariant = scheme.surfaceVariant.toArgb().toCssHex()
    val outline = scheme.outline.toArgb().toCssHex()
    return remember(primary, onSurface, onSurfaceVariant, surfaceVariant, outline) {
        """
        .vega-bindings {
          color: $onSurface;
          font-family: sans-serif;
          font-size: 12px;
        }
        .vega-bind { margin-top: 6px; }
        .vega-bind-name { color: $onSurfaceVariant; margin-right: 6px; }
        select, input[type=text], input[type=number] {
          font-family: inherit; color: $onSurface;
          background-color: $surfaceVariant;
          border: 1px solid $outline; border-radius: 8px; padding: 4px 8px;
        }
        input[type=range] { accent-color: $primary; background: transparent; border: none; padding: 0; vertical-align: middle; }
        input[type=checkbox], input[type=radio] { accent-color: $primary; }
        """.trimIndent()
    }
}

/**
 * Builds a self-contained HTML document that inlines the bundled Vega/Vega-Lite/vega-embed and
 * renders [specJson] into an SVG on load. The spec is embedded as a `<script type="application/
 * json">` block (not a JS string literal), same trick as [buildGeoJsonHtml] uses, so arbitrary
 * spec content never needs JS-string escaping — only the literal `</script` sequence is escaped.
 * `actions: false` hides vega-embed's default export/source menu, which would otherwise float a
 * "..." button over model-authored chart content.
 */
private fun buildVegaLiteHtml(specJson: String, themeConfigJson: String, controlsCss: String, vegaJs: String, vegaLiteJs: String, vegaEmbedJs: String): String {
    val safeSpec = specJson.replace("</", "<\\/")
    return """
        <html>
        <head>
        <style>
        html, body { background: transparent; margin: 0; padding: 8px; }
        #view { width: 100%; }
        $controlsCss
        </style>
        </head>
        <body>
        <div id="view"></div>
        <script type="application/json" id="vegalite-spec">$safeSpec</script>
        <script>$vegaJs</script>
        <script>$vegaLiteJs</script>
        <script>$vegaEmbedJs</script>
        <script>
          try {
            var spec = JSON.parse(document.getElementById('vegalite-spec').textContent);
            vegaEmbed('#view', spec, { config: $themeConfigJson, actions: false, renderer: 'svg' });
          } catch (e) {
            console.error('vega-lite render failed: ' + (e && e.stack ? e.stack : e));
          }
        </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun VegaLiteChatWidgetCard(
    specSource: String,
    allowNetwork: Boolean,
    onExpand: (ExpandedChatWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val isValidJson = remember(specSource) {
        try { Json.parseToJsonElement(specSource); true } catch (_: Exception) { false }
    }
    if (!isValidJson) {
        ChatWidgetCard(
            sourceText = specSource,
            documentHtml = "<html><body style=\"font-family:sans-serif;color:#b00020;padding:12px;\">Invalid Vega-Lite spec</body></html>",
            allowNetwork = allowNetwork,
            allowJavaScript = true,
            transparentBackground = true,
            onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = allowNetwork, allowJavaScript = true, transparentBackground = true)) },
            modifier = modifier
        )
        return
    }

    val (vegaJs, vegaLiteJs, vegaEmbedJs) = rememberVegaJsSources()
    val themeConfigJson = rememberVegaLiteThemeConfigJson()
    val controlsCss = rememberVegaLiteControlsCss()
    val documentHtml = remember(specSource, themeConfigJson, controlsCss, vegaJs, vegaLiteJs, vegaEmbedJs) {
        buildVegaLiteHtml(specSource, themeConfigJson, controlsCss, vegaJs, vegaLiteJs, vegaEmbedJs)
    }
    ChatWidgetCard(
        sourceText = specSource,
        documentHtml = documentHtml,
        allowNetwork = allowNetwork,
        allowJavaScript = true,
        transparentBackground = true,
        onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = allowNetwork, allowJavaScript = true, transparentBackground = true)) },
        modifier = modifier
    )
}
