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
            """.trimIndent()
        )
    )
}
