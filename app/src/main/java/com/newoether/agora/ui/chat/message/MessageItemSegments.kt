package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment

internal fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
    val merged = mutableListOf<MessageSegment>()
    for (seg in segs) {
        val last = merged.lastOrNull()
        // Only continuous answer/reasoning text is merged into one flowing block.
        // Transcriptions stay separate: each describes a distinct image, so a
        // 1:1 image↔block correspondence must be preserved.
        if (last != null && last.type == seg.type && (seg.type == "answer" || seg.type == "thought")) {
            merged[merged.lastIndex] = last.copy(
                content = last.content + seg.content,
                durationMs = mergeDurationMs(last.durationMs, seg.durationMs)
            )
        } else {
            merged.add(seg)
        }
    }
    // Drop content-less thought segments AFTER merging (a streamed thought arrives as blank
    // fragments that concatenate into real content). Models that signal thinking without
    // emitting a summary would otherwise render an empty "Thinking" block — and because every
    // renderer funnels through here, dropping them keeps the block keys, timeline dots, and
    // detail sheet indices all consistent with what is actually drawn.
    return merged.filterNot { it.type == "thought" && it.content.isBlank() }
}

private fun mergeDurationMs(first: Long?, second: Long?): Long? {
    val merged = (first ?: 0L) + (second ?: 0L)
    return merged.takeIf { it > 0L }
}

internal fun thoughtDurationMs(segs: List<MessageSegment>): Long? {
    return segs.sumOf { seg ->
        if (seg.type == "thought") seg.durationMs ?: 0L else 0L
    }.takeIf { it > 0L }
}

private fun MessageSegment.isBlankAnswerSegment(): Boolean =
    type == "answer" && content.isBlank()

internal fun MessageSegment.isVisibleAnswerSegment(): Boolean =
    type == "answer" && content.isNotBlank()

internal fun MessageSegment.isInfoSegment(): Boolean =
    (type == "thought" && content.isNotBlank()) || type == "tool" || type == "transcription"

internal fun ChatMessage.hasActiveAnswerSegment(): Boolean {
    val lastVisibleSegment = segments?.lastOrNull { !it.isBlankAnswerSegment() }
    return if (lastVisibleSegment != null) {
        lastVisibleSegment.isVisibleAnswerSegment()
    } else {
        text.isNotBlank()
    }
}

internal fun buildTimelineBlockKeys(
    messageId: String,
    segments: List<MessageSegment>,
    groupAdjacentBlocks: Boolean
): Set<String> {
    val keys = linkedSetOf<String>()
    var detailIndex = 0
    var index = 0
    while (index < segments.size) {
        val seg = segments[index]
        when {
            seg.type == "answer" -> {
                index++
            }
            seg.isInfoSegment() -> {
                if (groupAdjacentBlocks) {
                    var blockEnd = index
                    var firstDetailIndex: Int? = null
                    while (blockEnd < segments.size && !segments[blockEnd].isVisibleAnswerSegment()) {
                        val blockSeg = segments[blockEnd]
                        if (blockSeg.isInfoSegment()) {
                            if (firstDetailIndex == null) firstDetailIndex = detailIndex
                            detailIndex++
                        }
                        blockEnd++
                    }
                    keys += "$messageId:group:${firstDetailIndex ?: index}"
                    index = blockEnd
                } else {
                    keys += "$messageId:timeline:$detailIndex"
                    detailIndex++
                    index++
                }
            }
            else -> {
                index++
            }
        }
    }
    return keys
}

@Composable
internal fun AnimatedTimelineBlockAppearance(
    animationKey: String,
    animate: Boolean,
    content: @Composable () -> Unit
) {
    key(animationKey) {
        // Latch the first-appearance decision. Subsequent token snapshots remove this key from the
        // caller's "new" set, but must not cancel an animation already in flight.
        val play = remember(animationKey) { animate }
        val progress = remember(animationKey) {
            Animatable(if (play) 0f else 1f)
        }
        LaunchedEffect(animationKey) {
            if (play) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 350,
                        easing = LinearOutSlowInEasing,
                    ),
                )
            }
        }
        Box(
            modifier = Modifier.graphicsLayer {
                // This lambda is observed by the layer, so frame updates invalidate drawing only:
                // the block occupies its final size from frame one and never remeasures the list.
                val value = progress.value
                alpha = value
                val scale = 0.9f + 0.1f * value
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            },
        ) {
            content()
        }
    }
}

// Label a transcription segment; numbers them ("Image Transcription 1/2/…") only
// when more than one is present, so a single image keeps the clean unnumbered name.
@Composable
internal fun transcriptionLabel(segs: List<MessageSegment>, index: Int): String {
    val total = segs.count { it.type == "transcription" }
    if (total <= 1) return stringResource(R.string.transcription_label)
    val ordinal = segs.take(index + 1).count { it.type == "transcription" }
    return stringResource(R.string.transcription_label_numbered, ordinal)
}
