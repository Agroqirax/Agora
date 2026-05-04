package com.newoether.agora.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow

private fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
    val merged = mutableListOf<MessageSegment>()
    for (seg in segs) {
        val last = merged.lastOrNull()
        if (last != null && last.type == seg.type && seg.type == "thought") {
            merged[merged.lastIndex] = last.copy(content = last.content + seg.content)
        } else {
            merged.add(seg)
        }
    }
    return merged
}

private fun toolDisplayName(toolName: String?): String {
    return when (toolName) {
        "list_memory_files" -> "Look Up Memories"
        "read_memory_file" -> "Read Memory"
        "create_memory_file" -> "Add Memory"
        "edit_memory_file" -> "Edit Memory"
        "delete_memory_file" -> "Delete Memory"
        "update_active_memory" -> "Update Active Memory"
        else -> (toolName ?: "Tool").split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }
}

private fun toolSummary(seg: MessageSegment): String {
    val name = seg.toolName ?: ""
    val argsJson = try { Json.parseToJsonElement(seg.toolArgs ?: "{}").jsonObject } catch (_: Exception) { null }
    val fileName = argsJson?.get("name")?.let { (it as? JsonPrimitive)?.content }
        ?: argsJson?.get("names")?.let { names ->
            val arr = names as? kotlinx.serialization.json.JsonArray
            if (arr != null && arr.size == 1) (arr[0] as? JsonPrimitive)?.content else null
        }
    val nameCount = argsJson?.get("names")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size }
    val content = seg.toolResult ?: ""
    val isError = content.startsWith("Error")
    val fileCount = Regex("Memory files:\\s*\\n((?:- .+\\n?)*)").find(content)?.groupValues?.get(1)?.lines()?.count { it.isNotBlank() }
    return when (name) {
        "read_memory_file" -> when {
            isError -> content.lines().firstOrNull()?.take(100) ?: "Read memory"
            nameCount != null && nameCount > 1 -> "Read $nameCount memories"
            fileName != null -> "Read $fileName"
            else -> "Read memory"
        }
        "create_memory_file" -> when {
            isError -> content.lines().firstOrNull()?.take(100) ?: "Save memory failed"
            fileName != null -> "Saved $fileName"
            else -> "Saved a memory"
        }
        "edit_memory_file" -> when {
            isError -> content.lines().firstOrNull()?.take(100) ?: "Edit memory failed"
            fileName != null -> "Updated $fileName"
            else -> "Updated a memory"
        }
        "delete_memory_file" -> when {
            isError -> content.lines().firstOrNull()?.take(100) ?: "Remove memory failed"
            fileName != null -> "Removed $fileName"
            else -> "Removed a memory"
        }
        "list_memory_files" -> if (isError) "Look up memories failed" else (if (fileCount != null) "Looked through $fileCount saved memories" else "Looked through saved memories")
        "update_active_memory" -> if (isError) "Update active memory failed" else "Updated active memory"
        else -> content.lines().firstOrNull()?.take(100) ?: "Done"
    }
}

private fun toolResultSummary(toolName: String, toolArgs: String, result: String = ""): String {
    val isError = result.startsWith("Error")
    if (isError) return result.lines().firstOrNull()?.take(100) ?: "Tool call failed"
    val argsJson = try { Json.parseToJsonElement(toolArgs.ifBlank { "{}" }).jsonObject } catch (_: Exception) { null }
    val fileName = argsJson?.get("name")?.let { (it as? JsonPrimitive)?.content }
        ?: argsJson?.get("names")?.let { names ->
            val arr = names as? kotlinx.serialization.json.JsonArray
            if (arr != null && arr.size == 1) (arr[0] as? JsonPrimitive)?.content else null
        }
    val nameCount = argsJson?.get("names")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size }
    return when (toolName) {
        "read_memory_file" -> when {
            nameCount != null && nameCount > 1 -> "Read $nameCount memories"
            fileName != null -> "Read $fileName"
            else -> "Read memory"
        }
        "create_memory_file" -> if (fileName != null) "Saved $fileName" else "Saved a memory"
        "edit_memory_file" -> if (fileName != null) "Updated $fileName" else "Updated a memory"
        "delete_memory_file" -> if (fileName != null) "Removed $fileName" else "Removed a memory"
        "list_memory_files" -> "Looked through saved memories"
        "update_active_memory" -> "Updated active memory"
        else -> "Done"
    }
}

@Composable
fun MessageItem(
    message: ChatMessage, 
    onEdit: (String, String) -> Unit, 
    isStreaming: Boolean = false,
    isLoading: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    modelAliases: Map<String, String> = emptyMap(),
    visualizeContextRollout: Boolean = false,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onHeightChanged: (Int) -> Unit = {}
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    var isThoughtExpanded by rememberSaveable { mutableStateOf(false) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    val clipboardManager = LocalClipboardManager.current

    if (showInfoDialog) {
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val dateString = sdf.format(Date(message.timestamp))
        val modelDisplay = if (message.modelName != null) {
            val modelId = message.modelName.removePrefix("models/").substringAfter(":")
            val provider = message.modelName.removePrefix("models/").substringBefore(":")
            modelAliases[message.modelName] ?: ("$modelId ($provider)")
        } else "Unknown"

        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Message Info") },
            text = {
                Column {
                    Text("Time: $dateString", style = MaterialTheme.typography.bodyMedium)
                    if (message.participant == Participant.MODEL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Model: $modelDisplay", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Close") }
            }
        )
    }

    var currentTotalHeight by remember { mutableIntStateOf(0) }
    
    // Create a responder that ignores bring-into-view requests to prevent auto-scrolling on focus/selection
    val noOpResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { /* Do nothing */ }
        }
    }

    fun calculateReportedHeight(totalPx: Int, thoughtPx: Int): Int {
        // When we are NOT expanded, the thought block is animating down from its large height 
        // to its stableCollapsedThoughtHeight. We want the outer list padding to behave as if
        // the thought block INSTANTLY hit stableCollapsedThoughtHeight, avoiding the final "jump".
        // But we ONLY do this if the thought block is currently larger than its collapsed height 
        // AND we know what the collapsed height is.
        if (!isThoughtExpanded && stableCollapsedThoughtHeight > 0 && thoughtPx > stableCollapsedThoughtHeight) {
            val excessHeight = thoughtPx - stableCollapsedThoughtHeight
            return totalPx - excessHeight
        }
        return totalPx
    }

    LaunchedEffect(message.text, message.status, isEditing) {
        kotlinx.coroutines.delay(50)
        onHeightChanged(calculateReportedHeight(currentTotalHeight, currentThoughtBlockHeight))
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val backgroundColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.primaryContainer
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Participant.MODEL -> MaterialTheme.colorScheme.onSurface
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val shape = when (message.participant) {
        Participant.USER -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
        Participant.MODEL -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        Participant.ERROR -> RoundedCornerShape(12.dp)
    }

    val currentTypography = MaterialTheme.typography
    val customTypography = markdownTypography(
        text = currentTypography.bodyMedium,
        h1 = currentTypography.headlineSmall,
        h2 = currentTypography.titleLarge,
        h3 = currentTypography.titleMedium,
        h4 = currentTypography.titleSmall,
        h5 = currentTypography.titleSmall,
        h6 = currentTypography.titleSmall,
        code = currentTypography.bodyMedium.copy(
            fontFamily = MonoFamily,
            fontSize = 13.sp
        ),
        inlineCode = currentTypography.bodyMedium.copy(
            fontFamily = MonoFamily,
            fontSize = 13.sp
        ),
    )
    
    // Dedicated compact typography for thoughts
    val thoughtTypography = markdownTypography(
        text = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        paragraph = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        ordered = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bullet = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        list = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        h1 = currentTypography.bodyMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        h2 = currentTypography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        h3 = currentTypography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        h4 = currentTypography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        h5 = currentTypography.bodyMedium.copy(fontSize = 13.sp),
        h6 = currentTypography.bodyMedium.copy(fontSize = 12.sp),
        code = currentTypography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 12.sp),
        inlineCode = currentTypography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 12.sp),
    )

    val customMarkdownPadding = markdownPadding(block = 7.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 4.dp)

    val customMarkdownComponents = remember {
        markdownComponents(
            table = { model ->
                MarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                    headerBlock = { content, header, tableWidth, style ->
                        MarkdownTableHeader(
                            content = content,
                            header = header,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                    rowBlock = { content, row, tableWidth, style ->
                        MarkdownTableRow(
                            content = content,
                            header = row,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                )
            }
        )
    }

    val latexImageTransformer = remember(textColor) {
        LatexImageTransformer(
            textSize = 56f,
            color = textColor.toArgb(),
        )
    }

    val shouldAnimate = !isFirstComposition && !isSwitching

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged {
                currentTotalHeight = it.height
                onHeightChanged(calculateReportedHeight(it.height, currentThoughtBlockHeight))
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
        val contextAlpha = if (visualizeContextRollout && !isInContext) Modifier.alpha(0.38f) else Modifier
        if (message.participant == Participant.USER) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = shape,
                    color = backgroundColor,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .then(contextAlpha)
                        .then(if (shouldAnimate) Modifier.animateContentSize(animationSpec = tween(150)) else Modifier)
                ) {
                    if (isEditing) {
                        val editState = rememberTextFieldState(message.text)
                        val editScrollState = rememberScrollState()
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                                TextField(
                                    state = editState,
                                    scrollState = editScrollState,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onCancelEdit() }) { Text("Cancel") }
                                TextButton(onClick = { onEdit(message.id, editState.text.toString()) }) { Text("Send") }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(16.dp).bringIntoViewResponder(noOpResponder),
                            horizontalAlignment = Alignment.Start
                        ) {
                            if (message.images.isNotEmpty()) {
                                androidx.compose.foundation.lazy.LazyRow(
                                    modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(message.images) { imagePath ->
                                        coil.compose.AsyncImage(
                                            model = imagePath,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onImageClick(imagePath) },
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                    }
                                }
                            }
                            if (message.text.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (totalBranches > 1 && !isEditing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .then(contextAlpha)
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(100))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp)
                    ) {
                        IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                        }
                        Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (!isEditing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { onStartEdit() }, enabled = isEditingAllowed, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Info, contentDescription = "Info", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .then(contextAlpha)
            ) {
                Column {
                    // Status Header
                    if (message.participant == Participant.MODEL) {
                        val infiniteTransition = rememberInfiniteTransition(label = "sending")
                        val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), "rot")

                        val isThinkingNow = !message.thoughts.isNullOrBlank() || message.status == MessageStatus.THINKING
                        val statusText = when {
                            message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) "Cost ${message.tokenCount} tokens" else null
                            isStreaming && isThinkingNow -> "Thinking..."
                            isStreaming && message.text.isNotEmpty() -> "Answering..."
                            isStreaming -> "Sending..."
                            else -> null
                        }

                        if (statusText != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                if (isStreaming || message.status == MessageStatus.SENDING || message.status == MessageStatus.THINKING) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(12.dp).rotate(rotation), tint = if (statusText == "Thinking...") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                } else {
                                    val icon = when (message.status) {
                                        MessageStatus.SUCCESS -> Icons.Default.CheckCircle
                                        MessageStatus.STOPPED -> Icons.Default.Stop
                                        else -> Icons.Default.Info
                                    }
                                    Icon(icon, null, modifier = Modifier.size(12.dp), tint = if (message.status == MessageStatus.SUCCESS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    var debouncedText by remember(message.status) { mutableStateOf(message.text) }
                    if (isStreaming) {
                        var lastUpdateMs by remember { mutableLongStateOf(0L) }
                        LaunchedEffect(message.text) {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateMs >= 50) {
                                debouncedText = message.text
                                lastUpdateMs = now
                            }
                        }
                    } else {
                        debouncedText = message.text
                    }

                    Column {
                        val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                        // Merged segment block: single block, newest title/icon when collapsed
                        if (message.segments != null && message.segments!!.isNotEmpty()) {
                            val segs = mergeAdjacentSegments(message.segments!!)
                            val lastSeg = segs.last()
                            val isLastTool = lastSeg.type == "tool"
                            val isThinking = message.status == MessageStatus.THINKING
                            val collapsedTitle = if (isLastTool) {
                                toolSummary(lastSeg)
                            } else {
                                if (message.thoughtTitle != null) message.thoughtTitle
                                else if (!isThinking) {
                                    if (message.thoughtTimeMs != null && message.thoughtTimeMs > 0) {
                                        val seconds = message.thoughtTimeMs / 1000
                                        if (seconds >= 60) "Thought for ${seconds / 60}m ${seconds % 60}s"
                                        else "Thought for ${seconds}s"
                                    } else "Thinking complete"
                                } else "Thinking..."
                            }
                            var isMergedExpanded by remember { mutableStateOf(false) }
                            val mergedBottomPadding by animateDpAsState(
                                targetValue = if (isMergedExpanded) 12.dp else 4.dp,
                                animationSpec = tween(400), label = "mergedPad"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = mergedBottomPadding + 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .clickable { isMergedExpanded = !isMergedExpanded }
                                    .bringIntoViewResponder(noOpResponder)
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    if (isLastTool) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else {
                                        Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        collapsedTitle, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (isMergedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        null, modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                AnimatedVisibility(
                                    visible = isMergedExpanded,
                                    enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                                    exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        segs.forEachIndexed { idx, seg ->
                                            if (seg.type == "thought" && seg.content.isNotBlank()) {
                                                Text(
                                                    "Thinking", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                SelectionContainer {
                                                        Markdown(
                                                            seg.content, modifier = Modifier.fillMaxWidth().bringIntoViewResponder(noOpResponder),
                                                            typography = thoughtTypography, padding = thoughtMarkdownPadding,
                                                            components = customMarkdownComponents,
                                                            imageTransformer = latexImageTransformer
                                                        )
                                                    }
                                            } else if (seg.type == "tool") {
                                                val isToolError = (seg.toolResult ?: "").startsWith("Error")
                                                Text(
                                                    toolDisplayName(seg.toolName),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = if (isToolError) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = toolSummary(seg),
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                                                    color = if (isToolError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (idx < segs.lastIndex) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Thought/Reasoning Block (fallback for messages without segments)
                        if (message.segments == null && !message.thoughts.isNullOrBlank()) {
                            val isThinking = message.status == MessageStatus.THINKING
                            
                            // Throttle updates for thoughts to match main output behavior
                            var debouncedThoughts by remember { mutableStateOf(message.thoughts!!) }
                            if (isStreaming) {
                                LaunchedEffect(message.thoughts) {
                                    kotlinx.coroutines.delay(100)
                                    debouncedThoughts = message.thoughts!!
                                }
                            } else {
                                debouncedThoughts = message.thoughts!!
                            }

                            val bottomPadding by animateDpAsState(
                                targetValue = if (isThoughtExpanded) 12.dp else 4.dp,
                                animationSpec = tween(400),
                                label = "thoughtPadding"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged {
                                        currentThoughtBlockHeight = it.height
                                    }
                                    .padding(top = 8.dp, bottom = bottomPadding + 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .clickable {
                                        if (!isThoughtExpanded && stableCollapsedThoughtHeight == 0) {
                                            stableCollapsedThoughtHeight = currentThoughtBlockHeight
                                        }
                                        isThoughtExpanded = !isThoughtExpanded
                                    }
                                    .bringIntoViewResponder(noOpResponder)
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    
                                    // Dynamic Title: Use API provided title, or fallback to default labels
                                    val thoughtTitle = remember(message.thoughts, message.thoughtTitle, isThinking, message.thoughtTimeMs, message.modelName) {
                                        if (message.thoughtTitle != null) {
                                            message.thoughtTitle
                                        } else if (!isThinking) {
                                            if (message.thoughtTimeMs != null && message.thoughtTimeMs > 0) {
                                                val seconds = message.thoughtTimeMs / 1000
                                                if (seconds >= 60) {
                                                    val minutes = seconds / 60
                                                    val remSeconds = seconds % 60
                                                    "Thought for ${minutes}m ${remSeconds}s"
                                                } else {
                                                    "Thought for ${seconds}s"
                                                }
                                            } else {
                                                "Thinking complete"
                                            }
                                        } else {
                                            "Thinking..."
                                        }
                                    }

                                    Text(
                                        text = thoughtTitle, 
                                        style = MaterialTheme.typography.labelMedium, 
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), 
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    Icon(if (isThoughtExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isThoughtExpanded,
                                    enter = androidx.compose.animation.fadeIn(animationSpec = tween(400)) + androidx.compose.animation.expandVertically(animationSpec = tween(400)),
                                    exit = androidx.compose.animation.fadeOut(animationSpec = tween(400)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(400))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(20.dp))
                                        SelectionContainer {
                                            Markdown(
                                                content = debouncedThoughts,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .bringIntoViewResponder(noOpResponder),
                                                typography = thoughtTypography,
                                                padding = thoughtMarkdownPadding, // Use tighter padding for thoughts
                                                components = customMarkdownComponents,
                                                imageTransformer = latexImageTransformer
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Tool Call Block (fallback for messages without segments)
                        if (message.segments == null && message.toolCall != null) {
                            var isToolExpanded by remember { mutableStateOf(false) }
                            val toolBottomPadding by animateDpAsState(
                                targetValue = if (isToolExpanded) 12.dp else 4.dp,
                                animationSpec = tween(400),
                                label = "toolPadding"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = toolBottomPadding)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .clickable { isToolExpanded = !isToolExpanded }
                                    .bringIntoViewResponder(noOpResponder)
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Icon(
                                        Icons.Default.Build,
                                        null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = message.toolCall!!.toolName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (isToolExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isToolExpanded,
                                    enter = androidx.compose.animation.fadeIn(animationSpec = tween(400)) + androidx.compose.animation.expandVertically(animationSpec = tween(400)),
                                    exit = androidx.compose.animation.fadeOut(animationSpec = tween(400)) + androidx.compose.animation.shrinkVertically(animationSpec = tween(400))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Text(
                                            "Arguments:",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        SelectionContainer {
                                            Text(
                                                message.toolCall!!.arguments,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Result:",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        val summary = toolResultSummary(message.toolCall!!.toolName, message.toolCall!!.arguments, message.toolCall!!.result)
                                        SelectionContainer {
                                            Text(
                                                summary,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (shouldAnimate) Modifier.animateContentSize(animationSpec = tween(150)) else Modifier)
                                .bringIntoViewResponder(noOpResponder)
                        ) {
                            if (isError) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        SelectionContainer {
                                            Text(
                                                debouncedText.ifEmpty { "Failed to generate." },
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, lineHeight = 18.sp),
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            } else if (debouncedText.isNotEmpty()) {
                                val spans = remember(debouncedText) { parseLatexSpans(debouncedText) }
                                if (spans.all { !it.isLatex }) {
                                    SelectionContainer {
                                        Markdown(
                                            content = debouncedText,
                                            modifier = Modifier.fillMaxWidth(),
                                            typography = customTypography,
                                            padding = customMarkdownPadding,
                                            components = customMarkdownComponents,
                                            imageTransformer = latexImageTransformer
                                        )
                                    }
                                } else {
                                    val paragraphs = remember(debouncedText) { splitParagraphs(debouncedText) }
                                    Column {
                                        paragraphs.forEachIndexed { paraIdx, paragraph ->
                                            if (paraIdx > 0) Spacer(Modifier.height(8.dp))
                                            val paraSpans = remember(paragraph) { parseLatexSpans(paragraph) }
                                            if (paraSpans.all { !it.isLatex }) {
                                                SelectionContainer {
                                                    Markdown(
                                                        content = paragraph,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        typography = customTypography,
                                                        padding = customMarkdownPadding,
                                                        components = customMarkdownComponents,
                                                        imageTransformer = latexImageTransformer
                                                    )
                                                }
                                            } else {
                                                var pendingSpans = mutableListOf<LatexSpan>()
                                                for (span in paraSpans) {
                                                    if (span.isLatex && span.display) {
                                                        if (pendingSpans.isNotEmpty()) {
                                                            SelectionContainer {
                                                                LatexAwareText(
                                                                    spans = pendingSpans.toList(),
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    textStyle = customTypography.paragraph.copy(color = textColor),
                                                                    latexTextSize = 56f,
                                                                    latexColor = textColor.toArgb(),
                                                                    codeSpanStyle = customTypography.inlineCode.toSpanStyle(),
                                                                )
                                                            }
                                                            pendingSpans.clear()
                                                        }
                                                        val latexColor = if (message.participant == Participant.USER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                        val bmp = remember(span.content, latexColor) { renderLatexToBitmap(span.content, color = latexColor.toArgb()) }
                                                        if (bmp != null) {
                                                            Image(bitmap = bmp.asImageBitmap(), contentDescription = span.content, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(vertical = 12.dp))
                                                        } else {
                                                            SelectionContainer {
                                                                Markdown(content = "```\n${span.content}\n```", modifier = Modifier.fillMaxWidth(), typography = customTypography, padding = customMarkdownPadding, components = customMarkdownComponents, imageTransformer = latexImageTransformer)
                                                            }
                                                        }
                                                    } else {
                                                        pendingSpans.add(span)
                                                    }
                                                }
                                                if (pendingSpans.isNotEmpty()) {
                                                    SelectionContainer {
                                                        LatexAwareText(
                                                            spans = pendingSpans.toList(),
                                                            modifier = Modifier.fillMaxWidth(),
                                                            textStyle = customTypography.paragraph.copy(color = textColor),
                                                            latexTextSize = 56f,
                                                            latexColor = textColor.toArgb(),
                                                            codeSpanStyle = customTypography.inlineCode.toSpanStyle(),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!isStreaming && message.status == MessageStatus.STOPPED) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Generation stopped.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                        
                        if (message.participant == Participant.MODEL && !isStreaming) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                IconButton(onClick = { onRegenerate(message.id) }, enabled = !isLoading, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp), tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                
                                if (totalBranches > 1) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .clip(RoundedCornerShape(100))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .padding(horizontal = 4.dp)
                                    ) {
                                        IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                                        }
                                        Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                                        IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

