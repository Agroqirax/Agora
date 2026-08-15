package com.newoether.agora.ui.chat

import android.util.Xml
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xmlpull.v1.XmlPullParser

/**
 * Converts a GPX document into the same GeoJSON `FeatureCollection` shape the map widget already
 * knows how to render — `<wpt>` waypoints become `Point` features, `<trkseg>` track segments and
 * `<rte>` routes become `LineString` features — so [GeoJsonChatWidgetCard] and its native
 * "open in maps app" button logic ([parseGeoJsonForButtons]) work unchanged for GPX input.
 * Returns `null` if [xml] isn't well-formed XML, mirroring [parseGeoJsonForButtons]'s contract.
 */
fun gpxToGeoJson(xml: String): String? {
    val features = mutableListOf<JsonObject>()
    try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        var currentLine: MutableList<DoubleArray>? = null
        var currentPoint: DoubleArray? = null
        var currentName: String? = null
        val textBuffer = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuffer.setLength(0)
                    when (parser.name) {
                        "wpt" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentPoint = if (lat != null && lon != null) doubleArrayOf(lon, lat) else null
                            currentName = null
                        }
                        "trkpt", "rtept" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) currentLine?.add(doubleArrayOf(lon, lat))
                        }
                        "trkseg", "rte" -> currentLine = mutableListOf()
                    }
                }
                XmlPullParser.TEXT -> textBuffer.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "name" -> if (currentPoint != null) currentName = textBuffer.toString().trim().ifBlank { null }
                        "wpt" -> {
                            currentPoint?.let { (lon, lat) ->
                                features += geoJsonPointFeature(lat, lon, currentName)
                            }
                            currentPoint = null
                            currentName = null
                        }
                        "trkseg", "rte" -> {
                            currentLine?.let { pts -> if (pts.size >= 2) features += geoJsonLineFeature(pts) }
                            currentLine = null
                        }
                    }
                    textBuffer.setLength(0)
                }
            }
            event = parser.next()
        }
    } catch (_: Exception) {
        return null
    }
    if (features.isEmpty()) return null
    return geoJsonFeatureCollection(features).toString()
}

/**
 * Converts a KML document into a GeoJSON `FeatureCollection`, same rationale as [gpxToGeoJson]:
 * each `<Placemark>`'s `<Point>`/`<LineString>`/`<Polygon>` becomes the matching GeoJSON geometry,
 * with its `<name>` carried into `properties.name`. Returns `null` on unparseable XML.
 */
fun kmlToGeoJson(xml: String): String? {
    val features = mutableListOf<JsonObject>()
    try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        var inPlacemark = false
        var placemarkName: String? = null
        var geometryTag: String? = null
        var polygonRing: MutableList<DoubleArray>? = null
        var lineCoords: MutableList<DoubleArray>? = null
        var pointCoord: DoubleArray? = null
        val textBuffer = StringBuilder()

        fun parseCoordinatesText(text: String): List<DoubleArray> =
            text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
                val parts = tuple.split(",")
                val lon = parts.getOrNull(0)?.toDoubleOrNull()
                val lat = parts.getOrNull(1)?.toDoubleOrNull()
                if (lon != null && lat != null) doubleArrayOf(lon, lat) else null
            }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuffer.setLength(0)
                    when (parser.name) {
                        "Placemark" -> {
                            inPlacemark = true
                            placemarkName = null
                        }
                        "Point", "LineString" -> if (inPlacemark) geometryTag = parser.name
                        "outerBoundaryIs" -> if (inPlacemark) geometryTag = "Polygon"
                    }
                }
                XmlPullParser.TEXT -> textBuffer.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "name" -> if (inPlacemark && placemarkName == null) {
                            placemarkName = textBuffer.toString().trim().ifBlank { null }
                        }
                        "coordinates" -> {
                            val coords = parseCoordinatesText(textBuffer.toString())
                            when (geometryTag) {
                                "Point" -> pointCoord = coords.firstOrNull()
                                "LineString" -> lineCoords = coords.toMutableList()
                                "Polygon" -> polygonRing = coords.toMutableList()
                            }
                        }
                        "Placemark" -> {
                            when {
                                pointCoord != null -> features += geoJsonPointFeature(pointCoord!![1], pointCoord!![0], placemarkName)
                                lineCoords != null && lineCoords!!.size >= 2 -> features += geoJsonLineFeature(lineCoords!!)
                                polygonRing != null && polygonRing!!.size >= 3 -> features += geoJsonPolygonFeature(polygonRing!!, placemarkName)
                            }
                            inPlacemark = false
                            placemarkName = null
                            geometryTag = null
                            pointCoord = null
                            lineCoords = null
                            polygonRing = null
                        }
                    }
                    textBuffer.setLength(0)
                }
            }
            event = parser.next()
        }
    } catch (_: Exception) {
        return null
    }
    if (features.isEmpty()) return null
    return geoJsonFeatureCollection(features).toString()
}

private fun coordArray(lon: Double, lat: Double): JsonArray = buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) }

private fun geoJsonPointFeature(lat: Double, lon: Double, name: String?): JsonObject = buildJsonObject {
    put("type", "Feature")
    put("properties", buildJsonObject { name?.let { put("name", it) } })
    put("geometry", buildJsonObject {
        put("type", "Point")
        put("coordinates", coordArray(lon, lat))
    })
}

private fun geoJsonLineFeature(points: List<DoubleArray>): JsonObject = buildJsonObject {
    put("type", "Feature")
    put("properties", buildJsonObject { })
    put("geometry", buildJsonObject {
        put("type", "LineString")
        put("coordinates", buildJsonArray { points.forEach { (lon, lat) -> add(coordArray(lon, lat)) } })
    })
}

private fun geoJsonPolygonFeature(ring: List<DoubleArray>, name: String?): JsonObject = buildJsonObject {
    put("type", "Feature")
    put("properties", buildJsonObject { name?.let { put("name", it) } })
    put("geometry", buildJsonObject {
        put("type", "Polygon")
        put("coordinates", buildJsonArray {
            add(buildJsonArray { ring.forEach { (lon, lat) -> add(coordArray(lon, lat)) } })
        })
    })
}

private fun geoJsonFeatureCollection(features: List<JsonObject>): JsonObject = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", buildJsonArray { features.forEach { add(it) } })
}

/** Converts [source] as GPX and renders it through the existing GeoJSON map pipeline. */
@Composable
fun GpxChatWidgetCard(
    source: String,
    tileUrl: String,
    themeTiles: Boolean,
    onExpand: (ExpandedChatWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val converted = remember(source) { gpxToGeoJson(source) }
    if (converted == null) {
        InvalidMapSourceCard(source, "Invalid GPX", onExpand, modifier)
        return
    }
    GeoJsonChatWidgetCard(
        source = converted,
        tileUrl = tileUrl,
        themeTiles = themeTiles,
        onExpand = onExpand,
        modifier = modifier,
        displaySource = source,
    )
}

/** Converts [source] as KML and renders it through the existing GeoJSON map pipeline. */
@Composable
fun KmlChatWidgetCard(
    source: String,
    tileUrl: String,
    themeTiles: Boolean,
    onExpand: (ExpandedChatWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val converted = remember(source) { kmlToGeoJson(source) }
    if (converted == null) {
        InvalidMapSourceCard(source, "Invalid KML", onExpand, modifier)
        return
    }
    GeoJsonChatWidgetCard(
        source = converted,
        tileUrl = tileUrl,
        themeTiles = themeTiles,
        onExpand = onExpand,
        modifier = modifier,
        displaySource = source,
    )
}

@Composable
private fun InvalidMapSourceCard(sourceText: String, message: String, onExpand: (ExpandedChatWidget) -> Unit, modifier: Modifier) {
    val html = "<html><body style=\"font-family:sans-serif;color:#b00020;padding:12px;\">$message</body></html>"
    ChatWidgetCard(
        sourceText = sourceText,
        documentHtml = html,
        allowNetwork = false,
        allowJavaScript = true,
        transparentBackground = true,
        onExpand = { doc -> onExpand(ExpandedChatWidget(doc, allowNetwork = false, allowJavaScript = true, transparentBackground = true)) },
        modifier = modifier
    )
}
