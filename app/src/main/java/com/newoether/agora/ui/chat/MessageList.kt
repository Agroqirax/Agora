package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunUiProjection
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.chat.message.MessageItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class MessageListLayoutMode {
    STABLE,
    ACTIVE_SCROLL,
    COVERED_TRANSITION,
}

internal fun messageListLayoutMode(
    isSwitching: Boolean,
    isScrollInProgress: Boolean,
): MessageListLayoutMode = when {
    isSwitching -> MessageListLayoutMode.COVERED_TRANSITION
    isScrollInProgress -> MessageListLayoutMode.ACTIVE_SCROLL
    else -> MessageListLayoutMode.STABLE
}

internal fun calculateTailMinHeightPx(
    viewportHeightPx: Int,
    targetTopPx: Int,
    bottomObstructionPx: Int,
): Int = (viewportHeightPx - targetTopPx - bottomObstructionPx).coerceAtLeast(0)

internal fun calculateTailLayoutHeightPx(
    minimumHeightPx: Int,
    contentHeightPx: Int,
): Int = maxOf(minimumHeightPx, contentHeightPx)

/**
 * One stable LazyColumn item per conversation turn.
 *
 * A USER starts a turn and every following non-USER message remains in that turn until the next
 * USER. This identity must not change when a new turn is appended: otherwise the previous
 * assistant is disposed from the tail item and recreated as a standalone item, producing a
 * visible blank/reparse frame on Send.
 */
internal data class MessageListTurn(
    val key: String,
    val messages: List<ChatMessage>,
)

/**
 * Reuses unchanged turn objects across immutable streaming snapshots. Only the active tail turn
 * receives a new identity, allowing Compose to skip every historical LazyColumn item.
 */
internal class MessageListTurnCache {
    private var previousByKey: Map<String, MessageListTurn> = emptyMap()

    fun update(messages: List<ChatMessage>): List<MessageListTurn> {
        val next = buildMessageListTurns(messages).map { candidate ->
            previousByKey[candidate.key]
                ?.takeIf { previous -> previous.messages == candidate.messages }
                ?: candidate
        }
        previousByKey = next.associateBy { it.key }
        return next
    }
}

private data class RunProjectionMessageKey(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val timestamp: Long,
    val runId: String?,
    val runSequence: Long?,
)

private fun ChatMessage.toRunProjectionKey(): RunProjectionMessageKey =
    RunProjectionMessageKey(
        id = id,
        parentId = parentId,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = runSequence,
    )

internal fun buildMessageListTurns(messages: List<ChatMessage>): List<MessageListTurn> {
    if (messages.isEmpty()) return emptyList()

    val turns = mutableListOf<MessageListTurn>()
    var activeTurn = mutableListOf<ChatMessage>()

    fun flushActiveTurn() {
        if (activeTurn.isEmpty()) return
        turns += MessageListTurn(
            key = activeTurn.first().id,
            messages = activeTurn.toList(),
        )
        activeTurn = mutableListOf()
    }

    messages.forEach { message ->
        if (message.participant == Participant.USER) {
            flushActiveTurn()
            activeTurn += message
        } else if (activeTurn.firstOrNull()?.participant == Participant.USER) {
            activeTurn += message
        } else {
            // Preserve leading/error-only paths as their own stable items until a USER begins a
            // normal conversation turn.
            flushActiveTurn()
            turns += MessageListTurn(message.id, listOf(message))
        }
    }
    flushActiveTurn()
    return turns
}

internal fun messageListTurnIndex(
    turns: List<MessageListTurn>,
    messageId: String,
): Int = turns.indexOfFirst { turn -> turn.messages.any { it.id == messageId } }

internal fun estimateMessageListTurnHeightPx(
    turn: MessageListTurn,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float = turn.messages.sumOf { message ->
    (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
}.toFloat()

internal fun estimateSearchMatchCenterInTurnPx(
    turn: MessageListTurn,
    match: ConversationSearchMatch,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float {
    val targetIndex = turn.messages.indexOfFirst { it.id == match.messageId }
    if (targetIndex < 0) return fallbackHeightPx / 2f
    val precedingHeight = turn.messages
        .take(targetIndex)
        .sumOf { message ->
            (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
        }
        .toFloat()
    val target = turn.messages[targetIndex]
    val targetHeight = messageHeights[target.id]?.toFloat() ?: fallbackHeightPx
    val characterCenter = (match.start + match.endExclusive) / 2f
    val textFraction = if (target.text.isEmpty()) {
        0.5f
    } else {
        (characterCenter / target.text.length).coerceIn(0.08f, 0.92f)
    }
    return precedingHeight + targetHeight * textFraction
}

internal data class MessageListViewportAnchor(
    val messageId: String,
    val scrollOffsetPx: Int,
)

internal class MessageListMutationAnchorLock {
    private val activeMutationKeys = mutableSetOf<String>()

    var anchor: MessageListViewportAnchor? = null
        private set

    fun begin(
        key: String,
        candidate: MessageListViewportAnchor?,
    ): MessageListViewportAnchor? {
        activeMutationKeys += key
        if (anchor == null) anchor = candidate
        return anchor
    }

    /**
     * Returns the anchor exactly once, when the final overlapping mutation settles.
     * Repeated begin calls for the same reversing animation never replace the pre-change anchor.
     */
    fun finish(key: String): MessageListViewportAnchor? {
        if (!activeMutationKeys.remove(key) || activeMutationKeys.isNotEmpty()) return null
        return anchor.also { anchor = null }
    }

    fun cancel() {
        activeMutationKeys.clear()
        anchor = null
    }

    val activeMutationCount: Int
        get() = activeMutationKeys.size
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageList(
    messages: StableMessageList,
    allMessages: StableMessageList = StableMessageList(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    isLoading: Boolean = false,
    isSwitching: Boolean = false,
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    maxContextWindow: Int = 20,
    modelAliases: StableModelAliases = StableModelAliases(),
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: suspend (String, String) -> Boolean = { _, _ -> false },
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onFork: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    searchQuery: String = "",
    activeSearchMatch: ConversationSearchMatch? = null,
    onSearchMatchDistance: (key: String, distanceToViewportCenter: Float) -> Unit = { _, _ -> },
    selectionMode: Boolean = false,
    selectedMessageIds: Set<String> = emptySet(),
    onToggleMessageSelection: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var pendingEditMessageId by remember { mutableStateOf<String?>(null) }
    val mutationAnchorLock = remember(state) { MessageListMutationAnchorLock() }
    val mutationScope = rememberCoroutineScope()
    val pendingMutationSettles = remember(state) { mutableMapOf<String, Job>() }
    val searchMatchCentersInTurn = remember(state) { mutableStateMapOf<String, Float>() }

    fun cancelMutationAnchoring() {
        pendingMutationSettles.values.forEach { it.cancel() }
        pendingMutationSettles.clear()
        mutationAnchorLock.cancel()
    }

    LaunchedEffect(isSwitching) {
        if (isSwitching) cancelMutationAnchoring()
    }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) cancelMutationAnchoring()
        }
    }
    DisposableEffect(state) {
        onDispose { cancelMutationAnchoring() }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val visibleProjectionKey = remember(messages) {
        messages.list.map(ChatMessage::toRunProjectionKey)
    }
    val allProjectionKey = remember(allMessages) {
        allMessages.list.map(ChatMessage::toRunProjectionKey)
    }
    val inContextIds = remember(visibleProjectionKey, maxContextWindow) {
        val currentPath = visibleProjectionKey.filter { it.participant != Participant.ERROR }
        val contextStartIndex =
            (currentPath.size - maxContextWindow).coerceAtLeast(0)
        currentPath.drop(contextStartIndex).mapTo(linkedSetOf()) { it.id }
    }

    val turnCache = remember { MessageListTurnCache() }
    val turns = remember(messages) { turnCache.update(messages.list) }
    val lastUserMessage = messages.list.lastOrNull { it.participant == Participant.USER }

    // Text/status/tool deltas do not change branch/run structure. Cache this O(n) projection by its
    // structural fields; copy text is read from the live MessageItem below.
    val runPresentation = remember(visibleProjectionKey, allProjectionKey) {
        RunUiProjection.project(messages.list, allMessages.list)
    }

    val tailMinHeightPx = if (lastUserMessage == null || viewportHeight == 0) {
        0
    } else {
        calculateTailMinHeightPx(
            viewportHeightPx = viewportHeight,
            targetTopPx = with(density) { 140.dp.roundToPx() },
            bottomObstructionPx = with(density) { (bottomBarHeight + 8.dp).roundToPx() },
        )
    }
    val tailMinHeight = with(density) { tailMinHeightPx.toDp() }

    // One active match change owns exactly one scroll animation. Exact match offsets are cached
    // relative to their stable turn item, so centering never needs a visible pre-scroll followed
    // by a corrective second animation.
    LaunchedEffect(activeSearchMatch?.key) {
        val match = activeSearchMatch ?: return@LaunchedEffect
        val turnIndex = messageListTurnIndex(turns, match.messageId)
        if (turnIndex < 0) return@LaunchedEffect
        val topInsetPx = with(density) { 140.dp.toPx() }
        val bottomInsetPx = with(density) { bottomBarHeight.toPx() }
        val targetCenterY = topInsetPx +
            ((viewportHeight - bottomInsetPx - topInsetPx).coerceAtLeast(0f) / 2f)
        val matchCenterInTurn = searchMatchCentersInTurn[match.key]
            ?: estimateSearchMatchCenterInTurnPx(
                turn = turns[turnIndex],
                match = match,
                messageHeights = messageHeights,
                fallbackHeightPx = with(density) { 160.dp.toPx() },
            )
        state.animateScrollToItem(
            index = turnIndex,
            scrollOffset = (matchCenterInTurn - targetCenterY).roundToInt(),
        )
    }

    fun restoreAnchor(anchor: MessageListViewportAnchor): Boolean {
        val turnIndex = messageListTurnIndex(turns, anchor.messageId)
        if (turnIndex < 0) return false
        state.requestScrollToItem(
            turnIndex,
            anchor.scrollOffsetPx,
        )
        return true
    }

    val renderMessage: @Composable (ChatMessage) -> Unit = { message ->
        val isInContext = inContextIds.contains(message.id)
        val presentation = runPresentation[message.id]

        MessageItem(
            message = message,
            onEdit = { id, text ->
                if (pendingEditMessageId == null) {
                    pendingEditMessageId = id
                    mutationScope.launch {
                        val accepted = try {
                            onEditMessage(id, text)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (accepted && editingMessageId == id) {
                            editingMessageId = null
                        }
                        if (pendingEditMessageId == id) {
                            pendingEditMessageId = null
                        }
                    }
                }
            },
            // Every active MODEL owns its streaming renderer until its own terminal status.
            // Appending a queued USER must not dispose the previous turn's incremental renderer.
            isStreaming = message.participant == Participant.MODEL &&
                message.status in setOf(
                    MessageStatus.SENDING,
                    MessageStatus.THINKING,
                    MessageStatus.TOOL_CALLING,
                    MessageStatus.TRANSCRIBING,
                ),
            isLoading = isLoading || pendingEditMessageId == message.id,
            isEditingAllowed = !selectionMode &&
                (editingMessageId == null || editingMessageId == message.id) &&
                !isLoading,
            isEditing = editingMessageId == message.id,
            isSwitching = isSwitching,
            isInContext = isInContext,
            modelAliases = modelAliases,
            visualizeContextRollout = visualizeContextRollout,
            toolCallDisplayMode = toolCallDisplayMode,
            onStartEdit = { editingMessageId = message.id },
            onCancelEdit = { editingMessageId = null },
            showActions = !selectionMode && presentation?.showActions == true,
            actionCopyText = presentation
                ?.takeIf { it.showActions }
                ?.let { message.text.takeIf(String::isNotBlank) },
            showBranchSelector = !selectionMode && presentation?.showBranchSelector == true,
            branchIndex = presentation?.branchIndex ?: 0,
            totalBranches = presentation?.totalBranches ?: 1,
            onSwitchBranch = { direction ->
                val anchorId = presentation?.branchAnchorMessageId
                if (anchorId != null) {
                    onSwitchBranch(
                        presentation.branchAnchorParentId,
                        anchorId,
                        direction,
                    )
                }
            },
            onRegenerate = onRegenerate,
            onFork = onFork,
            onShare = onShare,
            deleteTargetMessageId = presentation?.deleteTargetMessageId ?: message.id,
            onDelete = onDelete,
            onMediaClick = onMediaClick,
            onFileContentClick = onFileContentClick,
            onPdfPagesClick = onPdfPagesClick,
            searchQuery = searchQuery,
            activeSearchMatch = activeSearchMatch,
            onSearchMatchPosition = { key, centerY ->
                val turnIndex = messageListTurnIndex(turns, message.id)
                val visibleTurn = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == turnIndex }
                if (visibleTurn != null) {
                    searchMatchCentersInTurn[key] = centerY - visibleTurn.offset
                }
                val topInsetPx = with(density) { 140.dp.toPx() }
                val bottomInsetPx = with(density) { bottomBarHeight.toPx() }
                val viewportCenterY = topInsetPx +
                    ((viewportHeight - bottomInsetPx - topInsetPx).coerceAtLeast(0f) / 2f)
                onSearchMatchDistance(
                    key,
                    kotlin.math.abs(centerY - viewportCenterY),
                )
            },
            selectionMode = selectionMode,
            selected = message.id in selectedMessageIds,
            onToggleSelection = { onToggleMessageSelection(message.id) },
            onHeightChanged = { height ->
                if (height > 0 && messageHeights[message.id] != height) {
                    val mode = messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress = state.isScrollInProgress,
                    )
                    // Measurement remains available to explicit scrolling calculations, but
                    // bottom geometry no longer reads it. The tail's minimum height absorbs
                    // content changes atomically in the same measure pass.
                    messageHeights[message.id] = height
                    if (mode == MessageListLayoutMode.STABLE) {
                        val lockedAnchor = mutationAnchorLock.anchor
                        if (lockedAnchor != null) {
                            restoreAnchor(lockedAnchor)
                        }
                    }
                }
            },
            onLayoutMutationStarted = { mutationKey ->
                pendingMutationSettles.remove(mutationKey)?.cancel()
                if (
                    messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress = state.isScrollInProgress,
                    ) == MessageListLayoutMode.STABLE
                ) {
                    val anchorMessage = turns
                        .getOrNull(state.firstVisibleItemIndex)
                        ?.messages
                        ?.firstOrNull()
                    val anchor = mutationAnchorLock.begin(
                        key = mutationKey,
                        candidate = anchorMessage?.let {
                            MessageListViewportAnchor(
                                messageId = it.id,
                                scrollOffsetPx = state.firstVisibleItemScrollOffset,
                            )
                        },
                    )
                    // Pre-arm the very first remeasure. Waiting for onSizeChanged is one frame
                    // too late when an AnimatedVisibility reverses under rapid taps.
                    if (anchor != null) restoreAnchor(anchor)
                }
            },
            onLayoutMutationSettled = { mutationKey ->
                pendingMutationSettles.remove(mutationKey)?.cancel()
                pendingMutationSettles[mutationKey] = mutationScope.launch {
                    // Transition.isRunning reaches false before the final size has necessarily
                    // propagated through the parent LazyColumn. Keep the original anchor through
                    // two complete frames; a reversing tap cancels this pending release.
                    withFrameNanos { }
                    withFrameNanos { }
                    mutationAnchorLock.finish(mutationKey)
                    pendingMutationSettles.remove(mutationKey)
                    // onSizeChanged already held the exact pre-mutation anchor throughout the
                    // transition. A final requestScrollToItem here produced a visible end-frame
                    // correction after the animation was otherwise complete.
                }
            },
            thoughtExpandedStates = thoughtExpandedStates,
        )
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled
        ) {
            items(turns, key = { it.key }) { turn ->
                // A turn's key and composition survive when the next USER is appended. Only the
                // new turn enters; the previous assistant never moves to a different Lazy item.
                Box(
                    modifier = Modifier,
                ) {
                    // The last turn atomically absorbs bottom space. Earlier turns keep the same
                    // Column call site with a zero minimum, so losing tail status cannot dispose
                    // or recreate any child message.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = if (turn.key == lastUserMessage?.id) tailMinHeight else 0.dp,
                            ),
                    ) {
                        turn.messages.forEach { message ->
                            key(message.id) {
                                renderMessage(message)
                            }
                        }
                    }
                }
            }
        }
    }
}
