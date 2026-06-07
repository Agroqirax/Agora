package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPromptsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.activeSystemPromptId.collectAsState()
    val showDocFab by viewModel.showDocumentationFab.collectAsState()
    var editingEntry by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showDeletePromptConfirm by remember { mutableStateOf<SystemPromptEntry?>(null) }

    BackHandler(enabled = editingEntry != null) {
        editingEntry = null
    }

    AnimatedContent(
        targetState = editingEntry,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally(initialOffsetX = { it }) togetherWith slideOutHorizontally(targetOffsetX = { -it })
            } else {
                slideInHorizontally(initialOffsetX = { -it }) togetherWith slideOutHorizontally(targetOffsetX = { it })
            }
        }
    ) { currentEntry ->
        if (currentEntry != null) {
            SystemPromptEditorPage(
                entry = currentEntry,
                onSave = { title, systemItems, userPrependItems, userPostpendItems ->
                    if (systemPrompts.any { it.id == currentEntry.id }) {
                        viewModel.updateSystemPrompt(currentEntry.id, title, systemItems, userPrependItems, userPostpendItems)
                    } else {
                        viewModel.addSystemPrompt(title, systemItems, userPrependItems, userPostpendItems)
                    }
                    editingEntry = null
                },
                onBack = { editingEntry = null },
                showDocFab = showDocFab
            )
        } else {
            PromptList(
                systemPrompts = systemPrompts,
                activeSystemPromptId = activeSystemPromptId,
                onSelectPrompt = { viewModel.setActiveSystemPrompt(it) },
                onEdit = { editingEntry = it },
                onAdd = { editingEntry = SystemPromptEntry(title = "") },
                onDeleteRequest = { showDeletePromptConfirm = it },
                onBack = onBack
            )
        }
    }

    showDeletePromptConfirm?.let { entry ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeletePromptConfirm = null },
            title = { Text(stringResource(R.string.prompts_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.prompts_delete_text, entry.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSystemPrompt(entry.id)
                        showDeletePromptConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeletePromptConfirm = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptList(
    systemPrompts: List<SystemPromptEntry>,
    activeSystemPromptId: String?,
    onSelectPrompt: (String) -> Unit,
    onEdit: (SystemPromptEntry) -> Unit,
    onAdd: () -> Unit,
    onDeleteRequest: (SystemPromptEntry) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prompts_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val promptItems: List<@Composable () -> Unit> = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.prompts_default)) },
                        supportingContent = { Text(stringResource(R.string.prompts_default_desc)) },
                        leadingContent = {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    )
                }

                systemPrompts.forEach { entry ->
                    add {
                        var showMenu by remember { mutableStateOf(false) }
                        SettingsItem(
                            headlineContent = { Text(entry.title, fontWeight = FontWeight.Medium) },
                            supportingContent = {
                                val preview = entry.resolvedSystemItems.firstOrNull()?.value ?: entry.content
                                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                RadioButton(selected = entry.id == activeSystemPromptId, onClick = { onSelectPrompt(entry.id) }, modifier = Modifier.size(24.dp))
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options), modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onEdit(entry) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDeleteRequest(entry) })
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onSelectPrompt(entry.id) }.padding(start = 16.dp)
                        )
                    }
                }

                add {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onAdd() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.prompts_add), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            SettingsGroup(title = stringResource(R.string.prompts_title), items = promptItems)
        }
    }
}
