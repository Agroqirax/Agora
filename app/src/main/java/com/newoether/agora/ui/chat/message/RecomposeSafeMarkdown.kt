package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex

private const val STREAMING_CROSSFADE_DURATION_MS = 180

/**
 * Latest-wins state for a two-buffer renderer.
 *
 * [front] is always the fully visible snapshot. [incoming] is prepared offscreen for a complete
 * frame and then faded over [front]. Updates received during that fade replace [pending] instead
 * of restarting the animation or building a stale queue. Finishing a fade promotes [incoming]
 * and immediately arms the newest pending value, so terminal provider updates can never snap.
 */
internal data class LatestWinsCrossfadeState<T>(
    val front: T? = null,
    val incoming: T? = null,
    val pending: T? = null,
    val transitionId: Int = 0,
) {
    val isFading: Boolean get() = incoming != null

    fun offer(
        content: T,
        animateChanges: Boolean,
        sameContent: (T, T) -> Boolean,
    ): LatestWinsCrossfadeState<T> {
        if (!animateChanges) {
            return if (
                front != null &&
                sameContent(front, content) &&
                incoming == null &&
                pending == null
            ) {
                this
            } else {
                LatestWinsCrossfadeState(front = content, transitionId = transitionId)
            }
        }

        if (incoming != null) {
            if (sameContent(incoming, content)) {
                return if (pending == null) this else copy(pending = null)
            }
            if (pending?.let { sameContent(it, content) } == true) {
                return this
            }
            return copy(pending = content)
        }

        if (front?.let { sameContent(it, content) } == true) return this
        return copy(
            incoming = content,
            pending = null,
            transitionId = transitionId + 1,
        )
    }

    fun finish(sameContent: (T, T) -> Boolean): LatestWinsCrossfadeState<T> {
        val promoted = incoming ?: return this
        val latest = pending
        return if (latest != null && !sameContent(promoted, latest)) {
            LatestWinsCrossfadeState(
                front = promoted,
                incoming = latest,
                transitionId = transitionId + 1,
            )
        } else {
            LatestWinsCrossfadeState(
                front = promoted,
                transitionId = transitionId,
            )
        }
    }
}

/**
 * Double-buffered Markdown crossfade.
 *
 * The first snapshot keeps the established immediate-render behavior. Every later update from a
 * live renderer, including its terminal provider snapshot, follows the prepared two-buffer path.
 * Historical content opts out and renders immediately.
 */
@Composable
internal fun RecomposeSafeMarkdown(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    render: @Composable (text: String) -> Unit,
) {
    // Capture whether this renderer was born live. A streaming -> terminal status flip must not
    // disable buffering while the final provider snapshot is still queued behind an active fade.
    val animateChanges = remember { isStreaming }
    LatestWinsCrossfade(
        content = content,
        animateChanges = animateChanges,
        modifier = modifier,
        sameContent = { first, second -> first == second },
        render = render,
    )
}

@Composable
internal fun <T : Any> LatestWinsCrossfade(
    content: T,
    animateChanges: Boolean,
    modifier: Modifier = Modifier,
    sameContent: (T, T) -> Boolean,
    render: @Composable (T) -> Unit,
) {
    var buffers: LatestWinsCrossfadeState<T> by remember {
        // Preserve the established UI contract: the first live snapshot is immediately visible.
        // Double buffering applies only to subsequent updates and terminal handoff.
        mutableStateOf(LatestWinsCrossfadeState(front = content))
    }
    var fadeAlpha by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(content, animateChanges) {
        buffers = buffers.offer(content, animateChanges, sameContent)
    }

    LaunchedEffect(buffers.transitionId) {
        if (!buffers.isFading) return@LaunchedEffect
        fadeAlpha = 0f
        // The incoming Markdown subtree must be composed and measured before it becomes visible.
        withFrameNanos { }
        val startNanos = withFrameNanos { it }
        val durationNanos = STREAMING_CROSSFADE_DURATION_MS * 1_000_000L
        while (fadeAlpha < 1f) {
            val nowNanos = withFrameNanos { it }
            fadeAlpha = (
                (nowNanos - startNanos).toFloat() / durationNanos
                ).coerceIn(0f, 1f)
        }
        val finished = buffers.finish(sameContent)
        // Promotion and alpha reset are one atomic frame. If [finished] immediately arms the
        // newest pending value, that value must begin invisible rather than inheriting alpha=1
        // from the transition that just completed.
        Snapshot.withMutableSnapshot {
            buffers = finished
            fadeAlpha = 0f
        }
    }

    Box(modifier = modifier) {
        buffers.front?.let { front ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(0f),
            ) {
                render(front)
            }
        }
        buffers.incoming?.let { incoming ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .alpha(fadeAlpha),
            ) {
                render(incoming)
            }
        }
    }
}
