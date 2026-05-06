package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPromptsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.activeSystemPromptId.collectAsState()
    var showPromptDialog by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showDeletePromptConfirm by remember { mutableStateOf<SystemPromptEntry?>(null) }

    val noOpResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("System Prompts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = "PROMPTS") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Default System Prompt") },
                    supportingContent = { Text("Define global personas or rules") },
                    leadingContent = {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                )

                systemPrompts.forEach { entry ->
                    var showMenu by remember { mutableStateOf(false) }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(entry.title, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(entry.content, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        leadingContent = {
                            RadioButton(selected = entry.id == activeSystemPromptId, onClick = { viewModel.setActiveSystemPrompt(entry.id) })
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showPromptDialog = entry })
                                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeletePromptConfirm = entry })
                                }
                            }
                        },
                        modifier = Modifier.clickable { viewModel.setActiveSystemPrompt(entry.id) }.padding(start = 16.dp)
                    )
                }

                TextButton(
                    onClick = { showPromptDialog = SystemPromptEntry(title = "", content = "") },
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New Prompt")
                }
            }
        }
    }

    // Prompt Dialog
    showPromptDialog?.let { entry ->
        var title by remember { mutableStateOf(entry.title) }
        val contentState = rememberTextFieldState(entry.content)
        val isEdit = systemPrompts.any { it.id == entry.id }

        AlertDialog(
            onDismissRequest = { showPromptDialog = null },
            title = { Text(if (isEdit) "Edit System Prompt" else "Add System Prompt") },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { fm.clearFocus() }) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Title (e.g. Translator)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewResponder(noOpResponder)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        OutlinedTextField(
                            state = contentState,
                            label = { Text("System Prompt") },
                            modifier = Modifier.fillMaxWidth(),
                            lineLimits = TextFieldLineLimits.MultiLine(1, 10),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val content = contentState.text.toString()
                    if (title.isNotBlank() && content.isNotBlank()) {
                        if (isEdit) viewModel.updateSystemPrompt(entry.id, title, content) else viewModel.addSystemPrompt(title, content)
                        showPromptDialog = null
                    }
                }) { Text(if (isEdit) "Save" else "Add") }
            },
            dismissButton = { TextButton(onClick = { showPromptDialog = null }) { Text("Cancel") } }
        )
    }

    // Delete Confirmation
    showDeletePromptConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeletePromptConfirm = null },
            title = { Text("Delete System Instruction?") },
            text = { Text("Are you sure you want to delete '${entry.title}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSystemPrompt(entry.id)
                        showDeletePromptConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeletePromptConfirm = null }) { Text("Cancel") } }
        )
    }
}
