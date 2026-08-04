package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

internal const val MESSAGE_ENTER_DURATION_MS = 320
internal const val SEGMENT_ENTER_DURATION_MS = 360
internal const val STATUS_CROSSFADE_DURATION_MS = 280
internal const val ACTIONS_ENTER_DURATION_MS = 320
internal const val ACTIONS_EXIT_DURATION_MS = 220
internal const val COMPOSER_ICON_CROSSFADE_DURATION_MS = 200

/**
 * A one-shot lifecycle appearance that owns only a draw layer.
 *
 * Content occupies its final measured size from the first frame. The animation therefore cannot
 * resize a LazyColumn item, invalidate Markdown layout, or compete with scroll positioning.
 */
@Composable
internal fun generationLifecycleAppearanceModifier(
    animationKey: String,
    animate: Boolean,
    durationMillis: Int,
    initialScale: Float = 1f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
): Modifier {
    val play = remember(animationKey) { animate }
    val progress = remember(animationKey) {
        Animatable(if (play) 0f else 1f)
    }
    LaunchedEffect(animationKey) {
        if (play) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    easing = LinearEasing,
                ),
            )
        }
    }
    return Modifier.graphicsLayer {
        val value = progress.value.coerceIn(0f, 1f)
        alpha = value
        val scaleProgress = LinearOutSlowInEasing.transform(value)
        val scale = initialScale + (1f - initialScale) * scaleProgress
        scaleX = scale
        scaleY = scale
        this.transformOrigin = transformOrigin
    }
}

internal fun assistantActionsVisible(
    isStreaming: Boolean,
    regenerateRequested: Boolean,
): Boolean = !isStreaming && !regenerateRequested
