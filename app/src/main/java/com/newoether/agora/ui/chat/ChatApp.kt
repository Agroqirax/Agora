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
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.interaction.DragInteraction
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
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
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
import com.newoether.agora.data.isOpenAiProtocolProvider
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.expandSelectedToolProtocolRows
import com.newoether.agora.util.gradientBlur
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.bottombar.CHAT_BOTTOM_BAR_OUTER_SHAPE
import com.newoether.agora.ui.chat.bottombar.ChatBottomBar
import com.newoether.agora.ui.chat.bottombar.LoopStatusBackdrop
import com.newoether.agora.ui.chat.bottombar.PendingAttachmentRemoval
import com.newoether.agora.ui.chat.message.hasActiveAnswerSegment
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.TypewriterMode
import com.newoether.agora.ui.components.TypewriterText
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.closeWithMotionPolicy
import com.newoether.agora.ui.motion.openWithMotionPolicy
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.AnimatedScrollDestination
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.RegenerationTransitionStage
import com.newoether.agora.viewmodel.SwitchingRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private val SCROLL_EASING = CubicBezierEasing(0.3f, 0.0f, 0.0f, 1.0f)
private val SEND_FEEDBACK_SCROLL_SPEC = DefaultFeedbackScrollSpec.copy(
    startup = FeedbackScrollStartupSpec(
        durationMillis = 240L,
        easing = FastOutSlowInEasing,
    ),
)
private const val CONVERSATION_RESOLVE_TIMEOUT_MS = 2_000L
private const val SCROLL_SETTLE_TIMEOUT_MS = 8_000L
private const val STABLE_LAYOUT_SAMPLES = 3
private const val LAYOUT_SAMPLE_INTERVAL_MS = 32L
private const val INLINE_SHARE_LIMIT_BYTES = 256 * 1024
private const val SHARE_ERROR_DETAIL_TOKEN = "__AGORA_SHARE_ERROR_DETAIL__"
private const val STREAM_SCROLL_RESUME_DELAY_MS = 160L
private const val DRAFT_TEXT_DEBOUNCE_MS = 300L
private const val DRAFT_PERSIST_RETRY_COUNT = 2
private const val DRAFT_PERSIST_RETRY_DELAY_MS = 80L

private data class ComposerDraftUiSnapshot(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val removals: List<PendingAttachmentRemoval>,
)

internal fun composerDraftWriteDelayMillis(
    previousAttachments: List<SelectedAttachment>,
    nextAttachments: List<SelectedAttachment>,
    hasPendingRemovals: Boolean,
): Long =
    if (previousAttachments != nextAttachments || hasPendingRemovals) {
        0L
    } else {
        DRAFT_TEXT_DEBOUNCE_MS
    }

internal data class NewChatMotionPolicy(
    val animateBackground: Boolean,
    val animateWelcomeText: Boolean,
)

internal fun newChatMotionPolicy(
    reduceMotion: Boolean,
    isNewChatMode: Boolean,
    isLoading: Boolean,
    isSwitching: Boolean,
    newChatEntryId: Long,
): NewChatMotionPolicy {
    if (reduceMotion) {
        return NewChatMotionPolicy(
            animateBackground = false,
            animateWelcomeText = false,
        )
    }
    return NewChatMotionPolicy(
        animateBackground = isNewChatMode && !isLoading && !isSwitching,
        animateWelcomeText = newChatEntryId == 1L,
    )
}

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
    bypassScrollIsolation: Boolean,
): State<List<ChatMessage>> {
    val rendered = remember(conversationId, upstream) {
        mutableStateOf(upstream.value)
    }
    val latestBypassScrollIsolation by rememberUpdatedState(bypassScrollIsolation)
    LaunchedEffect(conversationId, upstream, listState) {
        coroutineScope {
            var latest = upstream.value
            var deferred = listState.isScrollInProgress
            var hasOwnedScroll = listState.isScrollInProgress
            var resumeJob: Job? = null

            launch {
                snapshotFlow {
                    listState.isScrollInProgress to latestBypassScrollIsolation
                }
                    .distinctUntilChanged()
                    .collect { (scrolling, bypass) ->
                        resumeJob?.cancel()
                        if (bypass) {
                            deferred = false
                            hasOwnedScroll = false
                            if (rendered.value !== latest) rendered.value = latest
                        } else if (scrolling) {
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
                            latestBypassScrollIsolation ||
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

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    kotlinx.coroutines.FlowPreview::class,
)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    onNavigateBack: (() -> Unit)? = null,
    drawerEnabled: Boolean = true,
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
    val motionPolicy = LocalAgoraMotionPolicy.current
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

    val latestDrawerEnabled by rememberUpdatedState(drawerEnabled)
    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { newValue ->
            val allowed = newValue == DrawerValue.Closed || latestDrawerEnabled
            if (allowed && newValue != DrawerValue.Closed) {
                focusManager.clearFocus()
            }
            allowed
        }
    )
    LaunchedEffect(drawerEnabled, motionPolicy.allowSpatialTransitions) {
        if (!drawerEnabled) drawerState.closeWithMotionPolicy(motionPolicy)
    }

    val conversations by viewModel.conversations.collectAsState()
    // Defer value reads to the narrow composition regions that actually render messages. The
    // State objects themselves are stable, so stream snapshots no longer recompose all ChatApp.
    val messagesState = viewModel.messages.collectAsState()
    val allMessagesState = viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isCompacting by viewModel.isCompacting.collectAsState()
    val compactPreview by viewModel.compactPreview.collectAsState()
    val compactModel by viewModel.settings.contextCompactModel.collectAsState()
    val compactPrompt by viewModel.settings.contextCompactPrompt.collectAsState()
    val compactRetainCount by viewModel.settings.contextCompactRetainCount.collectAsState()
    var showManualCompactDialog by rememberSaveable { mutableStateOf(false) }
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
    val regenerationTransition by viewModel.regenerationTransition.collectAsState()
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
    val globalOpenAiServiceTierEnabled by
        viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val globalOpenAiServiceTier by viewModel.settings.openAiServiceTier.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val globalWebSearch by viewModel.settings.webSearchEnabled.collectAsState()
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val globalShell by viewModel.settings.shellEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by viewModel.settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by viewModel.settings.autoExpandActiveGroup.collectAsState()
    val detailedTokenUsage by viewModel.settings.detailedTokenUsage.collectAsState()
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
    val openAiServiceTierEnabled =
        convOverride?.openAiServiceTierEnabled ?: globalOpenAiServiceTierEnabled
    val openAiServiceTier = OpenAiServiceTiers.normalize(
        convOverride?.openAiServiceTier ?: globalOpenAiServiceTier,
    )
    val selectedProviderName = viewModel.getProviderForModel(selectedModel)
    val openAiServiceTierAvailable =
        isOpenAiProtocolProvider(selectedProviderName, customProviders)
    // Web Search and Shell: global switch OFF → always false, regardless of override
    val webSearchEnabled = globalWebSearch && (convOverride?.webSearchEnabled ?: true)
    val shellEnabled = globalShell && (convOverride?.shellEnabled ?: true)
    val contextWindow = ContextBudget.normalize(convOverride?.contextWindow ?: maxContextWindow)
    val contextUsage = remember(messagesState.value, allMessagesState.value, contextWindow) {
        contextWindowUsage(
            expandSelectedToolProtocolRows(messagesState.value, allMessagesState.value),
            contextWindow,
        )
    }
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val reduceMotion = motionPolicy.reduceMotion
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val haptics = rememberAgoraHaptics(hapticsEnabled)
    // The three send paths (manual Send, queue drain, loop cycle) converge in the Controller at
    // notifySendAccepted, the single choke point for Direct + Queued send acceptances. Wiring the
    // haptics there gives every accepted send exactly one confirm(), independent of which path
    // triggered it or which scroll policy applies.
    DisposableEffect(haptics) {
        viewModel.onSendAccepted = { _, _ -> haptics.confirm() }
        onDispose { viewModel.onSendAccepted = null }
    }


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
    LaunchedEffect(isExpanded, motionPolicy.allowSpatialTransitions) {
        if (isExpanded) {
            if (motionPolicy.allowSpatialTransitions) {
                spacerProgress.snapTo(0f)
                spacerProgress.animateTo(1f, tween(400, easing = spacerEasing))
            } else {
                spacerProgress.snapTo(1f)
            }
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
    var absoluteBottomScrollPhase by remember(currentConversationId) {
        mutableStateOf(AbsoluteBottomScrollPhase.IDLE)
    }
    var absoluteBottomRequestToken by remember(currentConversationId) {
        mutableLongStateOf(0L)
    }
    var absoluteBottomRequestFeedbackSpec by remember(currentConversationId) {
        mutableStateOf(DefaultFeedbackScrollSpec)
    }
    val bottomButtonHideThresholdPx = with(density) { 64.dp.toPx() }
    val bottomButtonShowThresholdPx = with(density) { 96.dp.toPx() }
    var isNearAbsoluteBottom by remember(currentConversationId) {
        mutableStateOf(true)
    }
    var isWithinAbsoluteBottomAttachThreshold by remember(currentConversationId) {
        mutableStateOf(false)
    }
    var composerInputFocused by remember { mutableStateOf(false) }
    val imeBottomPx = with(density) { imeBottom.roundToPx() }
    var imeBottomAnchorState by remember(currentConversationId) {
        mutableStateOf(
            ImeBottomAnchorState(
                observedInsetPx = imeBottomPx,
                bottomEligibleBeforeInsetChange = false,
            ),
        )
    }
    val imeBottomEligibleNow =
        currentConversationId != null &&
            loadedMessagesConversationId == currentConversationId &&
            composerInputFocused &&
            isWithinAbsoluteBottomAttachThreshold
    SideEffect {
        val next = reduceImeBottomAnchor(
            current = imeBottomAnchorState,
            event = ImeBottomAnchorEvent.InsetsObserved(
                insetPx = imeBottomPx,
                bottomEligibleNow = imeBottomEligibleNow,
                anchorAllowed = composerInputFocused,
            ),
        )
        if (next != imeBottomAnchorState) imeBottomAnchorState = next
    }
    // STOPPING deliberately keeps the generation slot's loading flag true until both coroutine
    // unwind and durable finalization finish. It cannot produce more content, though, so treating
    // it as a growing stream lets a transient terminal-layout contraction drive either follow
    // actor upward (in the worst case all the way to the top).
    val latestGenerationCanGrow by rememberUpdatedState(isLoading && !isStopping)
    // Follow state belongs to one conversation. Reusing it across a conversation switch can
    // carry a stale auto-follow=true flag into the next screen and suppress its bottom button.
    val streamingTailController = rememberStreamingTailController(currentConversationId)
    fun requestAbsoluteBottomScroll(
        feedbackSpec: FeedbackScrollSpec = DefaultFeedbackScrollSpec,
    ): Boolean {
        if (absoluteBottomScrollPhase.isActive) return false
        imeBottomAnchorState = reduceImeBottomAnchor(
            imeBottomAnchorState,
            ImeBottomAnchorEvent.Cancelled,
        )
        absoluteBottomScrollPhase = reduceAbsoluteBottomScroll(
            absoluteBottomScrollPhase,
            AbsoluteBottomScrollEvent.Requested,
        )
        absoluteBottomRequestFeedbackSpec = feedbackSpec
        absoluteBottomRequestToken =
            if (absoluteBottomRequestToken == Long.MAX_VALUE) 1L
            else absoluteBottomRequestToken + 1L
        return true
    }
    LaunchedEffect(
        listState,
        currentConversationId,
        bottomButtonHideThresholdPx,
        bottomButtonShowThresholdPx,
    ) {
        val estimatedSentinelSizePx = with(density) { 1.dp.toPx() }
        snapshotFlow {
            val snapshot = absoluteBottomLayoutSnapshot(
                layoutInfo = listState.layoutInfo,
                canScrollForward = listState.canScrollForward,
            )
            snapshot to snapshot.estimatedRemainingDistancePx(estimatedSentinelSizePx)
        }
            .distinctUntilChanged()
            .collect { (snapshot, remainingDistancePx) ->
                isWithinAbsoluteBottomAttachThreshold =
                    isWithinAbsoluteBottomAttachThreshold(
                        snapshot = snapshot,
                        remainingDistancePx = remainingDistancePx,
                        thresholdPx = bottomButtonHideThresholdPx,
                    )
                isNearAbsoluteBottom = reduceAbsoluteBottomProximity(
                    wasNearBottom = isNearAbsoluteBottom,
                    canScrollForward = snapshot.canScrollForward,
                    remainingDistancePx = remainingDistancePx,
                    hideThresholdPx = bottomButtonHideThresholdPx,
                    showThresholdPx = bottomButtonShowThresholdPx,
                )
            }
    }
    val messageLifecycleAppearanceRegistry = remember {
        MessageLifecycleAppearanceRegistry()
    }
    val renderMessagesState = rememberScrollIsolatedMessages(
        conversationId = currentConversationId,
        upstream = messagesState,
        listState = listState,
        bypassScrollIsolation =
            streamingTailController.isAutoFollowing || absoluteBottomScrollPhase.isActive,
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
        if (!motionPolicy.allowProgrammaticScrollMotion) {
            listState.scrollToItem(targetIndex, 0)
            return true
        }

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

    fun estimateRemainingAbsoluteBottomDistance(): Float? {
        val layout = listState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.maxByOrNull { item -> item.index }
            ?: return null
        val currentMessages = messagesState.value
        val layoutTurns = buildMessageListTurns(currentMessages)
        val visibleSizes = layout.visibleItemsInfo.associate { item -> item.index to item.size }
        val fallbackHeight = visibleSizes.values
            .filter { size -> size > 1 }
            .takeIf { sizes -> sizes.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: with(density) { 72.dp.toPx() }
        val lastUserMessageId = currentMessages
            .lastOrNull { message -> message.participant == Participant.USER }
            ?.id
        val tailMinimumHeightPx = if (lastUserMessageId == null || viewportHeightPx == 0) {
            0f
        } else {
            calculateTailMinHeightPx(
                viewportHeightPx = viewportHeightPx,
                targetTopPx = with(density) { 140.dp.roundToPx() },
                bottomObstructionPx = with(density) {
                    (bottomBarHeight + shareSelectionBarSpace + 8.dp).roundToPx()
                },
            ).toFloat()
        }
        val sentinelHeightPx = with(density) { 1.dp.toPx() }

        fun estimatedItemSize(index: Int): Float {
            visibleSizes[index]?.let { size -> return size.toFloat() }
            val turn = layoutTurns.getOrNull(index) ?: return sentinelHeightPx
            val estimated = estimateMessageListTurnHeightPx(
                turn = turn,
                messageHeights = messageHeights,
                fallbackHeightPx = fallbackHeight,
            )
            return if (turn.key == lastUserMessageId) {
                maxOf(estimated, tailMinimumHeightPx)
            } else {
                estimated
            }
        }

        return estimateAbsoluteBottomDistancePx(
            lastVisibleIndex = lastVisible.index,
            lastVisibleEndOffsetPx = lastVisible.offset + lastVisible.size,
            viewportEndOffsetPx = layout.viewportEndOffset,
            afterContentPaddingPx = layout.afterContentPadding,
            totalItemsCount = layout.totalItemsCount,
            estimatedItemSizePx = ::estimatedItemSize,
        )
    }

    val latestImeBottomAnchorState by rememberUpdatedState(imeBottomAnchorState)
    val latestImeBottomPx by rememberUpdatedState(imeBottomPx)
    LaunchedEffect(
        currentConversationId,
        imeBottomAnchorState.active,
    ) {
        if (!imeBottomAnchorState.active) return@LaunchedEffect

        val actorStartNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
        var lastObservedInsetPx = latestImeBottomPx
        var lastInsetChangeNanos = actorStartNanos
        var stableFrames = 0
        while (true) {
            val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
            if (!latestImeBottomAnchorState.active) return@LaunchedEffect
            if (latestImeBottomPx != lastObservedInsetPx) {
                lastObservedInsetPx = latestImeBottomPx
                lastInsetChangeNanos = frameNanos
            }

            val layout = absoluteBottomLayoutSnapshot(
                layoutInfo = listState.layoutInfo,
                canScrollForward = listState.canScrollForward,
            )
            val remainingDistancePx =
                layout.remainingDistancePx
                    ?: estimateRemainingAbsoluteBottomDistance()
                    ?: if (listState.canScrollForward) {
                        layout.viewportSizePx * 0.5f
                    } else {
                        0f
                    }

            if (remainingDistancePx > 0.5f) {
                // IME anchoring is a positional correction, not navigational travel. Consume each
                // newly exposed gap in one frame in both motion modes, so the composer and list
                // remain visually attached to the keyboard instead of trailing its inset motion.
                listState.dispatchRawDelta(remainingDistancePx)
                stableFrames = 0
            } else {
                val insetStableForNanos = frameNanos - lastInsetChangeNanos
                stableFrames =
                    if (insetStableForNanos >= 80_000_000L) stableFrames + 1 else 0
                if (stableFrames >= 3) {
                    imeBottomAnchorState = reduceImeBottomAnchor(
                        latestImeBottomAnchorState,
                        ImeBottomAnchorEvent.CorrectionSettled,
                    )
                    return@LaunchedEffect
                }
            }

            if (frameNanos - actorStartNanos >= 1_600_000_000L) {
                // Bound the actor even if a malformed layout never exposes a stable sentinel. The
                // normal proximity state will then expose the bottom button instead of burning
                // frames indefinitely.
                imeBottomAnchorState = reduceImeBottomAnchor(
                    latestImeBottomAnchorState,
                    ImeBottomAnchorEvent.CorrectionSettled,
                )
                return@LaunchedEffect
            }
        }
    }

    suspend fun awaitScrollTargetCommitted(targetMessageId: String?): Boolean =
        withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
            snapshotFlow {
                val index = resolveScrollTargetIndex(messagesState.value, targetMessageId)
                index to listState.layoutInfo.totalItemsCount
            }.first { (index, itemCount) ->
                index >= 0 && index < itemCount
            }
            true
        } == true

    suspend fun animateAfterTargetCommitted(targetMessageId: String?): Boolean {
        if (!awaitScrollTargetCommitted(targetMessageId)) return false
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
                if (
                    completed &&
                    request.kind == SwitchingRequestKind.CONVERSATION &&
                    request.hapticOnCompletion
                ) {
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

    // One effect owns both loading and persistence for exactly one conversation. This prevents
    // the former pair of independent effects from cancelling a debounced tail write during a
    // fast switch. Attachment mutations bypass the text debounce; cancellation performs a final
    // non-cancellable flush before the next conversation is allowed to bind the shared composer.
    LaunchedEffect(currentConversationId) {
        val draftId = currentConversationId
        if (draftId == null) {
            // New-chat screen: clear the composer so a draft from the previous conversation
            // doesn't carry over.
            viewModel.loadingDraft = true
            try {
                composer.bindDraftOwner(null)
                textFieldState.edit { replace(0, length, "") }
                composer.selectedAttachments = emptyList()
            } finally {
                viewModel.loadingDraft = false
            }
            return@LaunchedEffect
        }

        viewModel.loadingDraft = true
        val loadedDraft = try {
            viewModel.loadDraft(draftId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.e("AgoraUI", "Failed to load composer draft for $draftId", error)
            com.newoether.agora.viewmodel.LoadedComposerDraft(
                text = "",
                attachments = emptyList(),
                revision = 0L,
            )
        }
        try {
            composer.bindDraftOwner(draftId)
            textFieldState.edit {
                replace(0, length, loadedDraft.text)
            }
            composer.selectedAttachments = loadedDraft.attachments
        } finally {
            viewModel.loadingDraft = false
        }

        var revision = loadedDraft.revision
        var persistedAttachments = loadedDraft.attachments

        fun captureDraft(): ComposerDraftUiSnapshot = ComposerDraftUiSnapshot(
            text = textFieldState.text.toString(),
            attachments = composer.selectedAttachments,
            removals = composer.attachmentRemovalsFor(draftId),
        )
        var latestSnapshot = captureDraft()

        suspend fun persistSnapshot(snapshot: ComposerDraftUiSnapshot) {
            var failureCount = 0
            while (true) {
                val result = viewModel.persistDraft(
                    conversationId = draftId,
                    expectedRevision = revision,
                    text = snapshot.text,
                    attachments = snapshot.attachments,
                    explicitlyRemovedAttachments =
                        snapshot.removals.map(PendingAttachmentRemoval::attachment),
                )
                revision = result.revision
                if (result.succeeded) {
                    if (result.matchesRequested) {
                        persistedAttachments = snapshot.attachments
                        composer.acknowledgeAttachmentRemovals(
                            snapshot.removals
                                .mapTo(linkedSetOf(), PendingAttachmentRemoval::id),
                        )
                    }
                    // A revision mismatch means a newer owner (most commonly accepted Send)
                    // already committed state. Never retry the stale snapshot over that state.
                    return
                }
                if (failureCount >= DRAFT_PERSIST_RETRY_COUNT) return
                failureCount += 1
                delay(DRAFT_PERSIST_RETRY_DELAY_MS * failureCount)
            }
        }

        try {
            snapshotFlow { captureDraft() }
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    // Retain a conversation-owned copy before any debounce suspension. A new
                    // LaunchedEffect may bind the shared composer while this one is cancelling.
                    latestSnapshot = snapshot
                    val delayMillis = composerDraftWriteDelayMillis(
                        previousAttachments = persistedAttachments,
                        nextAttachments = snapshot.attachments,
                        hasPendingRemovals = snapshot.removals.isNotEmpty(),
                    )
                    if (delayMillis > 0L) delay(delayMillis)
                    persistSnapshot(snapshot)
                }
        } finally {
            // LaunchedEffect cancellation normally remains cancellable. The final snapshot must
            // outlive a navigation/recomposition cancellation so its conversation cannot retain
            // stale text or attachment references.
            val finalSnapshot = if (composer.isDraftOwner(draftId)) {
                captureDraft()
            } else {
                latestSnapshot
            }
            withContext(NonCancellable) {
                persistSnapshot(finalSnapshot)
            }
        }
    }

    val animatedScrollRequest by viewModel.animatedScrollRequest.collectAsState()
    LaunchedEffect(
        absoluteBottomRequestToken,
        currentConversationId,
        motionPolicy.allowProgrammaticScrollMotion,
    ) {
        if (absoluteBottomRequestToken == 0L) return@LaunchedEffect
        try {
            val reachedBottom = if (motionPolicy.allowProgrammaticScrollMotion) {
                listState.animateToAbsoluteBottom(
                    isGenerationActive = { latestGenerationCanGrow },
                    estimateRemainingDistancePx = ::estimateRemainingAbsoluteBottomDistance,
                    minimumStepPx = with(density) { 2.dp.toPx() },
                    onPhaseChanged = { phase -> absoluteBottomScrollPhase = phase },
                    feedbackSpec = absoluteBottomRequestFeedbackSpec,
                )
            } else {
                absoluteBottomScrollPhase = AbsoluteBottomScrollPhase.SEEKING
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) {
                    listState.scrollToItem(lastIndex)
                    withFrameNanos { }
                    !listState.canScrollForward
                } else {
                    false
                }
            }
            if (reachedBottom) {
                imeBottomAnchorState = reduceImeBottomAnchor(
                    imeBottomAnchorState,
                    ImeBottomAnchorEvent.ExplicitBottomReached,
                )
            }
        } finally {
            if (absoluteBottomScrollPhase.isActive) {
                absoluteBottomScrollPhase = reduceAbsoluteBottomScroll(
                    absoluteBottomScrollPhase,
                    AbsoluteBottomScrollEvent.Cancelled,
                )
            }
        }
    }
    LaunchedEffect(listState, currentConversationId) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                imeBottomAnchorState = reduceImeBottomAnchor(
                    imeBottomAnchorState,
                    ImeBottomAnchorEvent.UserDragStarted,
                )
                if (absoluteBottomScrollPhase.isActive) {
                    absoluteBottomScrollPhase = reduceAbsoluteBottomScroll(
                        absoluteBottomScrollPhase,
                        AbsoluteBottomScrollEvent.Cancelled,
                    )
                    absoluteBottomRequestToken = 0L
                }
            }
        }
    }
    LaunchedEffect(
        conversationSearchActive,
        shareSelectionActive,
        isSwitching,
        regenerationTransition?.id,
        animatedScrollRequest?.id,
        imeBottomAnchorState.active,
    ) {
        val competingTransition =
            conversationSearchActive ||
                shareSelectionActive ||
                isSwitching ||
                regenerationTransition != null ||
                animatedScrollRequest != null
        if (competingTransition && absoluteBottomScrollPhase.isActive) {
            absoluteBottomScrollPhase = reduceAbsoluteBottomScroll(
                absoluteBottomScrollPhase,
                AbsoluteBottomScrollEvent.Cancelled,
            )
            absoluteBottomRequestToken = 0L
        }
        if (competingTransition && imeBottomAnchorState.active) {
            imeBottomAnchorState = reduceImeBottomAnchor(
                imeBottomAnchorState,
                ImeBottomAnchorEvent.Cancelled,
            )
        }
    }
    LaunchedEffect(
        regenerationTransition?.id,
        currentConversationId,
    ) {
        val request = regenerationTransition ?: return@LaunchedEffect
        if (request.scrollFinished) return@LaunchedEffect
        if (request.conversationId != currentConversationId) {
            viewModel.acknowledgeRegenerationScroll(request.id, success = false)
            return@LaunchedEffect
        }
        try {
            val success = animateToUserMessage(
                targetMessageId = request.targetUserMessageId,
                easing = SCROLL_EASING,
            )
            viewModel.acknowledgeRegenerationScroll(request.id, success)
        } catch (e: CancellationException) {
            viewModel.acknowledgeRegenerationScroll(request.id, success = false)
            throw e
        }
    }
    LaunchedEffect(
        regenerationTransition?.id,
        regenerationTransition?.stage,
        regenerationTransition?.scrollFinished,
        currentConversationId,
    ) {
        val request = regenerationTransition
            ?.takeIf {
                it.stage == RegenerationTransitionStage.COMMITTED &&
                    it.scrollFinished
            }
            ?: return@LaunchedEffect
        if (request.conversationId == currentConversationId) {
            snapshotFlow {
                messagesState.value.none { message -> message.id == request.oldMessageId }
            }.first { oldPathRemoved -> oldPathRemoved }
            withFrameNanos { }
        }
        viewModel.completeRegenerationTransition(request.id)
    }
    LaunchedEffect(animatedScrollRequest?.id, currentConversationId) {
        val request = animatedScrollRequest ?: return@LaunchedEffect
        if (request.conversationId != currentConversationId) {
            // A first Send arms its entrance/scroll request immediately before publishing the
            // newly-created conversation id. Keep that request alive across the single null-id
            // frame; this effect restarts as soon as currentConversationId is published.
            if (currentConversationId != null || !isNewChatMode) {
                viewModel.completeAnimatedScroll(request.id)
            }
            return@LaunchedEffect
        }
        when (request.destination) {
            AnimatedScrollDestination.MESSAGE -> {
                try {
                    if (!animateAfterTargetCommitted(request.targetMessageId)) {
                        DebugLog.e(
                            "AgoraUI",
                            "Animated scroll target was not committed: ${request.targetMessageId}",
                        )
                    }
                } finally {
                    viewModel.completeAnimatedScroll(request.id)
                }
            }
            AnimatedScrollDestination.ABSOLUTE_BOTTOM -> {
                val targetCommitted = try {
                    awaitScrollTargetCommitted(request.targetMessageId)
                } finally {
                    // Complete the readiness request before arming the bottom actor. The
                    // competing-transition gate therefore cannot cancel the Send's own scroll.
                    viewModel.completeAnimatedScroll(request.id)
                }
                if (
                    targetCommitted &&
                    request.conversationId == currentConversationId
                ) {
                    // ATTACHED_ONLY (loop cycles) skips scrolling when the user has scrolled
                    // away so automated messages never steal the scroll position. The one-shot
                    // bubble entrance animation still plays because the request was active
                    // during target commitment.
                    val shouldScroll =
                        !request.attachedOnly || isWithinAbsoluteBottomAttachThreshold
                    if (shouldScroll) {
                        requestAbsoluteBottomScroll(feedbackSpec = SEND_FEEDBACK_SCROLL_SPEC)
                    }
                } else if (!targetCommitted) {
                    DebugLog.e(
                        "AgoraUI",
                        "Absolute-bottom scroll target was not committed: ${request.targetMessageId}",
                    )
                }
            }
        }
    }

    BackHandler(enabled = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        focusManager.clearFocus()
        scope.launch { drawerState.closeWithMotionPolicy(motionPolicy) }
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
        gesturesEnabled = drawerEnabled,
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
            val newChatMotion = newChatMotionPolicy(
                reduceMotion = reduceMotion,
                isNewChatMode = isNewChatMode,
                isLoading = isLoading,
                isSwitching = isSwitching,
                newChatEntryId = newChatEntryId,
            )
            AnimatedBlobBackground(
                centerAlpha = ca,
                quarterAlpha = qa,
                blurRadius = 40f,
                dark = dark,
                blurEnabled = blurEffectsEnabled,
                motionEnabled = newChatMotion.animateBackground,
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
                        onOpenDrawer = {
                            if (drawerEnabled) {
                                focusManager.clearFocus()
                                scope.launch { drawerState.openWithMotionPolicy(motionPolicy) }
                            }
                        },
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
                            conversationSearchActive = false
                            conversationSearchQuery = ""
                            conversationSearchMatchIndex = -1
                            focusManager.clearFocus()
                        },
                        onSearchClick = {
                            shareSelectionActive = false
                            selectedShareMessageIds = emptySet()
                            conversationSearchActive = true
                        },
                        onSystemPromptClick = { showPromptDialog = true },
                        onForkConversation = {
                            viewModel.forkConversationFrom()
                        },
                        onShareConversation = {
                            conversationSearchActive = false
                            conversationSearchQuery = ""
                            conversationSearchMatchIndex = -1
                            focusManager.clearFocus()
                            selectedShareMessageIds = emptySet()
                            shareSelectionActive = true
                        },
                        onNewChat = {
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
                                val fadeInSpec = tween<Float>(500)
                                val enter = if (motionPolicy.allowSpatialTransitions) {
                                    val enterSpec = tween<Float>(
                                        700,
                                        easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f),
                                    )
                                    fadeIn(animationSpec = fadeInSpec) +
                                        scaleIn(
                                            initialScale = 0.6f,
                                            transformOrigin = TransformOrigin(0.5f, pivotY),
                                            animationSpec = enterSpec,
                                        )
                                } else {
                                    fadeIn(animationSpec = fadeInSpec)
                                }
                                enter
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
                            val streamingFollowAvailability = streamingTailAvailability(
                                generationActive = isLoading,
                                blocked =
                                    isStopping ||
                                        isSwitching ||
                                        conversationSearchActive ||
                                        shareSelectionActive ||
                                        !motionPolicy.allowProgrammaticScrollMotion,
                                programmaticHandoff =
                                    imeBottomAnchorState.active ||
                                        absoluteBottomScrollPhase.isActive ||
                                        animatedScrollRequest?.conversationId ==
                                            currentConversationId ||
                                        regenerationTransition?.conversationId ==
                                            currentConversationId,
                            )
                            Box(modifier = Modifier.fillMaxSize()) {
                            MessageList(
                                messages = StableMessageList(renderMessagesState.value),
                                allMessages = StableMessageList(allMessagesState.value),
                                conversationId = currentConversationId,
                                modifier = messageListModifier,
                                state = listState,
                                // Per-conversation generation gate: isLoading mirrors the OPEN
                                // conversation's slot only (ConversationGenerationState.onActive
                                // gates on current == id), so message actions freeze while THIS
                                // conversation generates — background conversations don't affect it.
                                isLoading = isLoading,
                                isCompacting = isCompacting,
                                compactPreview = compactPreview,
                                isStopping = isStopping,
                                isSwitching = isSwitching,
                                streamingAutoFollowEnabled =
                                    streamingFollowAvailability.enabled,
                                streamingAutoFollowPaused =
                                    streamingFollowAvailability.paused,
                                streamingTailWithinAttachThreshold =
                                    isWithinAbsoluteBottomAttachThreshold,
                                programmaticScrollActive =
                                    animatedScrollRequest?.conversationId ==
                                        currentConversationId,
                                streamingTailController = streamingTailController,
                                streamingIndicatorVisible =
                                    isLoading &&
                                        regenerationTransition?.stage !=
                                            RegenerationTransitionStage.ANIMATING,
                                regenerationTransition = regenerationTransition,
                                onRegenerationFadeOutFinished =
                                    viewModel::acknowledgeRegenerationFade,
                                visualizeContextRollout = visualizeContextRollout,
                                toolCallDisplayMode = toolCallDisplayMode,
                                thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                                autoExpandActiveGroup = autoExpandActiveGroup,
                                detailedTokenUsage = detailedTokenUsage,
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
                                    val accepted = viewModel.editMessage(id, text)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onSwitchBranch = { parentId, currentMessageId, direction ->
                                    haptics.selection()
                                    viewModel.switchBranch(parentId, currentMessageId, direction)
                                },
                                onRegenerate = { id ->
                                    val accepted = viewModel.regenerate(id)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onFork = { id ->
                                    viewModel.forkConversationFrom(id)
                                },
                                onShare = { id ->
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
                                    onMediaClick(urls, index)
                                },
                                onFileContentClick = onFileContentClick?.let { open ->
                                    { name, content ->
                                        open(name, content)
                                    }
                                },
                                onPdfPagesClick = { pages, idx ->
                                    onPdfPagesClick?.invoke(pages, idx)
                                },
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
                                    TypewriterText(
                                        text = welcomeText,
                                        animationKey = newChatEntryId,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        typeSpeedMs = 100,
                                        animate = newChatMotion.animateWelcomeText,
                                        mode = TypewriterMode.TEXT_GRADIENT,
                                        modifier = welcomeModifier,
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    // The proximity/scroll phase states above are themselves recreated for each
                    // conversation. Recreate this derived state with the same owner so its closure
                    // never keeps reading a previous conversation's state objects or the initial
                    // new-chat flag.
                    val regenerationScrollActive =
                        regenerationTransition?.conversationId == currentConversationId &&
                            regenerationTransition?.scrollFinished == false
                    val showButton by remember(
                        currentConversationId,
                        loadedMessagesConversationId,
                        isNewChatMode,
                        isSwitching,
                        listState,
                        streamingTailController,
                        regenerationScrollActive,
                        imeBottomAnchorState.active,
                    ) {
                        derivedStateOf {
                            val totalItemsCount = listState.layoutInfo.totalItemsCount
                            shouldShowAbsoluteBottomButton(
                                isNewChatMode = isNewChatMode,
                                isSwitching = isSwitching,
                                conversationContentReady =
                                    currentConversationId != null &&
                                        loadedMessagesConversationId == currentConversationId,
                                shareSelectionActive = shareSelectionActive,
                                hasItems = totalItemsCount > 1,
                                canScrollForward = listState.canScrollForward,
                                isNearBottom = isNearAbsoluteBottom,
                                isStreamingAutoFollowing =
                                    streamingTailController.isAutoFollowing,
                                scrollPhase = absoluteBottomScrollPhase,
                                competingProgrammaticScrollActive =
                                    regenerationScrollActive ||
                                        imeBottomAnchorState.active,
                            )
                        }
                    }

                    val fabElevation by animateDpAsState(
                        targetValue = if (showButton) 4.dp else 0.dp,
                        animationSpec = if (motionPolicy.allowSpatialTransitions) {
                            tween(400)
                        } else {
                            snap()
                        }
                    )

                    AnimatedVisibility(
                        visible = showButton,
                        enter = if (motionPolicy.allowSpatialTransitions) {
                            fadeIn(tween(400)) +
                                scaleIn(initialScale = 0.6f, animationSpec = tween(400))
                        } else {
                            fadeIn(tween(400))
                        },
                        exit = if (motionPolicy.allowSpatialTransitions) {
                            fadeOut(tween(400)) +
                                scaleOut(targetScale = 0.6f, animationSpec = tween(400))
                        } else {
                            fadeOut(tween(400))
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight + 8.dp)
                    ) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            FloatingActionButton(onClick = {
                                requestAbsoluteBottomScroll()
                            }, containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp), contentColor = MaterialTheme.colorScheme.onSurface, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(fabElevation), modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = shareSelectionActive,
                        enter = if (motionPolicy.allowSpatialTransitions) {
                            fadeIn(tween(220)) + scaleIn(
                                initialScale = 0.86f,
                                animationSpec = tween(220),
                            )
                        } else {
                            fadeIn(tween(220))
                        },
                        exit = if (motionPolicy.allowSpatialTransitions) {
                            fadeOut(tween(180)) + scaleOut(
                                targetScale = 0.86f,
                                animationSpec = tween(180),
                            )
                        } else {
                            fadeOut(tween(180))
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomBarHeight + 10.dp),
                    ) {
                        ShareSelectionFab(
                            allSelected = selectableShareMessageIds.isNotEmpty() &&
                                selectedShareMessageIds.containsAll(selectableShareMessageIds),
                            hasSelection = selectedShareMessageIds.isNotEmpty(),
                            onDismiss = {
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                            .onSizeChanged {
                                if (!isExpanded) bottomBarHeightPx = it.height.toFloat()
                            }
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(8.dp),
                    ) {
                        // This is a sibling behind the complete outer bar, not a child of the
                        // composer. Its lower overflow is therefore occluded by the 28dp Surface
                        // and shadow below.
                        LoopStatusBackdrop(
                            loop = currentLoop,
                            isRunning = currentConversationId in runningLoopIds,
                            onStop = { viewModel.stopCurrentLoop() },
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (isExpanded) Modifier.weight(1f) else Modifier),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            shadowElevation = 8.dp,
                            shape = CHAT_BOTTOM_BAR_OUTER_SHAPE,
                        ) {
                            Box(
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                ChatBottomBar(
                        onSendMessage = { text, attachments, onAccepted ->
                            viewModel.sendMessage(
                                text = text,
                                attachments = attachments,
                                onAccepted = onAccepted,
                            )
                        },
                        onStopGeneration = {
                            haptics.interrupt()
                            viewModel.stopGeneration()
                        },
                        isLoading = isLoading,
                        isCompacting = isCompacting,
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
                        openAiServiceTierAvailable = openAiServiceTierAvailable,
                        openAiServiceTierEnabled = openAiServiceTierEnabled,
                        openAiServiceTier = openAiServiceTier,
                        onCodeExecutionToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(codeExecutionEnabled = enabled) } },
                        onGoogleSearchToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(googleSearchEnabled = enabled) } },
                        onThinkingToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingEnabled = enabled) } },
                        onThinkingLevelChange = { level -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingLevel = level) } },
                        onThinkingBudgetEnabledChange = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetEnabled = enabled) } },
                        onThinkingBudgetTokensChange = { tokens -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetTokens = tokens) } },
                        onOpenAiServiceTierToggle = { enabled ->
                            haptics.toggle(enabled)
                            viewModel.updateConversationSetting(currentConversationId) {
                                it.copy(openAiServiceTierEnabled = enabled)
                            }
                        },
                        onOpenAiServiceTierChange = { tier ->
                            haptics.selection()
                            viewModel.updateConversationSetting(currentConversationId) {
                                it.copy(openAiServiceTier = OpenAiServiceTiers.normalize(tier))
                            }
                        },
                        webSearchEnabled = webSearchEnabled,
                        onWebSearchToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(webSearchEnabled = enabled) } },
                        shellEnabled = shellEnabled,
                        onShellToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(currentConversationId) { it.copy(shellEnabled = enabled) } },
                        // The model row owns its selection tick. Repeating it here produced the
                        // previous double buzz for one physical tap.
                        onModelSelect = { viewModel.setActiveModel(it) },
                        onImageClick = { url -> onMediaClick(listOf(url), 0) },
                        onAllMediaClick = { urls, idx -> onMediaClick(urls, idx) },
                        onFileContentClick = { name, content -> viewModel.showFilePreview(name, content) },
                        modifier = Modifier,
                        textFieldState = textFieldState,
                        composerState = composer,
                        focusRequester = inputFocusRequester,
                        onInputFocusChanged = { focused ->
                            if (composerInputFocused != focused) {
                                composerInputFocused = focused
                            }
                        },
                        isExpanded = isExpanded,
                        isExpandAnimating = isExpandAnimating,
                        onCollapse = { isExpanded = false },
                        onExpand = { isExpanded = true },
                        showWebSearch = globalWebSearch,
                        showShell = shellDevices.isNotEmpty() && globalShell,
                        onPdfPagesClick = { pages, idx -> onPdfPagesClick?.invoke(pages, idx) },
                        onPdfPreviewSelect = { pages, idx -> onPdfPreviewSelect?.invoke(pages, idx) },
                        pdfViewerSelection = pdfViewerSelection,
                        onTogglePdfSelection = onTogglePdfSelection,
                        onInitPdfSelection = onInitPdfSelection,
                        fullScreenViewerUrls = fullScreenViewerUrls,
                        compactDefaultModel = compactModel,
                        compactDefaultPrompt = compactPrompt,
                        compactDefaultRetainCount = compactRetainCount,
                        contextEstimatedTokens = contextUsage.estimatedTokenCount,
                        contextLogicalMessageCount = contextUsage.logicalMessageCount,
                        contextTokenBudget = contextUsage.tokenBudget,
                        hasCompactBoundary = contextUsage.hasCompactBoundary,
                        canCompact = currentConversationId != null && !isLoading && !isSwitching && !isStopping,
                        onCompactClick = {
                            showManualCompactDialog = true
                        },
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
                haptics.destructiveConfirmed()
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

    if (showManualCompactDialog) {
        ChatManualCompactDialog(
            initialModel = compactModel ?: selectedModel,
            initialPrompt = compactPrompt,
            initialRetainCount = compactRetainCount,
            enabledModels = enabledModels,
            modelAliases = modelAliases,
            isCompacting = isCompacting,
            onCompact = { model, prompt, retainCount ->
                showManualCompactDialog = false
                scope.launch {
                    val result = viewModel.compactContextManual(model, prompt, retainCount)
                    if (result is com.newoether.agora.viewmodel.CompactResult.Failed) {
                        viewModel.emitSnackbar(result.message)
                    }
                }
            },
            onDismiss = { showManualCompactDialog = false },
        )
    }
}
