package com.newoether.agora.data

object DefaultSkills {
    data class Builtin(
        val name: String,
        val description: String,
        val content: String
    )

    const val HTML_WIDGETS_SKILL_NAME = "html-widgets"

    const val MERMAID_WIDGETS_SKILL_NAME = "mermaid-widgets"

    const val VEGA_LITE_WIDGETS_SKILL_NAME = "vega-lite-widgets"

    const val GEOJSON_WIDGETS_SKILL_NAME = "geojson-widgets"

    val BUILTINS: List<Builtin> = listOf(
        Builtin(
            name = HTML_WIDGETS_SKILL_NAME,
            description = "How to render an interactive HTML/CSS/JS widget inline in the chat. Use when the user would benefit from a visualization, small UI, game, calculator, or other interactive web component.",
            content = """
                # Interactive HTML widgets

                To show an interactive HTML/CSS/JS widget inline in the chat, write a fenced code block using the language `html`, e.g. a fence opened with ```html.

                - Provide only an HTML fragment (elements plus optional inline `<style>`/`<script>`) — no `<html>`, `<head>`, or `<body>`.
                - The app already supplies page layout and Material 3 styling; avoid global CSS, body/html styling, or viewport sizing (100vh, fixed heights, etc.) unless the widget genuinely requires it, and let content size naturally.
                - The widget renders automatically right where the fence appears — do not also paste or explain the HTML/CSS/JS elsewhere in your reply.
                - You may use the css color variables --md-primary, --md-on-primary, --md-secondary, --md-on-secondary, --md-background, --md-on-background, --md-surface, --md-on-surface, --md-surface-variant, --md-on-surface-variant, --md-outline, --md-error, --md-on-error to style your widget. Prefer these over hard coded color values.
            """.trimIndent()
        ),
        Builtin(
            name = MERMAID_WIDGETS_SKILL_NAME,
            description = "How to render a Mermaid diagram (flowchart, sequence diagram, class diagram, etc.) inline in the chat. Use when the user would benefit from a diagram or visual explanation of a process, structure, or relationship.",
            content = """
                # Mermaid diagrams

                To show a diagram inline in the chat, write a fenced code block using the language `mermaid`, e.g. a fence opened with ```mermaid, containing standard Mermaid.js diagram syntax.

                - The diagram renders automatically right where the fence appears, matching the app's current light/dark theme automatically — do not also paste or explain the diagram source elsewhere in your reply, and do not try to control colors/theme yourself.
                - Common diagram types: `flowchart TD`/`graph TD` (flowcharts), `sequenceDiagram` (sequence diagrams), `classDiagram`, `erDiagram`, `stateDiagram-v2`, `gantt`, `pie`.
            """.trimIndent()
        ),
        Builtin(
            name = VEGA_LITE_WIDGETS_SKILL_NAME,
            description = "How to render a chart (bar, line, scatter, area, etc.) inline in the chat using a Vega-Lite spec. Use when the user would benefit from a data visualization.",
            content = """
                # Vega-Lite charts

                To show a chart inline in the chat, write a fenced code block using the language `vega-lite`, e.g. a fence opened with ```vega-lite, containing a single valid Vega-Lite JSON spec (`mark`, `encoding`, `data`, etc.).

                - The body must be strict, valid JSON: no `//` or `/* */` comments, no trailing commas.
                - Provide chart data inline via `"data": {"values": [...]}` — network access is off for this widget, so a `"data": {"url": ...}` reference will not load.
                - The chart renders automatically right where the fence appears, matching the app's current light/dark theme automatically — do not also paste or explain the spec elsewhere in your reply, and do not set your own `background`/text colors.
                - Omit `width`/`height` unless the chart needs a specific aspect ratio; it otherwise sizes to fit the card.
            """.trimIndent()
        ),
        Builtin(
            name = GEOJSON_WIDGETS_SKILL_NAME,
            description = "How to render a map (pins, routes, areas) inline in the chat from GeoJSON, a GPX track/waypoint file, or a KML file, with buttons to open locations/routes in the user's maps app. Use when the user asks about a place, route, or spatial data.",
            content = """
                # Maps: GeoJSON, GPX, KML

                To show a map inline in the chat, write a fenced code block using one of three languages, depending on the source data:

                - ```geojson — a single valid GeoJSON `Feature`, `FeatureCollection`, or bare geometry object.
                - ```gpx — a GPX file (`<gpx>` root, `<wpt>` waypoints, `<trk>`/`<trkseg>` tracks, `<rte>` routes).
                - ```kml — a KML file (`<kml>` root, `<Placemark>` elements containing `<Point>`, `<LineString>`, or `<Polygon>`).

                All three render through the same map widget and support the same features:

                - GeoJSON bodies must be strict, valid JSON: no `//` or `/* */` comments, no trailing commas. GPX/KML bodies must be well-formed XML. A parse error leaves the map blank / shows an error card.
                - The map renders automatically right where the fence appears, matching the app's current light/dark theme — do not also paste or explain the source data elsewhere in your reply.
                - Points/waypoints/`Point` placemarks render as pins, tracks/routes/`LineString` as routes, `Polygon` areas as shaded regions. The map auto-fits to the content and supports pan/zoom.
                - In GeoJSON, set a `name` or `title` property on a `Feature` to label its pin/button (e.g. `{"type": "Feature", "properties": {"name": "Eiffel Tower"}, "geometry": {"type": "Point", "coordinates": [2.2945, 48.8584]}}`); coordinates are `[longitude, latitude]`, per the GeoJSON spec. In GPX, a `<name>` inside `<wpt>` becomes its label. In KML, a `<name>` inside `<Placemark>` becomes its label.
                - Buttons appear automatically below the map to open a pin or route in the device's installed maps app — don't tell the user how to do this manually.
                - Basemap tile imagery only loads if the user has enabled network access for this widget in settings; the map, pins, routes, and areas still render without it.
            """.trimIndent()
        )
    )
}
