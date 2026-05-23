package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.components.TypewriterText
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onImageClick: (String) -> Unit,
    onFileContentClick: ((String, String) -> Unit)? = null,
    onPdfPagesClick: ((List<String>, Int) -> Unit)? = null,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { newValue ->
            if (newValue != DrawerValue.Closed) {
                focusManager.clearFocus()
            }
            true
        }
    )

    val conversations by viewModel.conversations.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val allMessages by viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val generatingInConversationId by viewModel.generatingInConversationId.collectAsState()
    val selectedModel by viewModel.currentActiveModel.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val modelAliases by viewModel.modelAliases.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val visualizeContextRollout by viewModel.visualizeContextRollout.collectAsState()
    val maxContextWindow by viewModel.maxContextWindow.collectAsState()
    val codeExecutionEnabled by viewModel.codeExecutionEnabled.collectAsState()
    val googleSearchEnabled by viewModel.googleSearchEnabled.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val thinkingLevel by viewModel.thinkingLevel.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val shellEnabled by viewModel.shellEnabled.collectAsState()

    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.activeSystemPromptId.collectAsState()

    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var conversationToRename by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val drawerWidth = configuration.screenWidthDp.dp * 0.8f
    var bottomBarHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    // Bottom offset to clear the Settings button in the drawer.
    var settingsButtonTopDp by remember { mutableFloatStateOf(80f) }
    val targetSnackbarOffset = if (drawerProgress <= 0.5f) {
        bottomBarHeight
    } else {
        val t = ((drawerProgress - 0.5f) * 2f).coerceIn(0f, 1f)
        (bottomBarHeight.value + (settingsButtonTopDp - bottomBarHeight.value) * t).dp
    }
    LaunchedEffect(targetSnackbarOffset) { onSnackbarOffsetChanged(targetSnackbarOffset) }
    val listState = viewModel.listState
    val textFieldState = rememberSaveable(saver = androidx.compose.foundation.text.input.TextFieldState.Saver) { androidx.compose.foundation.text.input.TextFieldState() }
    val inputFocusRequester = remember { FocusRequester() }

    val messageHeights = viewModel.messageHeights
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    var showLaunchContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        showLaunchContent = true
        inputFocusRequester.requestFocus()
    }

    suspend fun scrollToLastUserMessage(animate: Boolean = true, targetMessageId: String? = null) {
        if (messages.isEmpty() || viewportHeightPx == 0) return

        val targetIndex = if (targetMessageId != null) {
            val msg = messages.find { it.id == targetMessageId }
            if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                messages.indexOfFirst { it.id == msg.parentId }
            } else {
                messages.indexOfFirst { it.id == targetMessageId }
            }
        } else {
            messages.indexOfLast { it.participant == Participant.USER }
        }
        if (targetIndex == -1) return

        with(density) {
            val targetTopPx = 140.dp.toPx()
            val topPaddingPx = 140.dp.toPx()

            var totalHeightBeforePx = 0
            var hasAnyHeight = false
            for (i in 0 until targetIndex) {
                val h = messageHeights[messages[i].id]
                if (h != null) { totalHeightBeforePx += h; hasAnyHeight = true }
            }

            if (!hasAnyHeight && targetIndex > 0) {
                listState.scrollToItem(targetIndex, 0)
            } else {
                val targetScrollPx = (topPaddingPx + totalHeightBeforePx - targetTopPx).coerceAtLeast(0f)

                if (animate) {
                    var currentOffsetPx = listState.firstVisibleItemScrollOffset.toFloat()
                    for (i in 0 until listState.firstVisibleItemIndex) {
                        if (i < messages.size) {
                            currentOffsetPx += (messageHeights[messages[i].id] ?: 0)
                        }
                    }

                    val diff = targetScrollPx - currentOffsetPx
                    if (kotlin.math.abs(diff) > 2) {
                        listState.animateScrollBy(diff, tween(500, easing = FastOutSlowInEasing))
                    }
                } else {
                    listState.scrollToItem(0, targetScrollPx.toInt())
                }
            }
        }
    }

    val branchSwitchTrigger by viewModel.branchSwitchTrigger.collectAsState()

    LaunchedEffect(currentConversationId, branchSwitchTrigger) {
        if (currentConversationId != null) {
            snapshotFlow { messages }.filter { it.isNotEmpty() }.first()

            val targetIndex = if (branchSwitchTrigger != null) {
                val msg = messages.find { it.id == branchSwitchTrigger }
                if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                    messages.indexOfFirst { it.id == msg.parentId }
                } else {
                    messages.indexOfFirst { it.id == branchSwitchTrigger }
                }
            } else {
                messages.indexOfLast { it.participant == Participant.USER }
            }

            if (targetIndex != -1) {
                try {
                    withTimeout(4000) {
                        snapshotFlow {
                            val sum = messageHeights.values.sum()
                            Triple(messages, sum, viewportHeightPx)
                        }.collectLatest { data ->
                            val currentMsgs = data.component1()
                            val vHeight = data.component3()

                            val currentTargetIndex = if (branchSwitchTrigger != null) {
                                val msg = currentMsgs.find { it.id == branchSwitchTrigger }
                                if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                                    currentMsgs.indexOfFirst { it.id == msg.parentId }
                                } else {
                                    currentMsgs.indexOfFirst { it.id == branchSwitchTrigger }
                                }
                            } else {
                                currentMsgs.indexOfLast { it.participant == Participant.USER }
                            }

                            if (currentTargetIndex != -1 && vHeight > 0) {
                                with(density) {
                                    var totalHeightBeforePx = 0
                                    for (i in 0 until currentTargetIndex) {
                                        totalHeightBeforePx += messageHeights[currentMsgs[i].id] ?: 0
                                    }
                                    listState.scrollToItem(currentTargetIndex, 0)
                                }
                            }

                            delay(500)
                            this@withTimeout.cancel()
                        }
                    }
                } catch (e: Exception) {
                    // Timeout or intended cancellation
                }
            }
            viewModel.setSwitching(false)
        } else {
            viewModel.setSwitching(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToMessage.collect { messageId ->
            if (messageId != null) {
                try {
                    withTimeout(2000) {
                        snapshotFlow { messages.indexOfFirst { it.id == messageId } }
                            .filter { it != -1 }
                            .first()
                    }
                } catch (e: Exception) {
                    // Timeout
                }
                delay(50)
                scrollToLastUserMessage(animate = true, targetMessageId = messageId)
            } else {
                scrollToLastUserMessage(animate = true)
            }
        }
    }

    BackHandler(enabled = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        focusManager.clearFocus()
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue != DrawerValue.Closed) {
            isExpanded = false
        }
        focusManager.clearFocus()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = DrawerDefaults.scrimColor,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 2.dp,
                modifier = Modifier
                    .width(drawerWidth)
                    .onGloballyPositioned { coords ->
                        val x = coords.positionInWindow().x
                        if (!x.isNaN() && drawerWidthPx > 0f) {
                            drawerProgress = (1f + x / drawerWidthPx).coerceIn(0f, 1f)
                        }
                    }
                    .graphicsLayer {
                        clip = true
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { focusManager.clearFocus() }
                ) {
                    Text(stringResource(R.string.conversations), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    var searchQuery by remember { mutableStateOf("") }
                    var searchResults by remember { mutableStateOf<List<Pair<MessageEntity, Float>>>(emptyList()) }
                    var isSearchActive by remember { mutableStateOf(false) }

                    val manualSearchMethod by viewModel.manualSearchMethod.collectAsState()

                    LaunchedEffect(searchQuery) {
                        if (searchQuery.isBlank()) {
                            searchResults = emptyList()
                            isSearchActive = false
                        } else {
                            delay(200)
                            if (searchQuery.isNotBlank()) {
                                searchResults = if (manualSearchMethod == "rag")
                                    viewModel.semanticSearch(searchQuery)
                                else
                                    viewModel.searchMessages(searchQuery).map { it to 0f }
                                isSearchActive = true
                            }
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                                }
                            }
                        },
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isSearchActive) {
                        Button(
                            onClick = {
                                viewModel.createNewChat()
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSwitching,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.new_chat))
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (isSearchActive && searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (isSearchActive) {
                            val grouped = searchResults.groupBy { it.first.conversationId }
                            val titleMap = conversations.associate { it.id to it.title }
                            items(grouped.entries.toList()) { (convId, entries) ->
                                val bestScore = entries.maxOfOrNull { it.second } ?: 0f
                                SearchResultItem(
                                    title = titleMap[convId] ?: stringResource(R.string.unknown),
                                    messages = entries.map { it.first },
                                    score = bestScore,
                                    query = searchQuery,
                                    onClick = {
                                        viewModel.selectConversation(convId)
                                        scope.launch { drawerState.close() }
                                    }
                                )
                            }
                        } else {
                            items(conversations) { conversation ->
                                val isSelected = conversation.id == currentConversationId
                                var showMenu by remember { mutableStateOf(false) }
                                var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
                                var lastPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                                val density = LocalDensity.current

                                Box {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clip(CircleShape)
                                            .pointerInput(showMenu) {
                                                if (!showMenu) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                                            lastPosition = event.changes.first().position
                                                        }
                                                    }
                                                }
                                            }
                                            .combinedClickable(
                                                enabled = !isSwitching,
                                                onClick = {
                                                    viewModel.selectConversation(conversation.id)
                                                    scope.launch { drawerState.close() }
                                                },
                                                onLongClick = {
                                                    pressOffset = with(density) {
                                                        val x = lastPosition.x.toDp().coerceIn(16.dp, 200.dp)
                                                        DpOffset(x, lastPosition.y.toDp() - 28.dp)
                                                    }
                                                    showMenu = true
                                                }
                                            ),
                                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            text = conversation.title,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            maxLines = 1,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        offset = pressOffset,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.generate_title)) },
                                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                            enabled = !isSwitching && !isLoading,
                                            onClick = {
                                                showMenu = false
                                                viewModel.generateTitle(conversation.id)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rename)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        enabled = !isSwitching && !isLoading,
                                        onClick = {
                                            showMenu = false
                                            showRenameDialog = conversation.id
                                            conversationToRename = conversation.title
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = if (!isSwitching && !isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = if (!isSwitching && !isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        enabled = !isSwitching && !isLoading,
                                        onClick = {
                                            showMenu = false
                                            showDeleteConfirmDialog = conversation.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    FilledTonalButton(
                        onClick = {
                            focusManager.clearFocus()
                            onOpenSettings()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val screenHeightPx = configuration.screenHeightDp * density.density
                                val buttonTopPx = coords.positionInWindow().y
                                settingsButtonTopDp = (screenHeightPx - buttonTopPx) / density.density
                            },
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings))
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .onSizeChanged { viewportHeightPx = it.height }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            TopAppBar(
                                modifier = Modifier.statusBarsPadding(),
                                title = {
                                    val currentTitle = if (isNewChatMode) stringResource(R.string.app_name) else conversations.find { it.id == currentConversationId }?.title ?: stringResource(R.string.app_name)
                                    Column {
                                        if (isNewChatMode) {
                                            Text(currentTitle, fontWeight = FontWeight.Bold)
                                        } else {
                                            Text(currentTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        }
                                        if (!isNewChatMode && totalTokens > 0) {
                                            Text(stringResource(R.string.total_tokens, totalTokens), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { focusManager.clearFocus(); scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { showPromptDialog = true }) {
                                        Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.system_prompt))
                                    }
                                    IconButton(onClick = { focusManager.clearFocus(); isExpanded = false; viewModel.createNewChat() }) {
                                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat))
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background,
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    val topBarH = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                    val pivotY = ((LocalConfiguration.current.screenHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2f) / LocalConfiguration.current.screenHeightDp
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            val targetNewChat = targetState.first
                            val targetShowLaunch = targetState.second
                            val initialShowLaunch = initialState.second

                            if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialState.first)) {
                                val enterSpec = tween<Float>(700, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f))
                                val fadeInSpec = tween<Float>(500)
                                (fadeIn(animationSpec = fadeInSpec) + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, pivotY), animationSpec = enterSpec))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            } else {
                                fadeIn(animationSpec = tween(300))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            }
                        },
                        label = "MainContentTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (targetNewChat, targetShowLaunch) ->
                        if (!targetNewChat) {
                            MessageList(
                                messages = messages,
                                allMessages = allMessages,
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                                isLoading = isLoading && generatingInConversationId == currentConversationId,
                                isSwitching = isSwitching,
                                visualizeContextRollout = visualizeContextRollout,
                                maxContextWindow = maxContextWindow,
                                modelAliases = modelAliases,
                                bottomBarHeight = bottomBarHeight,
                                viewportHeight = viewportHeightPx,
                                messageHeights = messageHeights,
                                onEditMessage = { id, text ->
                                    val isFirstMessage = messages.isEmpty()
                                    viewModel.editMessage(id, text)
                                    scope.launch {
                                        if (!isFirstMessage) {
                                            delay(50)
                                            scrollToLastUserMessage(animate = true)
                                        }
                                    }
                                },
                                onSwitchBranch = { parentId, direction -> viewModel.switchBranch(parentId, direction) },
                                onRegenerate = { id ->
                                    viewModel.regenerate(id)
                                    scope.launch {
                                        delay(50)
                                        scrollToLastUserMessage(animate = true)
                                    }
                                },
                                onImageClick = onImageClick,
                                onFileContentClick = onFileContentClick,
                                onPdfPagesClick = onPdfPagesClick,
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = 140.dp,
                                    bottom = bottomBarHeight + 8.dp
                                )
                            )
                        } else if (targetShowLaunch) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = bottomBarHeight),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    TypewriterText(
                                        text = stringResource(R.string.welcome_to_agora),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(top = ((LocalConfiguration.current.screenHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2).dp)
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    AnimatedVisibility(
                        visible = isSwitching && !isTransitioningToNewChat,
                        enter = fadeIn(animationSpec = tween(200)),
                        exit = fadeOut(animationSpec = tween(200))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                    .onSizeChanged {
                        bottomBarHeightPx = it.height.toFloat()
                    },
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ChatBottomBar(
                        onSendMessage = { text, attachments ->
                            viewModel.sendMessage(text, attachments = attachments)
                            scope.launch {
                                delay(100)
                                scrollToLastUserMessage(animate = true)
                            }
                        },
                        onStopGeneration = { viewModel.stopGeneration() },
                        isLoading = isLoading,
                        isSwitching = isSwitching,
                        enabledModels = enabledModels,
                        selectedModel = selectedModel,
                        modelAliases = modelAliases,
                        codeExecutionEnabled = codeExecutionEnabled,
                        googleSearchEnabled = googleSearchEnabled,
                        thinkingEnabled = thinkingEnabled,
                        thinkingLevel = thinkingLevel,
                        onCodeExecutionToggle = { viewModel.setCodeExecutionEnabled(it) },
                        onGoogleSearchToggle = { viewModel.setGoogleSearchEnabled(it) },
                        onThinkingToggle = { viewModel.setThinkingEnabled(it) },
                        onThinkingLevelChange = { viewModel.setThinkingLevel(it) },
                        webSearchEnabled = webSearchEnabled,
                        onWebSearchToggle = { viewModel.setWebSearchEnabled(it) },
                        shellEnabled = shellEnabled,
                        onShellToggle = { viewModel.setShellEnabled(it) },
                        onModelSelect = { viewModel.setActiveModel(it) },
                        onImageClick = onImageClick,
                        onFileContentClick = { name, content -> viewModel.showFilePreview(name, content) },
                        modifier = Modifier,
                        textFieldState = textFieldState,
                        focusRequester = inputFocusRequester,
                        isExpanded = isExpanded,
                        onCollapse = { isExpanded = false },
                        onExpand = { isExpanded = true }
                    )
                }
            }
        }
    }

    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text(stringResource(R.string.rename_chat)) },
            text = {
                val fm = LocalFocusManager.current
                Box(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                    value = conversationToRename,
                    onValueChange = { conversationToRename = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(showRenameDialog!!, conversationToRename)
                    showRenameDialog = null
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text(stringResource(R.string.delete_chat)) },
            text = { Text(stringResource(R.string.delete_chat_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(showDeleteConfirmDialog!!)
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showPromptDialog) {
        val currentConversation = conversations.find { it.id == currentConversationId }
        val pendingPrompt by viewModel.pendingSystemPromptId.collectAsState()
        var selectedPromptId by remember(currentConversationId, pendingPrompt, currentConversation?.systemPromptId) {
            mutableStateOf(if (isNewChatMode) pendingPrompt else currentConversation?.systemPromptId)
        }

        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text(stringResource(R.string.conversation_prompt)) },
            text = {
                LazyColumn {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selectedPromptId = null }.padding(8.dp)
                        ) {
                            RadioButton(
                                selected = selectedPromptId == null,
                                onClick = { selectedPromptId = null }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val globalDefaultTitle = systemPrompts.find { it.id == activeSystemPromptId }?.title ?: stringResource(R.string.no_system_prompt)
                            Text(stringResource(R.string.global_default_format, globalDefaultTitle))
                        }
                    }
                    items(systemPrompts) { prompt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { selectedPromptId = prompt.id }.padding(8.dp)
                        ) {
                            RadioButton(
                                selected = selectedPromptId == prompt.id,
                                onClick = { selectedPromptId = prompt.id }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(prompt.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isNewChatMode) {
                        viewModel.setPendingSystemPrompt(selectedPromptId)
                    } else {
                        currentConversationId?.let { id ->
                            viewModel.setConversationSystemPrompt(id, selectedPromptId)
                        }
                    }
                    showPromptDialog = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SearchResultItem(
    title: String,
    messages: List<MessageEntity>,
    score: Float = 0f,
    query: String,
    onClick: () -> Unit
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun snippetAroundMatch(text: String, q: String, radius: Int = 20): String {
        val idx = text.lowercase().indexOf(q.lowercase())
        if (idx < 0 || text.length <= radius * 2 + q.length) return text
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + q.length + radius).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end) + suffix
    }

    fun highlight(text: String): androidx.compose.ui.text.AnnotatedString {
        if (query.isBlank()) return androidx.compose.ui.text.AnnotatedString(text)
        return buildAnnotatedString {
            var last = 0
            val lowerText = text.lowercase()
            val lowerQuery = query.lowercase()
            var idx = lowerText.indexOf(lowerQuery, last)
            while (idx >= 0) {
                append(text.substring(last, idx))
                withStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold)) {
                    append(text.substring(idx, idx + query.length))
                }
                last = idx + query.length
                idx = lowerText.indexOf(lowerQuery, last)
            }
            append(text.substring(last))
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlight(title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (score > 0f) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            messages.take(2).forEach { msg ->
                val role = if (msg.participant == Participant.USER)
                    stringResource(R.string.search_role_user)
                else
                    stringResource(R.string.search_role_model)
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = textColor)) { append("$role: ") }
                        withStyle(SpanStyle(color = textColor)) { append(highlight(snippetAroundMatch(msg.text, query))) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}
