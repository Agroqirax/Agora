package com.newoether.agora.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.FileProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.hasActiveAnswerSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val INLINE_SHARE_LIMIT_BYTES = 256 * 1024
private const val STREAM_SCROLL_RESUME_DELAY_MS = 160L

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
internal fun rememberScrollIsolatedMessages(
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

internal suspend fun launchConversationShare(
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
internal fun AnsweringHapticEffect(
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
