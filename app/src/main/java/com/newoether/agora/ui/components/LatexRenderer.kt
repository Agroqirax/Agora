package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import ru.noties.jlatexmath.JLatexMathDrawable

data class LatexSpan(
    val isLatex: Boolean,
    val content: String,
    val display: Boolean = false
)

fun parseLatexSpans(text: String): List<LatexSpan> {
    val spans = mutableListOf<LatexSpan>()
    var remaining = text
    while (remaining.isNotEmpty()) {
        // Try $$...$$, $...$, \[...\], \(...\)
        val di = remaining.indexOf("$$")
        val ii = remaining.indexOf("$")
        val dbi = remaining.indexOf("\\[")
        val ibi = remaining.indexOf("\\(")

        val candidates = listOf(
            Triple(if (di >= 0) di else Int.MAX_VALUE, "$$", true),
            Triple(if (ii >= 0 && ii != di) ii else Int.MAX_VALUE, "$", false),
            Triple(if (dbi >= 0) dbi else Int.MAX_VALUE, "\\]", true),
            Triple(if (ibi >= 0) ibi else Int.MAX_VALUE, "\\)", false),
        ).filter { it.first < Int.MAX_VALUE }

        if (candidates.isEmpty()) { spans.add(LatexSpan(false, remaining)); break }
        val (start, delim, display) = candidates.minBy { it.first }

        if (start > 0) spans.add(LatexSpan(false, remaining.substring(0, start)))

        val searchDelim = if (delim == "\\]") "\\]" else if (delim == "\\)") "\\)" else delim
        val end = remaining.indexOf(searchDelim, start + delim.length)
        if (end == -1) { spans.add(LatexSpan(false, remaining.substring(start))); break }

        val latex = remaining.substring(start + delim.length, end).trim()
        if (latex.isNotBlank()) spans.add(LatexSpan(true, latex, display))
        remaining = remaining.substring(end + searchDelim.length)
    }
    return spans
}

fun renderLatexToBitmap(latex: String, textSize: Float = 48f, color: Int = 0xFF000000.toInt()): Bitmap? {
    return try {
        val drawable = JLatexMathDrawable.builder(latex)
            .textSize(textSize)
            .color(color)
            .build()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 800
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bmp
    } catch (_: Exception) { null }
}
