package com.newoether.agora.ui.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Configures a [WebView] to render model-authored HTML as safely as this app can manage: no
 * origin (loaded via [WebView.loadDataWithBaseURL] with a null base, so there's nothing for
 * same-origin JS to target), no file/content access, no [WebView.addJavascriptInterface] bridge
 * to app internals ever, and network-loaded resources (remote images/CSS/CDN scripts) blocked
 * unless the user has explicitly opted in via the HTML widgets network setting.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configureWidgetWebView(webView: WebView, allowNetwork: Boolean) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        blockNetworkLoads = !allowNetwork
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HtmlWidgetCard(
    html: String,
    allowNetwork: Boolean,
    onExpand: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .combinedClickable(onClick = onExpand, onLongClick = onLongPress)
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    configureWidgetWebView(this, allowNetwork)
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            update = { /* html/allowNetwork are fixed for the lifetime of a completed tool call */ },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun FullScreenHtmlWebView(
    html: String,
    allowNetwork: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                configureWidgetWebView(this, allowNetwork)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
