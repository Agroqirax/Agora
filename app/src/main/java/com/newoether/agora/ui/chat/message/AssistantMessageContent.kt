package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.ChatType
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import kotlinx.coroutines.launch

/**
 * The left-aligned assistant (and error) message content: the streaming status header,
 * the thinking / tool-call timeline or compact segment block, the debounced markdown
 * body, any generated images, the stopped indicator, and the regenerate/overflow
 * action row.
 *
 * Extracted from [MessageItem]. The parent owns the reported-height bookkeeping and the
 * segment-detail sheet, so this composable reports the thought block height through
 * [setThoughtBlockHeight] and surfaces clicked segments through [onSegmentSelected].
 */
@Composable
internal fun AssistantMessageContent(
    message: ChatMessage,
    contextAlpha: Modifier,
    isStreaming: Boolean,
    isLoading: Boolean,
    isEditingAllowed: Boolean,
    showActions: Boolean,
    actionCopyText: String?,
    showBranchSelector: Boolean,
    toolCallDisplayMode: String,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    branchIndex: Int,
    totalBranches: Int,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: (String) -> Unit,
    onFork: () -> Unit,
    onShare: () -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
    onSegmentSelected: (List<Int>) -> Unit,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    setThoughtBlockHeight: (Int) -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current
    var showMenu by remember { mutableStateOf(false) }

    // During generation, eat horizontal nested-scroll so code blocks
    // cannot be panned. Vertical scroll and taps (thinking header,
    // stop button) pass through normally. Text selection is already
    // prevented during streaming by the stable Markdown selection host.
    val horizontalScrollEater = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                Offset(available.x, 0f)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(contextAlpha)
            .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
    ) {
        Column {
            // Status Header
            if (message.participant == Participant.MODEL) {
                val thinkingStatus = stringResource(R.string.thinking_ellipsis)
                val answeringStatus = stringResource(R.string.answering_ellipsis)
                val thinkingNow = message.status == MessageStatus.THINKING
                val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                val hasInFlightStatus = message.status == MessageStatus.SENDING ||
                    thinkingNow || isToolCalling || isTranscribing
                val hasActiveAnswer = message.hasActiveAnswerSegment()
                val toolCallingStatus = stringResource(R.string.tool_calling_ellipsis)
                val transcribingStatus = stringResource(R.string.transcription_ellipsis)
                val displayText = when {
                    // Keep the header's measured row across stream → terminal even when a
                    // provider omits usage. Removing it for tokenCount=0 shifts every Markdown
                    // line upward on the exact frame generation completes.
                    message.status == MessageStatus.SUCCESS ->
                        stringResource(R.string.cost_tokens, message.tokenCount.coerceAtLeast(0))
                    message.status == MessageStatus.STOPPED -> stringResource(R.string.generation_stopped)
                    isStreaming && isTranscribing -> transcribingStatus
                    isStreaming && isToolCalling -> toolCallingStatus
                    isStreaming && thinkingNow -> thinkingStatus
                    isStreaming && hasActiveAnswer -> answeringStatus
                    isStreaming -> stringResource(R.string.sending_ellipsis)
                    else -> null
                }.let { base ->
                    if (base != null && message.retryText != null) "$base (${message.retryText})"
                    else base
                }

                if (displayText != null) {
                    val text = displayText
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            if (isStreaming || hasInFlightStatus) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = if (text == thinkingStatus) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            } else {
                                val icon = when (message.status) {
                                    MessageStatus.SUCCESS -> Icons.Default.CheckCircle
                                    MessageStatus.STOPPED -> Icons.Default.Stop
                                    else -> Icons.Default.Info
                                }
                                Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (message.status == MessageStatus.SUCCESS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text, style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // GenerationManager already publishes a bounded stream cadence. A second UI debounce
            // delayed every chunk, retained a stale text job through Stop, and then replaced the
            // whole document at terminalization. Feed the latest immutable snapshot directly to
            // the off-main Markdown parser.
            val renderedText = message.text

            Column {
                val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                // Only zero out thought height when legacy thought block is not shown
                if (message.segments != null || message.thoughts.isNullOrBlank()) {
                    setThoughtBlockHeight(0)
                }

                val segmentsOrNull = message.segments
                val mergedSegments = remember(segmentsOrNull) {
                    mergeAdjacentSegments(segmentsOrNull.orEmpty())
                }
                val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                val useTimelineSegments = normalizedToolCallDisplayMode != ToolCallDisplayModes.COMPACT &&
                    mergedSegments.any { it.type == "answer" }
                val groupAdjacentTimelineTools = normalizedToolCallDisplayMode == ToolCallDisplayModes.GROUPED_TIMELINE
                val timelineBlockKeys = remember(
                    message.id,
                    mergedSegments,
                    groupAdjacentTimelineTools,
                ) {
                    buildTimelineBlockKeys(
                        message.id,
                        mergedSegments,
                        groupAdjacentTimelineTools,
                    )
                }
                val timelineAppearanceSeenKeys = remember(
                    message.id,
                    normalizedToolCallDisplayMode,
                ) {
                    timelineBlockKeys.toMutableSet()
                }
                var timelineAppearanceInitialized by remember(
                    message.id,
                    normalizedToolCallDisplayMode,
                ) {
                    mutableStateOf(false)
                }
                val timelineAnimatedBlockKeys = if (
                    isStreaming && timelineAppearanceInitialized
                ) {
                    timelineBlockKeys.filterNotTo(linkedSetOf()) {
                        it in timelineAppearanceSeenKeys
                    }
                } else {
                    emptySet()
                }
                val detailSegments = remember(mergedSegments) {
                    mergedSegments.filter { it.type != "answer" }
                }
                val compactVisible = !useTimelineSegments && detailSegments.isNotEmpty()
                var compactAppearanceSeen by remember(
                    message.id,
                    normalizedToolCallDisplayMode,
                ) {
                    mutableStateOf(compactVisible)
                }
                val animateCompactAppearance =
                    isStreaming && compactVisible && !compactAppearanceSeen
                SideEffect {
                    timelineAppearanceSeenKeys.addAll(timelineBlockKeys)
                    if (!timelineAppearanceInitialized) {
                        timelineAppearanceInitialized = true
                    }
                    if (compactVisible) compactAppearanceSeen = true
                }

                if (useTimelineSegments) {
                    TimelineSegmentsContent(
                        segments = mergedSegments,
                        detailSegments = detailSegments,
                        message = message,
                        isStreaming = isStreaming,
                        groupAdjacentBlocks = groupAdjacentTimelineTools,
                        expandedStates = thoughtExpandedStates,
                        renderContext = renderContext,
                        animatedBlockKeys = timelineAnimatedBlockKeys,
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        onSegmentClick = { indices ->
                            onSegmentSelected(indices)
                        }
                    )
                }

                // Compact segment block: single block, newest title/icon when collapsed.
                // Answer segments are timeline anchors only; compact mode still renders
                // message.text below as the complete answer.
                if (compactVisible) {
                    AnimatedTimelineBlockAppearance(
                        animationKey = "${message.id}:compact",
                        animate = animateCompactAppearance,
                    ) {
                        CompactSegmentBlock(
                            segs = detailSegments,
                            segmentIndices = detailSegments.indices.toList(),
                            message = message,
                            isStreaming = isStreaming,
                            useLiveStatus = true,
                            expandedStates = thoughtExpandedStates,
                            expansionKey = message.id,
                            onExpansionStarted = onLayoutMutationStarted,
                            onExpansionSettled = onLayoutMutationSettled,
                            onSegmentClick = { index -> onSegmentSelected(listOf(index)) },
                            onBlockHeightChanged = setThoughtBlockHeight,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .noOpBringIntoView()
                ) {
                    if (isError) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(12.dp))
                                NoAutoScrollSelectionContainer {
                                    Text(
                                        renderedText.ifEmpty { stringResource(R.string.failed_to_generate) },
                                        style = ChatType.errorBody,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    } else if (renderedText.isNotEmpty() && !useTimelineSegments) {
                        StreamingMarkdownDocument(
                            content = renderedText,
                            isStreaming = isStreaming,
                            renderContext = renderContext,
                            modifier = Modifier.fillMaxWidth(),
                            selectionEnabled = !isStreaming,
                        )
                    }
                }
                if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                    val genImages = message.images
                    // Generated images are primary output, not input references:
                    // render as a full-width square card, image cropped to fill
                    // with rounded corners, tap to view fullscreen.
                    Column(
                        modifier = Modifier.padding(top = if (renderedText.isNotEmpty()) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genImages.forEachIndexed { idx, path ->
                            coil.compose.AsyncImage(
                                model = path,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { onMediaClick(genImages, idx) },
                                        onLongClick = { haptics.longPress() }
                                    )
                            )
                        }
                    }
                }
                if (message.participant == Participant.MODEL) {
                    if (!isStreaming && showActions) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!actionCopyText.isNullOrBlank()) {
                                IconButton(onClick = { clipboardManager.setText(AnnotatedString(actionCopyText)); haptics.confirm() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                            IconButton(onClick = { onRegenerate(message.id) }, enabled = !isLoading, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(19.dp), tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            IconButton(
                                onClick = onFork,
                                enabled = !isLoading,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.CallSplit,
                                    contentDescription = stringResource(R.string.conversation_fork_from_here),
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isLoading) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    },
                                )
                            }
                            IconButton(
                                onClick = onShare,
                                enabled = !isLoading,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.conversation_share),
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isLoading) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    },
                                )
                            }
                            Box {
                                IconButton(onClick = { haptics.tap(); showMenu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                DropdownMenu(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 16.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.info)) },
                                        onClick = { haptics.tap(); showMenu = false; onShowInfo() },
                                        leadingIcon = { Icon(Icons.Default.Info, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        onClick = { haptics.tap(); showMenu = false; onShowDelete() },
                                        enabled = !isLoading,
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) }
                                    )
                                }
                            }

                            if (showBranchSelector && totalBranches > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .clip(RoundedCornerShape(100))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 4.dp)
                                ) {
                                    IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                                    }
                                    Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                                    IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
