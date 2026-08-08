package com.newoether.agora.ui.chat.message

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.theme.ChatType

/** One sheet owns both the virtualized segment index and its detail page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingSegmentsSheet(
    message: ChatMessage,
    initialSegmentIndex: Int,
    isStreaming: Boolean,
    markdownRenderContext: ChatMarkdownRenderContext,
    onMediaClick: (List<String>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val segments = remember(message.segments) {
        mergeAdjacentSegments(message.segments.orEmpty()).filter { it.type != "answer" }
    }
    var detailIndex by rememberSaveable(message.id) {
        mutableIntStateOf(initialSegmentIndex.takeIf { it in segments.indices } ?: -1)
    }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LaunchedEffect(initialSegmentIndex, segments.size) {
        if (initialSegmentIndex in segments.indices) detailIndex = initialSegmentIndex
        else if (detailIndex !in segments.indices) detailIndex = -1
    }
    ModalBottomSheet(
        // Back is the only detail -> list transition. A swipe/scrim dismissal has already hidden
        // the Material sheet, so keeping the host composable alive there would leave an invisible
        // sheet that cannot be reopened from the same message.
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        BackHandler(enabled = detailIndex >= 0) { detailIndex = -1 }
        DialogWindowEdgeToEdge()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp)
                .navigationBarsPadding(),
        ) {
            if (detailIndex < 0) {
                Text(
                    text = stringResource(R.string.thinking_segments_title),
                    style = ChatType.detailTitle,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = segments,
                        key = { index, segment ->
                            "${message.id}:$index:${segment.type}:${segment.toolCallId.orEmpty()}"
                        },
                    ) { index, segment ->
                        ListItem(
                            headlineContent = {
                                Text(segmentDetailTitle(segment, segments, index))
                            },
                            supportingContent = {
                                segmentPreview(segment)?.let { preview ->
                                    Text(preview, maxLines = 2, style = ChatType.meta)
                                }
                            },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { detailIndex = index },
                        )
                    }
                }
            } else {
                val segment = segments.getOrNull(detailIndex)
                if (segment == null) {
                    detailIndex = -1
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { detailIndex = -1 }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                        Text(
                            text = segmentDetailTitle(segment, segments, detailIndex),
                            style = ChatType.detailTitle,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(48.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (segment.type == "tool") {
                            ToolDetailContent(segment = segment, onMediaClick = onMediaClick)
                        } else if (segment.type == "transcription" && segment.content.isBlank()) {
                            Text(
                                text = "Image transcription is empty.",
                                style = ChatType.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            StreamingMarkdownDocument(
                                content = segment.content,
                                isStreaming = isStreaming && detailIndex == segments.lastIndex,
                                renderContext = markdownRenderContext,
                                modifier = Modifier.fillMaxWidth(),
                                selectionEnabled = !isStreaming,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private fun segmentPreview(segment: MessageSegment): String? = when (segment.type) {
    "tool" -> segment.toolResultText ?: segment.toolResult ?: segment.toolArgs
    else -> segment.content
}.orEmpty().trim().replace(Regex("\\s+"), " ").take(140).takeIf(String::isNotBlank)
