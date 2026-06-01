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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    allMessages: List<ChatMessage> = emptyList(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    isLoading: Boolean = false,
    isSwitching: Boolean = false,
    visualizeContextRollout: Boolean = false,
    maxContextWindow: Int = 20,
    modelAliases: Map<String, String> = emptyMap(),
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onSwitchBranch: (String?, Int) -> Unit = { _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val currentPath = messages.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()

    val lastUserMessageIndex = messages.indexOfLast { it.participant == Participant.USER }

    val extraPadding = if (lastUserMessageIndex == -1 || viewportHeight == 0) {
        0.dp
    } else {
        with(density) {
            val vDp = viewportHeight.toDp()
            val targetTopDp = 140.dp
            val availableSpaceDp = vDp - targetTopDp - (bottomBarHeight + 8.dp)
            var contentHeightPx = 0
            for (i in lastUserMessageIndex until messages.size) {
                contentHeightPx += messageHeights[messages[i].id] ?: 0
            }
            val contentHeightDp = contentHeightPx.toDp()
            (availableSpaceDp - contentHeightDp).coerceAtLeast(0.dp)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state
        ) {
            items(messages, key = { it.id }) { message ->
                val isLastMessage = messages.lastOrNull()?.id == message.id
                val isInContext = inContextIds.contains(message.id)
                val siblings = allMessages.filter { it.parentId == message.parentId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }.sortedBy { it.timestamp }
                val branchIndex = siblings.indexOfFirst { it.id == message.id }
                val totalBranches = siblings.size

                MessageItem(
                    message = message,
                    onEdit = { id, text ->
                        onEditMessage(id, text)
                        editingMessageId = null
                    },
                    isStreaming = isLastMessage && isLoading && message.participant == Participant.MODEL,
                    isLoading = isLoading,
                    isEditingAllowed = (editingMessageId == null || editingMessageId == message.id) && !isLoading,
                    isEditing = editingMessageId == message.id,
                    isSwitching = isSwitching,
                    isInContext = isInContext,
                    modelAliases = modelAliases,
                    visualizeContextRollout = visualizeContextRollout,
                    onStartEdit = { editingMessageId = message.id },
                    onCancelEdit = { editingMessageId = null },
                    branchIndex = branchIndex,
                    totalBranches = totalBranches,
                    onSwitchBranch = { direction -> onSwitchBranch(message.parentId, direction) },
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onMediaClick = onMediaClick,
                    onFileContentClick = onFileContentClick,
                    onPdfPagesClick = onPdfPagesClick,
                    onHeightChanged = { height -> messageHeights[message.id] = height },
                    thoughtExpandedStates = thoughtExpandedStates
                )
            }
            item {
                Spacer(modifier = Modifier.height(extraPadding))
            }
        }
    }
}
