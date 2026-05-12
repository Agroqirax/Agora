package com.newoether.agora

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.ChatApp
import com.newoether.agora.ui.chat.ChatBottomBar
import com.newoether.agora.ui.components.TypewriterText
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

    override fun attachBaseContext(newBase: Context) {
        val langCode = kotlinx.coroutines.runBlocking {
            SettingsManager(newBase).appLanguage.first()
        }
        val locale = when (langCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> null
        }
        if (locale != null) {
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        com.newoether.agora.util.DebugLog.init(this)
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
                        title = { Text(stringResource(R.string.database_incompatible)) },
                        text = { Text(stringResource(R.string.database_incompatible_desc)) },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.quit)) }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                applicationContext.deleteDatabase(ChatDatabase.DB_NAME)
                                activity?.recreate()
                            }) { Text(stringResource(R.string.clear_database)) }
                        }
                    )
                } else {
                    val database = ChatDatabase.build(this)
                    val factory = ChatViewModelFactory(application, settingsManager, database.chatDao(), memoryManager, this@MainActivity)
                    val viewModel: ChatViewModel = viewModel(factory = factory)
                    MainNavigation(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.isInForeground = true
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.isInForeground = false
    }
}

@Composable
fun MainNavigation(viewModel: ChatViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var fullScreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val pdfPages by viewModel.previewPdfPages.collectAsState()
    val pdfIndex by viewModel.previewPdfIndex.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                event.onAction?.invoke()
            }
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
                    focusManager.clearFocus()
                    fullScreenImageUrl = url
                },
                onFileContentClick = { name, content ->
                    focusManager.clearFocus()
                    viewModel.showFilePreview(name, content)
                },
                onPdfPagesClick = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.showPdfPreview(pages, idx)
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
                val context = androidx.compose.ui.platform.LocalContext.current
                val mimeType = remember(url) {
                    try { context.contentResolver.getType(android.net.Uri.parse(url)) } catch (_: Exception) { null }
                }
                val isVideo = mimeType?.startsWith("video/") == true ||
                    url.endsWith(".mp4", true) || url.endsWith(".webm", true) ||
                    url.endsWith(".mov", true) || url.endsWith(".avi", true) ||
                    url.contains("vid_original_")

                if (isVideo) {
                    com.newoether.agora.ui.chat.VideoPlayer(
                        uri = url,
                        onClose = { fullScreenImageUrl = null }
                    )
                } else {
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
                        contentDescription = stringResource(R.string.full_screen_image),
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
                    // PDF page counter and navigation
                    val pdfPageIndex = pdfPages.indexOf(url)
                    if (pdfPageIndex >= 0) {
                        Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.TopStart).padding(12.dp).statusBarsPadding()) {
                            Text("${pdfPageIndex + 1} / ${pdfPages.size}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                        if (pdfPageIndex > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(0.2f).align(Alignment.CenterStart).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fullScreenImageUrl = pdfPages[pdfPageIndex - 1] })
                        if (pdfPageIndex < pdfPages.size - 1) Box(Modifier.fillMaxHeight().fillMaxWidth(0.2f).align(Alignment.CenterEnd).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fullScreenImageUrl = pdfPages[pdfPageIndex + 1] })
                    }

                    // Close button
                    IconButton(
                        onClick = {
                            if (pdfPages.isNotEmpty()) viewModel.clearPreviews()
                            fullScreenImageUrl = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.provider_close),
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

            // Pipe PDF pages through the image viewer
            LaunchedEffect(pdfPages, pdfIndex) {
                if (pdfPages.isNotEmpty()) fullScreenImageUrl = pdfPages[pdfIndex.coerceIn(0, pdfPages.size - 1)]
            }

            // Text file viewer
            val fileContent by viewModel.previewFileContent.collectAsState()
            val fileName by viewModel.previewFileName.collectAsState()
            if (fileContent != null && fileName != null) {
                com.newoether.agora.ui.chat.TextFileViewer(content = fileContent!!, fileName = fileName!!, onClose = { viewModel.clearPreviews() })
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
