package com.newoether.agora.ui.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Configures a [WebView] to render model-authored HTML as safely as this app can manage: no
 * origin (loaded via [WebView.loadDataWithBaseURL] with a null base, so there's nothing for
 * same-origin JS to target), no file/content access, no [WebView.addJavascriptInterface] bridge
 * to app internals ever, and network-loaded resources (remote images/CSS/CDN scripts) blocked
 * unless the user has explicitly opted in via the HTML widgets network setting.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun configureWidgetWebView(webView: WebView, allowNetwork: Boolean, transparentBackground: Boolean) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        blockNetworkLoads = !allowNetwork
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
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
    }
}

private fun Int.toCssHex(): String = "#%06X".format(0xFFFFFF and this)

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

@Composable
fun HtmlWidgetCard(
    html: String,
    allowNetwork: Boolean,
    matchAppTheme: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveHtml = rememberThemedHtml(html, matchAppTheme)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (matchAppTheme) Modifier
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            )
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    configureWidgetWebView(this, allowNetwork, matchAppTheme)
                    loadDataWithBaseURL(null, effectiveHtml, "text/html", "utf-8", null)
                }
            },
            update = { /* html/settings are fixed for the lifetime of a completed tool call */ },
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            onClick = onExpand,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
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

@Composable
fun FullScreenHtmlWebView(
    html: String,
    allowNetwork: Boolean,
    matchAppTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val effectiveHtml = rememberThemedHtml(html, matchAppTheme)
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                configureWidgetWebView(this, allowNetwork, matchAppTheme)
                loadDataWithBaseURL(null, effectiveHtml, "text/html", "utf-8", null)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
