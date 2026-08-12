package com.newoether.agora

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.newoether.agora.ui.chat.ExpandedChatWidget
import com.newoether.agora.ui.chat.FullScreenHtmlViewer

/** Full-screen chat-widget overlay plus its [TopLevelPresentation] release-on-exit-complete
 *  wiring, matching the media/text preview pattern — extracted out of MainActivity.kt to keep
 *  that file within the repo's Kotlin-file line-count policy. */
@Composable
internal fun ChatWidgetPreviewOverlay(
    chatWidget: ExpandedChatWidget?,
    topLevelPresentation: TopLevelPresentationState,
    onClose: () -> Unit,
) {
    val transition = updateTransition(targetState = chatWidget != null, label = "chatWidgetPreview")
    LaunchedEffect(transition) {
        snapshotFlow { transition.currentState to transition.isRunning }.collect { (currentState, isRunning) ->
            if (!currentState && !isRunning) topLevelPresentation.release(TopLevelPresentation.WIDGET_PREVIEW)
        }
    }
    transition.AnimatedVisibility(visible = { it }, enter = fadeIn(), exit = fadeOut()) {
        var lastChatWidget by remember { mutableStateOf<ExpandedChatWidget?>(null) }
        LaunchedEffect(chatWidget) { if (chatWidget != null) lastChatWidget = chatWidget }
        val widget = lastChatWidget ?: return@AnimatedVisibility
        FullScreenHtmlViewer(chatWidget = widget, onClose = onClose)
    }
}
