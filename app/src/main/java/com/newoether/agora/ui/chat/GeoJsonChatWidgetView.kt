package com.newoether.agora.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.LocalDarkTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Caches the bundled Leaflet.js/leaflet.css source across the whole process, same rationale as
 *  [com.newoether.agora.ui.chat.MermaidChatWidgetView]'s asset cache — read from assets once. */
private object LeafletAssetCache {
    @Volatile private var cachedJs: String? = null
    @Volatile private var cachedCss: String? = null

    fun js(context: Context): String =
        cachedJs ?: synchronized(this) {
            cachedJs ?: context.assets.open("leaflet/leaflet.js")
                .bufferedReader().use { it.readText() }.also { cachedJs = it }
        }

    fun css(context: Context): String =
        cachedCss ?: synchronized(this) {
            cachedCss ?: context.assets.open("leaflet/leaflet.css")
                .bufferedReader().use { it.readText() }.also { cachedCss = it }
        }
}

@Composable
private fun rememberLeafletJs(): String {
    val context = LocalContext.current
    return remember { LeafletAssetCache.js(context) }
}

@Composable
private fun rememberLeafletCss(): String {
    val context = LocalContext.current
    return remember { LeafletAssetCache.css(context) }
}

/** A pin derived from a GeoJSON `Point`/`MultiPoint` feature, for the native "open in maps" chip row. */
private data class GeoJsonPin(val lat: Double, val lon: Double, val label: String)

/**
 * Walks the fence body just enough to drive the native "open in maps" pin buttons: finds Point
 * coordinates. The map itself is rendered by Leaflet's own (much more tolerant) GeoJSON parser
 * inside the WebView, so this only needs to succeed at extracting what it can — a `null` return
 * means the body isn't even valid JSON, which is the one case the widget treats as a hard error.
 */
private fun parseGeoJsonPins(source: String): List<GeoJsonPin>? {
    val root = try { Json.parseToJsonElement(source) } catch (_: Exception) { return null }
    val obj = root as? JsonObject ?: return null

    val pins = mutableListOf<GeoJsonPin>()
    var pinCounter = 0

    fun coordPair(element: JsonElement?): Pair<Double, Double>? {
        val arr = element as? JsonArray ?: return null
        val lon = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        return lat to lon
    }

    fun handleGeometry(geometry: JsonObject, label: String?) {
        val type = geometry["type"]?.jsonPrimitive?.contentOrNull ?: return
        val coordinates = geometry["coordinates"]
        when (type) {
            "Point" -> coordPair(coordinates)?.let { (lat, lon) ->
                pinCounter++
                pins += GeoJsonPin(lat, lon, label ?: "Pin $pinCounter")
            }
            "MultiPoint" -> (coordinates as? JsonArray)?.forEach { c ->
                coordPair(c)?.let { (lat, lon) ->
                    pinCounter++
                    pins += GeoJsonPin(lat, lon, label ?: "Pin $pinCounter")
                }
            }
            "GeometryCollection" -> (geometry["geometries"] as? JsonArray)?.forEach { g ->
                (g as? JsonObject)?.let { handleGeometry(it, label) }
            }
            // LineString/MultiLineString/Polygon/MultiPolygon: rendered on the map by Leaflet, but
            // none of them reduce to a single geo: point worth a pin button — skipped here.
        }
    }

    fun handleFeature(feature: JsonObject) {
        val props = feature["properties"] as? JsonObject
        val label = props?.get("name")?.jsonPrimitive?.contentOrNull
            ?: props?.get("title")?.jsonPrimitive?.contentOrNull
        (feature["geometry"] as? JsonObject)?.let { handleGeometry(it, label) }
    }

    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "FeatureCollection" -> (obj["features"] as? JsonArray)?.forEach { f -> (f as? JsonObject)?.let(::handleFeature) }
        "Feature" -> handleFeature(obj)
        else -> handleGeometry(obj, null)
    }

    // Cap what's shown: a handful of pin chips reads fine, an unbounded row from a large
    // FeatureCollection wouldn't.
    return pins.take(5)
}

private fun geoUri(lat: Double, lon: Double, label: String): Uri =
    Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")

private fun openInMapsApp(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No app on the device can handle this intent — fail silently, same posture as
        // link-tap handling elsewhere in the widget sandbox.
    }
}

/**
 * Builds a self-contained HTML document that inlines the bundled Leaflet and renders
 * [geoJsonSource] on an interactive pan/zoom map. Basemap tile imagery is always fetched from
 * [tileUrl] (a `{s}/{z}/{x}/{y}`-style template, defaulting to OpenStreetMap) — tile loading is
 * not gated behind a network toggle since a map with no basemap isn't a useful map. The GeoJSON
 * payload is embedded as a `<script type="application/json">` block (not a JS string literal) so
 * odd characters in properties can't need JS-string escaping — only the literal `</script`
 * sequence is escaped, which the HTML parser would otherwise treat as closing the tag early.
 *
 * When [themeTiles] is set and the app is currently in dark mode, the tile layer gets a CSS
 * `filter` that inverts/recolors the (necessarily light-background) raster tiles to roughly match
 * a dark UI — the same trick as https://github.com/BrendonKoz/gist:b1df234fe3ee388b402cd8e98f7eedbd.
 * It's a lossy patch (map labels/colors don't actually change, just get inverted+hue-rotated), so
 * it stays opt-in rather than automatic — many tile servers already ship their own dark styles,
 * which this would double-invert into something worse.
 */
private fun buildGeoJsonHtml(
    geoJsonSource: String,
    tileUrl: String,
    themeTiles: Boolean,
    darkMode: Boolean,
    leafletCss: String,
    leafletJs: String,
    primaryColor: String,
    onPrimaryColor: String,
    surfaceVariantColor: String,
    onSurfaceColor: String,
    outlineColor: String,
    controlBgColor: String,
    controlHoverColor: String,
    controlDisabledColor: String,
): String {
    val safeData = geoJsonSource.replace("</", "<\\/")
    val safeTileUrl = tileUrl.replace("'", "\\'")
    val tileLayerJs = """L.tileLayer('$safeTileUrl', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        className: 'map-tiles'
    }).addTo(map);"""
    val tileFilterCss = if (themeTiles && darkMode) {
        ".map-tiles { filter: brightness(0.6) invert(1) contrast(3) hue-rotate(200deg) saturate(0.3) brightness(0.7); }"
    } else ""
    return """
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
        <style>$leafletCss</style>
        <style>
        html, body { background: transparent; margin: 0; padding: 0; height: 100%; overflow: hidden; }
        #map { position: absolute; top: 0; left: 0; background: transparent; }
        .leaflet-control-attribution { font-size: 9px; }
        .leaflet-bar { box-shadow: 0 1px 4px rgba(0,0,0,0.4); }
        .leaflet-bar a {
          background-color: $controlBgColor;
          color: $onSurfaceColor;
          border-bottom: 1px solid $outlineColor;
        }
        .leaflet-bar a:hover,
        .leaflet-bar a:focus { background-color: $controlHoverColor; }
        .leaflet-bar a.leaflet-disabled {
          background-color: $controlBgColor;
          color: $controlDisabledColor;
        }
        $tileFilterCss
        </style>
        </head>
        <body>
        <div id="map"></div>
        <script type="application/json" id="geojson-data">$safeData</script>
        <script>$leafletJs</script>
        <script>
          var mapEl = document.getElementById('map');
          // Size the container from window.innerWidth/innerHeight in JS, not a CSS percentage/
          // inset chain through html/body — Leaflet reads getBoundingClientRect() on init, and
          // this sidesteps any doubt about how that chain resolves on a given WebView version.
          function sizeMapEl() {
            mapEl.style.width = window.innerWidth + 'px';
            mapEl.style.height = window.innerHeight + 'px';
          }
          sizeMapEl();
          try {
            var map = L.map(mapEl, { attributionControl: true, zoomControl: true });
            $tileLayerJs
            var data = JSON.parse(document.getElementById('geojson-data').textContent);
            var layer = L.geoJSON(data, {
              style: function(feature) {
                return { color: '$primaryColor', weight: 3, fillColor: '$surfaceVariantColor', fillOpacity: 0.35 };
              },
              pointToLayer: function(feature, latlng) {
                return L.circleMarker(latlng, { radius: 8, color: '$onPrimaryColor', weight: 2, fillColor: '$primaryColor', fillOpacity: 1 });
              }
            }).addTo(map);
            function fit() {
              sizeMapEl();
              map.invalidateSize();
              if (layer.getBounds().isValid()) {
                map.fitBounds(layer.getBounds(), { padding: [24, 24] });
              } else {
                map.setView([0, 0], 2);
              }
            }
            fit();
            // The ChatWidgetCard host animates its height in from an initial guess once the real
            // content height is known (see ChatWidgetCard's contentHeight logic) — Leaflet caches the
            // container size at init and won't notice that change on its own, so re-measure
            // whenever the WebView's own viewport size changes.
            window.addEventListener('resize', fit);
            if (window.ResizeObserver) {
              new ResizeObserver(fit).observe(document.body);
            } else {
              setTimeout(fit, 300);
            }
          } catch (e) {
            console.error('geojson map init failed: ' + (e && e.stack ? e.stack : e));
          }
        </script>
        </body>
        </html>
    """.trimIndent()
}

// Maps don't have a natural content height for ChatWidgetCard's default JS scrollHeight measurement
// to key off (see ChatWidgetCard's fixedHeight doc) — always use this fixed, comfortably map-sized height.
private val GeoJsonMapHeight = 320.dp

@Composable
fun GeoJsonChatWidgetCard(
    source: String,
    tileUrl: String,
    themeTiles: Boolean,
    onExpand: (ExpandedChatWidget) -> Unit,
    modifier: Modifier = Modifier,
    // The raw-source toggle shows this instead of [source] when set — for gpx/kml input,
    // [source] is already the converted GeoJSON used to drive Leaflet, but the "view source"
    // action should show the original GPX/KML text the model actually authored.
    displaySource: String = source,
) {
    val pins = remember(source) { parseGeoJsonPins(source) }
    if (pins == null) {
        ChatWidgetCard(
            sourceText = displaySource,
            documentHtml = "<html><body style=\"font-family:sans-serif;color:#b00020;padding:12px;\">Invalid GeoJSON</body></html>",
            allowNetwork = true,
            allowJavaScript = true,
            transparentBackground = true,
            onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = true, allowJavaScript = true, transparentBackground = true)) },
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val leafletJs = rememberLeafletJs()
    val leafletCss = rememberLeafletCss()
    val scheme = MaterialTheme.colorScheme
    val primaryColor = scheme.primary.toArgb().toCssHex()
    val onPrimaryColor = scheme.onPrimary.toArgb().toCssHex()
    val surfaceVariantColor = scheme.surfaceVariant.toArgb().toCssHex()
    val onSurfaceColor = scheme.onSurface.toArgb().toCssHex()
    val outlineColor = scheme.outline.toArgb().toCssHex()
    val controlBgColor = scheme.surfaceContainer.toArgb().toCssHex()
    val controlHoverColor = scheme.surfaceContainerHighest.toArgb().toCssHex()
    val controlDisabledColor = scheme.onSurface.copy(alpha = 0.38f).compositeOver(scheme.surfaceContainer).toArgb().toCssHex()
    val darkMode = LocalDarkTheme.current
    val documentHtml = remember(
        source, tileUrl, themeTiles, darkMode, leafletJs, leafletCss,
        primaryColor, onPrimaryColor, surfaceVariantColor,
        onSurfaceColor, outlineColor, controlBgColor, controlHoverColor, controlDisabledColor,
    ) {
        buildGeoJsonHtml(
            source, tileUrl, themeTiles, darkMode, leafletCss, leafletJs,
            primaryColor, onPrimaryColor, surfaceVariantColor,
            onSurfaceColor, outlineColor, controlBgColor, controlHoverColor, controlDisabledColor,
        )
    }

    Column(modifier = modifier) {
        ChatWidgetCard(
            sourceText = displaySource,
            documentHtml = documentHtml,
            allowNetwork = true,
            allowJavaScript = true,
            transparentBackground = true,
            onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = true, allowJavaScript = true, transparentBackground = true)) },
            fixedHeight = GeoJsonMapHeight,
        )
        if (pins.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pins.forEach { pin ->
                    AssistChip(
                        onClick = { openInMapsApp(context, geoUri(pin.lat, pin.lon, pin.label)) },
                        label = { Text(pin.label) },
                        leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
                    )
                }
            }
        }
    }
}
