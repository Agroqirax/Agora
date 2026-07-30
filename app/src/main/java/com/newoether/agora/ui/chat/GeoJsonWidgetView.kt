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
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Caches the bundled Leaflet.js/leaflet.css source across the whole process, same rationale as
 *  [com.newoether.agora.ui.chat.MermaidWidgetView]'s asset cache — read from assets once. */
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

private data class ParsedGeoJson(
    val pins: List<GeoJsonPin>,
    val routes: List<List<Pair<Double, Double>>>,
)

/**
 * Walks the fence body just enough to drive the native "open in maps" buttons: finds Point
 * coordinates (pins) and LineString coordinates (routes). The map itself is rendered by Leaflet's
 * own (much more tolerant) GeoJSON parser inside the WebView, so this only needs to succeed at
 * extracting what it can — a `null` return means the body isn't even valid JSON, which is the one
 * case the widget treats as a hard error.
 */
private fun parseGeoJsonForButtons(source: String): ParsedGeoJson? {
    val root = try { Json.parseToJsonElement(source) } catch (_: Exception) { return null }
    val obj = root as? JsonObject ?: return null

    val pins = mutableListOf<GeoJsonPin>()
    val routes = mutableListOf<List<Pair<Double, Double>>>()
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
            "LineString" -> {
                val pts = (coordinates as? JsonArray)?.mapNotNull { coordPair(it) } ?: emptyList()
                if (pts.size >= 2) routes += pts
            }
            "MultiLineString" -> (coordinates as? JsonArray)?.forEach { line ->
                val pts = (line as? JsonArray)?.mapNotNull { coordPair(it) } ?: emptyList()
                if (pts.size >= 2) routes += pts
            }
            "GeometryCollection" -> (geometry["geometries"] as? JsonArray)?.forEach { g ->
                (g as? JsonObject)?.let { handleGeometry(it, label) }
            }
            // Polygon/MultiPolygon: rendered on the map by Leaflet, but an area doesn't reduce to
            // a single geo: point worth a button — skipped here.
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
    // FeatureCollection wouldn't. Only the first route gets a button — multiple simultaneous
    // "open route" targets don't map cleanly onto a single maps-app hand-off anyway.
    return ParsedGeoJson(pins.take(5), routes.take(1))
}

private fun geoUri(lat: Double, lon: Double, label: String): Uri =
    Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")

/**
 * A directions URL for [points] (a LineString), for the "Open Route" button. `geo:` URIs can't
 * express a multi-stop route, so this always builds an `https://` link instead — [provider]
 * chooses which site/app it targets, defaulting to OpenStreetMap so a FOSS build doesn't hand
 * users to Google Maps by default (Google Maps remains an opt-in choice for full multi-waypoint
 * support, since OSM's own directions URL only carries an origin/destination pair).
 */
private fun routeUri(points: List<Pair<Double, Double>>, provider: String): Uri {
    val origin = points.first()
    val destination = points.last()
    return when (provider) {
        "google" -> {
            val waypoints = points.drop(1).dropLast(1).take(23)
            val builder = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("origin", "${origin.first},${origin.second}")
                .appendQueryParameter("destination", "${destination.first},${destination.second}")
            if (waypoints.isNotEmpty()) {
                builder.appendQueryParameter("waypoints", waypoints.joinToString("|") { "${it.first},${it.second}" })
            }
            builder.build()
        }
        else -> Uri.parse("https://www.openstreetmap.org/directions").buildUpon()
            .appendQueryParameter("engine", "fossgis_osrm_car")
            .appendQueryParameter("route", "${origin.first},${origin.second};${destination.first},${destination.second}")
            .build()
    }
}

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
            // The WidgetCard host animates its height in from an initial guess once the real
            // content height is known (see WidgetCard's contentHeight logic) — Leaflet caches the
            // container size at init and won't notice that change on its own, so re-measure
            // whenever the WebView's own viewport size changes.
            window.addEventListener('resize', fit);
            if (window.ResizeObserver) {
              new ResizeObserver(fit).observe(document.body);
            } else {
              setTimeout(fit, 300);
            }
          } catch (e) {
            console.error('geojson-render map init failed: ' + (e && e.stack ? e.stack : e));
          }
        </script>
        </body>
        </html>
    """.trimIndent()
}

// Maps don't have a natural content height for WidgetCard's default JS scrollHeight measurement
// to key off (see WidgetCard's fixedHeight doc) — always use this fixed, comfortably map-sized height.
private val GeoJsonMapHeight = 320.dp

@Composable
fun GeoJsonWidgetCard(
    source: String,
    tileUrl: String,
    themeTiles: Boolean,
    routeProvider: String,
    onExpand: (ExpandedWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val parsed = remember(source) { parseGeoJsonForButtons(source) }
    if (parsed == null) {
        WidgetCard(
            sourceText = source,
            documentHtml = "<html><body style=\"font-family:sans-serif;color:#b00020;padding:12px;\">Invalid GeoJSON</body></html>",
            allowNetwork = true,
            allowJavaScript = true,
            transparentBackground = true,
            onExpand = { doc -> onExpand(ExpandedWidget(doc, allowNetwork = true, allowJavaScript = true, transparentBackground = true)) },
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
    val darkMode = scheme.background.luminance() < 0.5f
    val documentHtml = remember(source, tileUrl, themeTiles, darkMode, leafletJs, leafletCss, primaryColor, onPrimaryColor, surfaceVariantColor) {
        buildGeoJsonHtml(source, tileUrl, themeTiles, darkMode, leafletCss, leafletJs, primaryColor, onPrimaryColor, surfaceVariantColor)
    }

    Column(modifier = modifier) {
        WidgetCard(
            sourceText = source,
            documentHtml = documentHtml,
            allowNetwork = true,
            allowJavaScript = true,
            transparentBackground = true,
            onExpand = { doc -> onExpand(ExpandedWidget(doc, allowNetwork = true, allowJavaScript = true, transparentBackground = true)) },
            fixedHeight = GeoJsonMapHeight,
        )
        if (parsed.pins.isNotEmpty() || parsed.routes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                parsed.pins.forEach { pin ->
                    AssistChip(
                        onClick = { openInMapsApp(context, geoUri(pin.lat, pin.lon, pin.label)) },
                        label = { Text(pin.label) },
                        leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
                    )
                }
                parsed.routes.firstOrNull()?.let { route ->
                    AssistChip(
                        onClick = { openInMapsApp(context, routeUri(route, routeProvider)) },
                        label = { Text("Open Route") },
                        leadingIcon = { Icon(Icons.Default.Directions, contentDescription = null) }
                    )
                }
            }
        }
    }
}
