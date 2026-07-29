package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
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

internal fun calculateBottomSpacerPx(
    viewportHeightPx: Int,
    targetTopPx: Int,
    bottomObstructionPx: Int,
    tailContentHeightPx: Int,
): Int {
    val availableTailHeight =
        viewportHeightPx - targetTopPx - bottomObstructionPx
    return (availableTailHeight - tailContentHeightPx).coerceAtLeast(0)
}

internal data class MessageListViewportAnchor(
    val messageId: String,
    val scrollOffsetPx: Int,
)

internal class MessageListMutationAnchorLock {
    private val activeMutationKeys = mutableSetOf<String>()

    var anchor: MessageListViewportAnchor? = null
        private set

    fun begin(key: String, candidate: MessageListViewportAnchor?) {
        activeMutationKeys += key
        if (anchor == null) anchor = candidate
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
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
    LaunchedEffect(isSwitching) {
        if (isSwitching) mutationAnchorLock.cancel()
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) mutationAnchorLock.cancel()
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val currentPath = messages.list.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()

    val lastUserMessageIndex = messages.list.indexOfLast { it.participant == Participant.USER }

    val runPresentation = remember(messages, allMessages) {
        RunUiProjection.project(messages.list, allMessages.list)
    }

    val extraPaddingPx = if (lastUserMessageIndex == -1 || viewportHeight == 0) {
        0
    } else {
        var contentHeightPx = 0
        for (i in lastUserMessageIndex until messages.list.size) {
            contentHeightPx += messageHeights[messages.list[i].id] ?: 0
        }
        calculateBottomSpacerPx(
            viewportHeightPx = viewportHeight,
            targetTopPx = with(density) { 140.dp.roundToPx() },
            bottomObstructionPx = with(density) { (bottomBarHeight + 8.dp).roundToPx() },
            tailContentHeightPx = contentHeightPx,
        )
    }
    val extraPadding = with(density) { extraPaddingPx.toDp() }

    fun restoreAnchor(anchor: MessageListViewportAnchor): Boolean {
        val anchorIndex = messages.list.indexOfFirst { it.id == anchor.messageId }
        if (anchorIndex < 0) return false
        state.requestScrollToItem(anchorIndex, anchor.scrollOffsetPx)
        return true
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled
        ) {
            items(messages.list, key = { it.id }) { message ->
                val isLastMessage = messages.list.lastOrNull()?.id == message.id
                val isInContext = inContextIds.contains(message.id)
                val presentation = runPresentation[message.id]

                // Fade newly-appended messages in. Placement/fade-out left off so this
                // doesn't fight the manual height/scroll padding management below.
                Box(modifier = Modifier.animateItem(fadeInSpec = tween(400), placementSpec = null, fadeOutSpec = null)) {
                MessageItem(
                    message = message,
                    onEdit = { id, text ->
                        onEditMessage(id, text)
                        editingMessageId = null
                    },
                    // isStreaming driven by message status, not isLoading flag
                    isStreaming = isLastMessage && message.participant == Participant.MODEL
                        && message.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING),
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
                    deleteTargetMessageId =
                        presentation?.deleteTargetMessageId ?: message.id,
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
                            messageHeights[message.id] = height
                            if (mode == MessageListLayoutMode.STABLE) {
                                // Commit the real height and its inverse spacer correction while
                                // retaining the pre-mutation viewport anchor when an explicit
                                // component animation is active. The fallback preserves the
                                // current behavior for unannounced streaming/layout changes.
                                val lockedAnchor = mutationAnchorLock.anchor
                                if (lockedAnchor != null) {
                                    restoreAnchor(lockedAnchor)
                                } else if (anchorIndex < messages.list.size) {
                                    state.requestScrollToItem(anchorIndex, anchorOffset)
                                }
                            }
                        }
                    },
                    onLayoutMutationStarted = { mutationKey ->
                        if (
                            messageListLayoutMode(
                                isSwitching = isSwitching,
                                isScrollInProgress = state.isScrollInProgress,
                            ) == MessageListLayoutMode.STABLE
                        ) {
                            val anchorMessage = messages.list
                                .getOrNull(state.firstVisibleItemIndex)
                            mutationAnchorLock.begin(
                                key = mutationKey,
                                candidate = anchorMessage?.let {
                                    MessageListViewportAnchor(
                                        messageId = it.id,
                                        scrollOffsetPx = state.firstVisibleItemScrollOffset,
                                    )
                                },
                            )
                        }
                    },
                    onLayoutMutationSettled = { mutationKey ->
                        val anchor = mutationAnchorLock.finish(mutationKey)
                        if (
                            anchor != null &&
                            messageListLayoutMode(
                                isSwitching = isSwitching,
                                isScrollInProgress = state.isScrollInProgress,
                            ) == MessageListLayoutMode.STABLE
                        ) {
                            restoreAnchor(anchor)
                        }
                    },
                    thoughtExpandedStates = thoughtExpandedStates
                )
                }
            }
            item {
                Spacer(modifier = Modifier.height(extraPadding))
            }
        }
    }
}
