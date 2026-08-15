package com.newoether.agora.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Caches the bundled Chart.js UMD build across the whole process, same rationale as the other
 *  widget asset caches (MermaidAssetCache, VegaAssetCache, LeafletAssetCache) — read once. */
private object ChartJsAssetCache {
    @Volatile private var cached: String? = null

    fun get(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: context.assets.open("chartjs/chart.min.js")
                .bufferedReader().use { it.readText() }.also { cached = it }
        }
}

@Composable
private fun rememberChartJsSource(): String {
    val context = LocalContext.current
    return remember { ChartJsAssetCache.get(context) }
}

/**
 * Builds the `window.chartTheme` object model-authored chart scripts reference for dataset/series
 * colors (`chartTheme.primary`, `chartTheme.palette[i]`, ...), mirroring the Material role set
 * already exposed to Vega-Lite specs via [rememberVegaLiteThemeConfigJson]'s `config`.
 */
@Composable
private fun rememberChartJsThemeJson(): String {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb().toCssHex()
    val onPrimary = scheme.onPrimary.toArgb().toCssHex()
    val secondary = scheme.secondary.toArgb().toCssHex()
    val tertiary = scheme.tertiary.toArgb().toCssHex()
    val surface = scheme.surface.toArgb().toCssHex()
    val onSurface = scheme.onSurface.toArgb().toCssHex()
    val surfaceVariant = scheme.surfaceVariant.toArgb().toCssHex()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb().toCssHex()
    val outline = scheme.outline.toArgb().toCssHex()
    val error = scheme.error.toArgb().toCssHex()
    return remember(primary, onPrimary, secondary, tertiary, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, error) {
        buildJsonObject {
            put("primary", primary)
            put("onPrimary", onPrimary)
            put("secondary", secondary)
            put("tertiary", tertiary)
            put("surface", surface)
            put("onSurface", onSurface)
            put("surfaceVariant", surfaceVariant)
            put("onSurfaceVariant", onSurfaceVariant)
            put("outline", outline)
            put("error", error)
            put("palette", buildJsonArray {
                add(JsonPrimitive(primary))
                add(JsonPrimitive(secondary))
                add(JsonPrimitive(tertiary))
                add(JsonPrimitive(error))
                add(JsonPrimitive(outline))
            })
        }.toString()
    }
}

/**
 * Builds a self-contained HTML document with a ready-made `<canvas id="chart">` and the bundled
 * Chart.js loaded as the global `Chart`. Unlike every other widget kind, [scriptSource] is raw
 * JavaScript the model wrote (not a declarative spec) — it's inlined as-is inside a `<script>` tag
 * (only the literal `</script` sequence is escaped, same trick as every other widget's embedding),
 * wrapped in try/catch so a script error surfaces via the same console-to-logcat routing as every
 * other widget instead of leaving a blank card. `Chart.defaults.color`/`borderColor` are set from
 * the theme before the model's script runs so an unstyled chart still looks reasonable even if the
 * model never references `chartTheme`.
 */
private fun buildChartJsHtml(scriptSource: String, themeJson: String, chartJs: String): String {
    val safeScript = scriptSource.replace("</", "<\\/")
    return """
        <html>
        <head>
        <style>
        html, body { background: transparent; margin: 0; padding: 0; height: 100%; overflow: hidden; }
        #chart-container { position: relative; width: 100%; height: 100%; padding: 8px; box-sizing: border-box; }
        </style>
        </head>
        <body>
        <div id="chart-container"><canvas id="chart"></canvas></div>
        <script>$chartJs</script>
        <script>
          window.chartTheme = $themeJson;
          Chart.defaults.color = window.chartTheme.onSurface;
          Chart.defaults.borderColor = window.chartTheme.outline;
          Chart.defaults.backgroundColor = window.chartTheme.primary;
          Chart.defaults.maintainAspectRatio = false;
          Chart.defaults.responsive = true;
        </script>
        <script>
          try {
            $safeScript
          } catch (e) {
            console.error('chart.js widget script failed: ' + (e && e.stack ? e.stack : e));
          }
        </script>
        </body>
        </html>
    """.trimIndent()
}

// Chart.js canvases inside a responsive container with no intrinsic CSS height are prone to
// reporting zero natural height (or feedback-looping on resize) to ChatWidgetCard's default JS
// scrollHeight measurement — sidestep that entirely with a fixed height, same approach the map
// widget uses for the same reason (see GeoJsonMapHeight).
private val ChartJsWidgetHeight = 280.dp

@Composable
fun ChartJsChatWidgetCard(
    scriptSource: String,
    allowNetwork: Boolean,
    onExpand: (ExpandedChatWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val chartJs = rememberChartJsSource()
    val themeJson = rememberChartJsThemeJson()
    val documentHtml = remember(scriptSource, themeJson, chartJs) {
        buildChartJsHtml(scriptSource, themeJson, chartJs)
    }
    ChatWidgetCard(
        sourceText = scriptSource,
        documentHtml = documentHtml,
        allowNetwork = allowNetwork,
        allowJavaScript = true,
        transparentBackground = true,
        onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = allowNetwork, allowJavaScript = true, transparentBackground = true)) },
        fixedHeight = ChartJsWidgetHeight,
        modifier = modifier
    )
}
