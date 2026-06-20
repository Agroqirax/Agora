package com.newoether.agora.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

private const val STREAMING_MARKDOWN_FLUSH_MS = 250L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    val isThoughtExpanded by remember(message.id) {
        derivedStateOf { thoughtExpandedStates[message.id] ?: false }
    }
    var showSegmentDetail by remember { mutableStateOf(false) }
    var selectedSegmentIndex by remember { mutableIntStateOf(-1) }
    var selectedSegmentIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    // Capture the fully-settled collapsed height after collapse animation finishes.
    // This lets calculateReportedHeight immediately report the post-collapse height
    // even mid-animation, so extraPadding doesn't "chase" the shrinking thought block.
    LaunchedEffect(isThoughtExpanded) {
        if (!isThoughtExpanded) {
            delay(500) // slightly longer than the 400ms collapse tween + mergedBottomPadding tween
            stableCollapsedThoughtHeight = currentThoughtBlockHeight
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current

    if (showInfoDialog) {
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val dateString = sdf.format(Date(message.timestamp))
        val modelDisplay = if (message.modelName != null) {
            val parsed = message.modelName?.let { com.newoether.agora.model.ModelId.parse(it) }
            val modelId = parsed?.modelName?.removePrefix("models/") ?: message.modelName
            val provider = parsed?.providerName ?: "Unknown"
            modelAliases[message.modelName] ?: ("$modelId ($provider)")
        } else stringResource(R.string.unknown)

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.message_info), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.time_with_label, dateString), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    if (message.participant == Participant.MODEL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.model_with_label, modelDisplay), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(stringResource(R.string.provider_close)) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_message_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.delete_message_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        haptics.reject()
                        onDelete(message.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    var currentTotalHeight by remember { mutableIntStateOf(0) }
    // No-op modifier that suppresses bring-into-view auto-scrolling on focus

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

    LaunchedEffect(message.text, message.status, isEditing, isThoughtExpanded) {
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
    // Chat-specific markdown scale — optimized for immersive reading.
    // Outfit's large x-height means 15sp reads like ~16sp Roboto.
    // Heading steps of 3sp (h1→h2→h3) and 2sp (h3→h4) create
    // a visible but not jarring hierarchy during long-form reading.
    val customTypography = markdownTypography(
        text = ChatType.body,
        paragraph = ChatType.body,
        ordered = ChatType.body,
        bullet = ChatType.body,
        list = ChatType.body,
        h1 = ChatType.mdH1,
        h2 = ChatType.mdH2,
        h3 = ChatType.mdH3,
        h4 = ChatType.mdH4,
        h5 = ChatType.mdH5,
        h6 = ChatType.mdH6,
        code = ChatType.code,
        inlineCode = ChatType.code,
        table = ChatType.body,
    )

    // Compact typography for thought blocks — subordinate to main chat body.
    // One tier below main markdown: body at 13sp (vs 15sp), headings similarly
    // stepped down. Readable for paragraph-level content but clearly secondary.
    val thoughtTypography = markdownTypography(
        text = ChatType.thoughtBody,
        paragraph = ChatType.thoughtBody,
        ordered = ChatType.thoughtBody,
        bullet = ChatType.thoughtBody,
        list = ChatType.thoughtBody,
        h1 = ChatType.thH1,
        h2 = ChatType.thH2,
        h3 = ChatType.thH3,
        h4 = ChatType.thH4,
        h5 = ChatType.thH5,
        h6 = ChatType.thH6,
        code = ChatType.thoughtCode,
        inlineCode = ChatType.thoughtCode,
    )

    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.surface
    // Composite fg at 0.1 alpha over bg to produce the exact opaque equivalent
    val codeBg = remember(fg, bg) {
        Color(
            red   = fg.red   * 0.1f + bg.red   * 0.9f,
            green = fg.green * 0.1f + bg.green * 0.9f,
            blue  = fg.blue  * 0.1f + bg.blue  * 0.9f,
        )
    }
    val customMarkdownColors = markdownColor(
        codeBackground = codeBg,
        inlineCodeBackground = Color.Transparent,
    )
    val customMarkdownPadding = markdownPadding(block = 8.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 5.dp)

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
    val markdownFlavour = remember { GFMFlavourDescriptor() }
    val markdownRenderContext = remember(
        customMarkdownColors,
        customTypography,
        customMarkdownPadding,
        customMarkdownComponents,
        latexImageTransformer,
        markdownFlavour,
    ) {
        ChatMarkdownRenderContext(
            colors = customMarkdownColors,
            typography = customTypography,
            padding = customMarkdownPadding,
            components = customMarkdownComponents,
            imageTransformer = latexImageTransformer,
            flavour = markdownFlavour,
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
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .then(contextAlpha)
                        .then(if (shouldAnimate) Modifier.animateContentSize(animationSpec = tween(500)) else Modifier)
                ) {
                    if (isEditing) {
                        val editState = rememberTextFieldState(message.text)
                        val editScrollState = rememberScrollState()
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(modifier = Modifier.noOpBringIntoView()) {
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
                                TextButton(onClick = { onCancelEdit() }) { Text(stringResource(R.string.cancel)) }
                                TextButton(onClick = { onEdit(message.id, editState.text.toString()) }, enabled = !isLoading) { Text(stringResource(R.string.send)) }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(16.dp).noOpBringIntoView(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            val hasMetaItems = message.attachmentMeta?.items?.isNotEmpty() == true
                        if (message.images.isNotEmpty() || hasMetaItems) {
                                val ctx = LocalContext.current
                                val meta = remember(message.attachmentMeta) {
                                    message.attachmentMeta
                                }
                                // Build display items: skip non-first video/PDF frames, add meta-only items
                                val displayItems = remember(message.images, meta) {
                                    val skipIndices = mutableSetOf<Int>()
                                    if (meta != null) {
                                        for (item in meta.items) {
                                            val count = item.pageCount ?: 1
                                            if (item.imageIndex != null && count > 1 && (item.type == "video" || item.type == "pdf")) {
                                                for (i in item.imageIndex + 1 until item.imageIndex + count) {
                                                    skipIndices.add(i)
                                                }
                                            }
                                        }
                                    }
                                    // Image-backed items
                                    val imageItems = message.images.mapIndexedNotNull { index, path ->
                                        if (index in skipIndices) null
                                        else {
                                            val item = findMetaForIndex(meta, index)
                                            Triple(index, path, item)
                                        }
                                    }
                                    // Meta-only items (file/PDF without image representation)
                                    val metaOnlyItems = meta?.items
                                        ?.filter { it.imageIndex == null && (it.type == "file" || it.type == "pdf" || it.type == "image") }
                                        ?.map { Triple(-1, "", it) }
                                        ?: emptyList()
                                    imageItems + metaOnlyItems
                                }

                                // Collect all image/video URLs for the pager
                                val allMediaUrls = remember(displayItems) {
                                    displayItems.mapNotNull { (_, imagePath, metaItem) ->
                                        val t = resolveAttachmentType(imagePath, metaItem, ctx)
                                        when (t) {
                                            "image" -> if (imagePath.isNotEmpty()) imagePath else null
                                            "video" -> metaItem?.originalUri
                                            else -> null
                                        }
                                    }
                                }

                                androidx.compose.foundation.lazy.LazyRow(
                                    modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(displayItems) { itemIdx, (index, imagePath, metaItem) ->
                                        val type = remember(imagePath, metaItem?.type) {
                                            resolveAttachmentType(imagePath, metaItem, ctx)
                                        }
                                        val isVideo = type == "video"
                                        val isPdf = type == "pdf"
                                        val isFileType = type == "file"

                                        val fileName = metaItem?.fileName ?: imagePath.substringAfterLast("/")
                                        val pdfPages = if (type == "pdf") {
                                            metaItem?.imageIndex?.let { start ->
                                                val count = metaItem.pageCount ?: 1
                                                val end = (start + count).coerceAtMost(message.images.size)
                                                if (start in 0 until message.images.size) message.images.subList(start, end) else emptyList()
                                            } ?: emptyList()
                                        } else emptyList()

                                        val mediaIndex = allMediaUrls.indexOf(
                                            when (type) {
                                                "video" -> metaItem?.originalUri
                                                else -> imagePath
                                            }
                                        ).coerceAtLeast(0)

                                        AttachmentThumbnailItem(
                                            type = type,
                                            imagePath = imagePath,
                                            fileName = fileName,
                                            originalUri = metaItem?.originalUri,
                                            textContent = metaItem?.textContent,
                                            pdfPages = pdfPages,
                                            allMediaUrls = allMediaUrls,
                                            mediaIndex = mediaIndex,
                                            handlers = ThumbnailClickHandlers(
                                                onMediaClick = onMediaClick,
                                                onFileClick = onFileContentClick,
                                                onPdfClick = onPdfPagesClick
                                            )
                                        )
                                        if (type == "pdf" && metaItem?.warning != null) {
                                            Text(metaItem.warning, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            if (message.text.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        text = message.text,
                                        style = ChatType.userBody,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.then(contextAlpha)
                    ) {
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)); haptics.success() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { onStartEdit() }, enabled = isEditingAllowed, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = if (isEditingAllowed) 0.6f else 0.3f))
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            DropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                                shape = RoundedCornerShape(12.dp),
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.info)) },
                                    onClick = { showMenu = false; showInfoDialog = true },
                                    leadingIcon = { Icon(Icons.Default.Info, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                    onClick = { showMenu = false; showDeleteConfirm = true },
                                    enabled = !isLoading,
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // During generation, eat horizontal nested-scroll so code blocks
            // cannot be panned. Vertical scroll and taps (thinking header,
            // stop button) pass through normally. Text selection is already
            // prevented during streaming — SelectionContainer is only in the
            // else (!isStreaming) branch.
            val horizontalScrollEater = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                        Offset(available.x, 0f)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .then(contextAlpha)
                    .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
            ) {
                Column {
                    // Status Header
                    if (message.participant == Participant.MODEL) {
                        val thinkingStatus = stringResource(R.string.thinking_ellipsis)
                        val answeringStatus = stringResource(R.string.answering_ellipsis)
                        // Hold the last non-fallback label so transitions between
                        // "Thinking… → Answering…" don't flash "Sending…" while
                        // the first answer token is still in-flight.
                        var heldLabel by remember { mutableStateOf("") }
                        var heldStatusText by remember { mutableStateOf("") }
                        // Update heldLabel after composition to avoid double-recomposition flash
                        val thinkingNow = message.status == MessageStatus.THINKING
                        val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                        val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                        val hasActiveAnswer = message.hasActiveAnswerSegment()
                        LaunchedEffect(thinkingNow, hasActiveAnswer, message.status) {
                            heldLabel = when {
                                thinkingNow -> "thinking"
                                isToolCalling -> "calling"
                                isTranscribing -> "transcribing"
                                hasActiveAnswer -> "answering"
                                message.status == MessageStatus.SUCCESS || message.status == MessageStatus.ERROR || message.status == MessageStatus.STOPPED -> ""
                                message.status == MessageStatus.SENDING -> ""
                                else -> heldLabel
                            }
                        }
                        val toolCallingStatus = stringResource(R.string.tool_calling_ellipsis)
                        val transcribingStatus = stringResource(R.string.transcription_ellipsis)
                        val statusText = when {
                            message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) stringResource(R.string.cost_tokens, message.tokenCount) else null
                            isStreaming && isTranscribing -> transcribingStatus
                            isStreaming && isToolCalling -> toolCallingStatus
                            isStreaming && thinkingNow -> thinkingStatus
                            isStreaming && hasActiveAnswer -> answeringStatus
                            isStreaming -> when (heldLabel) {
                                "thinking" -> thinkingStatus
                                "calling" -> toolCallingStatus
                                "transcribing" -> transcribingStatus
                                "answering" -> answeringStatus
                                else -> stringResource(R.string.sending_ellipsis)
                            }
                            else -> null
                        }.let { base ->
                            if (base != null && message.retryText != null) "$base (${message.retryText})"
                            else base
                        }
                        // Hold the last non-null label so the status bar doesn't collapse
                        // during the timing gap between isStreaming→false and the DB
                        // emitting the updated message status.
                        val displayText = when {
                            statusText != null -> statusText.also { heldStatusText = it }
                            message.status == MessageStatus.SENDING || message.status == MessageStatus.THINKING || message.status == MessageStatus.TOOL_CALLING || message.status == MessageStatus.TRANSCRIBING -> heldStatusText.takeIf { it.isNotEmpty() }
                            else -> null.also { heldStatusText = "" }
                        }

                        AnimatedVisibility(
                            visible = displayText != null,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            val text = displayText ?: return@AnimatedVisibility
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                    if (isStreaming) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = if (text == thinkingStatus) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    } else {
                                        val icon = when (message.status) {
                                            MessageStatus.SUCCESS -> Icons.Default.CheckCircle
                                            MessageStatus.STOPPED -> Icons.Default.Stop
                                            else -> Icons.Default.Info
                                        }
                                        Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (message.status == MessageStatus.SUCCESS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text, style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    var debouncedText by remember(isStreaming) { mutableStateOf(if (isStreaming) "" else message.text) }
                    if (!isStreaming) {
                        debouncedText = message.text
                    } else {
                        var lastUpdateMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        var flushJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        LaunchedEffect(message.text) {
                            if (message.text.isEmpty()) return@LaunchedEffect
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastUpdateMs
                            if (elapsed >= STREAMING_MARKDOWN_FLUSH_MS) {
                                flushJob?.cancel()
                                debouncedText = message.text
                                lastUpdateMs = now
                            } else {
                                flushJob?.cancel()
                                flushJob = launch {
                                    kotlinx.coroutines.delay(STREAMING_MARKDOWN_FLUSH_MS - elapsed)
                                    debouncedText = message.text
                                    lastUpdateMs = System.currentTimeMillis()
                                }
                            }
                        }
                    }

                    // Level 1: anti-shrink for text and thinking content (kept after streaming ends)
                    var streamingMaxHeightPx by remember { mutableIntStateOf(0) }
                    var thinkingContentMaxHeightPx by remember { mutableIntStateOf(0) }

                    // Reset anti-shrink heights when streaming restarts (e.g. regeneration)
                    LaunchedEffect(isStreaming) {
                        if (isStreaming) {
                            streamingMaxHeightPx = 0
                            thinkingContentMaxHeightPx = 0
                        }
                    }

                    Column {
                        val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                        // Only zero out thought height when legacy thought block is not shown
                        if (message.segments != null || message.thoughts.isNullOrBlank()) {
                            currentThoughtBlockHeight = 0
                        }

                        val segmentsOrNull = message.segments
                        val mergedSegments = remember(segmentsOrNull) {
                            mergeAdjacentSegments(segmentsOrNull.orEmpty())
                        }
                        val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                        val useTimelineSegments = normalizedToolCallDisplayMode != ToolCallDisplayModes.COMPACT &&
                            mergedSegments.any { it.type == "answer" }
                        val groupAdjacentTimelineTools = normalizedToolCallDisplayMode == ToolCallDisplayModes.GROUPED_TIMELINE
                        val timelineBlockKeys = remember(message.id, mergedSegments, groupAdjacentTimelineTools) {
                            buildTimelineBlockKeys(message.id, mergedSegments, groupAdjacentTimelineTools)
                        }
                        val timelineAppearanceSeenKeys = remember(message.id, normalizedToolCallDisplayMode) {
                            timelineBlockKeys.toMutableSet()
                        }
                        var timelineAppearanceInitialized by remember(message.id, normalizedToolCallDisplayMode) {
                            mutableStateOf(false)
                        }
                        val timelineAnimatedBlockKeys = if (isStreaming && timelineAppearanceInitialized) {
                            timelineBlockKeys.filterNotTo(linkedSetOf()) { it in timelineAppearanceSeenKeys }
                        } else {
                            emptySet()
                        }
                        SideEffect {
                            timelineAppearanceSeenKeys.addAll(timelineBlockKeys)
                            if (!timelineAppearanceInitialized) {
                                timelineAppearanceInitialized = true
                            }
                        }
                        val detailSegments = remember(mergedSegments) {
                            mergedSegments.filter { it.type != "answer" }
                        }

                        AnimatedVisibility(
                            visible = useTimelineSegments,
                            enter = fadeIn(tween(500)) + expandVertically(tween(500)),
                            exit = fadeOut(tween(500)) + shrinkVertically(tween(500))
                        ) {
                            TimelineSegmentsContent(
                                segments = mergedSegments,
                                detailSegments = detailSegments,
                                message = message,
                                isStreaming = isStreaming,
                                groupAdjacentBlocks = groupAdjacentTimelineTools,
                                expandedStates = thoughtExpandedStates,
                                renderContext = markdownRenderContext,
                                animatedBlockKeys = timelineAnimatedBlockKeys,
                                onSegmentClick = { indices ->
                                    selectedSegmentIndices = indices
                                    selectedSegmentIndex = indices.firstOrNull() ?: -1
                                    showSegmentDetail = true
                                }
                            )
                        }

                        // Compact segment block: single block, newest title/icon when collapsed.
                        // Answer segments are timeline anchors only; compact mode still renders
                        // message.text below as the complete answer.
                        AnimatedVisibility(
                            visible = !useTimelineSegments && detailSegments.isNotEmpty(),
                            enter = fadeIn(tween(500)) + expandVertically(tween(500)),
                            exit = fadeOut(tween(500)) + shrinkVertically(tween(500))
                        ) {
                            val segs = detailSegments
                            if (segs.isEmpty()) return@AnimatedVisibility
                            val lastSeg = segs.last()
                            val isLastTool = lastSeg.type == "tool"
                            val isToolInProgress = isLastTool && lastSeg.toolResult == null
                            val isThinking = message.status == MessageStatus.THINKING
                            val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                            val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                            val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
                            val thoughtMs = thoughtDurationMs(segs) ?: message.thoughtTimeMs
                            val hasThought = thoughtMs != null && thoughtMs > 0
                            val collapsedTitle = when {
                                isThinking -> message.thoughtTitle ?: stringResource(R.string.thinking_ellipsis)
                                isTranscribing -> message.thoughtTitle ?: stringResource(R.string.transcription_ellipsis)
                                isToolCalling || isToolInProgress -> toolDisplayName(lastSeg.toolName)
                                else -> {
                                    if (hasThought) {
                                        thoughtDurationTitle(thoughtMs!!, toolCount)
                                    } else if (toolCount > 0) {
                                        stringResource(R.string.called_n_tools, toolCount)
                                    } else if (message.thoughtTitle != null) {
                                        message.thoughtTitle
                                    } else if (segs.any { it.type == "transcription" }) {
                                        "Image Transcription"
                                    } else {
                                        stringResource(R.string.thinking_complete)
                                    }
                                }
                            }
                            val mergedBottomPadding by animateDpAsState(
                                targetValue = if (isThoughtExpanded) 12.dp else 4.dp,
                                animationSpec = tween(500), label = "mergedPad"
                            )
                            Surface(
                                tonalElevation = 2.dp,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = mergedBottomPadding + 6.dp)
                                    .noOpBringIntoView()
                                    .onSizeChanged { currentThoughtBlockHeight = it.height }
                            ) {
                                Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { thoughtExpandedStates[message.id] = !isThoughtExpanded }
                                        .padding(10.dp)
                                ) {
                                    if (isToolCalling || isToolInProgress) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else if (!isThinking && !hasThought && toolCount > 0) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else if (isTranscribing || collapsedTitle == "Image Transcription") {
                                        Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else {
                                        Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        collapsedTitle, style = ChatType.thoughtTitle,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (isThoughtExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        null, modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                AnimatedVisibility(
                                    visible = isThoughtExpanded,
                                    enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                                    exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .then(
                                                if (thinkingContentMaxHeightPx > 0)
                                                    Modifier.heightIn(min = with(LocalDensity.current) { thinkingContentMaxHeightPx.toDp() })
                                                else Modifier
                                            )
                                            .onSizeChanged { size ->
                                                if (isStreaming) {
                                                    thinkingContentMaxHeightPx = maxOf(thinkingContentMaxHeightPx, size.height)
                                                }
                                            }
                                    ) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        segs.forEachIndexed { idx, seg ->
                                            if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(18.dp))
                                                        .clickable {
                                                            selectedSegmentIndices = listOf(idx)
                                                            selectedSegmentIndex = idx
                                                            showSegmentDetail = true
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Text(
                                                        if (seg.type == "transcription") transcriptionLabel(segs, idx) else stringResource(R.string.tool_thinking),
                                                        style = ChatType.meta,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    if (seg.content.isNotBlank()) {
                                                        val flat = seg.content.replace('\n', ' ')
                                                        val preview = if (isStreaming && idx == segs.lastIndex) {
                                                            if (flat.length > 60) "…${flat.takeLast(60)}" else flat
                                                        } else flat
                                                        Text(
                                                            text = preview,
                                                            style = ChatType.metaNormal,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    } else {
                                                        Text(
                                                            text = "Image transcription is empty.",
                                                            style = ChatType.metaNormal,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        )
                                                    }
                                                }
                                            } else if (seg.type == "tool") {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(18.dp))
                                                        .clickable {
                                                            selectedSegmentIndices = listOf(idx)
                                                            selectedSegmentIndex = idx
                                                            showSegmentDetail = true
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Text(
                                                        toolDisplayName(seg.toolName),
                                                        style = ChatType.meta,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = toolSummary(seg),
                                                        style = ChatType.metaNormal,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
                                                }
                                            }
                                            if (idx < segs.lastIndex) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                                )
                                            }
                                        }
                                    }
                                }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (streamingMaxHeightPx > 0)
                                        Modifier.heightIn(min = with(LocalDensity.current) { streamingMaxHeightPx.toDp() })
                                    else Modifier
                                )
                                .onSizeChanged { size ->
                                    if (isStreaming) {
                                        streamingMaxHeightPx = maxOf(streamingMaxHeightPx, size.height)
                                    }
                                }
                                .noOpBringIntoView()
                        ) {
                            if (isError) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        SelectionContainer {
                                            Text(
                                                debouncedText.ifEmpty { stringResource(R.string.failed_to_generate) },
                                                style = ChatType.errorBody,
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            } else if (debouncedText.isNotEmpty() && !useTimelineSegments) {
                                var keepBlockRenderer by remember(message.id) { mutableStateOf(false) }
                                val useBlockRenderer = isStreaming || keepBlockRenderer
                                val streamingBlocks = rememberStreamingMarkdownBlocks(
                                    content = debouncedText,
                                    flavour = markdownFlavour,
                                    active = useBlockRenderer
                                )

                                LaunchedEffect(isStreaming) {
                                    if (isStreaming) {
                                        keepBlockRenderer = true
                                    }
                                }

                                Box {
                                    SelectionContainer {
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            if (useBlockRenderer) {
                                                StreamingMarkdownBlockContent(
                                                    blocks = streamingBlocks,
                                                    renderContext = markdownRenderContext,
                                                    modifier = Modifier
                                                        .fillMaxWidth(),
                                                    tailIsStreaming = isStreaming
                                                )
                                            }
                                            if (!useBlockRenderer && !isStreaming) {
                                                key("full-markdown") {
                                                    RecomposeSafeMarkdown(
                                                        content = debouncedText,
                                                        isStreaming = false,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                    ) { text ->
                                                        MarkdownTextContent(
                                                            text = text,
                                                            renderContext = markdownRenderContext
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (isStreaming) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .pointerInput(Unit) {
                                                    detectTapGestures(onLongPress = { })
                                                }
                                        )
                                    }
                                }
                            }
                        }
                        if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                            val genImages = message.images
                            // Generated images are primary output, not input references:
                            // render as a full-width square card, image cropped to fill
                            // with rounded corners, tap to view fullscreen.
                            Column(
                                modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                genImages.forEachIndexed { idx, path ->
                                    coil.compose.AsyncImage(
                                        model = path,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = { onMediaClick(genImages, idx) },
                                                onLongClick = { haptics.longPress() }
                                            )
                                    )
                                }
                            }
                        }
                        if (!isStreaming && message.status == MessageStatus.STOPPED) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.generation_stopped), style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                        
                        if (message.participant == Participant.MODEL) {
                            AnimatedVisibility(
                                visible = !isStreaming,
                                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)); haptics.success() }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                    IconButton(onClick = { onRegenerate(message.id) }, enabled = !isLoading, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(19.dp), tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                        DropdownMenu(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            shape = RoundedCornerShape(12.dp),
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.info)) },
                                                onClick = { showMenu = false; showInfoDialog = true },
                                                leadingIcon = { Icon(Icons.Default.Info, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.delete), color = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                                onClick = { showMenu = false; showDeleteConfirm = true },
                                                enabled = !isLoading,
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) }
                                            )
                                        }
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
                                            IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                                            }
                                            Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                                            IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Segment detail bottom sheet (self-contained draggable sheet + FSM).
    if (showSegmentDetail && selectedSegmentIndex >= 0) {
        SegmentDetailSheet(
            message = message,
            selectedSegmentIndex = selectedSegmentIndex,
            selectedSegmentIndices = selectedSegmentIndices,
            isStreaming = isStreaming,
            markdownColors = customMarkdownColors,
            thoughtTypography = thoughtTypography,
            thoughtMarkdownPadding = thoughtMarkdownPadding,
            markdownComponents = customMarkdownComponents,
            markdownFlavour = markdownFlavour,
            onDismiss = { showSegmentDetail = false }
        )
    }
}

