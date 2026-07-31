package com.newoether.agora.ui.chat

import android.util.Xml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser

private val GEOJSON_TYPE_VALUES = setOf(
    "FeatureCollection", "Feature", "Point", "MultiPoint",
    "LineString", "MultiLineString", "Polygon", "MultiPolygon", "GeometryCollection"
)

private val VEGA_LITE_TOP_LEVEL_KEYS = setOf(
    "mark", "encoding", "layer", "facet", "repeat", "hconcat", "vconcat", "concat"
)

/**
 * Models frequently tag a GeoJSON/Vega-Lite spec as `json`, or a GPX/KML file as `xml` — not
 * technically wrong, but it means the corresponding widget never activates. For a fence declared
 * as exactly `json` or `xml`, sniff [body]'s actual shape and return the specific widget fence
 * language it matches, so the caller can still dispatch it as that widget. Returns `null` for any
 * other declared language, or when [body] doesn't confidently match a known widget shape — either
 * way the caller falls through to a plain code block, same as today.
 */
fun sniffGenericFenceLanguage(declaredLanguage: String?, body: String): String? = when (declaredLanguage) {
    "json" -> sniffJsonWidgetLanguage(body)
    "xml" -> sniffXmlWidgetLanguage(body)
    else -> null
}

private fun sniffJsonWidgetLanguage(body: String): String? {
    val root = try { Json.parseToJsonElement(body) } catch (_: Exception) { return null }
    val obj = root as? JsonObject ?: return null

    val schema = obj["\$schema"]?.jsonPrimitive?.contentOrNull
    if (schema != null && schema.contains("vega-lite", ignoreCase = true)) return "vega-lite"

    val type = obj["type"]?.jsonPrimitive?.contentOrNull
    if (type != null && type in GEOJSON_TYPE_VALUES) return "geojson"

    if (VEGA_LITE_TOP_LEVEL_KEYS.any { it in obj }) return "vega-lite"

    return null
}

private fun sniffXmlWidgetLanguage(body: String): String? {
    return try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(body.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                return when (parser.name.lowercase()) {
                    "gpx" -> "gpx"
                    "kml" -> "kml"
                    else -> null
                }
            }
            event = parser.next()
        }
        null
    } catch (_: Exception) {
        null
    }
}
