package com.newoether.agora.ui.settings

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Shared animation specs for settings page transitions ──
//     One source of truth — tune here, every sub-page follows.

private val SpringDamping = Spring.DampingRatioLowBouncy
private val SpringStiff = Spring.StiffnessLow
private const val SpringVisibilityThreshold = 0.001f
private const val FadeDuration = 300
private const val EnterSlideFraction = 0.75f  // fraction of screen width
private const val ExitSlideFraction = 0.75f
private const val ScaleFrom = 0.94f
private const val ScaleTo = 0.94f

/** Spring used for all enter & exit animations (slide + scale + fade). */
internal fun settingsSpring(): SpringSpec<Float> = spring(
    dampingRatio = SpringDamping,
    stiffness = SpringStiff,
    visibilityThreshold = SpringVisibilityThreshold
)

/**
 * Settings transition host that keeps the navigation target interactive and
 * prevents the outgoing page from receiving touches while its exit animation is
 * still visible.
 */
@Composable
internal fun <T> GuardedAnimatedContent(
    targetState: T,
    forward: Boolean,
    content: @Composable (T) -> Unit
) {
    val enterSlideProgress = remember { Animatable(1f) }
    val enterAlpha = remember { Animatable(1f) }
    val enterScale = remember { Animatable(1f) }
    val outgoingSlideProgress = remember { Animatable(0f) }
    val outgoingAlpha = remember { Animatable(0f) }
    val outgoingScale = remember { Animatable(ScaleTo) }
    var visibleTarget by remember { mutableStateOf(targetState) }
    var outgoingPage by remember { mutableStateOf<OutgoingSettingsPage<T>?>(null) }
    var activeForward by remember { mutableStateOf(forward) }

    LaunchedEffect(targetState) {
        if (targetState != visibleTarget) {
            outgoingPage = OutgoingSettingsPage(visibleTarget)
            activeForward = forward
            enterSlideProgress.snapTo(0f)
            enterAlpha.snapTo(0f)
            enterScale.snapTo(ScaleFrom)
            outgoingSlideProgress.snapTo(0f)
            outgoingAlpha.snapTo(1f)
            outgoingScale.snapTo(1f)
            visibleTarget = targetState
            listOf(
                launch { enterSlideProgress.animateTo(1f, animationSpec = settingsSpring()) },
                launch { enterAlpha.animateTo(1f, animationSpec = tween(FadeDuration)) },
                launch { enterScale.animateTo(1f, animationSpec = settingsSpring()) },
                launch { outgoingSlideProgress.animateTo(1f, animationSpec = settingsSpring()) },
                launch { outgoingAlpha.animateTo(0f, animationSpec = settingsSpring()) },
                launch { outgoingScale.animateTo(ScaleTo, animationSpec = settingsSpring()) }
            ).joinAll()
            outgoingPage = null
            enterSlideProgress.snapTo(1f)
            enterAlpha.snapTo(1f)
            enterScale.snapTo(1f)
            outgoingSlideProgress.snapTo(0f)
            outgoingAlpha.snapTo(0f)
            outgoingScale.snapTo(ScaleTo)
        }
    }

    val activeOutgoingPage = outgoingPage
    val isTransitioning = activeOutgoingPage != null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val targetOffset = targetOffsetPx(activeForward, enterSlideProgress.value, widthPx)
        val outgoingOffset = outgoingOffsetPx(activeForward, outgoingSlideProgress.value, widthPx)

        if (activeOutgoingPage != null) {
            key("outgoing", activeOutgoingPage.state) {
                SettingsTransitionPage(
                    offsetX = outgoingOffset,
                    alpha = outgoingAlpha.value.coerceIn(0f, 1f),
                    scale = outgoingScale.value,
                    zIndex = 0f,
                    consumeInput = true
                ) {
                    content(activeOutgoingPage.state)
                }
            }
        }

        if (isTransitioning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0.5f)
                    .consumePointerInput()
            )
        }

        key("target", visibleTarget) {
            SettingsTransitionPage(
                offsetX = targetOffset,
                alpha = enterAlpha.value.coerceIn(0f, 1f),
                scale = enterScale.value,
                zIndex = 1f,
                consumeInput = false
            ) {
                content(visibleTarget)
            }
        }
    }
}

private data class OutgoingSettingsPage<T>(val state: T)

@Composable
private fun SettingsTransitionPage(
    offsetX: Int,
    alpha: Float,
    scale: Float,
    zIndex: Float,
    consumeInput: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(zIndex)
            .offset { IntOffset(offsetX, 0) }
            .alpha(alpha)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (consumeInput) Modifier.consumePointerInput() else Modifier)
    ) {
        content()
    }
}

private fun targetOffsetPx(forward: Boolean, progress: Float, widthPx: Int): Int {
    val distance = widthPx * EnterSlideFraction
    val offset = distance * (1f - progress)
    return if (forward) offset.roundToInt() else -offset.roundToInt()
}

private fun outgoingOffsetPx(forward: Boolean, progress: Float, widthPx: Int): Int {
    val distance = widthPx * ExitSlideFraction
    val offset = distance * progress
    return if (forward) -offset.roundToInt() else offset.roundToInt()
}

private fun Modifier.consumePointerInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
