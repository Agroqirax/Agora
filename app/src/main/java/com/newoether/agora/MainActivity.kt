package com.newoether.agora

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.room.RoomDatabase
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.ChatBottomBar
import com.newoether.agora.ui.chat.MessageList
import com.newoether.agora.ui.settings.SettingsScreen
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ChatViewModelFactory
import kotlinx.coroutines.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        AgoraForegroundService.createChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val storedVersion = ChatDatabase.getStoredVersion(this)
        val needsErrorDialog = storedVersion > ChatDatabase.CURRENT_VERSION

        val memoryManager = MemoryManager(applicationContext)
        val settingsManager = SettingsManager(applicationContext)

        enableEdgeToEdge()
        setContent {
            AgoraTheme {
                val activity = LocalActivity.current

                if (needsErrorDialog) {
                    AlertDialog(
                        onDismissRequest = { activity?.finish() },
                        title = { Text("Database Incompatible") },
                        text = { Text("The database is incompatible with this version of Agora. Please clear the database and restart the app.") },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text("Quit") }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                applicationContext.deleteDatabase(ChatDatabase.DB_NAME)
                                activity?.recreate()
                            }) { Text("Clear Database") }
                        }
                    )
                } else {
                    val database = ChatDatabase.build(this)
                    val factory = ChatViewModelFactory(application, settingsManager, database.chatDao(), memoryManager)
                    val viewModel: ChatViewModel = viewModel(factory = factory)
                    MainNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun MainNavigation(viewModel: ChatViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var fullScreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatApp(
                viewModel = viewModel,
                onOpenSettings = {
                    showSettings = true
                },
                onImageClick = { url ->
                    fullScreenImageUrl = url
                }
            )

            // Scrim that fades in behind the settings page
            AnimatedVisibility(
                visible = showSettings,
                enter = fadeIn(animationSpec = tween(400)),
                exit = fadeOut(animationSpec = tween(400))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .pointerInput(Unit) {
                            detectTapGestures { showSettings = false }
                        }
                )
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(animationSpec = tween(400)) { it },
                exit = slideOutHorizontally(animationSpec = tween(400)) { it }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            showSettings = false
                        }
                    )

                }
            }

            // Full screen image preview
            AnimatedVisibility(
                visible = fullScreenImageUrl != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                // Keep the last URL for the duration of the exit animation
                var lastUrl by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(fullScreenImageUrl) {
                    if (fullScreenImageUrl != null) lastUrl = fullScreenImageUrl
                }
                
                val url = lastUrl ?: return@AnimatedVisibility
                
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                
                var scale by remember(url) { mutableFloatStateOf(1f) }
                var offsetX by remember(url) { mutableFloatStateOf(0f) }
                var offsetY by remember(url) { mutableFloatStateOf(0f) }
                
                var containerSize by remember { mutableStateOf(Size.Zero) }
                var imageSize by remember { mutableStateOf(Size.Zero) }
                var animationJob by remember { mutableStateOf<Job?>(null) }
                var lastCentroid by remember { mutableStateOf(Offset.Unspecified) }

                fun getMaxOffsets(currentScale: Float): Pair<Float, Float> {
                    if (imageSize == Size.Zero || containerSize == Size.Zero) return 0f to 0f
                    val imageAspectRatio = imageSize.width / imageSize.height
                    val containerAspectRatio = containerSize.width / containerSize.height
                    
                    val contentWidth = if (imageAspectRatio > containerAspectRatio) containerSize.width else containerSize.height * imageAspectRatio
                    val contentHeight = if (imageAspectRatio > containerAspectRatio) containerSize.width / imageAspectRatio else containerSize.height
                    
                    val maxX = (contentWidth * currentScale - containerSize.width).coerceAtLeast(0f) / 2f
                    val maxY = (contentHeight * currentScale - containerSize.height).coerceAtLeast(0f) / 2f
                    return maxX to maxY
                }

                // Helper for rubber-band resistance
                fun rubberBandValue(fullDelta: Float, dimension: Float): Float {
                    if (dimension <= 0f) return 0f
                    val c = 0.45f
                    return (fullDelta * c * dimension) / (dimension + c * fullDelta)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .pointerInput(url) {
                            detectTapGestures(
                                onTap = {
                                    if (scale <= 1.05f) fullScreenImageUrl = null
                                        },
                                onDoubleTap = { tapOffset ->
                                    animationJob?.cancel()
                                    animationJob = scope.launch {
                                        val startScale = scale
                                        val startOffsetX = offsetX
                                        val startOffsetY = offsetY

                                        if (startScale > 1.05f) {
                                            // Zoom Out to 1x
                                            val targetScale = 1f
                                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)

                                            AnimationState(startScale).animateTo(
                                                targetScale,
                                                spring(
                                                    stiffness = Spring.StiffnessMediumLow,
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    visibilityThreshold = 0.001f
                                                )
                                            ) {
                                                scale = value
                                                // Pivot zoom out: shrink towards the tapped point
                                                val r = if (startScale != 0f) value / startScale else 1f
                                                val unconstrainedX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                                val unconstrainedY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)

                                                // Clamp to valid boundaries for the CURRENT scale
                                                val (maxX, maxY) = getMaxOffsets(value)
                                                offsetX = unconstrainedX.coerceIn(-maxX, maxX)
                                                offsetY = unconstrainedY.coerceIn(-maxY, maxY)
                                            }
                                        } else {
                                            // Zoom In to 3x
                                            val targetScale = 3f
                                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)

                                            AnimationState(startScale).animateTo(
                                                targetScale,
                                                spring(
                                                    stiffness = Spring.StiffnessMediumLow,
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    visibilityThreshold = 0.001f
                                                )
                                            ) {
                                                scale = value
                                                val r = if (startScale != 0f) value / startScale else 1f

                                                // Pivot zoom: maintain the tapped point visually stationary
                                                val unconstrainedX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                                val unconstrainedY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
                                                // Clamp to valid boundaries for the CURRENT scale
                                                val (maxX, maxY) = getMaxOffsets(value)
                                                offsetX = unconstrainedX.coerceIn(-maxX, maxX)
                                                offsetY = unconstrainedY.coerceIn(-maxY, maxY)
                                            }
                                        }
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    coil.compose.AsyncImage(
                        model = url,
                        contentDescription = "Full screen image",
                        onSuccess = { state ->
                            imageSize = state.painter.intrinsicSize
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(url) {
                                val velocityTracker = VelocityTracker()
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    animationJob?.cancel()
                                    var pastTouchSlop = false
                                    val touchSlop = viewConfiguration.touchSlop
                                    // Reset centroid for new gesture
                                    lastCentroid = Offset.Unspecified
                                    // Maintain logical state for the duration of this gesture
                                    var logicalScale = scale
                                    var logicalOffsetX = offsetX
                                    var logicalOffsetY = offsetY
                                    do {
                                        val event = awaitPointerEvent()
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        if (!pastTouchSlop) {
                                            val panAmount = panChange.getDistance()
                                            if (zoomChange != 1f || panAmount > touchSlop) {
                                                pastTouchSlop = true
                                            }
                                        }
                                        if (pastTouchSlop) {
                                            val centroid = event.calculateCentroid(useCurrent = false)
                                            if (zoomChange != 1f && centroid != Offset.Unspecified) {
                                                lastCentroid = centroid
                                            }
                                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                                val oldVisualScale = scale
                                                // 1. Update logical scale and map to visual scale
                                                logicalScale = (logicalScale * zoomChange).coerceIn(0.1f, 30f)
                                                val newVisualScale = if (logicalScale < 1f) {
                                                    1f - rubberBandValue(1f - logicalScale, 1f)
                                                } else if (logicalScale > 10f) {
                                                    10f + rubberBandValue(logicalScale - 10f, 5f)
                                                } else {
                                                    logicalScale
                                                }
                                                // 2. Use the visual scale ratio to transform offsets (keeps centroid stable)
                                                val r = if (oldVisualScale != 0f) newVisualScale / oldVisualScale else 1f
                                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                                // Update logical offsets (used for rubber-band calculation)
                                                logicalOffsetX = logicalOffsetX * r + (centroid.x - center.x) * (1f - r) + panChange.x
                                                logicalOffsetY = logicalOffsetY * r + (centroid.y - center.y) * (1f - r) + panChange.y
                                                val (maxX, maxY) = getMaxOffsets(newVisualScale)
                                                scale = newVisualScale
                                                offsetX = if (logicalOffsetX > maxX) maxX + rubberBandValue(logicalOffsetX - maxX, containerSize.width)
                                                else if (logicalOffsetX < -maxX) -maxX - rubberBandValue(-maxX - logicalOffsetX, containerSize.width)
                                                else logicalOffsetX
                                                offsetY = if (logicalOffsetY > maxY) maxY + rubberBandValue(logicalOffsetY - maxY, containerSize.height)
                                                else if (logicalOffsetY < -maxY) -maxY - rubberBandValue(-maxY - logicalOffsetY, containerSize.height)
                                                else logicalOffsetY
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                        }
                                        if (event.changes.size == 1) {
                                            val change = event.changes.first()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        } else {
                                            velocityTracker.resetTracking()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    // Release handler: Snap-back or Fling
                                    val rawVelocity = velocityTracker.calculateVelocity()
                                    val maxV = with(density) { 2500.dp.toPx() }
                                    val velocity = Velocity(
                                        x = if (rawVelocity.x.isNaN()) 0f else rawVelocity.x.coerceIn(-maxV, maxV),
                                        y = if (rawVelocity.y.isNaN()) 0f else rawVelocity.y.coerceIn(-maxV, maxV)
                                    )

                                    animationJob = scope.launch {
                                        val rbCoeff = 0.45f
                                        if (scale < 0.95f || scale > 10.05f) {
                                            val sS = scale
                                            val sX = offsetX
                                            val sY = offsetY
                                            val targetS = scale.coerceIn(1f, 10f)
                                            val (targetMaxX, targetMaxY) = getMaxOffsets(targetS)
                                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                            val pivot = if (lastCentroid != Offset.Unspecified) lastCentroid else center
                                            val targetR = if (sS != 0f) targetS / sS else 1f
                                            val finalPivotX = sX * targetR + (pivot.x - center.x) * (1f - targetR)
                                            val finalPivotY = sY * targetR + (pivot.y - center.y) * (1f - targetR)
                                            val targetX = finalPivotX.coerceIn(-targetMaxX, targetMaxX)
                                            val targetY = finalPivotY.coerceIn(-targetMaxY, targetMaxY)
                                            AnimationState(0f).animateTo(
                                                1f,
                                                spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
                                            ) {
                                                val currentScale = sS + (targetS - sS) * value
                                                scale = currentScale
                                                val r = if (sS != 0f) currentScale / sS else 1f
                                                val pivotOffsetX = sX * r + (pivot.x - center.x) * (1f - r)
                                                val pivotOffsetY = sY * r + (pivot.y - center.y) * (1f - r)
                                                offsetX = pivotOffsetX + (targetX - finalPivotX) * value
                                                offsetY = pivotOffsetY + (targetY - finalPivotY) * value
                                            }
                                        } else {
                                            // Handle axes independently
                                            launch {
                                                val (maxX, _) = getMaxOffsets(scale)
                                                if (offsetX > maxX || offsetX < -maxX) {
                                                    val targetX = offsetX.coerceIn(-maxX, maxX)
                                                    // Use damped velocity for visual snap-back to avoid "kick"
                                                    AnimationState(initialValue = offsetX, initialVelocity = velocity.x * rbCoeff)
                                                        .animateTo(targetX, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                            offsetX = value
                                                        }
                                                } else if (velocity.x != 0f) {
                                                    val decay = splineBasedDecay<Float>(density)
                                                    var hitX = false
                                                    var velX = 0f
                                                    var posX = 0f
                                                    AnimationState(initialValue = offsetX, initialVelocity = velocity.x)
                                                        .animateDecay(decay) {
                                                            val (curMaxX, _) = getMaxOffsets(scale)
                                                            if (value > curMaxX || value < -curMaxX) {
                                                                velX = this.velocity
                                                                posX = value
                                                                hitX = true
                                                                cancelAnimation()
                                                            } else {
                                                                offsetX = value
                                                            }
                                                        }
                                                    if (hitX) {
                                                        val (curMaxX, _) = getMaxOffsets(scale)
                                                        val finalTargetX = posX.coerceIn(-curMaxX, curMaxX)
                                                        AnimationState(initialValue = posX, initialVelocity = velX * rbCoeff)
                                                            .animateTo(finalTargetX, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                                offsetX = value
                                                            }
                                                    }
                                                }
                                            }
                                            launch {
                                                val (_, maxY) = getMaxOffsets(scale)
                                                if (offsetY > maxY || offsetY < -maxY) {
                                                    val targetY = offsetY.coerceIn(-maxY, maxY)
                                                    AnimationState(initialValue = offsetY, initialVelocity = velocity.y * rbCoeff)
                                                        .animateTo(targetY, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                            offsetY = value
                                                        }
                                                } else if (velocity.y != 0f) {
                                                    val decay = splineBasedDecay<Float>(density)
                                                    var hitY = false
                                                    var velY = 0f
                                                    var posY = 0f
                                                    AnimationState(initialValue = offsetY, initialVelocity = velocity.y)
                                                        .animateDecay(decay) {
                                                            val (_, curMaxY) = getMaxOffsets(scale)
                                                            if (value > curMaxY || value < -curMaxY) {
                                                                velY = this.velocity
                                                                posY = value
                                                                hitY = true
                                                                cancelAnimation()
                                                            } else {
                                                                offsetY = value
                                                            }
                                                        }
                                                    if (hitY) {
                                                        val (_, curMaxY) = getMaxOffsets(scale)
                                                        val finalTargetY = posY.coerceIn(-curMaxY, curMaxY)
                                                        AnimationState(initialValue = posY, initialVelocity = velY * rbCoeff)
                                                            .animateTo(finalTargetY, spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)) {
                                                                offsetY = value
                                                            }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    velocityTracker.resetTracking()
                                }
                            }.graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            ),
                        contentScale = ContentScale.Fit
                    )
                    // Close button
                    IconButton(
                        onClick = { fullScreenImageUrl = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }
                
                BackHandler {
                    if (scale > 1.05f) {
                        animationJob?.cancel()
                        animationJob = scope.launch {
                            val startScale = scale
                            val startOffsetX = offsetX
                            val startOffsetY = offsetY
                            AnimationState(0f).animateTo(1f, spring(stiffness = Spring.StiffnessLow)) {
                                scale = startScale + (1f - startScale) * value
                                offsetX = startOffsetX + (0f - startOffsetX) * value
                                offsetY = startOffsetY + (0f - startOffsetY) * value
                            }
                        }
                    } else {
                        fullScreenImageUrl = null
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onImageClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

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
    val messages by viewModel.messages.collectAsState()
    val allMessages by viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val generatingInConversationId by viewModel.generatingInConversationId.collectAsState()
    val selectedModel by viewModel.currentActiveModel.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val modelAliases by viewModel.modelAliases.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val visualizeContextRollout by viewModel.visualizeContextRollout.collectAsState()
    val maxContextWindow by viewModel.maxContextWindow.collectAsState()
    val codeExecutionEnabled by viewModel.codeExecutionEnabled.collectAsState()
    val googleSearchEnabled by viewModel.googleSearchEnabled.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()

    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.activeSystemPromptId.collectAsState()

    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var conversationToRename by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val drawerWidth = configuration.screenWidthDp.dp * 0.8f
    var bottomBarHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    val listState = viewModel.listState
    val textFieldState = rememberSaveable(saver = androidx.compose.foundation.text.input.TextFieldState.Saver) { androidx.compose.foundation.text.input.TextFieldState() }

    // Shared layout state in ViewModel to prevent jumps during transitions
    val messageHeights = viewModel.messageHeights
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    
    // Logic to trigger launch animation for "Ask Agora"
    var showLaunchContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        showLaunchContent = true
    }

    // Logic to scroll to a specific message or the last user message
    suspend fun scrollToLastUserMessage(animate: Boolean = true, targetMessageId: String? = null) {
        if (messages.isEmpty() || viewportHeightPx == 0) return
        
        val targetIndex = if (targetMessageId != null) {
            val msg = messages.find { it.id == targetMessageId }
            if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                messages.indexOfFirst { it.id == msg.parentId }
            } else {
                messages.indexOfFirst { it.id == targetMessageId }
            }
        } else {
            messages.indexOfLast { it.participant == Participant.USER }
        }
        if (targetIndex == -1) return

        with(density) {
            val targetTopPx = 140.dp.toPx()
            val topPaddingPx = 140.dp.toPx()
            
            // Calculate absolute pixel offset of the target position
            var totalHeightBeforePx = 0
            var hasAnyHeight = false
            for (i in 0 until targetIndex) {
                val h = messageHeights[messages[i].id]
                if (h != null) { totalHeightBeforePx += h; hasAnyHeight = true }
            }

            if (!hasAnyHeight && targetIndex > 0) {
                // Heights not measured yet — fall back to item-level scroll
                listState.scrollToItem(targetIndex, 0)
            } else {
                val targetScrollPx = (topPaddingPx + totalHeightBeforePx - targetTopPx).coerceAtLeast(0f)

                if (animate) {
                    var currentOffsetPx = listState.firstVisibleItemScrollOffset.toFloat()
                    for (i in 0 until listState.firstVisibleItemIndex) {
                        if (i < messages.size) {
                            currentOffsetPx += (messageHeights[messages[i].id] ?: 0)
                        }
                    }

                    val diff = targetScrollPx - currentOffsetPx
                    if (kotlin.math.abs(diff) > 2) {
                        listState.animateScrollBy(diff, tween(500, easing = FastOutSlowInEasing))
                    }
                } else {
                    listState.scrollToItem(0, targetScrollPx.toInt())
                }
            }
        }
    }

    val branchSwitchTrigger by viewModel.branchSwitchTrigger.collectAsState()

    // Trigger scroll when conversation changes, requested, or branch switches
    LaunchedEffect(currentConversationId, branchSwitchTrigger) {
        if (currentConversationId != null) {
            // 1. Wait for messages to populate
            snapshotFlow { messages }.filter { it.isNotEmpty() }.first()
            
            val targetIndex = if (branchSwitchTrigger != null) {
                val msg = messages.find { it.id == branchSwitchTrigger }
                if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                    messages.indexOfFirst { it.id == msg.parentId }
                } else {
                    messages.indexOfFirst { it.id == branchSwitchTrigger }
                }
            } else {
                messages.indexOfLast { it.participant == Participant.USER }
            }
            
            if (targetIndex != -1) {
                // 2. PIN the list reactively using snapshotFlow for maximum robustness
                // collectLatest ensures that every time the state changes, we re-scroll and reset the stability delay
                try {
                    withTimeout(4000) {
                        snapshotFlow {
                            val sum = messageHeights.values.sum()
                            Triple(messages, sum, viewportHeightPx)
                        }.collectLatest { data ->
                            val currentMsgs = data.component1()
                            val vHeight = data.component3()

                            val currentTargetIndex = if (branchSwitchTrigger != null) {
                                val msg = currentMsgs.find { it.id == branchSwitchTrigger }
                                if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                                    currentMsgs.indexOfFirst { it.id == msg.parentId }
                                } else {
                                    currentMsgs.indexOfFirst { it.id == branchSwitchTrigger }
                                }
                            } else {
                                currentMsgs.indexOfLast { it.participant == Participant.USER }
                            }

                            if (currentTargetIndex != -1 && vHeight > 0) {
                                with(density) {
                                    var totalHeightBeforePx = 0
                                    for (i in 0 until currentTargetIndex) {
                                        totalHeightBeforePx += messageHeights[currentMsgs[i].id] ?: 0
                                    }
                                    listState.scrollToItem(currentTargetIndex, 0)
                                }
                            }
                            
                            // Wait for 500ms of no changes to consider the layout stable
                            delay(500)
                            this@withTimeout.cancel() // Stable - stop the flow
                        }
                    }
                } catch (e: Exception) {
                    // Timeout or intended cancellation
                }
            }
            viewModel.setSwitching(false)
        } else {
            viewModel.setSwitching(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToMessage.collect { messageId ->
            if (messageId != null) {
                try {
                    withTimeout(2000) {
                        snapshotFlow { messages.indexOfFirst { it.id == messageId } }
                            .filter { it != -1 }
                            .first()
                    }
                } catch (e: Exception) {
                    // Timeout
                }
                kotlinx.coroutines.delay(50)
                scrollToLastUserMessage(animate = true, targetMessageId = messageId)
            } else {
                scrollToLastUserMessage(animate = true)
            }
        }
    }

    BackHandler(enabled = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        scope.launch { drawerState.close() }
    }

    // Collapse expanded text panel and clear focus when drawer opens
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue != DrawerValue.Closed) {
            isExpanded = false
            focusManager.clearFocus()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = DrawerDefaults.scrimColor,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 2.dp,
                modifier = Modifier
                    .width(drawerWidth)
                    .graphicsLayer {
                        // Force the drawer onto its own layer for smoother swipe performance
                        clip = true
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Text("Conversations", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { 
                            viewModel.createNewChat()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSwitching,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Chat")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(conversations) { conversation ->
                            val isSelected = conversation.id == currentConversationId
                            var showMenu by remember { mutableStateOf(false) }
                            var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
                            var lastPosition by remember { mutableStateOf(Offset.Zero) }
                            val density = LocalDensity.current

                            Box {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(CircleShape)
                                        .pointerInput(showMenu) {
                                            if (!showMenu) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        lastPosition = event.changes.first().position
                                                    }
                                                }
                                            }
                                        }
                                        .combinedClickable(
                                            enabled = !isSwitching,
                                            onClick = {
                                                viewModel.selectConversation(conversation.id)
                                                scope.launch { drawerState.close() }
                                            },
                                            onLongClick = {
                                                pressOffset = with(density) {
                                                    // Clamp horizontal offset to prevent menu overflow
                                                    val x = lastPosition.x.toDp().coerceIn(16.dp, 200.dp)
                                                    DpOffset(x, lastPosition.y.toDp() - 28.dp)
                                                }
                                                showMenu = true
                                            }
                                        ),
                                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = conversation.title,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    offset = pressOffset,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Generate Title") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        enabled = !isSwitching && !isLoading,
                                        onClick = {
                                            showMenu = false
                                            viewModel.generateTitle(conversation.id)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        enabled = !isSwitching && !isLoading,
                                        onClick = {
                                            showMenu = false
                                            showRenameDialog = conversation.id
                                            conversationToRename = conversation.title
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = if (!isSwitching && !isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = if (!isSwitching && !isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        enabled = !isSwitching && !isLoading,
                                        onClick = {
                                            showMenu = false
                                            showDeleteConfirmDialog = conversation.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    FilledTonalButton(
                        onClick = {
                            focusManager.clearFocus()
                            onOpenSettings()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Settings")
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .onSizeChanged { viewportHeightPx = it.height }
        ) {
            // Global static background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            TopAppBar(
                                modifier = Modifier.statusBarsPadding(),
                                title = { 
                                    val currentTitle = if (isNewChatMode) "Agora" else conversations.find { it.id == currentConversationId }?.title ?: "Agora"
                                    Column {
                                        if (isNewChatMode) {
                                            Text(currentTitle, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(currentTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        }
                                        if (!isNewChatMode && totalTokens > 0) {
                                            Text("Total: $totalTokens tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { focusManager.clearFocus(); scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { showPromptDialog = true }) {
                                        Icon(Icons.Default.Psychology, contentDescription = "System Prompt")
                                    }
                                    IconButton(onClick = { focusManager.clearFocus(); isExpanded = false; viewModel.createNewChat() }) {
                                        Icon(Icons.Default.Add, contentDescription = "New Chat")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background,
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    val topBarH = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                    val pivotY = ((LocalConfiguration.current.screenHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2f) / LocalConfiguration.current.screenHeightDp
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            val targetNewChat = targetState.first
                            val targetShowLaunch = targetState.second
                            val initialShowLaunch = initialState.second

                            if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialState.first)) {
                                val enterSpec = tween<Float>(700, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f))
                                val fadeInSpec = tween<Float>(500)
                                (fadeIn(animationSpec = fadeInSpec) + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, pivotY), animationSpec = enterSpec))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            } else {
                                // Transition FROM New Chat (or between histories): Simple crossfade
                                fadeIn(animationSpec = tween(300))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            }
                        },
                        label = "MainContentTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (targetNewChat, targetShowLaunch) ->
                        if (!targetNewChat) {
                            MessageList(
                                messages = messages,
                                allMessages = allMessages,
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                                isLoading = isLoading && generatingInConversationId == currentConversationId,
                                isSwitching = isSwitching,
                                visualizeContextRollout = visualizeContextRollout,
                                maxContextWindow = maxContextWindow,
                                modelAliases = modelAliases,
                                bottomBarHeight = bottomBarHeight,
                                viewportHeight = viewportHeightPx,
                                messageHeights = messageHeights,
                                onEditMessage = { id, text ->
                                    val isFirstMessage = messages.isEmpty()
                                    viewModel.editMessage(id, text)
                                    scope.launch {
                                        if (!isFirstMessage) {
                                            kotlinx.coroutines.delay(50)
                                            scrollToLastUserMessage(animate = true)
                                        }
                                    }
                                },
                                onSwitchBranch = { parentId, direction -> viewModel.switchBranch(parentId, direction) },
                                onRegenerate = { id -> 
                                    viewModel.regenerate(id)
                                    scope.launch {
                                        kotlinx.coroutines.delay(50)
                                        scrollToLastUserMessage(animate = true)
                                    }
                                },
                                onImageClick = onImageClick,
                                contentPadding = PaddingValues(
                                    start = 8.dp, 
                                    end = 8.dp, 
                                    top = 140.dp, 
                                    bottom = bottomBarHeight + 8.dp
                                )
                            )
                        } else if (targetShowLaunch) {
                            // Show "New Chat" Interface (Refined Manifesto Style)
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
                                    // Welcome - positioned from top
                                    Text(
                                        "Welcome to Agora.",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(top = ((LocalConfiguration.current.screenHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2).dp)
                                    )
                                }
                            }
                        } else {
                            // Empty box while waiting for launch animation trigger
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    // Loading overlay for chat switching
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
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Solid Bottom Bar with surface color extending to bottom
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { 
                        bottomBarHeightPx = it.height.toFloat()
                    },
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 16.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    ChatBottomBar(
                        onSendMessage = { text, images ->
                            viewModel.sendMessage(text, images)
                            scope.launch {
                                delay(100)
                                scrollToLastUserMessage(animate = true)
                            }
                        },
                        onStopGeneration = { viewModel.stopGeneration() },
                        isLoading = isLoading,
                        isSwitching = isSwitching,
                        enabledModels = enabledModels,
                        selectedModel = selectedModel,
                        modelAliases = modelAliases,
                        codeExecutionEnabled = codeExecutionEnabled,
                        googleSearchEnabled = googleSearchEnabled,
                        thinkingEnabled = thinkingEnabled,
                        onCodeExecutionToggle = { viewModel.setCodeExecutionEnabled(it) },
                        onGoogleSearchToggle = { viewModel.setGoogleSearchEnabled(it) },
                        onThinkingToggle = { viewModel.setThinkingEnabled(it) },
                        webSearchEnabled = webSearchEnabled,
                        onWebSearchToggle = { viewModel.setWebSearchEnabled(it) },
                        onModelSelect = { viewModel.setActiveModel(it) },
                        onImageClick = onImageClick,
                        modifier = Modifier,
                        textFieldState = textFieldState,
                        isExpanded = isExpanded,
                        onCollapse = { isExpanded = false },
                        onExpand = { isExpanded = true }
                    )
                }
            }
        }
    }

    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Chat") },
            text = {
                val fm = androidx.compose.ui.platform.LocalFocusManager.current
                Box(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                    value = conversationToRename,
                    onValueChange = { conversationToRename = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(showRenameDialog!!, conversationToRename)
                    showRenameDialog = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Chat?") },
            text = { Text("Are you sure you want to delete this conversation? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(showDeleteConfirmDialog!!)
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPromptDialog) {
        val currentConversation = conversations.find { it.id == currentConversationId }
        val pendingPrompt by viewModel.pendingSystemPromptId.collectAsState()
        var selectedPromptId by remember(currentConversationId, pendingPrompt, currentConversation?.systemPromptId) { 
            mutableStateOf(if (isNewChatMode) pendingPrompt else currentConversation?.systemPromptId) 
        }

        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text("Conversation Prompt") },
            text = {
                LazyColumn {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selectedPromptId = null }.padding(8.dp)
                        ) {
                            RadioButton(
                                selected = selectedPromptId == null,
                                onClick = { selectedPromptId = null }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val globalDefaultTitle = systemPrompts.find { it.id == activeSystemPromptId }?.title ?: "None"
                            Text("Global Default ($globalDefaultTitle)")
                        }
                    }
                    items(systemPrompts) { prompt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selectedPromptId = prompt.id }.padding(8.dp)
                        ) {
                            RadioButton(
                                selected = selectedPromptId == prompt.id,
                                onClick = { selectedPromptId = prompt.id }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(prompt.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isNewChatMode) {
                        viewModel.setPendingSystemPrompt(selectedPromptId)
                    } else {
                        currentConversationId?.let { id ->
                            viewModel.setConversationSystemPrompt(id, selectedPromptId)
                        }
                    }
                    showPromptDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
