package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.chat.AttachmentThumbnailItem
import com.newoether.agora.ui.chat.ThumbnailClickHandlers
import com.newoether.agora.ui.chat.findMetaForIndex
import com.newoether.agora.ui.chat.resolveAttachmentType
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

private const val STREAMING_MARKDOWN_FLUSH_MS = 250L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: ChatMessage, 
    onEdit: (String, String) -> Unit, 
    isStreaming: Boolean = false,
    isLoading: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    modelAliases: Map<String, String> = emptyMap(),
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    val isThoughtExpanded by remember(message.id) {
        derivedStateOf { thoughtExpandedStates[message.id] ?: false }
    }
    var showSegmentDetail by remember { mutableStateOf(false) }
    var selectedSegmentIndex by remember { mutableIntStateOf(-1) }
    var selectedSegmentIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    // Capture the fully-settled collapsed height after collapse animation finishes.
    // This lets calculateReportedHeight immediately report the post-collapse height
    // even mid-animation, so extraPadding doesn't "chase" the shrinking thought block.
    LaunchedEffect(isThoughtExpanded) {
        if (!isThoughtExpanded) {
            delay(500) // slightly longer than the 400ms collapse tween + mergedBottomPadding tween
            stableCollapsedThoughtHeight = currentThoughtBlockHeight
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptics = LocalAgoraHaptics.current

    if (showInfoDialog) {
        MessageInfoDialog(
            message = message,
            modelAliases = modelAliases,
            onDismiss = { showInfoDialog = false }
        )
    }

    if (showDeleteConfirm) {
        MessageDeleteDialog(
            onConfirm = {
                showDeleteConfirm = false
                haptics.reject()
                onDelete(message.id)
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    var currentTotalHeight by remember { mutableIntStateOf(0) }
    // No-op modifier that suppresses bring-into-view auto-scrolling on focus

    fun calculateReportedHeight(totalPx: Int, thoughtPx: Int): Int {
        // When we are NOT expanded, the thought block is animating down from its large height 
        // to its stableCollapsedThoughtHeight. We want the outer list padding to behave as if
        // the thought block INSTANTLY hit stableCollapsedThoughtHeight, avoiding the final "jump".
        // But we ONLY do this if the thought block is currently larger than its collapsed height 
        // AND we know what the collapsed height is.
        if (!isThoughtExpanded && stableCollapsedThoughtHeight > 0 && thoughtPx > stableCollapsedThoughtHeight) {
            val excessHeight = thoughtPx - stableCollapsedThoughtHeight
            return totalPx - excessHeight
        }
        return totalPx
    }

    LaunchedEffect(message.text, message.status, isEditing, isThoughtExpanded) {
        kotlinx.coroutines.delay(50)
        onHeightChanged(calculateReportedHeight(currentTotalHeight, currentThoughtBlockHeight))
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val backgroundColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.primaryContainer
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Participant.MODEL -> MaterialTheme.colorScheme.onSurface
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val shape = when (message.participant) {
        Participant.USER -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
        Participant.MODEL -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        Participant.ERROR -> RoundedCornerShape(12.dp)
    }

    val markdownAssets = rememberChatMarkdownAssets(textColor)
    val markdownRenderContext = markdownAssets.renderContext
    val customMarkdownColors = markdownAssets.colors
    val thoughtTypography = markdownAssets.thoughtTypography
    val thoughtMarkdownPadding = markdownAssets.thoughtPadding
    val customMarkdownComponents = markdownAssets.components
    val markdownFlavour = markdownAssets.flavour

    val shouldAnimate = !isFirstComposition && !isSwitching

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged {
                currentTotalHeight = it.height
                onHeightChanged(calculateReportedHeight(it.height, currentThoughtBlockHeight))
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
        val contextAlpha = if (visualizeContextRollout && !isInContext) Modifier.alpha(0.38f) else Modifier
        if (message.participant == Participant.USER) {
            UserMessageBubble(
                message = message,
                shape = shape,
                backgroundColor = backgroundColor,
                textColor = textColor,
                contextAlpha = contextAlpha,
                shouldAnimate = shouldAnimate,
                isEditing = isEditing,
                isLoading = isLoading,
                isEditingAllowed = isEditingAllowed,
                branchIndex = branchIndex,
                totalBranches = totalBranches,
                onEdit = onEdit,
                onCancelEdit = onCancelEdit,
                onStartEdit = onStartEdit,
                onSwitchBranch = onSwitchBranch,
                onMediaClick = onMediaClick,
                onFileContentClick = onFileContentClick,
                onPdfPagesClick = onPdfPagesClick,
                onShowInfo = { showInfoDialog = true },
                onShowDelete = { showDeleteConfirm = true },
            )
        } else {
            AssistantMessageContent(
                message = message,
                contextAlpha = contextAlpha,
                isStreaming = isStreaming,
                isLoading = isLoading,
                isEditingAllowed = isEditingAllowed,
                toolCallDisplayMode = toolCallDisplayMode,
                thoughtExpandedStates = thoughtExpandedStates,
                isThoughtExpanded = isThoughtExpanded,
                renderContext = markdownRenderContext,
                markdownFlavour = markdownFlavour,
                branchIndex = branchIndex,
                totalBranches = totalBranches,
                onSwitchBranch = onSwitchBranch,
                onRegenerate = onRegenerate,
                onMediaClick = onMediaClick,
                onShowInfo = { showInfoDialog = true },
                onShowDelete = { showDeleteConfirm = true },
                onSegmentSelected = { indices ->
                    selectedSegmentIndices = indices
                    selectedSegmentIndex = indices.firstOrNull() ?: -1
                    showSegmentDetail = true
                },
                setThoughtBlockHeight = { currentThoughtBlockHeight = it },
            )
        }
    }

    // Segment detail bottom sheet (self-contained draggable sheet + FSM).
    if (showSegmentDetail && selectedSegmentIndex >= 0) {
        SegmentDetailSheet(
            message = message,
            selectedSegmentIndex = selectedSegmentIndex,
            selectedSegmentIndices = selectedSegmentIndices,
            isStreaming = isStreaming,
            markdownColors = customMarkdownColors,
            thoughtTypography = thoughtTypography,
            thoughtMarkdownPadding = thoughtMarkdownPadding,
            markdownComponents = customMarkdownComponents,
            markdownFlavour = markdownFlavour,
            onDismiss = { showSegmentDetail = false }
        )
    }
}

