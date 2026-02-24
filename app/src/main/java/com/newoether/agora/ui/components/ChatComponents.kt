package com.newoether.agora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

@Composable
fun MessageList(
    messages: List<ChatMessage>, 
    allMessages: List<ChatMessage> = emptyList(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    isLoading: Boolean = false,
    isSwitching: Boolean = false,
    visualizeContextRollout: Boolean = false,
    maxContextWindow: Int = 20,
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: MutableMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onSwitchBranch: (String?, Int) -> Unit = { _, _ -> },
    onRegenerate: (String) -> Unit = {}
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // Calculate which messages are in context
    val currentPath = messages.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()
    
    val lastUserMessageIndex = messages.indexOfLast { it.participant == Participant.USER }
    
    // Calculation of extra padding to allow scrolling last user message to 180dp from top.
    val extraPadding = if (lastUserMessageIndex == -1 || viewportHeight == 0) {
        0.dp
    } else {
        with(density) {
            val vDp = viewportHeight.toDp()
            val targetTopDp = 140.dp
            val availableSpaceDp = vDp - targetTopDp - (bottomBarHeight + 8.dp)
            var contentHeightPx = 0
            for (i in lastUserMessageIndex until messages.size) {
                contentHeightPx += messageHeights[messages[i].id] ?: 0
            }
            val contentHeightDp = contentHeightPx.toDp()
            (availableSpaceDp - contentHeightDp).coerceAtLeast(0.dp)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state
        ) {
            items(messages, key = { it.id }) { message ->
                val isLastMessage = messages.lastOrNull()?.id == message.id
                val isInContext = inContextIds.contains(message.id)
                val siblings = allMessages.filter { it.parentId == message.parentId }.sortedBy { it.timestamp }
                val branchIndex = siblings.indexOfFirst { it.id == message.id }
                val totalBranches = siblings.size

                MessageItem(
                    message = message, 
                    onEdit = { id, text -> 
                        onEditMessage(id, text)
                        editingMessageId = null
                    },
                    isStreaming = isLastMessage && isLoading && message.participant == Participant.MODEL,
                    isEditingAllowed = !isLoading && (editingMessageId == null || editingMessageId == message.id),
                    isEditing = editingMessageId == message.id,
                    isSwitching = isSwitching,
                    isInContext = isInContext,
                    visualizeContextRollout = visualizeContextRollout,
                    onStartEdit = { editingMessageId = message.id },
                    onCancelEdit = { editingMessageId = null },
                    branchIndex = branchIndex,
                    totalBranches = totalBranches,
                    onSwitchBranch = { direction -> onSwitchBranch(message.parentId, direction) },
                    onRegenerate = onRegenerate,
                    onHeightChanged = { height -> messageHeights[message.id] = height }
                )
            }
            item {
                Spacer(modifier = Modifier.height(extraPadding))
            }
        }
    }
}

@Composable
fun MessageItem(
    message: ChatMessage, 
    onEdit: (String, String) -> Unit, 
    isStreaming: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    visualizeContextRollout: Boolean = false,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onHeightChanged: (Int) -> Unit = {}
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    var isThoughtExpanded by rememberSaveable { mutableStateOf(false) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    
    val clipboardManager = LocalClipboardManager.current

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
    )
    
    // Dedicated compact typography for thoughts
    val thoughtTypography = markdownTypography(
        text = currentTypography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
        h1 = currentTypography.titleSmall.copy(fontSize = 12.sp),
        h2 = currentTypography.titleSmall.copy(fontSize = 11.sp),
        h3 = currentTypography.titleSmall.copy(fontSize = 10.sp),
        h4 = currentTypography.titleSmall.copy(fontSize = 10.sp),
        h5 = currentTypography.titleSmall.copy(fontSize = 10.sp),
        h6 = currentTypography.titleSmall.copy(fontSize = 10.sp),
    )

    val customMarkdownPadding = markdownPadding(block = 12.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 4.dp)

    val shouldAnimate = !isFirstComposition && !isSwitching

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { 
                currentTotalHeight = it.height
                onHeightChanged(calculateReportedHeight(it.height, currentThoughtBlockHeight)) 
            }
            .padding(vertical = 8.dp)
            .then(if (visualizeContextRollout && !isInContext) Modifier.alpha(0.38f) else Modifier),
        horizontalAlignment = alignment
    ) {
        if (message.participant == Participant.USER) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = shape,
                    color = backgroundColor,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
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
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (message.images.isNotEmpty()) {
                                androidx.compose.foundation.lazy.LazyRow(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, if (message.participant == Participant.USER) Alignment.End else Alignment.Start)
                                ) {
                                    items(message.images) { imagePath ->
                                        coil.compose.AsyncImage(
                                            model = imagePath,
                                            contentDescription = null,
                                            modifier = Modifier.sizeIn(maxWidth = 200.dp, maxHeight = 200.dp).clip(RoundedCornerShape(8.dp)),
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
                                        color = textColor,
                                        modifier = Modifier.bringIntoViewResponder(noOpResponder)
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
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column {
                    // Status Header
                    if (message.participant == Participant.MODEL) {
                        val infiniteTransition = rememberInfiniteTransition(label = "sending")
                        val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), "rot")

                        val statusText = when {
                            message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) "Cost ${message.tokenCount} tokens" else null
                            message.text.isNotEmpty() -> "Answering..."
                            message.status == MessageStatus.THINKING -> "Thinking..."
                            message.status == MessageStatus.SENDING -> "Sending..."
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

                    var debouncedText by remember { mutableStateOf(message.text) }
                    if (isStreaming) {
                        LaunchedEffect(message.text) {
                            kotlinx.coroutines.delay(100)
                            debouncedText = message.text
                        }
                    } else {
                        debouncedText = message.text
                    }

                    Column {
                        val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR
                        
                        // Thought/Reasoning Block
                        if (!message.thoughts.isNullOrBlank()) {
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
                                    .padding(top = 8.dp, bottom = bottomPadding)
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
                                    Icon(Icons.Default.Language, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    
                                    // Dynamic Title: Content inside ** ** or # Heading
                                    val thoughtTitle = remember(message.thoughts, isThinking, message.thoughtTimeMs) {
                                        val raw = message.thoughts ?: ""
                                        if (!isThinking) {
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
                                                "Thought"
                                            }
                                        } else {
                                            val boldMatches = Regex("\\*\\*(.*?)\\*\\*").findAll(raw).map { it.groupValues[1] }.toList()
                                            val headingMatches = Regex("(?m)^#+\\s*(.*)$").findAll(raw).map { it.groupValues[1] }.toList()
                                            
                                            val allMatches = (boldMatches + headingMatches).filter { it.isNotBlank() }
                                            if (allMatches.isNotEmpty()) {
                                                allMatches.last()
                                            } else {
                                                if (raw.length > 40) "..." + raw.takeLast(40) else raw.ifBlank { "Thinking..." }
                                            }
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
                                        val density = androidx.compose.ui.platform.LocalDensity.current
                                        CompositionLocalProvider(
                                            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * 0.8f)
                                        ) {
                                            SelectionContainer {
                                                Markdown(
                                                    content = debouncedThoughts,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .bringIntoViewResponder(noOpResponder),
                                                    typography = thoughtTypography,
                                                    padding = thoughtMarkdownPadding // Use tighter padding for thoughts
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .then(if (isStreaming || shouldAnimate) Modifier.animateContentSize(animationSpec = tween(150)) else Modifier)
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
                                                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                                        modifier = Modifier.bringIntoViewResponder(noOpResponder)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    } else if (debouncedText.isNotEmpty()) {
                                                        SelectionContainer {
                                                            Markdown(
                                                                content = debouncedText,
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .bringIntoViewResponder(noOpResponder),
                                                                typography = customTypography,
                                                                padding = customMarkdownPadding
                                                            )
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
                                IconButton(onClick = { onRegenerate(message.id) }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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

fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    color: Color,
    width: androidx.compose.ui.unit.Dp = 3.dp
): Modifier = drawWithContent {
    drawContent()
    if (scrollState.maxValue > 0) {
        val viewPortHeight = size.height
        val totalHeight = scrollState.maxValue + viewPortHeight
        val thumbHeight = (viewPortHeight / totalHeight) * viewPortHeight
        val thumbOffset = (scrollState.value / totalHeight.toFloat()) * viewPortHeight
        drawRoundRect(color = color, topLeft = Offset(size.width - width.toPx() - 4.dp.toPx(), thumbOffset), size = Size(width.toPx(), thumbHeight), cornerRadius = CornerRadius(width.toPx() / 2))
    }
}

@Composable
fun ChatBottomBar(
    onSendMessage: (String, List<String>) -> Unit,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String> = emptyMap(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() }
) {
    val scrollState = rememberScrollState()
    var expanded by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)
    val isMultiLine = textFieldState.text.contains('\n') || textFieldState.text.length > 50

    val noOpResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    var selectedImageUris by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        selectedImageUris = selectedImageUris + uris.map { it.toString() }
    }

    Column(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight().statusBarsPadding() else Modifier).padding(8.dp)) {
        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { isExpanded = false }) { Icon(Icons.Default.CloseFullscreen, "Collapse", tint = MaterialTheme.colorScheme.primary) }
            }
        }
        
        if (selectedImageUris.isNotEmpty() && !isExpanded) {
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedImageUris) { uriStr ->
                    Box {
                        coil.compose.AsyncImage(
                            model = uriStr,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-5).dp)
                                .size(18.dp)
                                .background(Color.Black.copy(alpha = 0.8f), CircleShape)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { selectedImageUris = selectedImageUris - uriStr },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = Color.White,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).bringIntoViewResponder(noOpResponder)) {
            TextField(state = textFieldState, scrollState = scrollState, modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)), placeholder = { Text("Ask Agora anything...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }, enabled = true, lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6), colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, cursorColor = MaterialTheme.colorScheme.primary), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
            if (isMultiLine && !isExpanded) IconButton(onClick = { isExpanded = true }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp).size(32.dp)) { Icon(Icons.Default.OpenInFull, "Expand", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(100)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                IconButton(
                    onClick = { 
                        launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, 
                    modifier = Modifier.size(32.dp)
                ) { 
                    Icon(Icons.Default.Add, "Add Image", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) 
                }
                val displayText = when {
                    isModelValid -> modelAliases[selectedModel] ?: selectedModel.removePrefix("models/")
                    enabledModels.isNotEmpty() -> "Select Model"
                    else -> "No model selected"
                }
                Box {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = 160.dp), contentPadding = PaddingValues(start = 12.dp, end = 8.dp)) { Text(displayText, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isModelValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = MaterialTheme.shapes.medium) {
                        if (enabledModels.isEmpty()) DropdownMenuItem(text = { Text("No models enabled") }, onClick = { expanded = false }, enabled = false)
                        else enabledModels.forEach { model -> DropdownMenuItem(text = { Text(modelAliases[model] ?: model.removePrefix("models/")) }, onClick = { onModelSelect(model); expanded = false }) }
                    }
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Settings, "Settings", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                var toolsMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { toolsMenuExpanded = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "Tools", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(expanded = toolsMenuExpanded, onDismissRequest = { toolsMenuExpanded = false }, shape = RoundedCornerShape(16.dp)) {
                        DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(12.dp)); Text("Code Execution") } }, trailingIcon = { Switch(checked = codeExecutionEnabled, onCheckedChange = { onCodeExecutionToggle(it) }, modifier = Modifier.scale(0.7f)) }, onClick = { onCodeExecutionToggle(!codeExecutionEnabled) })
                        DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(12.dp)); Text("Google Search") } }, trailingIcon = { Switch(checked = googleSearchEnabled, onCheckedChange = { onGoogleSearchToggle(it) }, modifier = Modifier.scale(0.7f)) }, onClick = { onGoogleSearchToggle(!googleSearchEnabled) })
                    }
                }
            }
            val canSend = (textFieldState.text.isNotBlank() || selectedImageUris.isNotEmpty()) && !isLoading && isModelValid && !isSwitching
            val isActionable = (isLoading || canSend) && !isSwitching
            FloatingActionButton(
                onClick = { 
                    if (isSwitching) return@FloatingActionButton
                    if (isLoading) onStopGeneration() 
                    else if (canSend) { 
                        onSendMessage(textFieldState.text.toString(), selectedImageUris)
                        selectedImageUris = emptyList()
                        textFieldState.edit { replace(0, length, "") }
                        isExpanded = false 
                    } 
                }, 
                containerColor = if (isActionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, 
                contentColor = if (isActionable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, 
                modifier = Modifier.size(48.dp), 
                shape = CircleShape, 
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
            ) { 
                Icon(if (isLoading) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, "Action", modifier = Modifier.size(24.dp)) 
            }
        }
    }
}
