package com.newoether.agora.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.R
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

@Composable
fun VideoPlayer(
    uri: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var contentReady by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (contentReady && !closing) 1f else 0f,
        animationSpec = tween(400)
    )

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun showControlsTemporarily() {
        controlsVisible = true
        scope.launch {
            delay(3000)
            if (isPlaying) controlsVisible = false
        }
    }

    BackHandler(enabled = contentReady && !closing) {
        closing = true
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    contentReady = true
                    durationMs = player.duration.coerceAtLeast(0)
                }
                isPlaying = player.playWhenReady && state == Player.STATE_READY
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                currentPositionMs = new.positionMs.coerceAtLeast(0)
            }
        }
        player.addListener(listener)

        // Poll position while playing
        val job = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    currentPositionMs = player.currentPosition.coerceAtLeast(0)
                }
                delay(200)
            }
        }

        onDispose {
            job.cancel()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(closing) {
        if (closing) {
            delay(400)
            onClose()
        }
    }

    LaunchedEffect(contentReady) {
        if (contentReady) {
            showControlsTemporarily()
        }
    }

    val progress = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f

    fun formatTime(ms: Long): String {
        val s = (ms / 1000)
        val m = s / 60
        val sec = s % 60
        return "${m}:${sec.toString().padStart(2, '0')}"
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Video surface (no built-in controls)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .alpha(alpha)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showControlsTemporarily() }
        ) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Central play/pause button (visible when paused or controls shown)
            AnimatedVisibility(
                visible = !isPlaying || controlsVisible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                IconButton(
                    onClick = {
                        if (isPlaying) player.pause() else player.play()
                        showControlsTemporarily()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // Top gradient + close button
        AnimatedVisibility(
            visible = controlsVisible && !closing,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(
                    onClick = { closing = true },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.provider_close),
                        tint = Color.White
                    )
                }
            }
        }

        // Bottom controls bar
        AnimatedVisibility(
            visible = controlsVisible && !closing,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Seekbar
                Slider(
                    value = progress,
                    onValueChange = { player.seekTo((it * durationMs).roundToLong()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )

                // Time labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(currentPositionMs),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    Text(
                        formatTime(durationMs),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
