package com.newoether.agora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

/**
 * A finite typewriter owned by one real page-entry [animationKey].
 *
 * Saveable progress prevents configuration changes from replaying the entry. Once typing
 * completes the cursor branch leaves composition, disposing its infinite transition, and the
 * [LaunchedEffect] returns permanently. Leaving the page cancels an in-flight effect normally.
 */
@Composable
fun TypewriterText(
    text: String,
    animationKey: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    typeSpeedMs: Int = 100,
) {
    var visibleCount by rememberSaveable(animationKey) { mutableIntStateOf(0) }
    var completed by rememberSaveable(animationKey) { mutableStateOf(false) }

    LaunchedEffect(animationKey, text) {
        if (completed) return@LaunchedEffect
        if (text.isEmpty()) {
            visibleCount = 0
            completed = true
            return@LaunchedEffect
        }
        visibleCount = visibleCount.coerceIn(0, text.length)
        for (count in (visibleCount + 1)..text.length) {
            visibleCount = count
            delay(typeSpeedMs.toLong())
        }
        completed = true
    }

    Row(modifier = modifier) {
        Text(
            text = text.take(visibleCount),
            style = style,
            fontWeight = fontWeight,
            color = color,
        )
        if (!completed) {
            val cursorAlpha by rememberInfiniteTransition(
                label = "welcomeCursor",
            ).animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(530, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "welcomeCursorAlpha",
            )
            Text(
                text = "|",
                style = style,
                fontWeight = fontWeight,
                color = color.copy(alpha = cursorAlpha),
                modifier = Modifier.alpha(cursorAlpha),
            )
        }
    }
}
