package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64

import androidx.compose.foundation.Image

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import ru.noties.jlatexmath.JLatexMathDrawable

data class LatexSpan(
    val isLatex: Boolean,
    val content: String,
    val display: Boolean = false
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
                if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank()) spans.add(LatexSpan(true, latex, true))
                i += end + 2
                continue
            }
        }

        // \[ display math
        if (remaining.startsWith("\\[")) {
            val end = remaining.indexOf("\\]", 2)
            if (end >= 0) {
                if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank()) spans.add(LatexSpan(true, latex, true))
                i += end + 2
                continue
            }
        }

        // \( inline math
        if (remaining.startsWith("\\(")) {
            val end = remaining.indexOf("\\)", 2)
            if (end >= 0) {
                if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank()) spans.add(LatexSpan(true, latex, false))
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
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    val latex = remaining.substring(1, end).trim()
                    if (latex.isNotBlank()) spans.add(LatexSpan(true, latex, false))
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

fun splitParagraphs(text: String): List<String> {
    val result = mutableListOf<String>()
    val buf = StringBuilder()
    var i = 0
    while (i < text.length) {
        val remaining = text.substring(i)

        // ``` fenced code block — keep as an atomic paragraph
        if (remaining.startsWith("```")) {
            val end = remaining.indexOf("```", 3)
            if (end >= 0) {
                if (buf.isNotBlank()) { result.add(buf.toString().trim()); buf.clear() }
                result.add(remaining.substring(0, end + 3))
                i += end + 3
                continue
            }
        }

        // \n\n paragraph separator
        if (remaining.startsWith("\n\n")) {
            if (buf.isNotBlank()) { result.add(buf.toString().trim()); buf.clear() }
            i += 2
            // skip additional consecutive newlines
            while (i < text.length && text[i] == '\n') i++
            continue
        }

        buf.append(remaining[0])
        i++
    }
    if (buf.isNotBlank()) result.add(buf.toString().trim())
    return result
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
            modifier = Modifier.fillMaxWidth(),
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
        return PlaceholderConfig(size = Size(60f, 22f), verticalAlign = PlaceholderVerticalAlign.Center)
    }
}

// Data class for paragraph groups, tracking \n count for proportional spacing
private data class ParaGroup(val spans: List<LatexSpan>, val spacingBefore: Float)

private fun buildParaGroups(inlineSpans: List<LatexSpan>, paragraphSpacing: Float): List<ParaGroup> {
    val groups = mutableListOf<ParaGroup>()
    val currentSpans = mutableListOf<LatexSpan>()
    var pendingSpacing = paragraphSpacing

    fun flush() {
        if (currentSpans.isNotEmpty()) {
            groups.add(ParaGroup(currentSpans.toList(), pendingSpacing))
            currentSpans.clear()
            pendingSpacing = 0f
        } else {
            pendingSpacing += paragraphSpacing
        }
    }

    for (span in inlineSpans) {
        if (span.isLatex) {
            currentSpans.add(span)
        } else {
            var remaining = span.content
            while (remaining.isNotEmpty()) {
                val nlIdx = remaining.indexOf('\n')
                if (nlIdx < 0) {
                    currentSpans.add(LatexSpan(false, remaining))
                    break
                }
                if (nlIdx > 0) {
                    currentSpans.add(LatexSpan(false, remaining.substring(0, nlIdx)))
                }
                remaining = remaining.substring(nlIdx + 1)
                flush()
                pendingSpacing = paragraphSpacing
                while (remaining.startsWith('\n')) {
                    pendingSpacing += paragraphSpacing
                    remaining = remaining.substring(1)
                }
            }
        }
    }
    flush()
    return groups
}

private fun buildParagraphStrings(
    groups: List<ParaGroup>,
    textStyle: TextStyle,
    settings: AnnotatorSettings,
): List<AnnotatedString> {
    var latexIdx = 0
    return groups.map { group ->
        buildAnnotatedString {
            for (span in group.spans) {
                if (span.isLatex) {
                    appendInlineContent("LATEX_$latexIdx", span.content)
                    latexIdx++
                } else if (span.content.isNotBlank()) {
                    append(span.content.buildMarkdownAnnotatedString(textStyle, settings))
                }
            }
        }
    }
}

private data class LatexBitmapEntry(val tag: String, val bitmap: Bitmap?, val content: String)

private data class LatexRenderState(
    val entries: List<LatexBitmapEntry>,
    val inlineContent: Map<String, InlineTextContent>,
    val paragraphStrings: List<AnnotatedString>,
    val paragraphGroups: List<ParaGroup>,
)

@Composable
fun LatexAwareText(
    spans: List<LatexSpan>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle,
    latexTextSize: Float = 56f,
    latexColor: Int,
    codeSpanStyle: SpanStyle = SpanStyle(),
    paragraphSpacing: Float = 10f,
) {
    val density = LocalDensity.current
    val inlineSpans = remember(spans) { spans.filter { !it.display } }
    val settings = annotatorSettings(
        linkTextSpanStyle = TextLinkStyles(style = textStyle.toSpanStyle()),
        codeSpanStyle = codeSpanStyle,
        referenceLinkHandler = ReferenceLinkHandlerImpl(),
    )

    val state = remember(inlineSpans, settings, latexTextSize, latexColor, density, paragraphSpacing) {
        if (inlineSpans.isEmpty()) return@remember null
        val entries = inlineSpans.filter { it.isLatex }.mapIndexed { idx, span ->
            val tag = "LATEX_$idx"
            val bmp = renderLatexToBitmap(span.content, latexTextSize, latexColor)
            LatexBitmapEntry(tag, bmp, span.content)
        }
        val ic = buildMap<String, InlineTextContent> {
            entries.forEach { entry ->
                if (entry.bitmap != null) {
                    val rawW = with(density) { entry.bitmap.width.toSp().value }
                    val rawH = with(density) { entry.bitmap.height.toSp().value }
                    val maxH = if (textStyle.lineHeight.isSp) textStyle.lineHeight.value
                               else textStyle.fontSize.value * 1.4f
                    val scale = if (rawH > maxH) maxH / rawH else 1f
                    val pw = maxOf(rawW * scale, 24f)
                    val ph = maxOf(rawH * scale, 16f)
                    put(entry.tag, InlineTextContent(
                        Placeholder(width = pw.sp, height = ph.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
                    ) { _ ->
                        Image(
                            painter = BitmapPainter(entry.bitmap.asImageBitmap()),
                            contentDescription = entry.content,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    })
                }
            }
        }
        val groups = buildParaGroups(inlineSpans, paragraphSpacing)
        val paraStrings = buildParagraphStrings(groups, textStyle, settings)
        LatexRenderState(entries, ic, paraStrings, groups)
    } ?: return

    if (state.paragraphStrings.isEmpty()) return

    if (state.paragraphStrings.size == 1 && state.paragraphGroups.first().spacingBefore == 0f) {
        BasicText(
            text = state.paragraphStrings.first(),
            modifier = modifier,
            style = textStyle,
            inlineContent = state.inlineContent,
        )
    } else {
        Column(modifier = modifier) {
            state.paragraphStrings.forEachIndexed { idx, annotated ->
                val spacingBefore = state.paragraphGroups[idx].spacingBefore
                if (spacingBefore > 0f) {
                    Spacer(Modifier.height(spacingBefore.dp))
                }
                BasicText(
                    text = annotated,
                    modifier = Modifier,
                    style = textStyle,
                    inlineContent = state.inlineContent,
                )
            }
        }
    }
}
