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
import androidx.compose.ui.platform.LocalContext
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
    onSendMessage: (String, List<String>) -> Unit,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String> = emptyMap(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    thinkingEnabled: Boolean = true,
    webSearchEnabled: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onThinkingToggle: (Boolean) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onImageClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    isExpanded: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)

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
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selectedImageUris = selectedImageUris + uris.map { it.toString() }
    }

    Column(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight().statusBarsPadding() else Modifier).animateContentSize(tween(400)).padding(8.dp)) {
        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onCollapse) { Icon(Icons.Default.CloseFullscreen, "Collapse", tint = MaterialTheme.colorScheme.primary) }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).animateContentSize(tween(400))) {
        if (selectedImageUris.isNotEmpty() && !isExpanded) {
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedImageUris) { uriStr ->
                    val context = LocalContext.current
                    val mimeType = remember(uriStr) {
                        try {
                            context.contentResolver.getType(Uri.parse(uriStr))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val isVideo = mimeType?.startsWith("video/") == true
                    val isFile = mimeType != null && !mimeType.startsWith("image/") && !mimeType.startsWith("video/")
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
                        modifier = Modifier.width(64.dp)
                    ) {
                        Box {
                            val thumbModifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .then(if (!isFile) Modifier.clickable { onImageClick(uriStr) } else Modifier)

                            when {
                                isVideo && videoThumb != null -> {
                                    Image(
                                        bitmap = videoThumb!!.asImageBitmap(),
                                        contentDescription = "Video thumbnail",
                                        modifier = thumbModifier,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play",
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
                                            "Video",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                isFile -> {
                                    Box(
                                        modifier = thumbModifier
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.AttachFile,
                                            "File",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
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
                        if (isFile) {
                            val fileName = remember(uriStr) {
                                try {
                                    val cursor = context.contentResolver.query(
                                        Uri.parse(uriStr), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                                    )
                                    cursor?.use {
                                        if (it.moveToFirst()) {
                                            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                            if (idx >= 0) it.getString(idx) else null
                                        } else null
                                    }
                                } catch (_: Exception) { null }
                            } ?: uriStr.substringAfterLast("/")
                            Text(
                                text = fileName,
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

        Box(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).bringIntoViewResponder(noOpResponder)) {
            TextField(state = textFieldState, scrollState = scrollState, modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)), placeholder = { Text("Ask Agora anything...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }, enabled = true, lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6), colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, cursorColor = MaterialTheme.colorScheme.primary), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
            if (!isExpanded) {
                val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp).size(32.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(Icons.Default.OpenInFull, "Expand", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) }
            }
        }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(100)).padding(horizontal = 8.dp, vertical = 2.dp)) {
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
                        modifier = Modifier.size(32.dp).menuAnchor()
                    ) {
                        Icon(Icons.Default.Add, "Add Attachment", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        focusable = false,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Photos")
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Videos")
                                }
                            },
                            onClick = {
                                showAddMenu = false
                                lastAddDismissTime = 0L
                                launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Files")
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
                    enabledModels.isNotEmpty() -> "Select Model"
                    else -> "No model selected"
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
                        modifier = Modifier.widthIn(max = 160.dp).menuAnchor(),
                        contentPadding = PaddingValues(start = 12.dp, end = 8.dp)
                    ) { 
                        Text(
                            displayText, 
                            style = MaterialTheme.typography.labelLarge, 
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
                        focusable = false,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (enabledModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models enabled") }, 
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
                        modifier = Modifier.size(32.dp).menuAnchor()
                    ) { 
                        Icon(Icons.Default.MoreVert, "Tools", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) 
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
                        focusable = false,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val isGemini = provider.equals("google", ignoreCase = true)
                        if (isGemini) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("Code Execution")
                                        Spacer(modifier = Modifier.width(6.dp))
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
                                        Text("Google Search")
                                        Spacer(modifier = Modifier.width(6.dp))
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
                                    Text("Thinking")
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
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Web Search")
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
                        onCollapse()
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

@Composable
private fun ProviderBadge(provider: String) {
    val badgeColor = when (provider.lowercase()) {
        "google", "gemini" -> Color(0xFF4285F4)
        "anthropic" -> Color(0xFFD97757)
        "openai" -> Color(0xFF74AA9C)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = badgeColor.copy(alpha = 0.15f)
    ) {
        Text(
            provider,
            color = badgeColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
