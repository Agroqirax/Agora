package com.newoether.agora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.components.ChatBottomBar
import com.newoether.agora.ui.components.MessageList
import com.newoether.agora.ui.screens.SettingsScreen
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
        
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN selectedBranchesJson TEXT")
            }
        }
        
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTimeMs INTEGER")
            }
        }

        val database = Room.databaseBuilder(
            applicationContext,
            ChatDatabase::class.java,
            "agora_db"
        )
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .build()
        val settingsManager = SettingsManager(applicationContext)

        enableEdgeToEdge()
        setContent {
            AgoraTheme {
                val factory = ChatViewModelFactory(application, settingsManager, database.chatDao())
                val viewModel: ChatViewModel = viewModel(factory = factory)
                MainNavigation(viewModel)
            }
        }
    }
}

@Composable
fun SloganItem(title: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            lineHeight = 20.sp
        )
    }
}

@Composable
fun MainNavigation(viewModel: ChatViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var fullScreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    
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

                    BackHandler {
                        showSettings = false
                    }
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
                    val c = 0.75f
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
                                                offsetX = startOffsetX * r + (tapOffset.x - center.x) * (1f - r)
                                                offsetY = startOffsetY * r + (tapOffset.y - center.y) * (1f - r)
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
                                    val (boundX, boundY) = getMaxOffsets(scale)
                                    val isOutOfBounds = scale < 1f || scale > 10f ||
                                            offsetX > boundX || offsetX < -boundX ||
                                            offsetY > boundY || offsetY < -boundY

                                    // Calculate velocity before launching the coroutine to avoid race with resetTracking
                                    val rawVelocity = velocityTracker.calculateVelocity()
                                    val velocity = Velocity(
                                        x = if (rawVelocity.x.isNaN()) 0f else rawVelocity.x,
                                        y = if (rawVelocity.y.isNaN()) 0f else rawVelocity.y
                                    )
                                    
                                    animationJob = scope.launch {
                                        if (isOutOfBounds) {
                                            val sS = scale
                                            val sX = offsetX
                                            val sY = offsetY

                                            val targetS = scale.coerceIn(1f, 10f)
                                            val (targetMaxX, targetMaxY) = getMaxOffsets(targetS)

                                            // If we have a pivot from the gesture, use it to make the zoom feel natural
                                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                            val pivot = if (lastCentroid != Offset.Unspecified) lastCentroid else center

                                            // Calculate where the offset SHOULD be at the target scale to keep the pivot stationary
                                            val targetR = if (sS != 0f) targetS / sS else 1f
                                            val finalPivotX = sX * targetR + (pivot.x - center.x) * (1f - targetR)
                                            val finalPivotY = sY * targetR + (pivot.y - center.y) * (1f - targetR)
                                            // The actual target is the pivot-based destination, clamped to valid boundaries
                                            val targetX = finalPivotX.coerceIn(-targetMaxX, targetMaxX)
                                            val targetY = finalPivotY.coerceIn(-targetMaxY, targetMaxY)

                                            AnimationState(0f).animateTo(
                                                1f,
                                                spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                                            ) {
                                                val currentScale = sS + (targetS - sS) * value
                                                scale = currentScale

                                                val r = if (sS != 0f) currentScale / sS else 1f
                                                // Calculate the "ideal" offset at this point in the animation if we were strictly pivoting
                                                val pivotOffsetX = sX * r + (pivot.x - center.x) * (1f - r)
                                                val pivotOffsetY = sY * r + (pivot.y - center.y) * (1f - r)

                                                // Interpolate from the ideal pivot path to the actual (clamped) target
                                                // This adds a smooth "slide" correction if the pivot target was out of bounds
                                                offsetX = pivotOffsetX + (targetX - finalPivotX) * value
                                                offsetY = pivotOffsetY + (targetY - finalPivotY) * value
                                            }
                                        } else if (scale > 1f) {
                                            val decay = splineBasedDecay<Offset>(density)
                                            var hitBoundary = false
                                            var velocityAtBoundary = Offset.Zero
                                            var positionAtBoundary = Offset.Zero
                                            
                                            // 1. Decay until we hit a boundary
                                            AnimationState(
                                                typeConverter = Offset.VectorConverter,
                                                initialValue = Offset(offsetX, offsetY),
                                                initialVelocity = Offset(velocity.x, velocity.y)
                                            ).animateDecay(decay) {
                                                val (maxX, maxY) = getMaxOffsets(scale)
                                                if (value.x > maxX || value.x < -maxX || value.y > maxY || value.y < -maxY) {
                                                    val rawV = this.velocity
                                                    velocityAtBoundary = Offset(
                                                        x = if (rawV.x.isNaN()) 0f else rawV.x,
                                                        y = if (rawV.y.isNaN()) 0f else rawV.y
                                                    )
                                                    positionAtBoundary = value
                                                    hitBoundary = true
                                                    cancelAnimation()
                                                } else {
                                                    offsetX = value.x
                                                    offsetY = value.y
                                                }
                                            }
                                            
                                            // 2. If hit, hand off to a spring animation (target is the boundary)
                                            // A spring with initial velocity and DampingRatioNoBouncy will overshoot and pull back.
                                            if (hitBoundary) {
                                                val (maxX, maxY) = getMaxOffsets(scale)
                                                val targetX = positionAtBoundary.x.coerceIn(-maxX, maxX)
                                                val targetY = positionAtBoundary.y.coerceIn(-maxY, maxY)
                                                
                                                AnimationState(
                                                    typeConverter = Offset.VectorConverter,
                                                    initialValue = positionAtBoundary,
                                                    initialVelocity = velocityAtBoundary
                                                ).animateTo(
                                                    targetValue = Offset(targetX, targetY),
                                                    animationSpec = spring(
                                                        stiffness = Spring.StiffnessMediumLow,
                                                        dampingRatio = Spring.DampingRatioNoBouncy
                                                    )
                                                ) {
                                                    offsetX = when {
                                                        value.x > maxX -> maxX + rubberBandValue(value.x - maxX, containerSize.width)
                                                        value.x < -maxX -> -maxX - rubberBandValue(-maxX - value.x, containerSize.width)
                                                        else -> value.x
                                                    }
                                                    offsetY = when {
                                                        value.y > maxY -> maxY + rubberBandValue(value.y - maxY, containerSize.height)
                                                        value.y < -maxY -> -maxY - rubberBandValue(-maxY - value.y, containerSize.height)
                                                        else -> value.y
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    velocityTracker.resetTracking()
                                }
                            }
                            .graphicsLayer(
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    val conversations by viewModel.conversations.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val allMessages by viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
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

    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var conversationToRename by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
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
            messages.indexOfFirst { it.id == targetMessageId }
        } else {
            messages.indexOfLast { it.participant == Participant.USER }
        }
        if (targetIndex == -1) return

        with(density) {
            val targetTopPx = 140.dp.toPx()
            val topPaddingPx = 140.dp.toPx()
            
            // Calculate absolute pixel offset of the target position
            var totalHeightBeforePx = 0
            for (i in 0 until targetIndex) {
                totalHeightBeforePx += messageHeights[messages[i].id] ?: 0
            }
            
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
                // Use the exact same target pixel offset for jumping
                listState.scrollToItem(0, targetScrollPx.toInt())
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
                messages.indexOfFirst { it.id == branchSwitchTrigger }
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
                                currentMsgs.indexOfFirst { it.id == branchSwitchTrigger }
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
                        
                        // Wait for layout heights to register
                        snapshotFlow { messageHeights[messageId] }
                            .filter { it != null && it > 0 }
                            .first()
                            
                        scrollToLastUserMessage(animate = true, targetMessageId = messageId)
                    }
                } catch (e: Exception) {
                    // Timeout
                }
            } else {
                scrollToLastUserMessage(animate = true)
            }
        }
    }

    BackHandler(enabled = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        scope.launch { drawerState.close() }
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
                    Text("Chat History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        enabled = !isSwitching,
                                        onClick = {
                                            showMenu = false
                                            showRenameDialog = conversation.id
                                            conversationToRename = conversation.title
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = if (!isSwitching) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = if (!isSwitching) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        enabled = !isSwitching,
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
                                    val currentTitle = if (isNewChatMode) "Agora Chat" else conversations.find { it.id == currentConversationId }?.title ?: "Agora Chat"
                                    Column {
                                        Text(currentTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) 
                                        if (!isNewChatMode && totalTokens > 0) {
                                            Text("Total: $totalTokens tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { viewModel.createNewChat() }) {
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
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            val targetNewChat = targetState.first
                            val targetShowLaunch = targetState.second
                            val initialShowLaunch = initialState.second
                            
                            if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialState.first)) {
                                // Transition TO New Chat (or at Launch): Fade out history, Scale in prompt
                                // Less extreme initial velocity, smooth finish
                                val enterSpec = tween<Float>(700, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f))
                                val fadeInSpec = tween<Float>(500)
                                (fadeIn(animationSpec = fadeInSpec) + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 0.45f), animationSpec = enterSpec))
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
                                isLoading = isLoading,
                                isSwitching = isSwitching,
                                visualizeContextRollout = visualizeContextRollout,
                                maxContextWindow = maxContextWindow,
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
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 32.dp)
                                ) {
                                    // Padding for Top Bar
                                    Spacer(modifier = Modifier.height(140.dp))

                                    // App Logo (Transparent Large)
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.agora_transparent_large),
                                        contentDescription = "Agora Logo",
                                        modifier = Modifier
                                            .size(100.dp)
                                    )

                                    Spacer(modifier = Modifier.height(20.dp))

                                    // Brand & Mission
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "AGORA",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 4.sp,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "AN OPEN SOURCE AI CLIENT",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 4.sp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(48.dp))

                                    // Slogan Section
                                    Text(
                                        text = "Access powerful models without the guardrails of corporate silos",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        lineHeight = 24.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(horizontal = 48.dp)
                                    )

                                    Spacer(modifier = Modifier.height(48.dp))

                                    // Suggestion Chips (More organic layout)
                                    Text(
                                        "START A CONVERSATION",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val suggestions = listOf(
                                            "Summarize article" to "Summarize this article: ",
                                            "Code a script" to "Write a python script for ",
                                            "Travel plan" to "Plan a 5-day trip to Tokyo",
                                            "Explain physics" to "Explain quantum physics to me"
                                        )

                                        suggestions.chunked(2).forEach { rowItems ->
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                rowItems.forEach { (label, prompt) ->
                                                    SuggestionChip(
                                                        onClick = { 
                                                            textFieldState.edit { 
                                                                replace(0, length, prompt)
                                                            }
                                                        },
                                                        label = { 
                                                            Text(
                                                                text = label,
                                                                style = MaterialTheme.typography.labelLarge,
                                                                fontWeight = FontWeight.SemiBold,
                                                                modifier = Modifier.padding(horizontal = 4.dp)
                                                            ) 
                                                        },
                                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                        ),
                                                        border = null,
                                                        shape = CircleShape
                                                    )
                                                }
                                            }
                                        }
                                    }
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
                        },
                        onStopGeneration = { viewModel.stopGeneration() },
                        isLoading = isLoading,
                        isSwitching = isSwitching,
                        enabledModels = enabledModels,
                        selectedModel = selectedModel,
                        modelAliases = modelAliases,
                        codeExecutionEnabled = codeExecutionEnabled,
                        googleSearchEnabled = googleSearchEnabled,
                        onCodeExecutionToggle = { viewModel.setCodeExecutionEnabled(it) },
                        onGoogleSearchToggle = { viewModel.setGoogleSearchEnabled(it) },
                        onModelSelect = { viewModel.setSelectedModel(it) },
                        onOpenSettings = onOpenSettings,
                        onImageClick = onImageClick,
                        modifier = Modifier,
                        textFieldState = textFieldState
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
                TextField(
                    value = conversationToRename,
                    onValueChange = { conversationToRename = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
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
}
