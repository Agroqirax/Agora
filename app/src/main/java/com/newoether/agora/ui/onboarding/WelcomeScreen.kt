package com.newoether.agora.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

data class WelcomePage(
    val title: String,
    val description: String,
    val darkVideoResId: Int? = null,
    val lightVideoResName: String? = null
)

private fun resolveVideoRes(isDarkTheme: Boolean, darkResId: Int?, lightResName: String?, context: android.content.Context): Int? {
    if (isDarkTheme) return darkResId
    if (lightResName != null) {
        val id = context.resources.getIdentifier(lightResName, "raw", context.packageName)
        if (id != 0) return id
    }
    return darkResId // fallback to dark if light not found
}

@Composable
fun WelcomeScreen(onComplete: () -> Unit, isDarkTheme: Boolean = true) {
    val context = LocalContext.current
    val pages = listOf(
        WelcomePage(
            title = "Welcome to Agora",
            description = "A BYOK app that takes back your data sovereignty.",
            darkVideoResId = R.raw.welcome_video_1,
            lightVideoResName = "welcome_video_1_light"
        ),
        WelcomePage(
            title = "Bring Your Own Key",
            description = "Set up your API keys or import a GGUF model to unlock on-device inference.",
            darkVideoResId = R.raw.welcome_video_2,
            lightVideoResName = "welcome_video_2_light"
        ),
        WelcomePage(
            title = "Choose Your Model",
            description = "Connect to 8+ AI providers or run models locally with llama.cpp.",
            darkVideoResId = R.raw.welcome_video_3,
            lightVideoResName = "welcome_video_3_light"
        ),
        WelcomePage(
            title = "You're All Set!",
            description = "Start chatting with full control over your data and privacy.",
            darkVideoResId = R.raw.welcome_video_4,
            lightVideoResName = "welcome_video_4_light"
        )
    )

    // Preload all video players — prepare but don't play until first visit
    val players = remember {
        pages.map { page ->
            val resId = resolveVideoRes(isDarkTheme, page.darkVideoResId, page.lightVideoResName, context)
            resId?.let {
                val uri = "android.resource://${context.packageName}/$it"
                ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    playWhenReady = false
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { players.forEach { it?.release() } }
    }

    val visitedPages = remember { mutableSetOf<Int>() }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var exiting by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(600)
    )

    // Start playback on first visit to each page
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage !in visitedPages) {
            visitedPages.add(pagerState.currentPage)
            players[pagerState.currentPage]?.playWhenReady = true
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        showContent = true
    }

    LaunchedEffect(exiting) {
        if (exiting) {
            kotlinx.coroutines.delay(300)
            onComplete()
        }
    }

    AnimatedVisibility(
        visible = !exiting,
        exit = fadeOut(animationSpec = tween(300))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, end = 16.dp)
                        .alpha(contentAlpha),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(
                        onClick = { if (pagerState.currentPage < pages.size - 1) exiting = true },
                        enabled = showContent && pagerState.currentPage < pages.size - 1,
                        modifier = Modifier.alpha(if (pagerState.currentPage < pages.size - 1) 1f else 0f)
                    ) {
                        Text("Skip")
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    userScrollEnabled = showContent,
                    beyondViewportPageCount = 1
                ) { index ->
                    val page = pages[index]
                    val pageOffset = (index - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                    val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)
                    val scale = 1f - (absOffset * 0.12f)
                    val pageAlpha = 1f - (absOffset * 0.4f)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = pageAlpha
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1.8f),
                            contentAlignment = Alignment.Center
                        ) {
                        players[index]?.let { LoopVideo(player = it) }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).alpha(contentAlpha)) {
                            AnimatedContent(
                                targetState = page.title,
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = { fadeIn() togetherWith fadeOut() }
                            ) { title ->
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            AnimatedContent(
                                targetState = page.description,
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = { fadeIn() togetherWith fadeOut() }
                            ) { desc ->
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(48.dp))
                    }
                }

                Row(
                    modifier = Modifier.padding(bottom = 16.dp).alpha(contentAlpha),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pages.size) { idx ->
                        val isSelected = pagerState.currentPage == idx
                        val dotSize by animateDpAsState(
                            targetValue = if (isSelected) 10.dp else 8.dp,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 48.dp)
                        .navigationBarsPadding()
                        .alpha(contentAlpha)
                ) {
                    val isLastPage = pagerState.currentPage == pages.size - 1
                    Button(
                        onClick = {
                            if (isLastPage) {
                                exiting = true
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(500, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                        enabled = showContent,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = if (isLastPage) "Get Started" else "Continue",
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    } // AnimatedVisibility
}

@Composable
private fun LoopVideo(player: ExoPlayer) {
    val context = LocalContext.current
    var isReady by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = tween(400)
    )

    DisposableEffect(player) {
        // Player may already be ready (preloaded) — check immediately
        if (player.playbackState == Player.STATE_READY) {
            isReady = true
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isReady = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = Modifier.fillMaxSize().aspectRatio(1f).alpha(alpha)
    )
}
