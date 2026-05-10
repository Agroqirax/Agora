package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SystemPromptEntry

private fun variableDisplayName(key: String): String = when (key) {
    PredefinedVariables.TIME -> "Time"
    PredefinedVariables.DATE -> "Date"
    PredefinedVariables.ACTIVE_MEMORY -> "Active Memory"
    PredefinedVariables.MODEL_ID -> "Model ID"
    else -> key
}

private fun variableIcon(key: String): ImageVector = when (key) {
    PredefinedVariables.TIME -> Icons.Default.Schedule
    PredefinedVariables.DATE -> Icons.Default.CalendarMonth
    PredefinedVariables.ACTIVE_MEMORY -> Icons.Default.Memory
    PredefinedVariables.MODEL_ID -> Icons.Default.Info
    else -> Icons.Default.Info
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptEditorPage(
    entry: SystemPromptEntry?,
    onSave: (
        title: String,
        systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>,
        userPostpendItems: List<PromptTemplateItem>
    ) -> Unit,
    onBack: () -> Unit
) {
    val isEdit = entry != null
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var selectedTab by remember { mutableIntStateOf(0) }

    val systemItems = remember {
        mutableStateListOf<PromptTemplateItem>().also { list ->
            entry?.resolvedSystemItems?.let { list.addAll(it) }
        }
    }
    val userPrependItems = remember {
        mutableStateListOf<PromptTemplateItem>().also { list ->
            entry?.userPrependItems?.let { list.addAll(it) }
        }
    }
    val userPostpendItems = remember {
        mutableStateListOf<PromptTemplateItem>().also { list ->
            entry?.userPostpendItems?.let { list.addAll(it) }
        }
    }

    var showVariablePicker by remember { mutableStateOf(false) }
    var insertAtIndex by remember { mutableIntStateOf(-1) }
    var titleError by remember { mutableStateOf(false) }

    val currentItems: MutableList<PromptTemplateItem> = when (selectedTab) {
        0 -> systemItems
        1 -> userPrependItems
        else -> userPostpendItems
    }

    BackHandler(enabled = showVariablePicker) {
        showVariablePicker = false
    }
    BackHandler(enabled = !showVariablePicker) {
        onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) stringResource(R.string.template_edit_title) else stringResource(R.string.template_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        if (title.isBlank()) {
                            titleError = true
                            return@TextButton
                        }
                        onSave(title, systemItems.toList(), userPrependItems.toList(), userPostpendItems.toList())
                    }) {
                        Text(stringResource(R.string.provider_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text(stringResource(R.string.prompts_title_hint)) },
                isError = titleError,
                supportingText = if (titleError) {{ Text(stringResource(R.string.template_title_required)) }} else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            stringResource(R.string.template_tab_system),
                            maxLines = 1,
                            color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            stringResource(R.string.template_tab_prepend),
                            maxLines = 1,
                            color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            stringResource(R.string.template_tab_postpend),
                            maxLines = 1,
                            color = if (selectedTab == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            // Tab content
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(200)) + slideInVertically(tween(200)) { it * dir })
                        .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { it * -dir })
                }
            ) {
                Column {
                    if (currentItems.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.template_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    for (i in currentItems.indices) {
                        val item = currentItems[i]

                        InsertBetweenButton(
                            onInsertText = { currentItems.add(i, PromptTemplateItem(type = PromptItemType.CUSTOM, value = "")) },
                            onInsertVariable = { insertAtIndex = i; showVariablePicker = true }
                        )

                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            TemplateItemRow(
                                item = item,
                                onChange = { updated -> currentItems[i] = updated },
                                onDelete = { currentItems.removeAt(i) },
                                onMoveUp = if (i > 0) {{ val moved = currentItems.removeAt(i); currentItems.add(i - 1, moved) }} else null,
                                onMoveDown = if (i < currentItems.lastIndex) {{ val moved = currentItems.removeAt(i); currentItems.add(i + 1, moved) }} else null
                            )
                        }
                    }

                    InsertBetweenButton(
                        onInsertText = { currentItems.add(PromptTemplateItem(type = PromptItemType.CUSTOM, value = "")) },
                        onInsertVariable = { insertAtIndex = currentItems.size; showVariablePicker = true }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Preview
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.template_preview),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            ElevatedCard(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().animateContentSize(tween(200))
            ) {
                val previewText = PredefinedVariables.compile(
                    items = currentItems.toList(),
                    runtimeValues = PredefinedVariables.EXAMPLE_VALUES
                )
                Text(
                    text = previewText.ifEmpty { stringResource(R.string.template_preview_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Variable picker bottom sheet
    if (showVariablePicker) {
        val targetIndex = insertAtIndex
        ModalBottomSheet(
            onDismissRequest = { showVariablePicker = false; insertAtIndex = -1 },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp
        ) {
            Text(
                text = stringResource(R.string.template_variable_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (key in PredefinedVariables.ALL) {
                ListItem(
                    headlineContent = { Text(variableDisplayName(key)) },
                    supportingContent = { Text("{${key}}") },
                    leadingContent = {
                        Icon(variableIcon(key), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable {
                        val item = PromptTemplateItem(type = PromptItemType.PREDEFINED, value = key)
                        if (targetIndex >= 0 && targetIndex <= currentItems.size) {
                            currentItems.add(targetIndex, item)
                        } else {
                            currentItems.add(item)
                        }
                        showVariablePicker = false
                        insertAtIndex = -1
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InsertBetweenButton(
    onInsertText: () -> Unit,
    onInsertVariable: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Box {
            FilledTonalIconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.template_insert_title),
                    modifier = Modifier.size(12.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(12.dp)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.template_add_text)) },
                    leadingIcon = { Icon(Icons.Default.TextFields, null) },
                    onClick = { expanded = false; onInsertText() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.template_add_variable)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                    onClick = { expanded = false; onInsertVariable() }
                )
            }
        }
    }
}

@Composable
private fun TemplateItemRow(
    item: PromptTemplateItem,
    onChange: (PromptTemplateItem) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.template_move_up), modifier = Modifier.size(18.dp))
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.template_move_down), modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_delete), modifier = Modifier.size(18.dp))
            }
        }
        when (item.type) {
            PromptItemType.CUSTOM -> {
                var text by remember(item.id) { mutableStateOf(item.value) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { newValue ->
                        text = newValue
                        onChange(item.copy(value = newValue))
                    },
                    label = { Text(stringResource(R.string.template_custom_text_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PromptItemType.PREDEFINED -> {
                ElevatedCard(
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                    ) {
                        Icon(
                            variableIcon(item.value),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = variableDisplayName(item.value),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "{${item.value}}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
