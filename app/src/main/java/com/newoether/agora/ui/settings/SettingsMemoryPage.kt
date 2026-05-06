package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMemoryPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val accessPastConversations by viewModel.accessPastConversations.collectAsState()
    val accessSavedMemories by viewModel.accessSavedMemories.collectAsState()
    var activeMemoryContent by remember { mutableStateOf("") }
    var memoryFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFileEditor by remember { mutableStateOf<String?>(null) }
    var fileEditorContent by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }
    var showDeleteFileConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        activeMemoryContent = viewModel.memoryManager.getActiveMemory()
        memoryFiles = viewModel.memoryManager.listFiles()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
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
        val fm = LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = "ACCESS") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Access saved memories") },
                    supportingContent = { Text("Allow the model to read, create, and edit memory files") },
                    leadingContent = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = accessSavedMemories, onCheckedChange = { viewModel.setAccessSavedMemories(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setAccessSavedMemories(!accessSavedMemories) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Access past conversations") },
                    supportingContent = { Text("Allow the model to search conversation history (RAG)") },
                    leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = accessPastConversations, onCheckedChange = { viewModel.setAccessPastConversations(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setAccessPastConversations(!accessPastConversations) }
                )
            }

            SettingsGroup(title = "ACTIVE MEMORY") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Active Memory Context") },
                    supportingContent = {
                        Text(
                            if (activeMemoryContent.isBlank()) "No active memory set. The model will remember this across all conversations."
                            else activeMemoryContent.take(100) + if (activeMemoryContent.length > 100) "..." else ""
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showFileEditor = "ACTIVE_MEMORY"
                        fileEditorContent = activeMemoryContent
                    }
                )
            }

            SettingsGroup(title = "SAVED MEMORIES") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Add Memory") },
                    supportingContent = { Text("Add a new memory to saved memories") },
                    leadingContent = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { showNewFileDialog = true }
                )

                if (memoryFiles.isEmpty()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("No files yet", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                        supportingContent = { Text("Create a file or let the model create one via tool calling") }
                    )
                } else {
                    memoryFiles.forEach { fileName ->
                        var showFileMenu by remember { mutableStateOf(false) }
                        val displayName = fileName.removeSuffix(".md")
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(displayName, fontWeight = FontWeight.Medium) },
                            leadingContent = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showFileMenu = true }) {
                                        Icon(Icons.Default.MoreVert, "Menu", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    }
                                    DropdownMenu(
                                        expanded = showFileMenu,
                                        onDismissRequest = { showFileMenu = false },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Edit") },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = {
                                                showFileMenu = false
                                                try {
                                                    showFileEditor = fileName
                                                    fileEditorContent = viewModel.memoryManager.readFile(fileName)
                                                } catch (_: Exception) {}
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                showFileMenu = false
                                                showDeleteFileConfirm = fileName
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete file confirmation
    showDeleteFileConfirm?.let { fileName ->
        AlertDialog(
            onDismissRequest = { showDeleteFileConfirm = null },
            title = { Text("Delete Memory?") },
            text = { Text("Are you sure you want to delete '$fileName'? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.memoryManager.deleteFile(fileName)
                        memoryFiles = viewModel.memoryManager.listFiles()
                        showDeleteFileConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteFileConfirm = null }) { Text("Cancel") } }
        )
    }

    // File Editor Dialog
    showFileEditor?.let { fileName ->
        val isActiveMemory = fileName == "ACTIVE_MEMORY"
        var editFileName by remember { mutableStateOf(if (isActiveMemory) "" else fileName.removeSuffix(".md")) }
        var editContent by remember { mutableStateOf(fileEditorContent) }

        AlertDialog(
            onDismissRequest = {
                showFileEditor = null
                fileEditorContent = ""
            },
            title = { Text(if (isActiveMemory) "Edit Active Memory" else "Edit Memory") },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    if (isActiveMemory) {
                        Text(
                            "This content is included in every API call. Write facts, preferences, or context the model should always remember.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        OutlinedTextField(
                            value = editFileName,
                            onValueChange = { editFileName = it },
                            label = { Text("Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("Content") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 400.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isActiveMemory) {
                        viewModel.memoryManager.updateActiveMemory(editContent)
                        activeMemoryContent = viewModel.memoryManager.getActiveMemory()
                    } else {
                        if (editFileName.isNotBlank() && editFileName != fileName.removeSuffix(".md")) {
                            viewModel.memoryManager.deleteFile(fileName)
                            viewModel.memoryManager.createFile(editFileName, editContent)
                        } else {
                            viewModel.memoryManager.editFile(fileName, editContent)
                        }
                        memoryFiles = viewModel.memoryManager.listFiles()
                    }
                    showFileEditor = null
                    fileEditorContent = ""
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFileEditor = null
                    fileEditorContent = ""
                }) { Text("Cancel") }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Add Memory") },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text("Content") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) {
                        try {
                            viewModel.memoryManager.createFile(newFileName, newFileContent)
                            memoryFiles = viewModel.memoryManager.listFiles()
                        } catch (_: Exception) {}
                    }
                    showNewFileDialog = false
                    newFileName = ""
                    newFileContent = ""
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewFileDialog = false
                    newFileName = ""
                    newFileContent = ""
                }) { Text("Cancel") }
            }
        )
    }
}
