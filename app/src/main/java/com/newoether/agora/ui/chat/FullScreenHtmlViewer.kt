package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.newoether.agora.R

/**
 * Full-screen expanded view of an inline [HtmlWidgetCard]. Unlike [FullScreenMediaViewer]'s
 * photo/video treatment, this uses the normal chat background (not a translucent black scrim) —
 * HTML widgets are ordinary UI content, often with dark text in light mode, so a dark overlay
 * behind them would wreck contrast rather than provide the usual "focus on media" effect.
 */
@Composable
fun FullScreenHtmlViewer(
    widget: ExpandedWidget,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        FullScreenHtmlWebView(
            widget = widget,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
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
