package com.newoether.agora.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

data class VideoSliceResult(
    val uri: String,
    val frameCount: Int,
    val intervalMs: Long
)

object VideoSliceDefaults {
    fun defaultFrameCount(durationMs: Long): Int {
        val seconds = durationMs / 1000
        return when {
            seconds < 10 -> 3
            seconds < 30 -> 5
            seconds < 60 -> 8
            else -> maxOf(2, minOf(20, (seconds / 5).toInt()))
        }
    }
}

@Composable
fun VideoSliceDialog(
    videoUri: String,
    durationMs: Long,
    onConfirm: (VideoSliceResult) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val seconds = durationMs / 1000
    val defaultFrames = remember(durationMs) { VideoSliceDefaults.defaultFrameCount(durationMs) }

    var useFrameCountMode by remember { mutableStateOf(true) }
    var frameCount by remember { mutableIntStateOf(defaultFrames) }
    var intervalSec by remember(durationMs) {
        mutableIntStateOf(maxOf(1, (seconds / defaultFrames).toInt()))
    }

    // Keep the two in sync
    val effectiveFrameCount = if (useFrameCountMode) frameCount else maxOf(2, (seconds / intervalSec).toInt())
    val effectiveIntervalMs = if (useFrameCountMode) {
        if (frameCount > 1) durationMs / frameCount else 0L
    } else {
        intervalSec * 1000L
    }

    // First frame thumbnail
    val thumbnail = remember(videoUri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, android.net.Uri.parse(videoUri))
            val bitmap = retriever.frameAtTime
            retriever.release()
            bitmap?.let { Bitmap.createScaledBitmap(it, 200, (200f * it.height / it.width).roundToInt(), true) }
                ?.also { if (it != bitmap) bitmap.recycle() }
        } catch (_: Exception) { null }
    }

    val durationFormatted = remember(durationMs) {
        val m = seconds / 60
        val s = seconds % 60
        "${m}:${s.toString().padStart(2, '0')}"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Title
                Text(
                    "Video Frame Extraction",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Duration: $durationFormatted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // Thumbnail preview
                if (thumbnail != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = useFrameCountMode,
                        onClick = { useFrameCountMode = true },
                        label = { Text("By Frame Count") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !useFrameCountMode,
                        onClick = { useFrameCountMode = false },
                        label = { Text("By Interval") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Slider
                if (useFrameCountMode) {
                    Text(
                        "Frames: $effectiveFrameCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = frameCount.toFloat(),
                        onValueChange = { frameCount = it.roundToInt().coerceIn(2, 20) },
                        valueRange = 2f..20f,
                        steps = 17
                    )
                    Text(
                        "~${(effectiveIntervalMs / 1000f).let { if (it < 1) "${(it * 1000).roundToInt()}ms" else "${it.roundToInt()}s" }} between frames",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Interval: ${effectiveIntervalMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = intervalSec.toFloat(),
                        onValueChange = { intervalSec = it.roundToInt().coerceIn(1, minOf(30, seconds.toInt())) },
                        valueRange = 1f..minOf(30f, seconds.toFloat()).coerceAtLeast(2f),
                        steps = minOf(29, seconds.toInt() - 1)
                    )
                    Text(
                        "${effectiveFrameCount} frames",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onConfirm(VideoSliceResult(videoUri, effectiveFrameCount, effectiveIntervalMs))
                    }) {
                        Text("Extract $effectiveFrameCount frames")
                    }
                }
            }
        }
    }
}
