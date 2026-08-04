package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs

internal const val AbsoluteBottomSentinelKey = "agora:absolute-bottom"

internal enum class AbsoluteBottomScrollPhase {
    IDLE,
    SEEKING,
    FOLLOWING,
    SETTLING,
}

internal val AbsoluteBottomScrollPhase.isActive: Boolean
    get() = this != AbsoluteBottomScrollPhase.IDLE

internal sealed interface AbsoluteBottomScrollEvent {
    data object Requested : AbsoluteBottomScrollEvent
    data object TargetUnavailable : AbsoluteBottomScrollEvent
    data object TargetAvailable : AbsoluteBottomScrollEvent
    data object ExtentChanged : AbsoluteBottomScrollEvent
    data object BottomReached : AbsoluteBottomScrollEvent
    data object Finished : AbsoluteBottomScrollEvent
    data object Cancelled : AbsoluteBottomScrollEvent
}

internal fun reduceAbsoluteBottomScroll(
    current: AbsoluteBottomScrollPhase,
    event: AbsoluteBottomScrollEvent,
): AbsoluteBottomScrollPhase = when (event) {
    AbsoluteBottomScrollEvent.Requested,
    AbsoluteBottomScrollEvent.TargetUnavailable,
    -> AbsoluteBottomScrollPhase.SEEKING

    AbsoluteBottomScrollEvent.TargetAvailable,
    AbsoluteBottomScrollEvent.ExtentChanged,
    -> if (current.isActive) AbsoluteBottomScrollPhase.FOLLOWING else current

    AbsoluteBottomScrollEvent.BottomReached ->
        if (current.isActive) AbsoluteBottomScrollPhase.SETTLING else current

    AbsoluteBottomScrollEvent.Finished,
    AbsoluteBottomScrollEvent.Cancelled,
    -> AbsoluteBottomScrollPhase.IDLE
}

internal data class AbsoluteBottomLayoutSnapshot(
    val totalItemsCount: Int,
    val canScrollForward: Boolean,
    val viewportStartOffsetPx: Int,
    val viewportEndOffsetPx: Int,
    val afterContentPaddingPx: Int,
    val sentinelOffsetPx: Int?,
    val sentinelSizePx: Int?,
) {
    val sentinelVisible: Boolean
        get() = sentinelOffsetPx != null && sentinelSizePx != null

    val remainingDistancePx: Float?
        get() {
            val offset = sentinelOffsetPx ?: return null
            val size = sentinelSizePx ?: return null
            val contentEnd = viewportEndOffsetPx - afterContentPaddingPx
            return (offset + size - contentEnd).toFloat().coerceAtLeast(0f)
        }

    val viewportSizePx: Int
        get() = (viewportEndOffsetPx - viewportStartOffsetPx).coerceAtLeast(0)
}

internal fun absoluteBottomLayoutSnapshot(
    layoutInfo: LazyListLayoutInfo,
    canScrollForward: Boolean,
): AbsoluteBottomLayoutSnapshot {
    val sentinelIndex = layoutInfo.totalItemsCount - 1
    val sentinel = if (sentinelIndex >= 0) {
        layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == sentinelIndex }
    } else {
        null
    }
    return AbsoluteBottomLayoutSnapshot(
        totalItemsCount = layoutInfo.totalItemsCount,
        canScrollForward = canScrollForward,
        viewportStartOffsetPx = layoutInfo.viewportStartOffset,
        viewportEndOffsetPx = layoutInfo.viewportEndOffset,
        afterContentPaddingPx = layoutInfo.afterContentPadding,
        sentinelOffsetPx = sentinel?.offset,
        sentinelSizePx = sentinel?.size,
    )
}

internal fun estimateAbsoluteBottomDistancePx(
    lastVisibleIndex: Int,
    lastVisibleEndOffsetPx: Int,
    viewportEndOffsetPx: Int,
    afterContentPaddingPx: Int,
    totalItemsCount: Int,
    estimatedItemSizePx: (Int) -> Float,
): Float {
    if (lastVisibleIndex < 0 || totalItemsCount <= 0) return 0f
    var estimatedContentEnd = lastVisibleEndOffsetPx.toFloat()
    for (index in (lastVisibleIndex + 1) until totalItemsCount) {
        estimatedContentEnd += estimatedItemSizePx(index).coerceAtLeast(0f)
    }
    val viewportContentEnd = viewportEndOffsetPx - afterContentPaddingPx
    return (estimatedContentEnd - viewportContentEnd).coerceAtLeast(0f)
}

internal fun shouldShowAbsoluteBottomButton(
    isNewChatMode: Boolean,
    isSwitching: Boolean,
    conversationContentReady: Boolean,
    shareSelectionActive: Boolean,
    hasItems: Boolean,
    canScrollForward: Boolean,
    isNearBottom: Boolean,
    isStreamingAutoFollowing: Boolean,
    scrollPhase: AbsoluteBottomScrollPhase,
): Boolean =
    !isNewChatMode &&
        !isSwitching &&
        conversationContentReady &&
        !shareSelectionActive &&
        hasItems &&
        canScrollForward &&
        !isNearBottom &&
        !isStreamingAutoFollowing &&
        !scrollPhase.isActive

/**
 * Hysteretic proximity latch for the absolute-bottom button.
 *
 * A single threshold makes the button flicker when a layout/IME update moves the sentinel by a
 * pixel or two. Once hidden, the button therefore stays hidden until the viewport has moved a
 * meaningful distance away from the physical end.
 */
internal fun reduceAbsoluteBottomProximity(
    wasNearBottom: Boolean,
    canScrollForward: Boolean,
    remainingDistancePx: Float?,
    hideThresholdPx: Float,
    showThresholdPx: Float,
): Boolean {
    require(hideThresholdPx >= 0f)
    require(showThresholdPx >= hideThresholdPx)
    if (!canScrollForward) return true
    val distance = remainingDistancePx ?: return false
    return if (wasNearBottom) {
        distance <= showThresholdPx
    } else {
        distance <= hideThresholdPx
    }
}

/**
 * Smoothly reaches the list's physical maximum extent. While seeking, every layout change
 * retargets the same actor; once it reaches the bottom of an active generation, ownership is
 * handed to MessageList's attached-tail actor. The stable final sentinel makes the target
 * independent from the streaming-tail indicator; [LazyListState.canScrollForward] remains the
 * completion authority.
 *
 * No scroll mutation is held while already at the bottom. That keeps touch input responsive.
 * A real drag cancels the owning effect in [ChatApp]; new content wakes the suspended snapshot
 * observer and is absorbed by the same frame-coalesced actor.
 */
internal suspend fun LazyListState.animateToAbsoluteBottom(
    isGenerationActive: () -> Boolean,
    estimateRemainingDistancePx: () -> Float?,
    minimumStepPx: Float,
    onPhaseChanged: (AbsoluteBottomScrollPhase) -> Unit,
): Boolean {
    var phase = AbsoluteBottomScrollPhase.IDLE
    var followedActiveGeneration = isGenerationActive()

    fun dispatch(event: AbsoluteBottomScrollEvent) {
        val next = reduceAbsoluteBottomScroll(phase, event)
        if (next != phase) {
            phase = next
            onPhaseChanged(next)
        }
    }

    dispatch(AbsoluteBottomScrollEvent.Requested)
    try {
        while (currentCoroutineContext().isActive) {
            followedActiveGeneration = followedActiveGeneration || isGenerationActive()
            var layout = absoluteBottomLayoutSnapshot(layoutInfo, canScrollForward)
            if (!layout.sentinelVisible) dispatch(AbsoluteBottomScrollEvent.TargetUnavailable)

            val reached = smoothSeekToItem(
                targetIndex = { (layoutInfo.totalItemsCount - 1).coerceAtLeast(0) },
                targetErrorPx = { sentinel ->
                    dispatch(AbsoluteBottomScrollEvent.TargetAvailable)
                    val current = absoluteBottomLayoutSnapshot(
                        layoutInfo = layoutInfo,
                        canScrollForward = canScrollForward,
                    )
                    val contentEnd =
                        current.viewportEndOffsetPx - current.afterContentPaddingPx
                    (sentinel.offset + sentinel.size - contentEnd)
                        .toFloat()
                        .coerceAtLeast(0f)
                },
                estimatedErrorPx = estimateRemainingDistancePx,
                exactTargetReady = {
                    absoluteBottomLayoutSnapshot(
                        layoutInfo = layoutInfo,
                        canScrollForward = canScrollForward,
                    ).sentinelVisible
                },
                minimumStepPx = minimumStepPx,
            )
            if (!reached) {
                dispatch(AbsoluteBottomScrollEvent.Finished)
                return false
            }

            dispatch(AbsoluteBottomScrollEvent.BottomReached)
            layout = absoluteBottomLayoutSnapshot(layoutInfo, canScrollForward)
            if (isGenerationActive()) {
                // Do not keep a second long-lived follow owner for the rest of generation.
                // ChatApp exits the handoff phase and explicitly attaches MessageList's one
                // frame-driven tail actor, which a real upward drag can cancel immediately.
                dispatch(AbsoluteBottomScrollEvent.Finished)
                return true
            }

            val minimumSettlingMs = if (followedActiveGeneration) 700L else 192L
            val settlingStartNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
            var previousLayout = layout
            var stableFrames = 0
            while (currentCoroutineContext().isActive) {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                layout = absoluteBottomLayoutSnapshot(layoutInfo, canScrollForward)
                if (isGenerationActive() || layout.canScrollForward || !layout.sentinelVisible) {
                    followedActiveGeneration =
                        followedActiveGeneration || isGenerationActive()
                    dispatch(AbsoluteBottomScrollEvent.ExtentChanged)
                    break
                }
                stableFrames = if (layout == previousLayout) stableFrames + 1 else 0
                previousLayout = layout
                val settlingElapsedMs =
                    (frameNanos - settlingStartNanos).coerceAtLeast(0L) / 1_000_000L
                if (
                    (settlingElapsedMs >= minimumSettlingMs && stableFrames >= 6) ||
                    settlingElapsedMs >= 1_600L
                ) {
                    dispatch(AbsoluteBottomScrollEvent.Finished)
                    return true
                }
            }
        }
        return false
    } finally {
        if (phase.isActive) dispatch(AbsoluteBottomScrollEvent.Cancelled)
    }
}
