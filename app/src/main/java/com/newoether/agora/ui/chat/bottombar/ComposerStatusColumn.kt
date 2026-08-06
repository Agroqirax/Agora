package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.QueuedSend
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val COMPOSER_STATUS_ROW_HEIGHT: Dp = 40.dp
internal val COMPOSER_STATUS_ROW_SHAPE = RoundedCornerShape(20.dp)

private val STATUS_ROW_GAP = 4.dp
private val STATUS_BOTTOM_BUFFER = COMPOSER_STATUS_ROW_HEIGHT + STATUS_ROW_GAP
private const val STATUS_POSITION_DURATION_MS = 240

private sealed interface ComposerStatusItem {
    val stableKey: String

    data class Loop(val value: LoopEntity) : ComposerStatusItem {
        override val stableKey: String = "loop:${value.conversationId}"
    }

    data class Queue(val value: QueuedSend) : ComposerStatusItem {
        override val stableKey: String = "queue:${value.id}"
    }
}

/**
 * Preserves the observed order of live rows so every newly observed cron or queue entry is
 * appended at the bottom instead of being inserted above an existing row.
 */
private class ComposerStatusOrder {
    private val ordinalByKey = LinkedHashMap<String, Long>()
    private var nextOrdinal = 0L

    fun update(candidates: List<ComposerStatusItem>): List<ComposerStatusItem> {
        val liveKeys = candidates.mapTo(HashSet(candidates.size)) { it.stableKey }
        ordinalByKey.keys.retainAll(liveKeys)
        candidates.forEach { candidate ->
            if (candidate.stableKey !in ordinalByKey) {
                ordinalByKey[candidate.stableKey] = nextOrdinal++
            }
        }
        return candidates.sortedBy { ordinalByKey.getValue(it.stableKey) }
    }
}

private data class StatusTombstone(
    val key: String,
    val yPx: Float,
)

@Composable
internal fun ComposerStatusColumn(
    activeLoop: LoopEntity?,
    loopRunning: Boolean,
    onStopLoop: () -> Unit,
    queuedSends: List<QueuedSend>,
    onRemoveQueuedSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val order = remember { ComposerStatusOrder() }
    val orderedItems = remember(activeLoop, queuedSends) {
        val candidates = buildList {
            activeLoop?.let { add(ComposerStatusItem.Loop(it)) }
            queuedSends
                .sortedBy(QueuedSend::createdAt)
                .forEach { add(ComposerStatusItem.Queue(it)) }
        }
        order.update(candidates)
    }
    val latestItems by rememberUpdatedState(orderedItems)
    val transitionRequests = remember {
        Channel<List<ComposerStatusItem>>(capacity = Channel.CONFLATED)
    }
    var displayedItems by remember { mutableStateOf(orderedItems) }
    var tombstones by remember { mutableStateOf<List<StatusTombstone>>(emptyList()) }
    val itemTranslations = remember { mutableStateMapOf<String, Float>() }
    val columnTranslation = remember { Animatable(0f) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { COMPOSER_STATUS_ROW_HEIGHT.toPx() }
    val rowGapPx = with(density) { STATUS_ROW_GAP.toPx() }

    SideEffect {
        transitionRequests.trySend(latestItems)
    }
    DisposableEffect(transitionRequests) {
        onDispose { transitionRequests.close() }
    }

    LaunchedEffect(
        transitionRequests,
        rowHeightPx,
        rowGapPx,
        allowSpatialTransitions,
    ) {
        for (targetItems in transitionRequests) {
            if (!allowSpatialTransitions) {
                columnTranslation.snapTo(0f)
                itemTranslations.clear()
                tombstones = emptyList()
                displayedItems = targetItems
                continue
            }
            val oldItems = displayedItems
            val oldKeys = oldItems.map(ComposerStatusItem::stableKey)
            val targetKeys = targetItems.map(ComposerStatusItem::stableKey)
            if (oldKeys == targetKeys) {
                displayedItems = targetItems
                continue
            }

            // Finish an append already in progress before calculating deletion placement.
            if (columnTranslation.value != 0f) {
                columnTranslation.animateTo(
                    targetValue = 0f,
                    animationSpec = statusPositionSpec(),
                )
            }
            itemTranslations.clear()
            tombstones = emptyList()

            val targetKeySet = targetKeys.toHashSet()
            val onlyAppended =
                oldKeys.all { it in targetKeySet } &&
                    targetKeys.take(oldKeys.size) == oldKeys

            if (onlyAppended && targetItems.size > oldItems.size) {
                val distance = stackHeightPx(
                    count = targetItems.size,
                    rowHeightPx = rowHeightPx,
                    rowGapPx = rowGapPx,
                ) - stackHeightPx(
                    count = oldItems.size,
                    rowHeightPx = rowHeightPx,
                    rowGapPx = rowGapPx,
                )
                displayedItems = targetItems
                columnTranslation.snapTo(distance.coerceAtLeast(0f))
                columnTranslation.animateTo(
                    targetValue = 0f,
                    animationSpec = statusPositionSpec(),
                )
                continue
            }

            val oldHeight = stackHeightPx(oldItems.size, rowHeightPx, rowGapPx)
            val targetHeight = stackHeightPx(targetItems.size, rowHeightPx, rowGapPx)
            val oldIndexByKey = oldKeys.withIndex().associate { it.value to it.index }
            val targetIndexByKey = targetKeys.withIndex().associate { it.value to it.index }

            val survivorTranslations = buildMap {
                targetKeys.forEach { stableKey ->
                    val oldIndex = oldIndexByKey[stableKey] ?: return@forEach
                    val targetIndex = targetIndexByKey.getValue(stableKey)
                    val oldScreenY = rowY(oldIndex, rowHeightPx, rowGapPx) - oldHeight
                    val targetScreenY = rowY(targetIndex, rowHeightPx, rowGapPx) - targetHeight
                    val translation = oldScreenY - targetScreenY
                    if (abs(translation) >= 0.5f) put(stableKey, translation)
                }
            }
            val removedTombstones = oldItems.mapIndexedNotNull { index, item ->
                if (item.stableKey in targetKeySet) return@mapIndexedNotNull null
                val oldScreenY = rowY(index, rowHeightPx, rowGapPx) - oldHeight
                val yInTargetViewport = oldScreenY + targetHeight
                if (yInTargetViewport + rowHeightPx <= 0f || yInTargetViewport >= targetHeight) {
                    null
                } else {
                    StatusTombstone(item.stableKey, yInTargetViewport)
                }
            }

            survivorTranslations.forEach { (stableKey, translation) ->
                itemTranslations[stableKey] = translation
            }
            tombstones = removedTombstones
            displayedItems = targetItems

            coroutineScope {
                survivorTranslations.forEach { (stableKey, initialTranslation) ->
                    launch {
                        Animatable(initialTranslation).animateTo(
                            targetValue = 0f,
                            animationSpec = statusPositionSpec(),
                        ) {
                            itemTranslations[stableKey] = value
                        }
                    }
                }
            }
            itemTranslations.clear()
            tombstones = emptyList()
        }
    }

    if (displayedItems.isEmpty()) return

    val visibleHeight = stackHeightDp(displayedItems.size)
    val contentHeight = visibleHeight + STATUS_BOTTOM_BUFFER
    StatusOverflowViewport(
        visibleHeight = visibleHeight,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(contentHeight),
        ) {
            tombstones.forEach { tombstone ->
                key(tombstone.key) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(COMPOSER_STATUS_ROW_HEIGHT)
                            .graphicsLayer { translationY = tombstone.yPx },
                        shape = COMPOSER_STATUS_ROW_SHAPE,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {}
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = columnTranslation.value },
            ) {
                displayedItems.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.height(STATUS_ROW_GAP))
                    key(item.stableKey) {
                        val itemModifier = Modifier.graphicsLayer {
                            translationY = itemTranslations[item.stableKey] ?: 0f
                        }
                        when (item) {
                            is ComposerStatusItem.Loop -> LoopControlBar(
                                loop = item.value,
                                isRunning = loopRunning,
                                onStop = onStopLoop,
                                modifier = itemModifier,
                            )
                            is ComposerStatusItem.Queue -> QueuedMessageRow(
                                queued = item.value,
                                onRemove = { onRemoveQueuedSend(item.value.id) },
                                modifier = itemModifier,
                            )
                        }
                    }
                }
                // One fixed overscan row lives underneath the higher-z composer. It keeps motion
                // at the shared edge from exposing the host background.
                Spacer(Modifier.height(STATUS_BOTTOM_BUFFER))
            }
        }
    }
}

@Composable
private fun StatusOverflowViewport(
    visibleHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visibleHeightPx = with(LocalDensity.current) { visibleHeight.roundToPx() }
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(
            constraints.copy(
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
        )
        val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, visibleHeightPx) {
            placeable.placeRelative(0, 0)
        }
    }
}

private fun stackHeightDp(count: Int): Dp =
    if (count <= 0) 0.dp
    else COMPOSER_STATUS_ROW_HEIGHT * count + STATUS_ROW_GAP * (count - 1)

private fun stackHeightPx(
    count: Int,
    rowHeightPx: Float,
    rowGapPx: Float,
): Float =
    if (count <= 0) 0f
    else rowHeightPx * count + rowGapPx * (count - 1)

private fun rowY(index: Int, rowHeightPx: Float, rowGapPx: Float): Float =
    index * (rowHeightPx + rowGapPx)

private fun statusPositionSpec() = tween<Float>(
    durationMillis = STATUS_POSITION_DURATION_MS,
    easing = LinearOutSlowInEasing,
)
