package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import androidx.compose.ui.text.PlaceholderVerticalAlign
import ru.noties.jlatexmath.JLatexMathDrawable

data class LatexSpan(
    val isLatex: Boolean,
    val content: String,
    val display: Boolean = false,
)

private val dollarAmountPattern = Regex("""\$\d+(?!\$)(?!\w)""")

fun String.escapeDollarForMarkdown(): String = buildString {
    val src = this@escapeDollarForMarkdown
    var i = 0
    while (i < src.length) {
        val ch = src[i]
        val remaining = src.substring(i)

        // ``` fenced code block — pass through
        if (remaining.startsWith("```")) {
            val end = remaining.indexOf("```", 3)
            if (end >= 0) {
                append(remaining.substring(0, end + 3))
                i += end + 3; continue
            }
        }
        // ` inline code — pass through
        if (ch == '`') {
            val end = remaining.indexOf('`', 1)
            if (end >= 0) {
                append(remaining.substring(0, end + 1))
                i += end + 1; continue
            }
        }
        // Bare $ → \$ (but not already-escaped \$)
        if (ch == '$' && (i == 0 || src[i - 1] != '\\')) {
            append('\\')
        }
        append(ch)
        i++
    }
}

fun parseLatexSpans(text: String): List<LatexSpan> {
    val spans = mutableListOf<LatexSpan>()
    val buf = StringBuilder()
    var i = 0
    while (i < text.length) {
        val remaining = text.substring(i)

        // ``` fenced code block — skip until closing ```
        if (remaining.startsWith("```")) {
            val end = remaining.indexOf("```", 3)
            if (end >= 0) {
                if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                spans.add(LatexSpan(false, remaining.substring(0, end + 3)))
                i += end + 3
                continue
            }
        }

        // ` inline code — skip until closing `
        if (remaining[0] == '`' && !remaining.startsWith("```")) {
            val end = remaining.indexOf('`', 1)
            if (end >= 0) {
                buf.append(remaining.substring(0, end + 1))
                i += end + 1
                continue
            }
        }

        // $$ display math
        if (remaining.startsWith("$$")) {
            val end = remaining.indexOf("$$", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank() && latex.none { it.code > 127 }) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, true))
                } else if (latex.isNotBlank()) {
                    buf.append(remaining.substring(0, end + 2))
                }
                i += end + 2
                continue
            }
        }

        // \[ display math
        if (remaining.startsWith("\\[")) {
            val end = remaining.indexOf("\\]", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank() && latex.none { it.code > 127 }) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, true))
                } else if (latex.isNotBlank()) {
                    buf.append(remaining.substring(0, end + 2))
                }
                i += end + 2
                continue
            }
        }

        // \( inline math
        if (remaining.startsWith("\\(")) {
            val end = remaining.indexOf("\\)", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank() && latex.none { it.code > 127 }) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, false))
                } else if (latex.isNotBlank()) {
                    buf.append(remaining.substring(0, end + 2))
                }
                i += end + 2
                continue
            }
        }

        // $ inline math — skip if preceded by \ (escaped)
        if (remaining[0] == '$' && !remaining.startsWith("$$")) {
            val nextChar = if (remaining.length > 1) remaining[1] else ' '
            val prevChar = if (i > 0) text[i - 1] else ' '
            if (prevChar != '\\') {
                // $<digits> followed by non-$ non-word → dollar amount, not math
                if (dollarAmountPattern.find(remaining)?.range?.first == 0) {
                    buf.append('$')
                    i++
                    continue
                }
                val end = remaining.indexOf('$', 1)
                val closingOk = end >= 0 && (end == remaining.length - 1 || remaining[end - 1] != '\\')
                if (closingOk) {
                    val latex = remaining.substring(1, end).trim()
                    if (latex.isNotBlank() && latex.none { it.code > 127 }) {
                        if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                        spans.add(LatexSpan(true, latex, false))
                    } else if (latex.isNotBlank()) {
                        buf.append(remaining.substring(0, end + 1))
                    }
                    i += end + 1
                    continue
                }
            }
        }

        // Escaped \$ → literal $
        if (remaining.startsWith("\\$")) {
            buf.append('$')
            i += 2
            continue
        }

        buf.append(remaining[0])
        i++
    }
    if (buf.isNotEmpty()) spans.add(LatexSpan(false, buf.toString()))

    // Escape $ in non-LaTeX spans for markdown compatibility
    for (idx in spans.indices) {
        val span = spans[idx]
        if (!span.isLatex) {
            spans[idx] = span.copy(content = span.content.escapeDollarForMarkdown())
        }
    }

    // Trim whitespace around inline LaTeX spans
    for (idx in spans.indices) {
        val span = spans[idx]
        if (span.isLatex && !span.display) {
            if (idx > 0) {
                val prev = spans[idx - 1]
                if (!prev.isLatex) spans[idx - 1] = prev.copy(content = prev.content.trimEnd())
            }
            if (idx + 1 < spans.size) {
                val next = spans[idx + 1]
                if (!next.isLatex) spans[idx + 1] = next.copy(content = next.content.trimStart())
            }
        }
    }

    return spans
}
fun renderLatexToBitmap(latex: String, textSize: Float = 48f, color: Int = 0xFF000000.toInt(), fallbackW: Int = 800, fallbackH: Int = 200, minW: Int = 0): Bitmap? {
    return try {
        val drawable = JLatexMathDrawable.builder(latex)
            .textSize(textSize)
            .color(color)
            .build()
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val w = maxOf(iw.takeIf { it > 0 } ?: fallbackW, minW)
        val h = ih.takeIf { it > 0 } ?: fallbackH
        val usedFallbackW = iw <= 0 || iw < minW
        val usedFallbackH = ih <= 0
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }
}

fun canRenderLatex(latex: String): Boolean {
    return try {
        JLatexMathDrawable.builder(latex).textSize(48f).color(0).build()
        true
    } catch (_: Exception) { false }
}

private fun encodeLatexUrl(latex: String): String {
    return Base64.encodeToString(latex.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
}

private fun decodeLatexUrl(encoded: String): String {
    return Base64.decode(encoded, Base64.URL_SAFE).decodeToString()
}

fun inlineLatexToMarkdown(latexContent: String): String {
    return "![latex](latex://${encodeLatexUrl(latexContent)})"
}

private fun renderTextToBitmap(text: String, textSize: Float, color: Int): Bitmap {
    val paint = android.graphics.Paint().apply {
        this.textSize = textSize * 0.6f
        this.color = color
        isAntiAlias = true
    }
    val fm = paint.fontMetrics
    val w = (paint.measureText(text) + 8f).toInt().coerceAtLeast(1)
    val h = ((fm.descent - fm.ascent) + 8f).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(0x00000000)
    val canvas = Canvas(bmp)
    canvas.drawText(text, 4f, -fm.ascent + 4f, paint)
    return bmp
}

class LatexImageTransformer(
    private val textSize: Float = 40f,
    private val color: Int = 0xFF000000.toInt(),
) : ImageTransformer {
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 64
    }

    @Composable
    override fun transform(link: String): ImageData? {
        if (!link.startsWith("latex://")) return null
        val latex = try {
            decodeLatexUrl(link.removePrefix("latex://"))
        } catch (_: Exception) {
            return null
        }

        val bmp: Bitmap = synchronized(cache) {
            cache.getOrPut("$latex|$textSize|$color") {
                val fw = (textSize * 10).toInt()
                val fh = (textSize * 2).toInt()
                val rendered = renderLatexToBitmap(latex, textSize, color, fallbackW = fw, fallbackH = fh, minW = 0)
                if (rendered != null) {
                    rendered
                } else {
                    val fallback = renderTextToBitmap("$$latex$", textSize, color)
                    fallback
                }
            }
        }
        return ImageData(
            painter = BitmapPainter(bmp.asImageBitmap()),
            contentDescription = latex,
            modifier = Modifier,
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.Fit,
        )
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?
    ): PlaceholderConfig {
        if (!link.startsWith("latex://")) return super.placeholderConfig(
            link, density, containerSize, imageWidth, imageSize, imageSizeChanged
        )
        val w = with(density) {
            if (imageSize.isUnspecified) (textSize * 2f).toDp().value
            else imageSize.width.toDp().value
        }
        val h = with(density) {
            if (imageSize.isUnspecified) (textSize * 1.2f).toDp().value
            else imageSize.height.toDp().value
        }
        return PlaceholderConfig(size = Size(w, h), verticalAlign = PlaceholderVerticalAlign.Center)
    }
}
