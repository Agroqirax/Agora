package com.newoether.agora.ui.chat

import android.net.Uri
import android.os.Build
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate

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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBottomBar(
    onSendMessage: (String, List<com.newoether.agora.model.SelectedAttachment>) -> Unit,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String> = emptyMap(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    thinkingEnabled: Boolean = true,
    thinkingLevel: String = "medium",
    webSearchEnabled: Boolean = false,
    shellEnabled: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onThinkingToggle: (Boolean) -> Unit = {},
    onThinkingLevelChange: (String) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onShellToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onImageClick: (String) -> Unit = {},
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    focusRequester: FocusRequester = FocusRequester(),
    isExpanded: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)

    // No-op bring-into-view to prevent auto-scrolling on text field focus

    val coroutineScope = rememberCoroutineScope()

    var selectedAttachments by remember { mutableStateOf<List<com.newoether.agora.model.SelectedAttachment>>(emptyList()) }
    var processingStates by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var pendingSend by remember { mutableStateOf(false) }

    // PDF page selection dialog state
    var showPdfPageDialog by remember { mutableStateOf(false) }
    var pendingPdfUri by remember { mutableStateOf<String?>(null) }
    var pendingPdfPages by remember { mutableIntStateOf(0) }
    var pendingPdfFileName by remember { mutableStateOf<String?>(null) }
    var pendingPdfMimeType by remember { mutableStateOf<String?>(null) }

    // Video slicing dialog state
    var showVideoSliceDialog by remember { mutableStateOf(false) }
    var pendingVideoUri by remember { mutableStateOf<String?>(null) }
    var pendingVideoDurationMs by remember { mutableLongStateOf(0L) }
    var pendingVideoQueue by remember { mutableStateOf<List<String>>(emptyList()) }

    val context = LocalContext.current

    // Helper: process next video in queue, showing slice dialog
    fun processNextVideo() {
        if (pendingVideoQueue.isNotEmpty()) {
            val uri = pendingVideoQueue.first()
            pendingVideoQueue = pendingVideoQueue.drop(1).toMutableList()
            val durationMs = try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, android.net.Uri.parse(uri))
                val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                dur
            } catch (_: Exception) { 0L }
            pendingVideoUri = uri
            pendingVideoDurationMs = durationMs
            showVideoSliceDialog = true
        }
    }

    // Start frame extraction for a video, return list of frame paths
    suspend fun extractVideoFrames(videoUri: String, frameCount: Int, intervalMs: Long): List<String> {
        return withContext(Dispatchers.IO) {
            val paths = mutableListOf<String>()
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, android.net.Uri.parse(videoUri))
                var timeUs = 0L
                val intervalUs = intervalMs * 1000L
                for (i in 0 until frameCount) {
                    val bitmap = retriever.getFrameAtTime(
                        timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        val file = java.io.File(context.filesDir, "vid_${java.util.UUID.randomUUID()}_$i.jpg")
                        file.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        bitmap.recycle()
                        paths.add(file.absolutePath)
                    }
                    timeUs += intervalUs
                    processingStates = processingStates + (videoUri to (i + 1).toFloat() / frameCount)
                }
                retriever.release()
            } catch (_: Exception) {}
            processingStates = processingStates - videoUri
            paths
        }
    }

    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        selectedAttachments = selectedAttachments + uris.map {
            com.newoether.agora.model.SelectedAttachment(
                uri = it.toString(), type = "image",
                mimeType = try { context.contentResolver.getType(it) } catch (_: Exception) { null }
            )
        }
    }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        val urisToQueue = uris.map { it.toString() }
        pendingVideoQueue = pendingVideoQueue + urisToQueue
        if (!showVideoSliceDialog) processNextVideo()
    }
    // File validation rejection dialog
    var rejectedMessage by remember { mutableStateOf<String?>(null) }

    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val validAttachments = mutableListOf<com.newoether.agora.model.SelectedAttachment>()
        val rejectedMessages = mutableListOf<String>()
        for (uri in uris) {
            val validation = com.newoether.agora.util.FileValidator.validate(context, uri)
            if (!validation.valid) {
                rejectedMessages.add(com.newoether.agora.util.FileValidator.errorMessage(context, validation.error!!, validation.mimeType))
                continue
            }
            val mimeType = validation.mimeType
            val type = when {
                mimeType == "application/pdf" -> "pdf"
                mimeType != null -> "file"
                else -> "file"
            }
            val fileName = try {
                val cursor = context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else null
                    } else null
                }
            } catch (_: Exception) { null }
            if (type == "pdf" && !showPdfPageDialog) {
                // Queue first PDF for page selection dialog
                val pageCount = com.newoether.agora.util.PdfPageRenderer.getPageCount(context, uri)
                if (pageCount > 0) {
                    pendingPdfUri = uri.toString()
                    pendingPdfPages = pageCount
                    pendingPdfFileName = fileName
                    pendingPdfMimeType = mimeType
                    showPdfPageDialog = true
                    continue
                }
            }
            validAttachments.add(com.newoether.agora.model.SelectedAttachment(
                uri = uri.toString(), type = type,
                mimeType = mimeType, fileName = fileName
            ))
        }
        if (rejectedMessages.isNotEmpty()) {
            rejectedMessage = rejectedMessages.joinToString("\n")
        }
        selectedAttachments = selectedAttachments + validAttachments
    }

    Column(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onCollapse) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px), contentDescription = stringResource(R.string.collapse), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).animateContentSize(tween(400))) {
        if (selectedAttachments.isNotEmpty() && !isExpanded) {
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedAttachments.size) { index ->
                    val attachment = selectedAttachments[index]
                    val uriStr = attachment.uri
                    val isVideo = attachment.type == "video"
                    val isPdf = attachment.type == "pdf"
                    val isFile = attachment.type == "file"
                    val isProcessing = uriStr in processingStates
                    val progress = processingStates[uriStr] ?: 0f

                    var videoThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(uriStr, isVideo) {
                        if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                videoThumb = withContext(Dispatchers.IO) {
                                    context.contentResolver.loadThumbnail(
                                        Uri.parse(uriStr), android.util.Size(128, 128), null
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(64.dp).padding(top = 5.dp)
                    ) {
                        Box {
                            val clickableMod = when {
                                isFile -> {
                                    if (onFileContentClick != null) Modifier.clickable {
                                        val content = readFileContent(context, uriStr)
                                        onFileContentClick(attachment.fileName ?: uriStr, content)
                                    } else Modifier
                                }
                                isPdf -> {
                                    if (onPdfPagesClick != null) Modifier.clickable {
                                        onPdfPagesClick(emptyList(), 0) // pages rendered on send, preview with placeholder
                                    } else Modifier
                                }
                                isVideo -> Modifier.clickable { onImageClick(uriStr) }
                                else -> Modifier.clickable { onImageClick(uriStr) }
                            }
                            val thumbModifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(clickableMod)

                            when {
                                isVideo && videoThumb != null -> {
                                    Image(
                                        bitmap = videoThumb!!.asImageBitmap(),
                                        contentDescription = stringResource(R.string.video_thumbnail),
                                        modifier = thumbModifier,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.play),
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(24.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            .padding(4.dp)
                                    )
                                }
                                isVideo -> {
                                    Box(
                                        modifier = thumbModifier
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Videocam,
                                            stringResource(R.string.video),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                isPdf -> {
                                    FileThumbnail(fileName = null, isPdf = true, modifier = thumbModifier)
                                }
                                isFile -> {
                                    FileThumbnail(fileName = attachment.fileName ?: uriStr, isPdf = false, modifier = thumbModifier)
                                }
                                else -> {
                                    coil.compose.AsyncImage(
                                        model = uriStr,
                                        contentDescription = null,
                                        modifier = thumbModifier,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                            }

                            // Processing indicator overlay
                            if (isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 5.dp, y = (-5).dp)
                                .size(18.dp)
                                .background(Color.Black.copy(alpha = 0.8f), CircleShape)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable {
                                    // Clean up pre-extracted frame files
                                    val removed = selectedAttachments.getOrNull(index)
                                    if (removed?.processedFrames != null) {
                                        for (path in removed.processedFrames) {
                                            try { java.io.File(path).delete() } catch (_: Exception) {}
                                        }
                                    }
                                    selectedAttachments = selectedAttachments.toMutableList().also { it.removeAt(index) }
                                    processingStates = processingStates - uriStr
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                tint = Color.White,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                        }
                        if ((isFile || isPdf) && attachment.fileName != null) {
                            Text(
                                text = attachment.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).noOpBringIntoView()) {
            TextField(state = textFieldState, scrollState = scrollState, modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).focusRequester(focusRequester).verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)), placeholder = { Text(stringResource(R.string.ask_agora), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }, enabled = true, lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6), colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, cursorColor = MaterialTheme.colorScheme.primary), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
            if (!isExpanded) {
                val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp).size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px), contentDescription = stringResource(R.string.expand), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
            }
        }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp).shadow(4.dp, RoundedCornerShape(100), clip = false).background(MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp), RoundedCornerShape(100)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                var showAddMenu by remember { mutableStateOf(false) }
                var lastAddDismissTime by remember { mutableLongStateOf(0L) }
                ExposedDropdownMenuBox(
                    expanded = showAddMenu,
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (showAddMenu) {
                                showAddMenu = false
                            } else if (now - lastAddDismissTime > 200) {
                                showAddMenu = true
                            }
                        },
                        modifier = Modifier.size(32.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.add_attachment), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    ExposedDropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = {
                            if (showAddMenu) {
                                showAddMenu = false
                                lastAddDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.photos))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                photoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.videos))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                videoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.files))
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                fileLauncher.launch("*/*")
                            }
                        )
                    }
                }
                var activeMenu by remember { mutableStateOf<String?>(null) }
                var lastModelDismissTime by remember { mutableLongStateOf(0L) }
                var lastToolsDismissTime by remember { mutableLongStateOf(0L) }

                val modelId = selectedModel.removePrefix("models/").substringAfter(":")
                val provider = selectedModel.removePrefix("models/").substringBefore(":")
                
                val displayText = when {
                    isModelValid -> modelAliases[selectedModel] ?: ("$modelId ($provider)")
                    enabledModels.isNotEmpty() -> stringResource(R.string.select_model)
                    else -> stringResource(R.string.no_model_selected)
                }
                
                ExposedDropdownMenuBox(
                    expanded = activeMenu == "model",
                    onExpandedChange = { }
                ) {
                    TextButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (activeMenu == "model") {
                                activeMenu = null
                            } else if (now - lastModelDismissTime > 200) {
                                activeMenu = "model"
                            }
                        },
                        modifier = Modifier.height(38.dp).widthIn(max = 160.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        Text(
                            displayText, 
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis, 
                            color = if (isModelValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        ) 
                    }
                    
                    ExposedDropdownMenu(
                        expanded = activeMenu == "model", 
                        onDismissRequest = { 
                            if (activeMenu == "model") {
                                activeMenu = null
                                lastModelDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (enabledModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.models_no_models)) },
                                onClick = {
                                    activeMenu = null
                                    lastModelDismissTime = 0L // Reset to allow immediate re-open
                                },
                                enabled = false
                            )
                        } else {
                            enabledModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        val modelId = model.removePrefix("models/").substringAfter(":")
                                        val provider = model.removePrefix("models/").substringBefore(":")
                                        Text(modelAliases[model] ?: ("$modelId ($provider)"))
                                    },
                                    onClick = {
                                        onModelSelect(model)
                                        activeMenu = null
                                        lastModelDismissTime = 0L
                                    }
                                )
                            }
                        }
                    }
                }
                
                ExposedDropdownMenuBox(
                    expanded = activeMenu == "tools",
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = { 
                            val now = System.currentTimeMillis()
                            if (activeMenu == "tools") {
                                activeMenu = null
                            } else if (now - lastToolsDismissTime > 200) {
                                activeMenu = "tools"
                            }
                        }, 
                        modifier = Modifier.size(32.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    ) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.tools), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    ExposedDropdownMenu(
                        expanded = activeMenu == "tools",
                        onDismissRequest = {
                            if (activeMenu == "tools") {
                                activeMenu = null
                                lastToolsDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchTextFieldWidth = false,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val isGemini = provider.equals("google", ignoreCase = true)
                        if (isGemini) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.code_execution))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        ProviderBadge("Gemini")
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = codeExecutionEnabled,
                                        onCheckedChange = { onCodeExecutionToggle(it) },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                onClick = { onCodeExecutionToggle(!codeExecutionEnabled) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.google_search))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        ProviderBadge("Gemini")
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = googleSearchEnabled,
                                        onCheckedChange = { onGoogleSearchToggle(it) },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                onClick = { onGoogleSearchToggle(!googleSearchEnabled) }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.thinking))
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = thinkingEnabled,
                                    onCheckedChange = { onThinkingToggle(it) },
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = { onThinkingToggle(!thinkingEnabled) }
                        )
                        if (thinkingEnabled) {
                            val levels = listOf("low", "medium", "high")
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp).offset(y = (-10).dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                levels.forEach { level ->
                                    val selected = level == thinkingLevel
                                    val bgColor by animateColorAsState(
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        animationSpec = tween(250),
                                        label = "levelBg"
                                    )
                                    val textColor by animateColorAsState(
                                        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        animationSpec = tween(250),
                                        label = "levelText"
                                    )
                                    val fontWeight by animateFloatAsState(
                                        targetValue = if (selected) 1f else 0f,
                                        animationSpec = tween(250),
                                        label = "levelWeight"
                                    )
                                    Surface(
                                        onClick = { onThinkingLevelChange(level) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(50),
                                        color = bgColor,
                                        contentColor = textColor
                                    ) {
                                        Text(
                                            text = level.replaceFirstChar { it.uppercase() },
                                            modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (fontWeight > 0.5f) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.web_search))
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = webSearchEnabled,
                                    onCheckedChange = { onWebSearchToggle(it) },
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = { onWebSearchToggle(!webSearchEnabled) }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.shell_title))
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = shellEnabled,
                                    onCheckedChange = { onShellToggle(it) },
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = { onShellToggle(!shellEnabled) }
                        )
                    }
                }
            }
            // Pending send: wait for processing to finish, then auto-send
            val anyProcessing = processingStates.isNotEmpty()
            LaunchedEffect(pendingSend, anyProcessing) {
                if (pendingSend && !anyProcessing) {
                    onSendMessage(textFieldState.text.toString(), selectedAttachments)
                    selectedAttachments = emptyList()
                    textFieldState.edit { replace(0, length, "") }
                    onCollapse()
                    pendingSend = false
                }
            }
            val canSend = (textFieldState.text.isNotBlank() || selectedAttachments.isNotEmpty()) && !isLoading && isModelValid && !isSwitching
            val isActionable = (isLoading || canSend || pendingSend) && !isSwitching
            FloatingActionButton(
                onClick = {
                    if (isSwitching) return@FloatingActionButton
                    if (isLoading) onStopGeneration()
                    else if (pendingSend) {
                        pendingSend = false
                    }
                    else if (canSend) {
                        if (anyProcessing) {
                            pendingSend = true
                        } else {
                            onSendMessage(textFieldState.text.toString(), selectedAttachments)
                            selectedAttachments = emptyList()
                            textFieldState.edit { replace(0, length, "") }
                            onCollapse()
                        }
                    }
                },
                containerColor = if (isActionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isActionable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
            ) {
                if (pendingSend) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(if (isLoading) Icons.Default.Stop else Icons.Default.ArrowUpward, stringResource(R.string.action), modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    // File rejection dialog
    if (rejectedMessage != null) {
        AlertDialog(
            onDismissRequest = { rejectedMessage = null },
            title = { Text(stringResource(R.string.file_unsupported_title)) },
            text = { Text(rejectedMessage!!) },
            confirmButton = {
                TextButton(onClick = { rejectedMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // PDF page selection dialog
    if (showPdfPageDialog && pendingPdfUri != null) {
        PdfPageSelectDialog(
            totalPages = pendingPdfPages,
            onConfirm = { selection ->
                showPdfPageDialog = false
                selectedAttachments = selectedAttachments + com.newoether.agora.model.SelectedAttachment(
                    uri = pendingPdfUri!!, type = "pdf",
                    mimeType = pendingPdfMimeType,
                    fileName = pendingPdfFileName,
                    selectedPages = selection.selectedPages
                )
                pendingPdfUri = null
            },
            onDismiss = {
                showPdfPageDialog = false
                pendingPdfUri = null
            }
        )
    }

    // Video slice dialog
    if (showVideoSliceDialog && pendingVideoUri != null) {
        VideoSliceDialog(
            videoUri = pendingVideoUri!!,
            durationMs = pendingVideoDurationMs,
            onConfirm = { result ->
                showVideoSliceDialog = false
                val vidUri = result.uri
                val fileName = try {
                    val cursor = context.contentResolver.query(
                        Uri.parse(vidUri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) it.getString(idx) else null
                        } else null
                    }
                } catch (_: Exception) { null }
                val attachment = com.newoether.agora.model.SelectedAttachment(
                    uri = vidUri, type = "video",
                    frameCount = result.frameCount,
                    sliceIntervalMs = result.intervalMs,
                    fileName = fileName,
                    mimeType = "video/*"
                )
                selectedAttachments = selectedAttachments + attachment
                processingStates = processingStates + (vidUri to 0f)

                // Start frame extraction and store result paths
                coroutineScope.launch(Dispatchers.IO) {
                    val framePaths = extractVideoFrames(vidUri, result.frameCount, result.intervalMs)
                    selectedAttachments = selectedAttachments.map { a ->
                        if (a.uri == vidUri) a.copy(processedFrames = framePaths) else a
                    }
                }

                // Process next video in queue
                processNextVideo()
            },
            onDismiss = {
                showVideoSliceDialog = false
                // Process next video in queue
                processNextVideo()
            }
        )
    }
}

@Composable
private fun ProviderBadge(provider: String) {
    val badgeColor = when (provider.lowercase()) {
        "google", "gemini" -> MaterialTheme.colorScheme.onPrimaryContainer
        "anthropic" -> Color(0xFFD97757)
        "openai" -> Color(0xFF74AA9C)
        else -> MaterialTheme.colorScheme.primary
    }
    val badgeBackground = when (provider.lowercase()) {
        "google", "gemini" -> MaterialTheme.colorScheme.primaryContainer
        else -> badgeColor.copy(alpha = 0.15f)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = badgeBackground
    ) {
        Text(
            provider,
            color = badgeColor,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
