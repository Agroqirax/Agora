package com.newoether.agora.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.util.gradientBlur
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.bottombar.ChatBottomBar
import com.newoether.agora.ui.chat.message.hasActiveAnswerSegment
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.TypewriterText
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.SwitchingRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private val SCROLL_EASING = CubicBezierEasing(0.3f, 0.0f, 0.0f, 1.0f)
private const val CONVERSATION_RESOLVE_TIMEOUT_MS = 2_000L
private const val SCROLL_SETTLE_TIMEOUT_MS = 8_000L
private const val STABLE_LAYOUT_SAMPLES = 3
private const val LAYOUT_SAMPLE_INTERVAL_MS = 32L
private const val INLINE_SHARE_LIMIT_BYTES = 256 * 1024
private const val SHARE_ERROR_DETAIL_TOKEN = "__AGORA_SHARE_ERROR_DETAIL__"
private const val STREAM_SCROLL_RESUME_DELAY_MS = 160L

/**
 * Text/argument growth within an existing message tree can be coalesced while LazyColumn owns a
 * scroll animation. Structural changes remain immediate so a new thinking/tool block or lifecycle
 * state is never hidden behind the gate.
 */
internal fun sameStreamingRenderStructure(
    previous: List<ChatMessage>,
    next: List<ChatMessage>,
): Boolean {
    if (previous.size != next.size) return false
    return previous.indices.all { index ->
        val before = previous[index]
        val after = next[index]
        if (before === after) return@all true
        if (
            before.id != after.id ||
            before.parentId != after.parentId ||
            before.participant != after.participant ||
            before.status != after.status ||
            before.images.size != after.images.size ||
            before.retryText != after.retryText ||
            before.thoughts.isNullOrBlank() != after.thoughts.isNullOrBlank()
        ) {
            return@all false
        }
        val beforeSegments = before.segments
        val afterSegments = after.segments
        if (beforeSegments == null || afterSegments == null) {
            return@all beforeSegments == null && afterSegments == null
        }
        if (beforeSegments.size != afterSegments.size) return@all false
        beforeSegments.indices.all { segmentIndex ->
            val beforeSegment = beforeSegments[segmentIndex]
            val afterSegment = afterSegments[segmentIndex]
            beforeSegment.type == afterSegment.type &&
                beforeSegment.toolCallId == afterSegment.toolCallId &&
                beforeSegment.toolName == afterSegment.toolName &&
                beforeSegment.toolState == afterSegment.toolState &&
                (beforeSegment.toolResult == null) == (afterSegment.toolResult == null)
        }
    }
}

@Composable
private fun rememberScrollIsolatedMessages(
    conversationId: String?,
    upstream: State<List<ChatMessage>>,
    listState: LazyListState,
): State<List<ChatMessage>> {
    val rendered = remember(conversationId, upstream) {
        mutableStateOf(upstream.value)
    }
    LaunchedEffect(conversationId, upstream, listState) {
        coroutineScope {
            var latest = upstream.value
            var deferred = listState.isScrollInProgress
            var hasOwnedScroll = listState.isScrollInProgress
            var resumeJob: Job? = null

            launch {
                snapshotFlow { listState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collect { scrolling ->
                        resumeJob?.cancel()
                        if (scrolling) {
                            hasOwnedScroll = true
                            deferred = true
                        } else if (hasOwnedScroll) {
                            deferred = true
                            resumeJob = launch {
                                delay(STREAM_SCROLL_RESUME_DELAY_MS)
                                deferred = false
                                hasOwnedScroll = false
                                if (rendered.value !== latest) {
                                    rendered.value = latest
                                }
                            }
                        } else {
                            // Initial idle observation: do not impose a synthetic 160 ms delay on
                            // the first provider token.
                            deferred = false
                        }
                    }
            }

            launch {
                snapshotFlow { upstream.value }
                    .distinctUntilChanged()
                    .collect { next ->
                        latest = next
                        if (
                            !deferred ||
                            !sameStreamingRenderStructure(rendered.value, next)
                        ) {
                            rendered.value = next
                        }
                    }
            }
        }
    }
    return rendered
}

private suspend fun launchConversationShare(
    context: Context,
    text: String,
    chooserTitle: String,
) {
    val sendIntent = withContext(Dispatchers.IO) {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        if (utf8.size <= INLINE_SHARE_LIMIT_BYTES) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        } else {
            val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File.createTempFile("agora_conversation_", ".md", shareDirectory).apply {
                writeBytes(utf8)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Agora conversation", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    withContext(Dispatchers.Main.immediate) {
        val chooser = Intent.createChooser(sendIntent, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

@Composable
private fun AnsweringHapticEffect(
    messages: State<List<com.newoether.agora.model.ChatMessage>>,
    isLoading: Boolean,
    generatingInConversationId: String?,
    currentConversationId: String?,
    hapticsEnabled: Boolean,
    haptics: com.newoether.agora.ui.common.AgoraHaptics,
) {
    // Keep the 20 Hz streaming-message read inside this tiny restart group. Reading it at the top
    // of ChatApp invalidates the drawer, composer, backgrounds, and every overlay for each token.
    val answeringHapticActive = isLoading &&
        generatingInConversationId == currentConversationId &&
        messages.value.lastOrNull { it.participant == Participant.MODEL }?.let { message ->
            message.status == MessageStatus.SENDING && message.hasActiveAnswerSegment()
        } == true
    val appInForeground by com.newoether.agora.service.AppForegroundTracker.foreground.collectAsState()
    DisposableEffect(answeringHapticActive, hapticsEnabled, appInForeground, haptics) {
        if (answeringHapticActive && hapticsEnabled && appInForeground) {
            haptics.startAnsweringTexture()
        }
        onDispose {
            haptics.stopAnsweringTexture()
        }
    }
}

// isVisibleAnswerSegment() / hasActiveAnswerSegment() are shared (internal) from
// MessageItemSegments.kt.

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    onNavigateBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onOpenTasks: (String?) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit,
    onFileContentClick: ((String, String) -> Unit)? = null,
    onPdfPagesClick: ((List<String>, Int) -> Unit)? = null,
    onPdfPreviewSelect: ((List<String>, Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.conversation_share)
    val shareFailureTemplate = stringResource(
        R.string.conversation_share_failed,
        SHARE_ERROR_DETAIL_TOKEN,
    )

    LaunchedEffect(viewModel, context, shareChooserTitle, shareFailureTemplate) {
        viewModel.conversationShareText.collect { text ->
            try {
                launchConversationShare(
                    context = context,
                    text = text,
                    chooserTitle = shareChooserTitle,
                )
            } catch (e: Exception) {
                DebugLog.e("ChatShare", "Unable to launch conversation share", e)
                viewModel.emitSnackbar(
                    shareFailureTemplate.replace(
                        SHARE_ERROR_DETAIL_TOKEN,
                        e.localizedMessage ?: e.javaClass.simpleName,
                    )
                )
            }
        }
    }

    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { newValue ->
            if (newValue != DrawerValue.Closed) {
                focusManager.clearFocus()
            }
            true
        }
    )

    val conversations by viewModel.conversations.collectAsState()
    // Defer value reads to the narrow composition regions that actually render messages. The
    // State objects themselves are stable, so stream snapshots no longer recompose all ChatApp.
    val messagesState = viewModel.messages.collectAsState()
    val allMessagesState = viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val queuedSends by viewModel.queuedSends.collectAsState()
    val isStopping by viewModel.isStopping.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val currentConversation by viewModel.currentConversation.collectAsState()
    val loadedMessagesConversationId by viewModel.loadedMessagesConversationId.collectAsState()
    val currentLoop by viewModel.currentLoop.collectAsState()
    val runningLoopIds by viewModel.runningLoopConversationIds.collectAsState()
    val generatingInConversationId by viewModel.generatingInConversationId.collectAsState()
    val selectedModel by viewModel.currentActiveModel.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val thoughtExpandedStates = remember(currentConversationId) { mutableStateMapOf<String, Boolean>() }
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val newChatEntryId by viewModel.newChatEntryId.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val visualizeContextRollout by viewModel.settings.visualizeContextRollout.collectAsState()
    val maxContextWindow by viewModel.settings.maxContextWindow.collectAsState()
    val globalCodeExecution by viewModel.settings.codeExecutionEnabled.collectAsState()
    val globalGoogleSearch by viewModel.settings.googleSearchEnabled.collectAsState()
    val globalThinkingEnabled by viewModel.settings.thinkingEnabled.collectAsState()
    val globalThinkingLevel by viewModel.settings.thinkingLevel.collectAsState()
    val globalThinkingBudgetEnabled by viewModel.settings.thinkingBudgetEnabled.collectAsState()
    val globalThinkingBudgetTokens by viewModel.settings.thinkingBudgetTokens.collectAsState()
    val globalWebSearch by viewModel.settings.webSearchEnabled.collectAsState()
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val globalShell by viewModel.settings.shellEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val conversationSettings by viewModel.settings.conversationSettings.collectAsState()
    val pendingSettings by viewModel.pendingConversationSettings.collectAsState()
    // Resolved per-conversation values: override → global default
    val convId = currentConversationId
    val convOverride = if (convId != null) conversationSettings[convId] else pendingSettings
    val codeExecutionEnabled = convOverride?.codeExecutionEnabled ?: globalCodeExecution
    val googleSearchEnabled = convOverride?.googleSearchEnabled ?: globalGoogleSearch
    val thinkingEnabled = convOverride?.thinkingEnabled ?: globalThinkingEnabled
    val thinkingLevel = convOverride?.thinkingLevel ?: globalThinkingLevel
    val thinkingBudgetEnabled = convOverride?.thinkingBudgetEnabled ?: globalThinkingBudgetEnabled
    val thinkingBudgetTokens = convOverride?.thinkingBudgetTokens ?: globalThinkingBudgetTokens
    // Web Search and Shell: global switch OFF → always false, regardless of override
    val webSearchEnabled = globalWebSearch && (convOverride?.webSearchEnabled ?: true)
    val shellEnabled = globalShell && (convOverride?.shellEnabled ?: true)
    val contextWindow = convOverride?.contextWindow ?: maxContextWindow
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val haptics = rememberAgoraHaptics(hapticsEnabled)


    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var conversationToRename by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    // Composer-expand spacer collapse (44dp → 0). An Animatable driven from an effect replaces the
    // former hand-rolled clock, which wrote animation state DURING composition (Compose forbids
    // that — it makes the frame's output depend on when it happened to be composed) and ticked on
    // a fixed 16ms sleep that drifts against the real refresh rate.
    val spacerProgress = remember { Animatable(0f) }
    val spacerEasing = remember { CubicBezierEasing(0.15f, 0.5f, 0.25f, 1.0f) }
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            spacerProgress.snapTo(0f)
            spacerProgress.animateTo(1f, tween(400, easing = spacerEasing))
        } else {
            spacerProgress.snapTo(0f)
        }
    }
    val isExpandAnimating = spacerProgress.isRunning
    val outerSpacerHeightPx: Float =
        if (isExpanded) with(density) { 44.dp.toPx() } * (1f - spacerProgress.value) else 0f

    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeightDp = with(density) {
        windowSize.height.toDp().value.coerceAtLeast(1f)
    }
    val drawerWidth = with(density) { windowSize.width.toDp() } * 0.8f
    var bottomBarHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    // Bottom offset to clear the Settings button in the drawer.
    var settingsButtonTopDp by remember { mutableFloatStateOf(80f) }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // When expanded, the Surface fills the screen and the model-selector capsule sits
    // at the very bottom. Snackbar must clear: nav bar + IME + Surface outer padding + Box
    // bottom padding + Row height/margin + a small gap.
    val bottomInset = maxOf(navBarBottom, imeBottom)
    val expandedCapsuleOffset = bottomInset + 74.dp
    val targetSnackbarOffset = if (drawerProgress <= 0.5f) {
        if (isExpanded) expandedCapsuleOffset else (bottomBarHeight - 4.dp).coerceAtLeast(0.dp)
    } else {
        val t = ((drawerProgress - 0.5f) * 2f).coerceIn(0f, 1f)
        (bottomBarHeight.value + (settingsButtonTopDp - bottomBarHeight.value) * t).dp
    }
    LaunchedEffect(targetSnackbarOffset) { onSnackbarOffsetChanged(targetSnackbarOffset) }
    val listState = rememberLazyListState()
    val messageLifecycleAppearanceRegistry = remember {
        MessageLifecycleAppearanceRegistry()
    }
    val renderMessagesState = rememberScrollIsolatedMessages(
        conversationId = currentConversationId,
        upstream = messagesState,
        listState = listState,
    )
    var conversationSearchActive by rememberSaveable { mutableStateOf(false) }
    var conversationSearchQuery by rememberSaveable { mutableStateOf("") }
    var conversationSearchMatchIndex by remember { mutableIntStateOf(-1) }
    var shareSelectionActive by remember { mutableStateOf(false) }
    var selectedShareMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val messagesForSearchAndSelection = if (conversationSearchActive || shareSelectionActive) {
        messagesState.value
    } else {
        emptyList()
    }
    val selectableShareMessageIds = remember(messagesForSearchAndSelection) {
        messagesForSearchAndSelection.mapTo(linkedSetOf()) { it.id }
    }
    val shareSelectionBarSpace = if (shareSelectionActive) 68.dp else 0.dp
    val conversationSearchMatchDistances = remember(currentConversationId) {
        mutableStateMapOf<String, Float>()
    }
    val conversationSearchMatches = remember(messagesForSearchAndSelection, conversationSearchQuery) {
        findConversationSearchMatches(messagesForSearchAndSelection, conversationSearchQuery)
    }
    val searchTurns = remember(messagesForSearchAndSelection) {
        buildMessageListTurns(messagesForSearchAndSelection)
    }
    val searchTurnIndexByMessageId = remember(searchTurns) {
        buildMap {
            searchTurns.forEachIndexed { index, turn ->
                turn.messages.forEach { message -> put(message.id, index) }
            }
        }
    }
    LaunchedEffect(
        conversationSearchActive,
        conversationSearchQuery,
        conversationSearchMatches,
        currentConversationId,
    ) {
        if (!conversationSearchActive || conversationSearchQuery.isBlank() ||
            conversationSearchMatches.isEmpty()
        ) {
            conversationSearchMatchIndex = -1
            conversationSearchMatchDistances.clear()
            return@LaunchedEffect
        }
        conversationSearchMatchDistances.clear()
        val visibleDistances = withTimeoutOrNull(250L) {
            snapshotFlow {
                conversationSearchMatchDistances
                    .filterKeys { key -> conversationSearchMatches.any { it.key == key } }
                    .toMap()
            }
                .filter { it.isNotEmpty() }
                .debounce(32L)
                .first()
        }.orEmpty()
        val exactVisibleIndex = nearestVisibleConversationSearchMatchIndex(
            conversationSearchMatches,
            visibleDistances,
        )
        if (exactVisibleIndex != null) {
            conversationSearchMatchIndex = exactVisibleIndex
            return@LaunchedEffect
        }
        val layout = listState.layoutInfo
        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
        val anchorTurn = layout.visibleItemsInfo
            .minByOrNull { item ->
                kotlin.math.abs((item.offset + item.size / 2) - viewportCenter)
            }
            ?.index
            ?: listState.firstVisibleItemIndex
        conversationSearchMatchIndex = nearestConversationSearchMatchIndex(
            matches = conversationSearchMatches,
            turnIndexByMessageId = searchTurnIndexByMessageId,
            anchorTurnIndex = anchorTurn,
        )
    }
    LaunchedEffect(currentConversationId) {
        conversationSearchActive = false
        conversationSearchQuery = ""
        conversationSearchMatchIndex = -1
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
    }
    LaunchedEffect(selectableShareMessageIds) {
        selectedShareMessageIds = selectedShareMessageIds.intersect(selectableShareMessageIds)
    }
    val textFieldState = rememberSaveable(saver = androidx.compose.foundation.text.input.TextFieldState.Saver) { androidx.compose.foundation.text.input.TextFieldState() }
    val composer = com.newoether.agora.ui.chat.bottombar.rememberChatComposerState()
    val inputFocusRequester = remember { FocusRequester() }

    // Keyed per conversation: message ids are unique, but the map is also summed wholesale
    // (see the scroll math below), so entries left behind by a previous conversation would
    // inflate those totals and misplace the scroll.
    val messageHeights = remember(currentConversationId) {
        androidx.compose.runtime.mutableStateMapOf<String, Int>()
    }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    var showLaunchContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        showLaunchContent = true
        inputFocusRequester.requestFocus()
    }


    fun resolveScrollTargetMessage(
        currentMessages: List<com.newoether.agora.model.ChatMessage>,
        targetMessageId: String?,
    ): com.newoether.agora.model.ChatMessage? = if (targetMessageId != null) {
            val msg = currentMessages.find { it.id == targetMessageId }
            if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                currentMessages.find { it.id == msg.parentId }
            } else {
                msg
            }
        } else {
            currentMessages.lastOrNull { it.participant == Participant.USER }
        }

    fun resolveScrollTargetIndex(
        currentMessages: List<com.newoether.agora.model.ChatMessage>,
        targetMessageId: String?,
    ): Int {
        val target = resolveScrollTargetMessage(currentMessages, targetMessageId) ?: return -1
        return messageListTurnIndex(buildMessageListTurns(currentMessages), target.id)
    }

    suspend fun animateToUserMessage(
        targetMessageId: String? = null,
        easing: Easing = FastOutSlowInEasing,
    ): Boolean {
        val currentMessages = messagesState.value
        if (currentMessages.isEmpty() || viewportHeightPx == 0) return false
        val layoutTurns = buildMessageListTurns(currentMessages)
        val targetIndex = resolveScrollTargetIndex(currentMessages, targetMessageId)
        if (targetIndex == -1) return false

        val firstVisibleIndex = listState.firstVisibleItemIndex
        val visibleSizes = listState.layoutInfo.visibleItemsInfo.associate {
            it.index to it.size
        }
        val fallbackHeight = visibleSizes.values
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: with(density) { 72.dp.toPx() }
        fun heightAt(index: Int): Float {
            visibleSizes[index]?.let { return it.toFloat() }
            val turn = layoutTurns.getOrNull(index) ?: return fallbackHeight
            return estimateMessageListTurnHeightPx(turn, messageHeights, fallbackHeight)
        }

        val distance = if (targetIndex >= firstVisibleIndex) {
            var value = -listState.firstVisibleItemScrollOffset.toFloat()
            for (index in firstVisibleIndex until targetIndex) value += heightAt(index)
            value
        } else {
            var value = -listState.firstVisibleItemScrollOffset.toFloat()
            for (index in targetIndex until firstVisibleIndex) value -= heightAt(index)
            value
        }
        if (kotlin.math.abs(distance) > 2f) {
            // A single continuous distance animation has no animateScrollToItem seek/teleport and
            // therefore no visible exact-position correction on its final frame.
            listState.animateScrollBy(distance, tween(600, easing = easing))
        }
        return true
    }

    suspend fun animateAfterTargetCommitted(targetMessageId: String?): Boolean {
        val targetCommitted = withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
            snapshotFlow {
                val index = resolveScrollTargetIndex(messagesState.value, targetMessageId)
                index to listState.layoutInfo.totalItemsCount
            }.first { (index, itemCount) ->
                index >= 0 && index < itemCount
            }
            true
        } ?: return false
        if (!targetCommitted) return false
        return animateToUserMessage(targetMessageId)
    }

    /**
     * Branch/delete/conversation transitions stay covered. While covered, hard-position the
     * target whenever necessary and require three identical, correctly-positioned layout samples
     * before reporting settlement.
     */
    suspend fun settleCoveredTransition(targetMessageId: String?): Boolean =
        withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
            var stableSamples = 0
            var previousSignature: List<Any>? = null
            while (stableSamples < STABLE_LAYOUT_SAMPLES) {
                delay(LAYOUT_SAMPLE_INTERVAL_MS)
                val currentMessages = messagesState.value
                if (currentMessages.isEmpty()) {
                    val signature = listOf(0, viewportHeightPx)
                    if (signature == previousSignature) stableSamples += 1
                    else {
                        previousSignature = signature
                        stableSamples = 1
                    }
                    continue
                }
                val targetIndex = resolveScrollTargetIndex(currentMessages, targetMessageId)
                val target = resolveScrollTargetMessage(currentMessages, targetMessageId)
                if (targetIndex == -1 || target == null || viewportHeightPx <= 0) {
                    stableSamples = 0
                    previousSignature = null
                    continue
                }
                // A MODEL branch scrolls relative to its parent USER, but the new assistant bubble
                // itself must exist and stabilize before the cover may disappear. Otherwise two
                // regeneration branches with the same user anchor can appear "settled" before the
                // newly selected output has entered layout.
                val requestedTarget = targetMessageId?.let { id ->
                    currentMessages.firstOrNull { it.id == id }
                }
                if (targetMessageId != null && requestedTarget == null) {
                    stableSamples = 0
                    previousSignature = null
                    continue
                }
                val requestedTargetHeight = requestedTarget?.let { messageHeights[it.id] }
                if (
                    requestedTarget != null &&
                    (requestedTargetHeight == null || requestedTargetHeight <= 0)
                ) {
                    stableSamples = 0
                    previousSignature = null
                    continue
                }

                val positioned =
                    listState.firstVisibleItemIndex == targetIndex &&
                        listState.firstVisibleItemScrollOffset <= 2
                if (!positioned) {
                    // Covered transition: a hard correction is intentional and never visible.
                    listState.scrollToItem(targetIndex, 0)
                    stableSamples = 0
                    previousSignature = null
                    continue
                }

                val targetInfo = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetIndex }
                val measuredHeight = messageHeights[target.id]
                if (targetInfo == null || measuredHeight == null || measuredHeight <= 0) {
                    stableSamples = 0
                    previousSignature = null
                    continue
                }
                val signature = listOf(
                    targetIndex,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    targetInfo.offset,
                    targetInfo.size,
                    measuredHeight,
                    viewportHeightPx,
                    currentMessages.size,
                    requestedTarget?.id.orEmpty(),
                    requestedTargetHeight ?: 0,
                )
                if (signature == previousSignature) stableSamples += 1
                else {
                    previousSignature = signature
                    stableSamples = 1
                }
            }
            true
        } == true

    val switchingScrollRequest by viewModel.switchingScrollRequest.collectAsState()

    LaunchedEffect(switchingScrollRequest?.id, switchingScrollRequest?.readyForUi) {
        val request = switchingScrollRequest ?: return@LaunchedEffect
        if (!request.readyForUi || request.kind == SwitchingRequestKind.NEW_CHAT) {
            return@LaunchedEffect
        }
        var terminalized = false
        try {
            val targetConversationId = request.conversationId
            if (targetConversationId == null) {
                viewModel.failSwitchingScroll(request.id, "conversation disappeared")
                terminalized = true
                return@LaunchedEffect
            }

            if (request.kind == SwitchingRequestKind.CONVERSATION) {
                // The target id may equal the current id, so request identity — not a StateFlow
                // value edge — owns this effect. Room's first target-specific message snapshot is
                // also required before measuring; an empty target is represented by the loaded id.
                val resolved = withTimeoutOrNull(CONVERSATION_RESOLVE_TIMEOUT_MS) {
                    snapshotFlow {
                        Triple(
                            currentConversationId,
                            currentConversation?.id,
                            loadedMessagesConversationId,
                        )
                    }.filter { (currentId, loadedConversationId, loadedMessagesId) ->
                        currentId == targetConversationId &&
                            loadedConversationId == targetConversationId &&
                            loadedMessagesId == targetConversationId
                    }.first()
                }
                if (resolved == null) {
                    // Preserve the historical missing-target recovery, but terminalize this
                    // request first even when createNewChat is already a no-op.
                    viewModel.failSwitchingScroll(request.id, "conversation did not resolve")
                    terminalized = true
                    viewModel.createNewChat()
                    return@LaunchedEffect
                }
            } else if (currentConversationId != targetConversationId) {
                viewModel.failSwitchingScroll(request.id, "conversation changed")
                terminalized = true
                return@LaunchedEffect
            }

            if (settleCoveredTransition(request.targetMessageId)) {
                val completed = viewModel.completeSwitchingScroll(request.id)
                if (completed && request.kind == SwitchingRequestKind.CONVERSATION) {
                    haptics.confirm()
                }
            } else {
                viewModel.failSwitchingScroll(request.id, "layout failed to stabilize")
            }
            terminalized = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AgoraUI", "Switching request ${request.id} failed", e)
            viewModel.failSwitchingScroll(request.id, "unexpected UI failure")
            terminalized = true
        } finally {
            if (!terminalized) {
                // Owner gating makes this a no-op when a newer request caused cancellation.
                // When the composition itself disappears, it prevents a retained infinite cover.
                viewModel.failSwitchingScroll(request.id, "switching effect cancelled")
            }
        }
    }

    LaunchedEffect(currentConversationId) {
        // New chat's first send owns its persistent animated-scroll request. Conversation
        // navigation is handled above by a monotonic switching request, so this effect only
        // consumes the legacy one-shot suppression marker.
        if (viewModel.suppressNextOpenScroll) {
            viewModel.suppressNextOpenScroll = false
        }
    }

    // Load draft for the newly-opened conversation. loadingDraft gates the write-back
    // snapshotFlow; updateDraft itself also compares against lastLoadedDraft for the
    // debounce-delay window (belt-and-suspenders anti-loop).
    LaunchedEffect(currentConversationId) {
        val id = currentConversationId
        if (id == null) {
            // New-chat screen: clear the composer so a draft from the previous conversation
            // doesn't carry over.
            viewModel.loadingDraft = true
            textFieldState.edit { replace(0, length, "") }
            composer.selectedAttachments = emptyList()
            viewModel.loadingDraft = false
            return@LaunchedEffect
        }
        viewModel.loadingDraft = true
        val (draftText, draftAttachments) = try {
            viewModel.loadDraft(id)
        } catch (e: Exception) {
            "" to emptyList()
        }
        textFieldState.edit {
            replace(0, length, draftText)
        }
        composer.selectedAttachments = draftAttachments
        viewModel.loadingDraft = false
    }

    // Draft write-back, debounced. Keyed by conversation id and the id is CAPTURED when the
    // effect starts — a debounced write can therefore never attribute text typed in conversation
    // A to conversation B after a fast switch (the old bottom-bar effect read the live id at
    // fire time). Switching restarts the effect, dropping ≤300ms of pending tail — acceptable.
    // Declared AFTER the draft-load effect above so loadingDraft is already set when this runs.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(currentConversationId) {
        val draftId = currentConversationId ?: return@LaunchedEffect
        snapshotFlow { textFieldState.text.toString() to composer.selectedAttachments }
            .distinctUntilChanged()
            .debounce(300L)
            .collect { (text, attachments) ->
                if (!viewModel.loadingDraft) {
                    viewModel.updateDraft(draftId, text, attachments)
                }
            }
    }

    val animatedScrollRequest by viewModel.animatedScrollRequest.collectAsState()
    LaunchedEffect(animatedScrollRequest?.id, currentConversationId) {
        val request = animatedScrollRequest ?: return@LaunchedEffect
        if (request.conversationId != currentConversationId) return@LaunchedEffect
        if (!animateAfterTargetCommitted(request.targetMessageId)) {
            DebugLog.e(
                "AgoraUI",
                "Animated scroll target was not committed: ${request.targetMessageId}",
            )
        }
        viewModel.completeAnimatedScroll(request.id)
    }

    BackHandler(enabled = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        focusManager.clearFocus()
        scope.launch { drawerState.close() }
    }
    BackHandler(
        enabled = onNavigateBack != null &&
            drawerState.currentValue == DrawerValue.Closed &&
            drawerState.targetValue == DrawerValue.Closed,
    ) {
        focusManager.clearFocus()
        onNavigateBack?.invoke()
    }
    BackHandler(enabled = conversationSearchActive) {
        conversationSearchActive = false
        conversationSearchQuery = ""
        conversationSearchMatchIndex = -1
        focusManager.clearFocus()
    }
    BackHandler(enabled = shareSelectionActive) {
        shareSelectionActive = false
        selectedShareMessageIds = emptySet()
    }

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue != DrawerValue.Closed) {
            isExpanded = false
            focusManager.clearFocus()
        }
    }

    AnsweringHapticEffect(
        messages = messagesState,
        isLoading = isLoading,
        generatingInConversationId = generatingInConversationId,
        currentConversationId = currentConversationId,
        hapticsEnabled = hapticsEnabled,
        haptics = haptics,
    )

    CompositionLocalProvider(LocalAgoraHaptics provides haptics) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = DrawerDefaults.scrimColor,
        drawerContent = {
            ChatDrawerContent(
                viewModel = viewModel,
                drawerWidth = drawerWidth,
                drawerState = drawerState,
                scope = scope,
                inputFocusRequester = inputFocusRequester,
                onDrawerProgress = { drawerProgress = it },
                onSettingsButtonTop = { settingsButtonTopDp = it },
                onOpenSettings = onOpenSettings,
                onOpenTasks = { onOpenTasks(null) },
                onRequestRename = { id, title -> showRenameDialog = id; conversationToRename = title },
                onRequestDelete = { id -> showDeleteConfirmDialog = id },
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnTap()
                .onSizeChanged { viewportHeightPx = it.height }
        ) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val (targetCa, targetQa) = if (!dark) {
                0.00f to 0.00f
            } else if (isNewChatMode) {
                0.20f to 0.10f
            } else {
                0.02f to 0.01f
            }
            val ca by animateFloatAsState(targetCa, tween(800))
            val qa by animateFloatAsState(targetQa, tween(800))
            AnimatedBlobBackground(
                centerAlpha = ca,
                quarterAlpha = qa,
                blurRadius = 40f,
                dark = dark,
                blurEnabled = blurEffectsEnabled,
                motionEnabled = isNewChatMode && !isLoading && !isSwitching,
            )

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    ChatTopBar(
                        isNewChatMode = isNewChatMode,
                        conversations = conversations,
                        currentConversationId = currentConversationId,
                        currentConversationTitle = currentConversation?.title,
                        totalTokens = totalTokens,
                        searchActive = conversationSearchActive,
                        searchQuery = conversationSearchQuery,
                        searchMatchIndex = conversationSearchMatchIndex,
                        searchMatchCount = conversationSearchMatches.size,
                        conversationActionsEnabled =
                            !isNewChatMode && currentConversationId != null && !isLoading &&
                                !shareSelectionActive,
                        onNavigateBack = onNavigateBack,
                        onOpenDrawer = { haptics.tap(); focusManager.clearFocus(); scope.launch { drawerState.open() } },
                        onSearchQueryChange = { query ->
                            conversationSearchMatchIndex = -1
                            conversationSearchMatchDistances.clear()
                            conversationSearchQuery = query
                        },
                        onSearchPrevious = {
                            if (conversationSearchMatchIndex > 0) {
                                haptics.selection()
                                conversationSearchMatchIndex--
                            }
                        },
                        onSearchNext = {
                            if (conversationSearchMatchIndex in
                                0 until conversationSearchMatches.lastIndex
                            ) {
                                haptics.selection()
                                conversationSearchMatchIndex++
                            }
                        },
                        onSearchDismiss = {
                            haptics.tap()
                            conversationSearchActive = false
                            conversationSearchQuery = ""
                            conversationSearchMatchIndex = -1
                            focusManager.clearFocus()
                        },
                        onSearchClick = {
                            haptics.tap()
                            shareSelectionActive = false
                            selectedShareMessageIds = emptySet()
                            conversationSearchActive = true
                        },
                        onSystemPromptClick = { haptics.tap(); showPromptDialog = true },
                        onForkConversation = {
                            haptics.tap()
                            viewModel.forkConversationFrom()
                        },
                        onShareConversation = {
                            haptics.tap()
                            conversationSearchActive = false
                            conversationSearchQuery = ""
                            conversationSearchMatchIndex = -1
                            focusManager.clearFocus()
                            selectedShareMessageIds = emptySet()
                            shareSelectionActive = true
                        },
                        onNewChat = {
                            // Haptic = button touch feel, fires on every tap even when the action
                            // is a no-op (already on the new-chat screen), so feedback never feels dead.
                            if (!isNewChatMode) haptics.tap()
                            if (!isNewChatMode) {
                                isExpanded = false
                                viewModel.createNewChat()
                                inputFocusRequester.requestFocus()
                            }
                        },
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    val topBarH = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                    val pivotY =
                        ((windowHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2f)
                            .coerceAtLeast(0f) / windowHeightDp
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            val targetNewChat = targetState.first
                            val targetShowLaunch = targetState.second
                            val initialNewChat = initialState.first
                            val initialShowLaunch = initialState.second

                            if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialNewChat)) {
                                // Entering new-chat mode: scale+fade animation
                                val enterSpec = tween<Float>(700, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f))
                                val fadeInSpec = tween<Float>(500)
                                (fadeIn(animationSpec = fadeInSpec) + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, pivotY), animationSpec = enterSpec))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            } else if (!targetNewChat && !initialNewChat) {
                                // Switching between existing conversations: no animation
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                // Returning from new-chat to an existing conversation
                                fadeIn(animationSpec = tween(300))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            }
                        },
                        label = "MainContentTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (targetNewChat, targetShowLaunch) ->
                        if (!targetNewChat) {
                            val messageListModifier = if (blurEffectsEnabled) {
                                Modifier.fillMaxSize().gradientBlur(blurAtTopDp = 8f, blurAtBottomDp = 0f)
                            } else {
                                Modifier.fillMaxSize()
                            }
                            Box(modifier = Modifier.fillMaxSize()) {
                            MessageList(
                                messages = StableMessageList(renderMessagesState.value),
                                allMessages = StableMessageList(allMessagesState.value),
                                modifier = messageListModifier,
                                state = listState,
                                // Per-conversation generation gate: isLoading mirrors the OPEN
                                // conversation's slot only (ConversationGenerationState.onActive
                                // gates on current == id), so message actions freeze while THIS
                                // conversation generates — background conversations don't affect it.
                                isLoading = isLoading,
                                isSwitching = isSwitching,
                                visualizeContextRollout = visualizeContextRollout,
                                toolCallDisplayMode = toolCallDisplayMode,
                                maxContextWindow = contextWindow,
                                modelAliases = StableModelAliases(modelAliases),
                                bottomBarHeight = bottomBarHeight + shareSelectionBarSpace,
                                viewportHeight = viewportHeightPx,
                                messageHeights = messageHeights,
                                lifecycleAppearanceRegistry = messageLifecycleAppearanceRegistry,
                                lifecycleEntranceTargetMessageId = animatedScrollRequest
                                    ?.takeIf { it.conversationId == currentConversationId }
                                    ?.targetMessageId,
                                onEditMessage = { id, text ->
                                    // Same feel as the composer's Send: an edit re-sends, so it
                                    // gets the identical single confirmation tap.
                                    haptics.tap()
                                    viewModel.editMessage(id, text)
                                },
                                onSwitchBranch = { parentId, currentMessageId, direction ->
                                    haptics.selection()
                                    viewModel.switchBranch(parentId, currentMessageId, direction)
                                },
                                onRegenerate = { id ->
                                    haptics.tap()
                                    viewModel.regenerate(id)
                                },
                                onFork = { id ->
                                    haptics.tap()
                                    viewModel.forkConversationFrom(id)
                                },
                                onShare = { id ->
                                    haptics.tap()
                                    viewModel.shareGeneration(id)
                                },
                                onDelete = { id -> viewModel.deleteMessage(id) },
                                searchQuery = if (conversationSearchActive) {
                                    conversationSearchQuery
                                } else {
                                    ""
                                },
                                activeSearchMatch = conversationSearchMatches
                                    .getOrNull(conversationSearchMatchIndex),
                                onSearchMatchDistance = { key, distance ->
                                    conversationSearchMatchDistances[key] = distance
                                },
                                selectionMode = shareSelectionActive,
                                selectedMessageIds = selectedShareMessageIds,
                                onToggleMessageSelection = { messageId ->
                                    haptics.selection()
                                    selectedShareMessageIds =
                                        if (messageId in selectedShareMessageIds) {
                                            selectedShareMessageIds - messageId
                                        } else {
                                            selectedShareMessageIds + messageId
                                        }
                                },
                                onMediaClick = { urls, index ->
                                    haptics.tap()
                                    onMediaClick(urls, index)
                                },
                                onFileContentClick = onFileContentClick?.let { open ->
                                    { name, content ->
                                        haptics.tap()
                                        open(name, content)
                                    }
                                },
                                onPdfPagesClick = { pages, idx -> haptics.tap(); onPdfPagesClick?.invoke(pages, idx) },
                                thoughtExpandedStates = thoughtExpandedStates,
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = 140.dp,
                                    bottom = bottomBarHeight + shareSelectionBarSpace + 8.dp
                                )
                            )
                            }
                        } else if (targetShowLaunch) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = bottomBarHeight),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    val welcomeText = stringResource(R.string.welcome_to_agora)
                                    val availableWelcomeHeight =
                                        windowHeightDp +
                                            topBarH.value / 2f -
                                            bottomBarHeight.value
                                    val welcomeTopPadding =
                                        (availableWelcomeHeight / 2f).coerceAtLeast(0f).dp
                                    val welcomeModifier =
                                        Modifier.padding(top = welcomeTopPadding)
                                    if (newChatEntryId == 1L) {
                                        TypewriterText(
                                            text = welcomeText,
                                            animationKey = newChatEntryId,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = welcomeModifier,
                                        )
                                    } else {
                                        Text(
                                            text = welcomeText,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = welcomeModifier,
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    val showButton by remember {
                        derivedStateOf {
                            if (isNewChatMode || shareSelectionActive) false
                            else {
                                val info = listState.layoutInfo
                                val total = info.totalItemsCount
                                total > 0 && info.visibleItemsInfo.none { it.index == total - 1 }
                            }
                        }
                    }

                    val fabElevation by animateDpAsState(
                        targetValue = if (showButton) 4.dp else 0.dp,
                        animationSpec = tween(400)
                    )

                    AnimatedVisibility(
                        visible = showButton,
                        enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.6f, animationSpec = tween(400)),
                        exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.6f, animationSpec = tween(400)),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight + 8.dp)
                    ) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            FloatingActionButton(onClick = {
                                haptics.tap()
                                scope.launch { animateToUserMessage(easing = SCROLL_EASING) }
                            }, containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp), contentColor = MaterialTheme.colorScheme.onSurface, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(fabElevation), modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = shareSelectionActive,
                        enter = fadeIn(tween(220)) + scaleIn(
                            initialScale = 0.86f,
                            animationSpec = tween(220),
                        ),
                        exit = fadeOut(tween(180)) + scaleOut(
                            targetScale = 0.86f,
                            animationSpec = tween(180),
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomBarHeight + 10.dp),
                    ) {
                        ShareSelectionFab(
                            allSelected = selectableShareMessageIds.isNotEmpty() &&
                                selectedShareMessageIds.containsAll(selectableShareMessageIds),
                            hasSelection = selectedShareMessageIds.isNotEmpty(),
                            onDismiss = {
                                haptics.tap()
                                shareSelectionActive = false
                                selectedShareMessageIds = emptySet()
                            },
                            onToggleAll = {
                                haptics.selection()
                                selectedShareMessageIds =
                                    if (selectableShareMessageIds.isNotEmpty() &&
                                        selectedShareMessageIds.containsAll(selectableShareMessageIds)
                                    ) {
                                        emptySet()
                                    } else {
                                        selectableShareMessageIds
                                    }
                            },
                            onConfirm = {
                                val selection = selectedShareMessageIds
                                if (selection.isNotEmpty()) {
                                    haptics.tap()
                                    shareSelectionActive = false
                                    selectedShareMessageIds = emptySet()
                                    viewModel.shareMessages(selection)
                                }
                            },
                        )
                    }

                    AnimatedVisibility(
                        visible = isSwitching && !isTransitioningToNewChat,
                        enter = fadeIn(animationSpec = tween(200)),
                        exit = fadeOut(animationSpec = tween(200))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            val gradientTopPaddingPx = with(density) { 20.dp.toPx() }
            val gradientWidthPx = with(density) { 40.dp.toPx() }
            val bgColor = MaterialTheme.colorScheme.background
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight().statusBarsPadding() else Modifier)
                    .drawBehind {
                        val totalH = size.height
                        if (totalH > 0f) {
                            val (transparentEnd, fadeEnd) = if (isExpanded) {
                                // In expanded mode, keep the gradient compact at the top
                                val h = gradientTopPaddingPx.coerceAtMost(totalH * 0.12f)
                                val w = gradientWidthPx.coerceAtMost(totalH * 0.24f)
                                (h / totalH) to ((h + w) / totalH)
                            } else {
                                val te = (gradientTopPaddingPx / totalH).coerceIn(0f, 1f)
                                val fe = ((gradientTopPaddingPx + gradientWidthPx) / totalH).coerceIn(0f, 1f)
                                te to fe
                            }
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        transparentEnd to Color.Transparent,
                                        fadeEnd to bgColor,
                                    ),
                                    startY = 0f,
                                    endY = totalH
                                )
                            )
                        }
                    },
                color = Color.Transparent
            ) {
                Column {
                    if (outerSpacerHeightPx > 0f) {
                        Spacer(modifier = Modifier.height(with(density) { outerSpacerHeightPx.toDp() }))
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                            .onSizeChanged {
                            if (!isExpanded) bottomBarHeightPx = it.height.toFloat()
                        }
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ChatBottomBar(
                        onSendMessage = { text, attachments ->
                            viewModel.sendMessage(text, attachments = attachments)
                        },
                        onStopGeneration = {
                            haptics.destructive()
                            viewModel.stopGeneration()
                        },
                        isLoading = isLoading,
                        isSwitching = isSwitching,
                        enabledModels = enabledModels,
                        selectedModel = selectedModel,
                        modelAliases = modelAliases,
                        codeExecutionEnabled = codeExecutionEnabled,
                        googleSearchEnabled = googleSearchEnabled,
                        thinkingEnabled = thinkingEnabled,
                        thinkingLevel = thinkingLevel,
                        thinkingBudgetEnabled = thinkingBudgetEnabled,
                        thinkingBudgetTokens = thinkingBudgetTokens,
                        activeLoop = currentLoop,
                        loopRunning = currentConversationId in runningLoopIds,
                        onStopLoop = { viewModel.stopCurrentLoop() },
                        onCodeExecutionToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(codeExecutionEnabled = enabled) } },
                        onGoogleSearchToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(googleSearchEnabled = enabled) } },
                        onThinkingToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingEnabled = enabled) } },
                        onThinkingLevelChange = { level -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingLevel = level) } },
                        onThinkingBudgetEnabledChange = { enabled -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetEnabled = enabled) } },
                        onThinkingBudgetTokensChange = { tokens -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetTokens = tokens) } },
                        webSearchEnabled = webSearchEnabled,
                        onWebSearchToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(webSearchEnabled = enabled) } },
                        shellEnabled = shellEnabled,
                        onShellToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(shellEnabled = enabled) } },
                        // The model row owns its selection tick. Repeating it here produced the
                        // previous double buzz for one physical tap.
                        onModelSelect = { viewModel.setActiveModel(it) },
                        onImageClick = { url -> haptics.tap(); onMediaClick(listOf(url), 0) },
                        onAllMediaClick = { urls, idx -> haptics.tap(); onMediaClick(urls, idx) },
                        onFileContentClick = { name, content -> haptics.tap(); viewModel.showFilePreview(name, content) },
                        modifier = Modifier,
                        textFieldState = textFieldState,
                        composerState = composer,
                        focusRequester = inputFocusRequester,
                        isExpanded = isExpanded,
                        isExpandAnimating = isExpandAnimating,
                        // No haptic here: onCollapse also fires on back gesture and — the reason
                        // Send felt like a double tap — automatically after a successful send.
                        // The collapse BUTTON does its own haptic, where a press actually happened.
                        onCollapse = { isExpanded = false },
                        onExpand = { haptics.tap(); isExpanded = true },
                        showWebSearch = globalWebSearch,
                        showShell = shellDevices.isNotEmpty() && globalShell,
                        onPdfPagesClick = { pages, idx -> haptics.tap(); onPdfPagesClick?.invoke(pages, idx) },
                        onPdfPreviewSelect = { pages, idx -> haptics.tap(); onPdfPreviewSelect?.invoke(pages, idx) },
                        pdfViewerSelection = pdfViewerSelection,
                        onTogglePdfSelection = onTogglePdfSelection,
                        onInitPdfSelection = onInitPdfSelection,
                        fullScreenViewerUrls = fullScreenViewerUrls,
                        onAdvancedClick = { showAdvancedDialog = true },
                        queuedSends = queuedSends,
                        onRemoveQueuedSend = viewModel::removeQueuedSend,
                        isStopping = isStopping,
                    )
                }
            }
            }
        }
        }
    }
    }

    showRenameDialog?.let { id ->
        ChatRenameDialog(
            initialName = conversationToRename,
            onSave = { newName ->
                viewModel.renameConversation(id, newName)
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    showDeleteConfirmDialog?.let { id ->
        ChatDeleteConfirmDialog(
            onConfirm = {
                haptics.destructive()
                viewModel.deleteConversation(id)
                showDeleteConfirmDialog = null
            },
            onDismiss = { showDeleteConfirmDialog = null }
        )
    }

    if (showPromptDialog) {
        ChatSystemPromptDialog(viewModel = viewModel, onDismiss = { showPromptDialog = false })
    }

    if (showAdvancedDialog) {
        ChatAdvancedSettingsDialog(viewModel = viewModel, onDismiss = { showAdvancedDialog = false })
    }
}
