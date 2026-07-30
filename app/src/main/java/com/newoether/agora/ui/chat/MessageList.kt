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
fun MessageList(
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
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    val mutationAnchorLock = remember(state) { MessageListMutationAnchorLock() }
    val mutationScope = rememberCoroutineScope()
    val pendingMutationSettles = remember(state) { mutableMapOf<String, Job>() }

    fun cancelMutationAnchoring() {
        pendingMutationSettles.values.forEach { it.cancel() }
        pendingMutationSettles.clear()
        mutationAnchorLock.cancel()
    }

    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
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

    val currentPath = messages.list.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()

    val turns = remember(messages) { buildMessageListTurns(messages.list) }
    val lastUserMessage = messages.list.lastOrNull { it.participant == Participant.USER }

    val runPresentation = remember(messages, allMessages) {
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
                onEditMessage(id, text)
                editingMessageId = null
            },
            // Every active MODEL owns its streaming renderer until its own terminal status.
            // Appending a queued USER must not disable the previous turn's double buffer.
            isStreaming = message.participant == Participant.MODEL &&
                message.status in setOf(
                    MessageStatus.SENDING,
                    MessageStatus.THINKING,
                    MessageStatus.TOOL_CALLING,
                    MessageStatus.TRANSCRIBING,
                ),
            isLoading = isLoading,
            isEditingAllowed = (editingMessageId == null || editingMessageId == message.id) && !isLoading,
            isEditing = editingMessageId == message.id,
            isSwitching = isSwitching,
            isInContext = isInContext,
            modelAliases = modelAliases,
            visualizeContextRollout = visualizeContextRollout,
            toolCallDisplayMode = toolCallDisplayMode,
            onStartEdit = { editingMessageId = message.id },
            onCancelEdit = { editingMessageId = null },
            showActions = presentation?.showActions == true,
            actionCopyText = presentation?.copyText,
            showBranchSelector = presentation?.showBranchSelector == true,
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
            deleteTargetMessageId = presentation?.deleteTargetMessageId ?: message.id,
            onDelete = onDelete,
            onMediaClick = onMediaClick,
            onFileContentClick = onFileContentClick,
            onPdfPagesClick = onPdfPagesClick,
            onHeightChanged = { height ->
                if (height > 0 && messageHeights[message.id] != height) {
                    val mode = messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress = state.isScrollInProgress,
                    )
                    val anchorIndex = state.firstVisibleItemIndex
                    val anchorOffset = state.firstVisibleItemScrollOffset
                    // Measurement remains available to explicit scrolling calculations, but
                    // bottom geometry no longer reads it. The tail's minimum height absorbs
                    // content changes atomically in the same measure pass.
                    messageHeights[message.id] = height
                    if (mode == MessageListLayoutMode.STABLE) {
                        val lockedAnchor = mutationAnchorLock.anchor
                        if (lockedAnchor != null) {
                            restoreAnchor(lockedAnchor)
                        } else if (anchorIndex < state.layoutInfo.totalItemsCount) {
                            state.requestScrollToItem(anchorIndex, anchorOffset)
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
                    val anchor = mutationAnchorLock.finish(mutationKey)
                    pendingMutationSettles.remove(mutationKey)
                    if (
                        anchor != null &&
                        messageListLayoutMode(
                            isSwitching = isSwitching,
                            isScrollInProgress = state.isScrollInProgress,
                        ) == MessageListLayoutMode.STABLE
                    ) {
                        restoreAnchor(anchor)
                    }
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
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(400),
                        placementSpec = null,
                        fadeOutSpec = null,
                    ),
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
