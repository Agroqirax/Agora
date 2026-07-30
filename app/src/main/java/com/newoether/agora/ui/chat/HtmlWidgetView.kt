package com.newoether.agora.ui.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.newoether.agora.util.DebugLog
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.ui.theme.ChatType
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

/**
 * Configures a [WebView] to render model-authored HTML as safely as this app can manage: no
 * origin (loaded via [WebView.loadDataWithBaseURL] with a null base, so there's nothing for
 * same-origin JS to target), no file/content access, no [WebView.addJavascriptInterface] bridge
 * to app internals ever, and network-loaded resources (remote images/CSS/CDN scripts) blocked
 * unless the user has explicitly opted in via the HTML widgets network setting.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun configureWidgetWebView(
    webView: WebView,
    allowNetwork: Boolean,
    allowJavaScript: Boolean,
    transparentBackground: Boolean,
    onContentHeightPx: ((Int) -> Unit)? = null
) {
    webView.settings.apply {
        javaScriptEnabled = allowJavaScript
        domStorageEnabled = true
        blockNetworkLoads = !allowNetwork
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
        // Force a 1:1 CSS-px-to-dp mapping. Without this, some WebView versions lay the page
        // out at a "desktop-width" virtual viewport (~980px) and scale it down to fit, which
        // makes document.documentElement.scrollHeight report a value in the wrong scale —
        // the content-height measurement below would come out systematically too small.
        useWideViewPort = false
        loadWithOverviewMode = false
    }
    if (transparentBackground) {
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    // Compose's own gesture handling (the drawer's edge-swipe, LazyColumn's vertical drag,
    // horizontal nested-scroll eaters, etc.) otherwise steals touch moves out from under the
    // WebView's own content (e.g. a <input type=range> slider), since AndroidView sits inside
    // that gesture tree. Claim the touch stream for the WebView the moment a finger lands on it.
    webView.setOnTouchListener { v, _ ->
        v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            // Never navigate the widget itself away from the loaded document — hand link
            // taps off to the system browser instead, same as tapping a link elsewhere in chat.
            if (url.startsWith("data:") || url == "about:blank") return false
            return try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) {
                true
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // No addJavascriptInterface bridge is used anywhere — evaluateJavascript is a
            // one-shot "run this script, hand back the result" call, not a persistent bridge,
            // so this stays within the same sandboxing posture as the rest of this WebView.
            // This measurement itself relies on JS, so it's a no-op when JS is disabled — the
            // card just falls back to WidgetMaxHeight (or fixedHeight, when the caller set one).
            if (!allowJavaScript) return
            onContentHeightPx?.let { cb ->
                view.evaluateJavascript("document.documentElement.scrollHeight.toString()") { result ->
                    result?.trim('"')?.toDoubleOrNull()?.toInt()?.let(cb)
                }
            }
        }
    }
    // Model-authored widget JS runs with no debugger attached and no addJavascriptInterface
    // bridge back to Kotlin — console.log/warn/error is the only way a script failure (or a
    // deliberate diagnostic) becomes visible, so route it to logcat instead of the void.
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val at = "${message.sourceId()}:${message.lineNumber()}"
            when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> DebugLog.e("WidgetWebView", "$at ${message.message()}")
                ConsoleMessage.MessageLevel.WARNING -> DebugLog.w("WidgetWebView", "$at ${message.message()}")
                else -> DebugLog.d("WidgetWebView", "$at ${message.message()}")
            }
            return true
        }
    }
}

internal fun Int.toCssHex(): String = "#%06X".format(0xFFFFFF and this)

/**
 * Builds a `<style>` block exposing the current Material 3 color scheme as CSS custom
 * properties (`--md-*`) and applying a baseline reset so plain HTML elements (inputs, buttons,
 * range sliders, links) aren't left with the browser's default light-mode styling, which clashes
 * against a transparent background and can be unreadable in dark mode.
 */
@Composable
private fun rememberWidgetThemeCss(): String {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary.toArgb().toCssHex()
    val onPrimary = scheme.onPrimary.toArgb().toCssHex()
    val secondary = scheme.secondary.toArgb().toCssHex()
    val onSecondary = scheme.onSecondary.toArgb().toCssHex()
    val background = scheme.background.toArgb().toCssHex()
    val onBackground = scheme.onBackground.toArgb().toCssHex()
    val surface = scheme.surface.toArgb().toCssHex()
    val onSurface = scheme.onSurface.toArgb().toCssHex()
    val surfaceVariant = scheme.surfaceVariant.toArgb().toCssHex()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb().toCssHex()
    val outline = scheme.outline.toArgb().toCssHex()
    val error = scheme.error.toArgb().toCssHex()
    val onError = scheme.onError.toArgb().toCssHex()
    return """
        <style>
        :root {
          --md-primary: $primary; --md-on-primary: $onPrimary;
          --md-secondary: $secondary; --md-on-secondary: $onSecondary;
          --md-background: $background; --md-on-background: $onBackground;
          --md-surface: $surface; --md-on-surface: $onSurface;
          --md-surface-variant: $surfaceVariant; --md-on-surface-variant: $onSurfaceVariant;
          --md-outline: $outline; --md-error: $error; --md-on-error: $onError;
        }
        html, body { background: transparent !important; color: var(--md-on-surface); }
        body { font-family: sans-serif; }
        a { color: var(--md-primary); }
        input, textarea, select, button {
          font-family: inherit; color: var(--md-on-surface);
          background-color: var(--md-surface-variant);
          border: 1px solid var(--md-outline); border-radius: 8px; padding: 6px 10px;
        }
        button { background-color: var(--md-primary); color: var(--md-on-primary); border: none; padding: 8px 16px; }
        input[type=range] { accent-color: var(--md-primary); background: transparent; border: none; padding: 0; }
        input[type=checkbox], input[type=radio] { accent-color: var(--md-primary); }
        </style>
    """.trimIndent()
}

/** Best-effort insertion of a `<style>` block as early as possible in the document. */
private fun injectStyle(html: String, styleTag: String): String {
    val headTag = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    if (headTag != null) {
        val insertAt = headTag.range.last + 1
        return html.substring(0, insertAt) + styleTag + html.substring(insertAt)
    }
    val bodyTag = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html)
    if (bodyTag != null) {
        return html.substring(0, bodyTag.range.first) + styleTag + html.substring(bodyTag.range.first)
    }
    val htmlTag = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
    if (htmlTag != null) {
        val insertAt = htmlTag.range.last + 1
        return html.substring(0, insertAt) + styleTag + html.substring(insertAt)
    }
    return styleTag + html
}

@Composable
private fun rememberThemedHtml(html: String, matchAppTheme: Boolean): String {
    if (!matchAppTheme) return html
    val css = rememberWidgetThemeCss()
    return remember(html, css) { injectStyle(html, css) }
}

private val WidgetMinHeight = 64.dp
private val WidgetMaxHeight = 420.dp

/** A fully-rendered widget's document, plus the sandbox flags it needs — carried from wherever
 *  it was rendered inline (see [WidgetCard]) to the full-screen viewer, so the viewer never has
 *  to re-derive per-kind theming/network policy itself; it just reloads the exact same document. */
data class ExpandedWidget(
    val html: String,
    val allowNetwork: Boolean,
    val allowJavaScript: Boolean,
    val transparentBackground: Boolean
)

/**
 * Kind-agnostic widget card chrome: the sandboxed WebView host, height animation between
 * [WidgetMinHeight]/[WidgetMaxHeight], the raw-source toggle, and the expand/view-source action
 * buttons. Each widget kind (HTML, Mermaid, ...) builds its own [documentHtml] and wraps this.
 */
@Composable
fun WidgetCard(
    sourceText: String,
    documentHtml: String,
    allowNetwork: Boolean,
    allowJavaScript: Boolean,
    transparentBackground: Boolean,
    onExpand: (html: String) -> Unit,
    modifier: Modifier = Modifier,
    // Opt out of the JS scrollHeight auto-sizing below and just use this height. For content with
    // no natural document height to measure — a map filling its viewport rather than flowing text/
    // diagram content — that measurement reads as ~0 and collapses the card to WidgetMinHeight.
    fixedHeight: Dp? = null,
) {
    var showSource by remember(documentHtml) { mutableStateOf(false) }
    var contentHeight by remember(documentHtml) { mutableStateOf<Dp?>(null) }
    // Start at the roomy end, not the small end: the WebView's own JS-side height
    // measurement runs against whatever size this Box currently is, so starting small
    // would make that very measurement come out wrong for content sized in viewport units.
    // Shrinking down after the real height is known reads as "settling", not "getting smaller".
    val targetHeight = fixedHeight ?: (contentHeight ?: WidgetMaxHeight).coerceIn(WidgetMinHeight, WidgetMaxHeight)
    Box(
        modifier = modifier
            .fillMaxWidth()
            // The rendered widget's height is an exact value driven by the WebView's JS-side
            // scrollHeight measurement (no intrinsic Compose size to measure otherwise); the
            // source view has no such measurement and just wraps its own (scroll-clamped) text
            // height instead. animateContentSize() smooths the jump between those two regimes,
            // and also the WebView height settling in as contentHeight arrives asynchronously.
            .animateContentSize()
            .then(
                if (showSource) Modifier.heightIn(min = WidgetMinHeight, max = WidgetMaxHeight)
                else Modifier.height(targetHeight)
            )
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (transparentBackground && !showSource) Modifier
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            )
    ) {
        if (showSource) {
            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = sourceText,
                    style = ChatType.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        configureWidgetWebView(
                            this, allowNetwork, allowJavaScript, transparentBackground,
                            onContentHeightPx = if (fixedHeight != null) null else { px ->
                                // scrollHeight is already in dp-equivalent CSS pixels here (WebView's
                                // JS coordinate space is device-independent when useWideViewPort is
                                // off), so wrap it directly — do NOT run it through Density.toDp(),
                                // which would treat it as physical pixels and divide by density again.
                                contentHeight = px.dp
                            }
                        )
                        loadDataWithBaseURL(null, documentHtml, "text/html", "utf-8", null)
                    }
                },
                update = { /* html/settings are fixed for the lifetime of a completed tool call */ },
                modifier = Modifier.fillMaxSize()
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                onClick = { showSource = !showSource },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
            ) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp).padding(6.dp)
                )
            }
            Surface(
                onClick = { onExpand(documentHtml) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp).padding(6.dp)
                )
            }
        }
    }
}

@Composable
fun HtmlWidgetCard(
    html: String,
    allowNetwork: Boolean,
    allowJavaScript: Boolean,
    matchAppTheme: Boolean,
    onExpand: (ExpandedWidget) -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveHtml = rememberThemedHtml(html, matchAppTheme)
    WidgetCard(
        sourceText = html,
        documentHtml = effectiveHtml,
        allowNetwork = allowNetwork,
        allowJavaScript = allowJavaScript,
        transparentBackground = matchAppTheme,
        onExpand = { doc -> onExpand(ExpandedWidget(doc, allowNetwork, allowJavaScript, matchAppTheme)) },
        modifier = modifier
    )
}

/** One fence-triggered widget kind: which `​```<fenceLanguage>` block it claims, whether it's
 *  currently enabled (per-kind app setting), and how to render the fence body when it is. */
class WidgetFenceSpec(
    val fenceLanguage: String,
    val enabled: Boolean,
    val render: @Composable (body: String) -> Unit
)

/**
 * `markdownComponents(codeFence = ...)` interception point: a fenced code block whose language
 * matches an enabled [WidgetFenceSpec] renders as that widget; any other language, or a matching
 * but currently-disabled kind, falls through to the library's own fence rendering — so the model
 * can choose to inline-render or show source just by picking the fence language, and disabling a
 * widget kind in settings always degrades to a plain code block rather than breaking.
 */
@Composable
fun MarkdownWidgetFence(
    model: MarkdownComponentModel,
    specs: List<WidgetFenceSpec>,
) {
    val node = model.node
    val content = model.content
    val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content)?.toString()
    val spec = specs.firstOrNull { it.fenceLanguage == language && it.enabled }
    if (spec != null && node.children.size >= 3) {
        // Same body-extraction math as the library's own MarkdownCodeFence (MarkdownCode.kt),
        // since that helper isn't exposed with a hook to swap in a different renderer per-fence.
        val start = node.children[2].startOffset
        val minCodeFenceCount = if (language != null && node.children.size > 3) 3 else 2
        val end = node.children[(node.children.size - 2).coerceAtLeast(minCodeFenceCount)].endOffset
        val body = content.subSequence(start, end).toString().replaceIndent()
        spec.render(body)
    } else {
        com.mikepenz.markdown.compose.elements.MarkdownCodeFence(
            content = content,
            node = node,
            style = model.typography.code,
        )
    }
}

@Composable
fun FullScreenHtmlWebView(
    widget: ExpandedWidget,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                configureWidgetWebView(this, widget.allowNetwork, widget.allowJavaScript, widget.transparentBackground)
                loadDataWithBaseURL(null, widget.html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
