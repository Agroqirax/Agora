package com.newoether.agora.ui.chat

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image

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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.ThinkingControlPanel
import com.newoether.agora.ui.common.thinkingControlShortLabel
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.noOpBringIntoView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatBottomBar(
    onSendMessage: (String, List<com.newoether.agora.model.SelectedAttachment>) -> Boolean,
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
    thinkingBudgetEnabled: Boolean = false,
    thinkingBudgetTokens: Int = 4096,
    webSearchEnabled: Boolean = false,
    shellEnabled: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onThinkingToggle: (Boolean) -> Unit = {},
    onThinkingLevelChange: (String) -> Unit = {},
    onThinkingBudgetEnabledChange: (Boolean) -> Unit = {},
    onThinkingBudgetTokensChange: (Int) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onShellToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onImageClick: (String) -> Unit = {},
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfPreviewSelect: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfViewerClosed: (() -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    focusRequester: FocusRequester = FocusRequester(),
    isExpanded: Boolean = false,
    isExpandAnimating: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {},
    showWebSearch: Boolean = true,
    showShell: Boolean = true,
    onAdvancedClick: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)

    // No-op bring-into-view to prevent auto-scrolling on text field focus

    val composer = rememberChatComposerState()

    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    var showThinkingSheet by rememberSaveable { mutableStateOf(false) }

    // Restore PDF dialog after viewer closes
    LaunchedEffect(fullScreenViewerUrls) {
        if (fullScreenViewerUrls == null && composer.pdfDialogHiddenForPreview && composer.pendingPdfUri != null) {
            composer.showPdfPageDialog = true
            composer.pdfDialogHiddenForPreview = false
        }
    }

    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> composer.onPickImages(uris) }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> composer.onPickVideos(uris) }
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> composer.onPickFiles(uris, onInitPdfSelection) }

    Box(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = EnterTransition.None,
                exit = shrinkVertically(tween(250)) + fadeOut(tween(250))
            ) {
                Spacer(modifier = Modifier.height(44.dp))
            }

            Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).animateContentSize(tween(400))) {
        if (composer.selectedAttachments.isNotEmpty() && !isExpanded) {
            val allMediaUrls = composer.selectedAttachments.filter {
                it.type == "image" || it.type == "video"
            }.map { it.uri }
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(composer.selectedAttachments.size) { index ->
                    val attachment = composer.selectedAttachments[index]
                    val uriStr = attachment.uri
                    val isVideo = attachment.type == "video"
                    val isPdf = attachment.type == "pdf"
                    val isFile = attachment.type == "file"
                    val isProcessing = uriStr in composer.processingStates
                    val progress = composer.processingStates[uriStr] ?: 0f

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
                                        val allPaths = attachment.preRenderedPaths ?: emptyList()
                                        val sel = attachment.selectedPages
                                        val paths = if (sel != null && allPaths.isNotEmpty()) {
                                            allPaths.filterIndexed { i, _ -> i in sel }
                                        } else allPaths
                                        onPdfPagesClick(paths, 0)
                                    } else Modifier
                                }
                                isVideo -> {
                                    val mediaIndex = allMediaUrls.indexOf(uriStr).coerceAtLeast(0)
                                    Modifier.combinedClickable(
                                        onClick = { onAllMediaClick?.invoke(allMediaUrls, mediaIndex) },
                                        onLongClick = { haptics.longPress() }
                                    )
                                }
                                else -> {
                                    val mediaIndex = allMediaUrls.indexOf(uriStr).coerceAtLeast(0)
                                    Modifier.combinedClickable(
                                        onClick = { onAllMediaClick?.invoke(allMediaUrls, mediaIndex) },
                                        onLongClick = { haptics.longPress() }
                                    )
                                }
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
                                    composer.removeAttachmentAt(index)
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
            TextField(
                state = textFieldState,
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                    .focusRequester(focusRequester)
                    .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                placeholder = {
                    Text(
                        stringResource(R.string.ask_agora),
                        style = ChatType.input,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                enabled = true,
                lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface)
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = !isExpanded,
                enter = fadeIn(tween(250)),
                exit = ExitTransition.None,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                IconButton(onClick = { if (!isExpandAnimating) onExpand() }, modifier = Modifier.padding(end = 4.dp, top = 4.dp).size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px), contentDescription = stringResource(R.string.expand), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
            }
        }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp).background(MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp), RoundedCornerShape(100)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                var showAddMenu by remember { mutableStateOf(false) }
                var lastAddDismissTime by remember { mutableLongStateOf(0L) }
                ExposedDropdownMenuBox(
                    expanded = showAddMenu,
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = {
                            haptics.action()
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
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                                    Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.photos))
                                }
                            },
                            onClick = {
                                haptics.selection()
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
                                haptics.selection()
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
                                haptics.selection()
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

                val parsed = com.newoether.agora.model.ModelId.parse(selectedModel)
                val modelId = parsed.modelName.removePrefix("models/")
                val provider = parsed.providerName
                
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
                            haptics.action()
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
                            color = if (isModelValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                                        val parsed = com.newoether.agora.model.ModelId.parse(model)
                                        val modelId = parsed.modelName.removePrefix("models/")
                                        val provider = parsed.providerName
                                        Text(modelAliases[model] ?: ("$modelId ($provider)"))
                                    },
                                    onClick = {
                                        haptics.selection()
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
                            haptics.action()
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
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                        val isGemini = provider.equals("google", ignoreCase = true) && isModelValid
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
                                    Column {
                                        Text(stringResource(R.string.thinking))
                                        Text(
                                            text = thinkingControlShortLabel(
                                                thinkingEnabled,
                                                thinkingLevel,
                                                thinkingBudgetEnabled,
                                                thinkingBudgetTokens
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = thinkingEnabled,
                                    onCheckedChange = { onThinkingToggle(it) },
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = {
                                haptics.action()
                                activeMenu = null
                                showThinkingSheet = true
                            }
                        )
                        if (showWebSearch) {
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
                        }
                        if (showShell) {
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
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.advanced_settings))
                                }
                            },
                            // Unlike the toggle rows, this opens a dialog — collapse the menu first.
                            onClick = { haptics.action(); activeMenu = null; onAdvancedClick() }
                        )
                    }
                }
            }
            // Pending send: wait for processing to finish, then auto-send
            val anyProcessing = composer.processingStates.isNotEmpty()
            LaunchedEffect(composer.pendingSend, anyProcessing) {
                if (composer.pendingSend && !anyProcessing) {
                    if (onSendMessage(textFieldState.text.toString(), composer.selectedAttachments)) {
                        composer.clearAttachments()
                        textFieldState.edit { replace(0, length, "") }
                        onCollapse()
                    }
                    composer.pendingSend = false
                }
            }
            val canSend = (textFieldState.text.isNotBlank() || composer.selectedAttachments.isNotEmpty()) && !isLoading && isModelValid && !isSwitching
            val isActionable = (isLoading || canSend || composer.pendingSend) && !isSwitching
            FloatingActionButton(
                onClick = {
                    if (isSwitching) return@FloatingActionButton
                    if (isLoading) onStopGeneration()
                    else if (composer.pendingSend) {
                        haptics.selection()
                        composer.pendingSend = false
                    }
                    else if (canSend) {
                        if (anyProcessing) {
                            haptics.action()
                            composer.pendingSend = true
                        } else {
                            if (onSendMessage(textFieldState.text.toString(), composer.selectedAttachments)) {
                                composer.clearAttachments()
                                textFieldState.edit { replace(0, length, "") }
                                onCollapse()
                            }
                        }
                    }
                },
                containerColor = animateColorAsState(if (isActionable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, tween(400), label = "fabContainer").value,
                contentColor = animateColorAsState(if (isActionable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(400), label = "fabContent").value,
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
            ) {
                val fabIcon = when {
                    composer.pendingSend -> "pending"
                    isLoading -> "stop"
                    else -> "send"
                }
                AnimatedContent(
                    targetState = fabIcon,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "fabIcon"
                ) { state ->
                    when (state) {
                        "pending" -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        "stop" -> Icon(Icons.Default.Stop, stringResource(R.string.action), modifier = Modifier.size(24.dp))
                        else -> Icon(Icons.Default.ArrowUpward, stringResource(R.string.action), modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
        ) {
            val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            IconButton(onClick = { if (!isExpandAnimating) onCollapse() }, modifier = Modifier.size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px), contentDescription = stringResource(R.string.collapse), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
        }
    }

    if (showThinkingSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThinkingSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                ThinkingControlPanel(
                    enabled = thinkingEnabled,
                    level = thinkingLevel,
                    budgetEnabled = thinkingBudgetEnabled,
                    budgetTokens = thinkingBudgetTokens,
                    onEnabledChange = onThinkingToggle,
                    onLevelChange = onThinkingLevelChange,
                    onBudgetEnabledChange = onThinkingBudgetEnabledChange,
                    onBudgetTokensChange = onThinkingBudgetTokensChange,
                    providerName = com.newoether.agora.model.ModelId.parse(selectedModel).providerName,
                    animateSections = true
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // File rejection dialog
    if (composer.rejectedMessage != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { composer.rejectedMessage = null },
            title = { Text(stringResource(R.string.file_unsupported_title), fontWeight = FontWeight.Bold) },
            text = { Text(composer.rejectedMessage!!) },
            confirmButton = {
                TextButton(onClick = { composer.rejectedMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // PDF page selection dialog
    if (composer.showPdfPageDialog && composer.pendingPdfUri != null) {
        PdfPageSelectDialog(
            totalPages = composer.pendingPdfPages,
            thumbnailPaths = composer.pendingPdfRenderedPaths,
            isLoading = composer.pendingPdfIsRendering,
            renderProgress = composer.pendingPdfRenderProgress,
            selectedPages = pdfViewerSelection,
            onTogglePage = { onTogglePdfSelection?.invoke(it) },
            onSelectAll = { select -> onTogglePdfSelection?.let { toggle ->
                (0 until composer.pendingPdfPages.coerceAtLeast(1)).forEach { i ->
                    if ((i in pdfViewerSelection) != select) toggle(i)
                }
            }},
            onPreviewPage = { index ->
                composer.showPdfPageDialog = false
                composer.pdfDialogHiddenForPreview = true
                onPdfPreviewSelect?.invoke(composer.pendingPdfRenderedPaths, index)
            },
            onConfirm = { selection ->
                composer.showPdfPageDialog = false
                val rendered = composer.pendingPdfRenderedPaths
                val sel = selection.selectedPages
                // Keep only the selected pages; delete the rest so unselected pages don't
                // pile up in filesDir. The kept paths are re-indexed 0..n so the attachment
                // and the send path (which filters preRenderedPaths by selectedPages) stay in sync.
                val keptPaths = rendered.filterIndexed { i, _ -> i in sel }
                rendered.filterIndexedTo(mutableListOf()) { i, _ -> i !in sel }
                    .forEach { runCatching { java.io.File(it).delete() } }
                composer.selectedAttachments = composer.selectedAttachments + com.newoether.agora.model.SelectedAttachment(
                    uri = composer.pendingPdfUri!!, type = "pdf",
                    mimeType = composer.pendingPdfMimeType,
                    fileName = composer.pendingPdfFileName,
                    selectedPages = keptPaths.indices.toSet(),
                    preRenderedPaths = keptPaths
                )
                composer.pendingPdfUri = null
                composer.pendingPdfRenderedPaths = emptyList()
            },
            onDismiss = {
                composer.showPdfPageDialog = false
                // Cancel an in-flight render (renderAllPages deletes its own partial files on
                // cancellation) and delete any fully-rendered pages — nothing was attached.
                composer.pdfRenderJob?.cancel()
                composer.pdfRenderJob = null
                composer.pendingPdfRenderedPaths.forEach { runCatching { java.io.File(it).delete() } }
                composer.pendingPdfUri = null
                composer.pendingPdfRenderedPaths = emptyList()
                composer.pendingPdfIsRendering = false
            }
        )
    }

    // Video slice dialog
    if (composer.showVideoSliceDialog && composer.pendingVideoUri != null) {
        VideoSliceDialog(
            videoUri = composer.pendingVideoUri!!,
            durationMs = composer.pendingVideoDurationMs,
            onConfirm = { result ->
                composer.showVideoSliceDialog = false
                composer.addSlicedVideo(result.uri, result.frameCount, result.intervalMs)
                // Process next video in queue
                composer.processNextVideo()
            },
            onDismiss = {
                composer.showVideoSliceDialog = false
                // Process next video in queue
                composer.processNextVideo()
            }
        )
    }
}
