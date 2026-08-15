package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.CircularBackButton
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.util.noOpBringIntoView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class SegmentSheetBackAction { DISMISS, SHOW_LIST }

internal fun usesVirtualizedSegmentDetail(
    selectedSegmentCount: Int,
    segmentType: String,
    segmentContentIsBlank: Boolean,
    isStreaming: Boolean,
    hasFooter: Boolean,
): Boolean =
    selectedSegmentCount == 1 &&
        segmentType != "tool" &&
        !(segmentType == "transcription" && segmentContentIsBlank) &&
        !isStreaming &&
        !hasFooter

internal fun segmentSheetBackAction(
    handleBackInternally: Boolean,
    showSegmentListFirst: Boolean,
    detailPageIndex: Int,
): SegmentSheetBackAction = if (
    handleBackInternally && showSegmentListFirst && detailPageIndex >= 0
) {
    SegmentSheetBackAction.SHOW_LIST
} else {
    SegmentSheetBackAction.DISMISS
}

// Segment detail bottom sheet (custom implementation).
//
// A self-contained draggable bottom sheet with its own finite-state machine
// (Collapsed / Half / Full) driving an Animatable fraction; the whole gesture +
// snap + dim subsystem lives here. The host (MessageItem) only decides WHICH
// segment(s) to show and toggles visibility via [onDismiss].
@Composable
internal fun SegmentDetailSheet(
    message: ChatMessage,
    selectedSegmentIndex: Int,
    selectedSegmentIndices: List<Int>,
    isStreaming: Boolean,
    markdownRenderContext: ChatMarkdownRenderContext,
    onMediaClick: (List<String>, Int) -> Unit,
    titleOverride: String? = null,
    directMarkdownContent: String? = null,
    emptyStreamingText: String? = null,
    errorText: String? = null,
    detailFooter: (@Composable () -> Unit)? = null,
    handleBackInternally: Boolean = false,
    showSegmentListFirst: Boolean = false,
    onDismiss: () -> Unit
) {
    val liveSegs = remember(message.segments) {
        mergeAdjacentSegments(message.segments.orEmpty()).filter { it.type != "answer" }
    }
    val selectedEntries = remember(
        liveSegs,
        selectedSegmentIndices,
        selectedSegmentIndex,
        directMarkdownContent,
    ) {
        if (directMarkdownContent != null) {
            listOf(0 to MessageSegment(type = "thought", content = directMarkdownContent))
        } else {
            selectedSegmentIndices.mapNotNull { index ->
                liveSegs.getOrNull(index)?.let { index to it }
            }.ifEmpty {
                liveSegs.getOrNull(selectedSegmentIndex)
                    ?.let { listOf(selectedSegmentIndex to it) }
                    .orEmpty()
            }
        }
    }
    val firstSelectedSegment = selectedEntries.firstOrNull()?.second
    if (firstSelectedSegment == null) {
        LaunchedEffect(message.id, selectedSegmentIndices, selectedSegmentIndex) {
            onDismiss()
        }
    } else {
        var detailPageIndex by remember(message.id, selectedSegmentIndices, showSegmentListFirst) {
            mutableIntStateOf(if (showSegmentListFirst) -1 else 0)
        }
        var lastDetailPageIndex by remember(message.id, selectedSegmentIndices) {
            mutableIntStateOf(0)
        }
        val showSegmentListPage = showSegmentListFirst && detailPageIndex < 0
        val renderedEntries = if (showSegmentListFirst) {
            listOfNotNull(selectedEntries.getOrNull(lastDetailPageIndex))
        } else {
            selectedEntries
        }
        val selectedSegs = renderedEntries.map { it.second }
        val seg = selectedSegs.first()
        val selectedLiveIndex = renderedEntries.first().first
        val motionPolicy = LocalAgoraMotionPolicy.current
        val density = LocalDensity.current
        val screenHeightPx =
            LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val segmentListScrollState = rememberScrollState()
        val lazyDetailListState = rememberLazyListState()
        var observedStreamingMarkdown by remember(message.id, selectedLiveIndex) {
            mutableStateOf(isStreaming)
        }
        SideEffect {
            if (isStreaming) observedStreamingMarkdown = true
        }
        val usesVirtualizedSingleMarkdown =
            directMarkdownContent == null &&
                usesVirtualizedSegmentDetail(
                    selectedSegmentCount = selectedSegs.size,
                    segmentType = seg.type,
                    segmentContentIsBlank = seg.content.isBlank(),
                    isStreaming = observedStreamingMarkdown,
                    hasFooter = detailFooter != null || errorText != null,
                )

        val PARTIAL = 0.45f
        val FULL = 0.94f

        // ── Finite state machine ──
        // Collapsed = 0, Half = PARTIAL, Full = FULL
        // Full is only entered when animateTo(FULL) completes naturally.
        val PHASE_COLLAPSED = 0; val PHASE_HALF = 1; val PHASE_FULL = 2
        var phase by remember { mutableIntStateOf(PHASE_HALF) }

        var rawFraction by remember { mutableFloatStateOf(0f) }
        val visualFraction = remember { Animatable(0f) }
        var snapJob by remember { mutableStateOf<Job?>(null) }
        var dismissing by remember { mutableStateOf(false) }

        val snapSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 350f, visibilityThreshold = 0.001f)

        // ── Snap target: midline (0.5) × velocity direction ──
        // velSign > 0 = upward (expanding), velSign < 0 = downward (collapsing)
        fun snapTarget(pos: Float, velSign: Float): Float {
            val goingUp = velSign >= 0f
            return when {
                pos > 0.5f && goingUp -> FULL      // upper half + up → full
                pos > 0.5f && !goingUp -> PARTIAL  // upper half + down → half
                pos <= 0.5f && goingUp -> PARTIAL  // lower half + up → half
                else -> 0f                          // lower half + down → collapsed
            }
        }

        // ── Single animation entry point. Sets phase after animation completes. ──
        fun animateTo(target: Float) {
            snapJob?.cancel()
            snapJob = coroutineScope.launch {
                if (motionPolicy.allowSpatialTransitions) {
                    visualFraction.animateTo(target, snapSpring)
                } else {
                    visualFraction.snapTo(target)
                }
                rawFraction = visualFraction.value
                phase = when (target) {
                    FULL -> PHASE_FULL
                    PARTIAL -> PHASE_HALF
                    else -> PHASE_COLLAPSED
                }
                if (target == 0f) onDismiss()
            }
        }

        fun dismiss() { dismissing = true; animateTo(0f) }

        LaunchedEffect(detailPageIndex) {
            if (detailPageIndex >= 0) {
                scrollState.scrollTo(0)
                lazyDetailListState.scrollToItem(0)
            }
        }

        // ── Grab: interrupt animation, sync raw to current visual position ──
        fun grabSheet() {
            if (dismissing) return
            if (snapJob?.isActive == true) {
                snapJob?.cancel()
                rawFraction = visualFraction.value
            }
        }

        // ── Initial appearance ──
        LaunchedEffect(Unit) {
            animateTo(PARTIAL)
            snapJob?.join()
            rawFraction = PARTIAL
        }

        // ── Safety-net snap: if drag ends without fling (velocity ≈ 0) ──
        LaunchedEffect(rawFraction) {
            if (dismissing || snapJob?.isActive == true) return@LaunchedEffect
            val pos = rawFraction
            delay(80)
            if (dismissing || pos != rawFraction || snapJob?.isActive == true) return@LaunchedEffect
            val target = snapTarget(pos, 0f)
            if (abs(target - pos) > 0.01f) animateTo(target)
        }

        // ── Dim: update the native window only while the sheet is actually moving. ──
        //
        // An unconditional frame loop kept both the UI thread and RenderThread awake after the
        // spring had settled. Animatable is snapshot-backed, so this collector sleeps at rest and
        // still emits every visual change during drag/snap animations.
        val dialogWindowRef = remember { mutableStateOf<android.view.Window?>(null) }

        LaunchedEffect(dialogWindowRef.value) {
            val window = dialogWindowRef.value ?: return@LaunchedEffect
            snapshotFlow { visualFraction.value }
                .map { fraction -> (0.32f * fraction).coerceIn(0f, 1f) }
                .distinctUntilChanged()
                .collect { dimAmount ->
                    val attributes = window.attributes
                    if (attributes.dimAmount != dimAmount) {
                        attributes.dimAmount = dimAmount
                        window.attributes = attributes
                    }
                }
        }

        // ── NestedScrollConnection ──
        // Half: content does NOT scroll — all delta goes to sheet expansion.
        // Full: content scrolls normally. Exit Full ONLY when content at top
        //       and finger still dragging down (source == Drag).
        val sheetScrollConnection = remember(
            usesVirtualizedSingleMarkdown,
            showSegmentListPage,
        ) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!dismissing && phase != PHASE_FULL) {
                        grabSheet()
                        val delta = -available.y / screenHeightPx
                        rawFraction = (rawFraction + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        if (rawFraction >= FULL && available.y < 0f) phase = PHASE_FULL
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero // Full: let content scroll
                }

                override fun onPostScroll(
                    consumed: Offset, available: Offset, source: NestedScrollSource
                ): Offset {
                    if (dismissing) return Offset.Zero
                    // Exit Full → Half: content at top + finger dragging down
                    if (phase == PHASE_FULL
                        && available.y > 0f
                        && if (showSegmentListPage) {
                            segmentListScrollState.value == 0
                        } else if (usesVirtualizedSingleMarkdown) {
                            lazyDetailListState.firstVisibleItemIndex == 0 &&
                                lazyDetailListState.firstVisibleItemScrollOffset == 0
                        } else {
                            scrollState.value == 0
                        }
                        && source == NestedScrollSource.UserInput
                    ) {
                        phase = PHASE_HALF
                        val delta = -available.y / screenHeightPx
                        rawFraction = (FULL + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (phase != PHASE_FULL && available.y != 0f) {
                        val velSign = if (available.y < 0f) 1f else -1f
                        animateTo(snapTarget(rawFraction, velSign))
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Dialog(
            onDismissRequest = {
                if (!dismissing) {
                    when (
                        segmentSheetBackAction(
                            handleBackInternally = handleBackInternally,
                            showSegmentListFirst = showSegmentListFirst,
                            detailPageIndex = detailPageIndex,
                        )
                    ) {
                        SegmentSheetBackAction.SHOW_LIST -> detailPageIndex = -1
                        SegmentSheetBackAction.DISMISS -> dismiss()
                    }
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false
            )
        ) {
            DialogWindowEdgeToEdge()
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindowRef.value = dialogWindow }

            Box(modifier = Modifier.fillMaxSize()) {
                // Transparent click-catcher — dim is handled by native Window.dimAmount.
                // Uses pointerInput to avoid reading visualFraction in composition.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (visualFraction.value > 0.02f) dismiss()
                                }
                            )
                        }
                )

                // Sheet height via Modifier.layout (layout phase) to avoid
                // recomposition on every spring animation frame.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val h = (screenHeightPx * visualFraction.value).roundToInt().coerceAtLeast(0)
                            val placeable = measurable.measure(
                                constraints.copy(minHeight = h, maxHeight = h)
                            )
                            layout(placeable.width, h) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Draggable header: drag handle + title + divider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    var velEma = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            velEma = 0f
                                            grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dismissing) return@detectVerticalDragGestures
                                            change.consume()
                                            velEma = velEma * 0.5f + (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            rawFraction = (rawFraction - dragAmount / screenHeightPx)
                                                .coerceIn(0f, FULL)
                                            coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                                            if (rawFraction >= FULL && dragAmount < 0f) phase = PHASE_FULL
                                        },
                                        onDragEnd = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            animateTo(snapTarget(rawFraction, velEma))
                                        }
                                    )
                                }
                        ) {
                            // Drag handle
                            Box(
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp).height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                )
                            }

                            val sheetTitle = when {
                                titleOverride != null -> titleOverride
                                showSegmentListPage ->
                                    stringResource(R.string.thinking_segments_title)
                                selectedSegs.size > 1 ->
                                    compactSegmentTitle(
                                        selectedSegs,
                                        message,
                                        useLiveStatus = false,
                                    )
                                seg.type == "tool" -> toolDisplayName(seg)
                                seg.type == "transcription" ->
                                    transcriptionLabel(liveSegs, selectedLiveIndex)
                                else -> stringResource(R.string.tool_thinking)
                            }
                            if (showSegmentListFirst && !showSegmentListPage) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularBackButton(
                                        onClick = { detailPageIndex = -1 },
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = sheetTitle,
                                        style = ChatType.detailTitle,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                Text(
                                    text = sheetTitle,
                                    style = ChatType.detailTitle,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }

                        val detailPageContent: @Composable () -> Unit = {
                        if (usesVirtualizedSingleMarkdown) {
                            DetailContentReveal(
                                revealKey = "${message.id}:$selectedLiveIndex",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(sheetScrollConnection)
                                    .noOpBringIntoView()
                                    .navigationBarsPadding(),
                            ) { revealModifier, onReady ->
                                NoAutoScrollSelectionContainer(
                                    modifier = revealModifier.fillMaxSize(),
                                ) {
                                    LazyMarkdownTextContent(
                                        text = seg.content,
                                        renderContext = markdownRenderContext,
                                        listState = lazyDetailListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            start = 24.dp,
                                            top = 4.dp,
                                            end = 24.dp,
                                            bottom = 32.dp,
                                        ),
                                        onReady = onReady,
                                    )
                                }
                            }
                        } else {
                            // Tool and grouped details use one conventional scroll owner. An
                            // actively streaming Markdown document must retain its incremental
                            // renderer when it becomes terminal, so it remains in this branch.
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(sheetScrollConnection)
                                    .verticalScroll(scrollState)
                                    .noOpBringIntoView()
                                    .padding(horizontal = 24.dp)
                                    .padding(top = if (seg.type == "tool") 6.dp else 4.dp)
                                    .navigationBarsPadding()
                                    .padding(bottom = 32.dp)
                            ) {
                                if (selectedSegs.size > 1) {
                                    selectedSegs.forEachIndexed { index, detailSeg ->
                                        val detailIndex = renderedEntries.getOrNull(index)?.first
                                            ?: liveSegs.indexOf(detailSeg).coerceAtLeast(0)
                                        Text(
                                            segmentDetailTitle(detailSeg, liveSegs, detailIndex),
                                            style = ChatType.detailTitle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(
                                                top = if (index == 0) 0.dp else 18.dp,
                                                bottom = 8.dp,
                                            ),
                                        )
                                        if (detailSeg.type == "tool") {
                                            ToolDetailContent(
                                                segment = detailSeg,
                                                onMediaClick = onMediaClick,
                                            )
                                        } else if (
                                            detailSeg.type == "transcription" &&
                                            detailSeg.content.isBlank()
                                        ) {
                                            Text(
                                                text = "Image transcription is empty.",
                                                style = ChatType.body,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.4f),
                                            )
                                        } else {
                                            val detailIsStreaming =
                                                isStreaming && index == selectedSegs.lastIndex
                                            StreamingDetailMarkdownReveal(
                                                revealKey = "${message.id}:$detailIndex",
                                                content = detailSeg.content,
                                                isStreaming = detailIsStreaming,
                                                renderContext = markdownRenderContext,
                                            )
                                        }
                                        if (index < selectedSegs.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(top = 18.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.3f),
                                            )
                                        }
                                    }
                                } else if (seg.type == "tool") {
                                    ToolDetailContent(
                                        segment = seg,
                                        onMediaClick = onMediaClick,
                                    )
                                } else if (
                                    seg.type == "transcription" &&
                                    seg.content.isBlank()
                                ) {
                                    Text(
                                        text = "Image transcription is empty.",
                                        style = ChatType.body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.4f),
                                    )
                                } else {
                                    StreamingDetailMarkdownReveal(
                                        revealKey = "${message.id}:$selectedLiveIndex",
                                        content = seg.content,
                                        isStreaming = isStreaming,
                                        renderContext = markdownRenderContext,
                                        emptyStreamingText = emptyStreamingText,
                                    )
                                }
                                errorText?.takeIf(String::isNotBlank)?.let {
                                    GenerationErrorBar(it)
                                }
                                detailFooter?.invoke()
                            }
                        }
                        }
                        if (showSegmentListFirst) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                SegmentSheetAnimatedPage(
                                    visible = showSegmentListPage,
                                    leadingPage = true,
                                ) {
                                    ThinkingSegmentListContent(
                                        message = message,
                                        segments = selectedEntries.map { it.second },
                                        segmentIndices = selectedEntries.map { it.first },
                                        isStreaming = isStreaming,
                                        scrollState = segmentListScrollState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .nestedScroll(sheetScrollConnection)
                                            .navigationBarsPadding(),
                                        onSegmentSelected = { liveIndex ->
                                            val targetIndex = selectedEntries.indexOfFirst {
                                                it.first == liveIndex
                                            }
                                            if (targetIndex >= 0) {
                                                lastDetailPageIndex = targetIndex
                                                detailPageIndex = targetIndex
                                            }
                                        },
                                    )
                                }
                                SegmentSheetAnimatedPage(
                                    visible = !showSegmentListPage,
                                    leadingPage = false,
                                ) {
                                    detailPageContent()
                                }
                            }
                        } else {
                            detailPageContent()
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SegmentSheetAnimatedPage(
    visible: Boolean,
    leadingPage: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { width -> if (leadingPage) -width else width } + fadeIn(),
        exit = slideOutHorizontally { width -> if (leadingPage) -width / 3 else width } + fadeOut(),
        content = { content() },
    )
}

@Composable
private fun ThinkingSegmentListContent(
    message: ChatMessage,
    segments: List<MessageSegment>,
    segmentIndices: List<Int>,
    isStreaming: Boolean,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    onSegmentSelected: (Int) -> Unit,
) {
    val appearanceRegistry = remember(message.id) { SegmentAppearanceRegistry() }
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val liveIndex = segmentIndices.getOrElse(index) { index }
            TimelineInfoSegmentCard(
                seg = segment,
                detailSegments = segments,
                detailIndex = index,
                isStreamingContent = isStreaming && index == segments.lastIndex,
                animateAppearance = false,
                cardAnimationKey = "${message.id}:sheet-list:$liveIndex",
                segmentAppearanceRegistry = appearanceRegistry,
                onClick = { onSegmentSelected(liveIndex) },
            )
        }
        Spacer(modifier = Modifier.navigationBarsPadding().height(24.dp))
    }
}

@Composable
private fun StreamingDetailMarkdownReveal(
    revealKey: String,
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
    emptyStreamingText: String? = null,
) {
    DetailContentReveal(
        revealKey = revealKey,
        modifier = Modifier.fillMaxWidth(),
    ) { revealModifier, onReady ->
        StreamingMarkdownMessage(
            content = content,
            isStreaming = isStreaming,
            renderContext = renderContext,
            modifier = revealModifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0 || content.isEmpty()) onReady()
                },
            selectionEnabled = !isStreaming,
            emptyStreamingText = emptyStreamingText,
        )
    }
}

@Composable
private fun DetailContentReveal(
    revealKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (revealModifier: Modifier, onReady: () -> Unit) -> Unit,
) {
    var ready by remember(revealKey) { mutableStateOf(false) }
    var showLoading by remember(revealKey) { mutableStateOf(false) }
    val revealAlpha by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "detailContentReveal:$revealKey",
    )

    LaunchedEffect(revealKey) {
        delay(120)
        if (!ready) showLoading = true
    }
    LaunchedEffect(ready) {
        if (ready) showLoading = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        content(
            Modifier.graphicsLayer { alpha = revealAlpha },
        ) {
            if (!ready) ready = true
        }
        AnimatedVisibility(
            visible = showLoading && !ready,
            enter = fadeIn(tween(160, easing = LinearEasing)),
            exit = fadeOut(tween(140, easing = LinearEasing)),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
