package com.newoether.agora.data

object DefaultSkills {
    data class Builtin(
        val name: String,
        val description: String,
        val content: String
    )

    /** Only visible/loadable while HTML widgets are enabled — see the `htmlWidgetsEnabled`
     *  gate in [com.newoether.agora.tool.SkillToolProvider]. */
    const val HTML_WIDGETS_SKILL_NAME = "html-widgets"

    /** Only visible/loadable while Mermaid diagrams are enabled — see the `mermaidWidgetsEnabled`
     *  gate in [com.newoether.agora.tool.SkillToolProvider]. */
    const val MERMAID_WIDGETS_SKILL_NAME = "mermaid-render"

    /** Only visible/loadable while GeoJSON maps are enabled — see the `geoJsonWidgetsEnabled`
     *  gate in [com.newoether.agora.tool.SkillToolProvider]. */
    const val GEOJSON_WIDGETS_SKILL_NAME = "geojson-render"

    val BUILTINS: List<Builtin> = listOf(
        Builtin(
            name = HTML_WIDGETS_SKILL_NAME,
            description = "How to render an interactive HTML/CSS/JS widget inline in the chat. Use when the user would benefit from a visualization, small UI, game, calculator, or other interactive web component.",
            content = """
                # Interactive HTML widgets

                To show an interactive HTML/CSS/JS widget inline in the chat, write a fenced code block using the language `html-render`, e.g. a fence opened with ```html-render.

                - Provide only an HTML fragment (elements plus optional inline `<style>`/`<script>`) — no `<html>`, `<head>`, or `<body>`.
                - The app already supplies page layout and Material 3 styling; avoid global CSS, body/html styling, or viewport sizing (100vh, fixed heights, etc.) unless the widget genuinely requires it, and let content size naturally.
                - The widget renders automatically right where the fence appears — do not also paste or explain the HTML/CSS/JS elsewhere in your reply.
                - Use a normal ```html fence (not `html-render`) when the user actually wants to see or copy HTML source instead of seeing it rendered.
                - You may use the css color variables --md-primary, --md-on-primary, --md-secondary, --md-on-secondary, --md-background, --md-on-background, --md-surface, --md-on-surface, --md-surface-variant, --md-on-surface-variant, --md-outline, --md-error, --md-on-error to style your widget. Prefer these over hard coded color values.
            """.trimIndent()
        ),
        Builtin(
            name = MERMAID_WIDGETS_SKILL_NAME,
            description = "How to render a Mermaid diagram (flowchart, sequence diagram, class diagram, etc.) inline in the chat. Use when the user would benefit from a diagram or visual explanation of a process, structure, or relationship.",
            content = """
                # Mermaid diagrams

                To show a diagram inline in the chat, write a fenced code block using the language `mermaid-render`, e.g. a fence opened with ```mermaid-render, containing standard Mermaid.js diagram syntax.

                - The diagram renders automatically right where the fence appears, matching the app's current light/dark theme automatically — do not also paste or explain the diagram source elsewhere in your reply, and do not try to control colors/theme yourself.
                - Common diagram types: `flowchart TD`/`graph TD` (flowcharts), `sequenceDiagram` (sequence diagrams), `classDiagram`, `erDiagram`, `stateDiagram-v2`, `gantt`, `pie`.
                - Use a normal ```mermaid fence (not `mermaid-render`) when the user actually wants to see or copy the diagram source instead of seeing it rendered.
            """.trimIndent()
        ),
        Builtin(
            name = GEOJSON_WIDGETS_SKILL_NAME,
            description = "How to render a map (pins, routes, areas) inline in the chat from GeoJSON, with buttons to open locations/routes in the user's maps app. Use when the user asks about a place, route, or spatial data.",
            content = """
                # GeoJSON maps

                To show a map inline in the chat, write a fenced code block using the language `geojson-render`, e.g. a fence opened with ```geojson-render, containing a single valid GeoJSON `Feature`, `FeatureCollection`, or bare geometry object.

                - The body must be strict, valid JSON: no `//` or `/* */` comments, no trailing commas. GeoJSON is plain JSON and a parse error leaves the map blank.
                - The map renders automatically right where the fence appears, matching the app's current light/dark theme — do not also paste or explain the GeoJSON elsewhere in your reply.
                - `Point`/`MultiPoint` geometries render as pins, `LineString`/`MultiLineString` as routes, `Polygon`/`MultiPolygon` as shaded areas. The map auto-fits to the content and supports pan/zoom.
                - Set a `name` or `title` property on a `Feature` to label its pin/button (e.g. `{"type": "Feature", "properties": {"name": "Eiffel Tower"}, "geometry": {"type": "Point", "coordinates": [2.2945, 48.8584]}}`). Coordinates are `[longitude, latitude]`, per the GeoJSON spec.
                - Buttons appear automatically below the map to open a pin or route in the device's installed maps app — don't tell the user how to do this manually.
                - Basemap tile imagery only loads if the user has enabled network access for this widget in settings; the map, pins, routes, and areas still render without it.
                - Use a normal ```geojson fence (not `geojson-render`) when the user actually wants to see or copy the raw GeoJSON instead of seeing it rendered.
            """.trimIndent()
        )
    )
}
