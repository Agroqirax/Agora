package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.newoether.agora.R

/**
 * Full-screen expanded view of an inline [HtmlWidgetCard], modeled on
 * [FullScreenMediaViewer]'s single-image treatment (translucent black background,
 * top-end close FAB, back-to-close).
 */
@Composable
fun FullScreenHtmlViewer(
    html: String,
    allowNetwork: Boolean,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))) {
        FullScreenHtmlWebView(
            html = html,
            allowNetwork = allowNetwork,
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            onClick = onClose,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(24.dp)
                .shadow(8.dp, CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.provider_close),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(48.dp).padding(12.dp)
            )
        }
    }
    BackHandler { onClose() }
}
