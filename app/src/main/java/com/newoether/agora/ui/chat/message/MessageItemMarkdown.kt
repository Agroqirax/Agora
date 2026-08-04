package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

// ── Streaming Markdown rendering (extracted from MessageItem.kt) ──────────────
// Pure code-motion: these were `private` members of MessageItem.kt; entry points
// used by MessageItem.kt / the timeline section are `internal`. Behavior unchanged.

@Stable
internal class ChatMarkdownRenderContext(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
    val components: MarkdownComponents,
    val imageTransformer: ImageTransformer,
    val flavour: MarkdownFlavourDescriptor,
    val plainTextStyle: TextStyle,
)

@Composable
internal fun MarkdownTextContent(
    text: String,
    renderContext: ChatMarkdownRenderContext,
    immediate: Boolean = false,
    includeFirstSpacer: Boolean = true,
    onReady: () -> Unit = {}
) {
    val markdownText = remember(text) { text.toRenderableMarkdownText() }
    MarkdownPreparedTextContent(
        text = markdownText,
        renderContext = renderContext,
        immediate = immediate,
        includeFirstSpacer = includeFirstSpacer,
        onReady = onReady
    )
}

/**
 * Detail pages can contain tens of thousands of Markdown characters. Parsing already happens off
 * the main thread; this variant also virtualizes top-level AST nodes so only visible blocks are
 * composed and measured.
 */
@Composable
internal fun LazyMarkdownTextContent(
    text: String,
    renderContext: ChatMarkdownRenderContext,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    includeFirstSpacer: Boolean = true,
    onReady: () -> Unit = {},
) {
    val markdownText = remember(text) { text.toRenderableMarkdownText() }
    MarkdownPreparedTextContent(
        text = markdownText,
        renderContext = renderContext,
        immediate = false,
        includeFirstSpacer = includeFirstSpacer,
        onReady = onReady,
        modifier = modifier,
        lazyListState = listState,
        lazyContentPadding = contentPadding,
    )
}

@Composable
private fun MarkdownPreparedTextContent(
    text: String,
    renderContext: ChatMarkdownRenderContext,
    immediate: Boolean = false,
    includeFirstSpacer: Boolean = true,
    onReady: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth(),
    lazyListState: LazyListState? = null,
    lazyContentPadding: PaddingValues = PaddingValues(),
) {
    val markdownText = text
    val markdownParser = remember(markdownText, renderContext.flavour) {
        MarkdownParser(renderContext.flavour)
    }
    val referenceLinkHandler = remember(markdownText) { ReferenceLinkHandlerImpl() }
    val markdownState = rememberMarkdownState(
        content = markdownText,
        flavour = renderContext.flavour,
        parser = markdownParser,
        referenceLinkHandler = referenceLinkHandler,
        immediate = immediate
    )
    val state by markdownState.state.collectAsState()
    val currentOnReady by rememberUpdatedState(onReady)

    LaunchedEffect(state) {
        // Error is terminal too: reveal the renderer's error UI instead of leaving a loading
        // indicator over it forever.
        if (state is State.Success || state is State.Error) currentOnReady()
    }

    com.mikepenz.markdown.compose.Markdown(
        state = state,
        modifier = modifier,
        colors = renderContext.colors,
        typography = renderContext.typography,
        padding = renderContext.padding,
        components = renderContext.components,
        imageTransformer = renderContext.imageTransformer,
        animations = markdownAnimations { this },
        success = { successState, components, modifier ->
            if (lazyListState == null) {
                MarkdownSuccessWithSpacing(
                    state = successState,
                    components = components,
                    modifier = modifier,
                    includeFirstSpacer = includeFirstSpacer,
                )
            } else {
                LazyMarkdownSuccessWithSpacing(
                    state = successState,
                    components = components,
                    listState = lazyListState,
                    modifier = modifier,
                    contentPadding = lazyContentPadding,
                    includeFirstSpacer = includeFirstSpacer,
                )
            }
        }
    )
}

@Composable
private fun MarkdownSuccessWithSpacing(
    state: State.Success,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
    includeFirstSpacer: Boolean = true
) {
    Column(modifier) {
        state.node.children.forEachIndexed { index, node ->
            MarkdownElement(
                node = node,
                components = components,
                content = state.content,
                includeSpacer = includeFirstSpacer || index > 0
            )
        }
    }
}

@Composable
private fun LazyMarkdownSuccessWithSpacing(
    state: State.Success,
    components: MarkdownComponents,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    includeFirstSpacer: Boolean = true,
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(
            items = state.node.children,
            key = { index, node ->
                "${node.startOffset}:${node.endOffset}:${node.type}:$index"
            },
        ) { index, node ->
            MarkdownElement(
                node = node,
                components = components,
                content = state.content,
                includeSpacer = includeFirstSpacer || index > 0,
            )
        }
    }
}

internal fun String.toRenderableMarkdownText(): String {
    val spans = parseLatexSpans(this)
    val markdown = if (spans.all { !it.isLatex }) {
        this
    } else {
        spans.joinToString("") { span ->
            if (span.isLatex) latexToMarkdown(span.content, span.display)
            else span.content
        }
    }
    return markdown.escapeForMarkdown()
}

internal fun String.escapeForMarkdown(): String =
    protectLiteralAngleBracketTags().escapeDollarForMarkdown()

private sealed interface MarkdownCodeContext {
    data object None : MarkdownCodeContext
    data class Inline(val ticks: Int) : MarkdownCodeContext
    data class Fence(val character: Char, val length: Int) : MarkdownCodeContext
}

/**
 * The renderer intentionally omits raw HTML nodes. Model output such as `<widget>` is normally
 * meant as literal text, so protect HTML-looking angle-bracket text outside Markdown code without
 * changing the persisted/copied message. Fenced and inline code are already literal by CommonMark
 * definition and remain byte-for-byte unchanged.
 */
internal fun String.protectLiteralAngleBracketTags(): String {
    if ('<' !in this) return this
    val output = StringBuilder(length)
    var index = 0
    var linePrefix = true
    var lineIndent = 0
    var code: MarkdownCodeContext = MarkdownCodeContext.None

    fun updateLineState(character: Char) {
        if (character == '\n') {
            linePrefix = true
            lineIndent = 0
        } else if (linePrefix && character == ' ' && lineIndent < 4) {
            lineIndent++
        } else {
            linePrefix = false
        }
    }

    fun appendRaw(value: String) {
        output.append(value)
        value.forEach(::updateLineState)
    }

    fun runLength(start: Int, character: Char): Int {
        var end = start
        while (end < length && this[end] == character) end++
        return end - start
    }

    while (index < length) {
        val character = this[index]
        when (val state = code) {
            MarkdownCodeContext.None -> {
                if (character == '`' || character == '~') {
                    val run = runLength(index, character)
                    when {
                        linePrefix && lineIndent <= 3 && run >= 3 -> {
                            appendRaw(substring(index, index + run))
                            code = MarkdownCodeContext.Fence(character, run)
                            index += run
                            continue
                        }
                        character == '`' -> {
                            appendRaw(substring(index, index + run))
                            code = MarkdownCodeContext.Inline(run)
                            index += run
                            continue
                        }
                    }
                }
                if (character == '<') {
                    val close = indexOf('>', startIndex = index + 1)
                    if (close > index) {
                        val inner = substring(index + 1, close)
                        if (inner.shouldProtectAngleBracketContent()) {
                            if (inner.startsWith('/')) {
                                appendRaw("</\u200B${inner.drop(1)}>")
                            } else {
                                appendRaw("<\u200B$inner>")
                            }
                            index = close + 1
                            continue
                        }
                    }
                }
            }
            is MarkdownCodeContext.Inline -> {
                if (character == '`') {
                    val run = runLength(index, character)
                    appendRaw(substring(index, index + run))
                    if (run == state.ticks) code = MarkdownCodeContext.None
                    index += run
                    continue
                }
            }
            is MarkdownCodeContext.Fence -> {
                if (linePrefix && lineIndent <= 3 && character == state.character) {
                    val run = runLength(index, character)
                    appendRaw(substring(index, index + run))
                    if (run >= state.length) code = MarkdownCodeContext.None
                    index += run
                    continue
                }
            }
        }
        appendRaw(character.toString())
        index++
    }
    return output.toString()
}

private val markdownAutolink = Regex("""(?i)(?:https?://|mailto:).+|[^<>\s]+@[^<>\s]+""")

private fun String.shouldProtectAngleBracketContent(): Boolean =
    isNotEmpty() && !matches(markdownAutolink)
