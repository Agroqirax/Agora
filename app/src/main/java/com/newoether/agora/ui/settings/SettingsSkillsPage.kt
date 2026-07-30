package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSkillsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val skillsEnabled by viewModel.settings.skillsEnabled.collectAsState()
    var skillFiles by remember { mutableStateOf<List<com.newoether.agora.data.SkillManager.SkillFileInfo>>(emptyList()) }
    var showFileEditor by remember { mutableStateOf<String?>(null) }
    var fileEditorContent by remember { mutableStateOf("") }
    var fileEditorDesc by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }
    var newFileDesc by remember { mutableStateOf("") }
    var showDeleteFileConfirm by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf<String?>(null) }

    fun refresh() { skillFiles = viewModel.skillManager.listFiles() }

    LaunchedEffect(Unit) { refresh() }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.skills_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("skills.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.memory_access_title),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.skills_access)) },
                            supportingContent = { Text(stringResource(R.string.skills_access_desc)) },
                            leadingContent = { Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = skillsEnabled, onCheckedChange = { viewModel.settings.setSkillsEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setSkillsEnabled(!skillsEnabled) }
                        )
                    }
                )
            )

            SettingsGroup(
                title = stringResource(R.string.skills_saved_title),
                items = buildList {
                    if (skillFiles.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.skills_no_files), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                supportingContent = { Text(stringResource(R.string.skills_create_hint), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        skillFiles.forEach { file ->
                            add {
                                var showFileMenu by remember { mutableStateOf(false) }
                                val displayName = file.name.removeSuffix(".md")
                                SettingsItem(
                                    headlineContent = { Text(displayName, fontWeight = FontWeight.Medium) },
                                    supportingContent = {
                                        val desc = if (file.isBuiltin) {
                                            "${stringResource(R.string.skills_builtin_label)}${if (file.description.isNotBlank()) " · ${file.description}" else ""}"
                                        } else file.description
                                        if (desc.isNotBlank()) Text(desc)
                                    },
                                    leadingContent = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) },
                                    trailingContent = {
                                        Box {
                                            IconButton(onClick = { showFileMenu = true }) {
                                                Icon(Icons.Default.MoreVert, stringResource(R.string.menu), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                            }
                                            DropdownMenu(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = showFileMenu,
                                                onDismissRequest = { showFileMenu = false },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.provider_edit)) },
                                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                                    onClick = {
                                                        showFileMenu = false
                                                        try {
                                                            showFileEditor = file.name
                                                            fileEditorContent = viewModel.skillManager.readFile(file.name)
                                                            fileEditorDesc = file.description
                                                        } catch (_: Exception) {}
                                                    }
                                                )
                                                if (file.isBuiltin) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.skills_reset_default)) },
                                                        leadingIcon = { Icon(Icons.Default.Restore, null) },
                                                        onClick = {
                                                            showFileMenu = false
                                                            showResetConfirm = file.name
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        showFileMenu = false
                                                        showDeleteFileConfirm = file.name
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    add {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { showNewFileDialog = true }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.skills_add), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            )
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Reset-to-default confirmation
    showResetConfirm?.let { fileName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showResetConfirm = null },
            title = { Text(stringResource(R.string.skills_reset_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.skills_reset_confirm_text, fileName.removeSuffix(".md"))) },
            confirmButton = {
                TextButton(onClick = {
                    try { viewModel.skillManager.resetToDefault(fileName) } catch (_: Exception) {}
                    refresh()
                    showResetConfirm = null
                }) { Text(stringResource(R.string.skills_reset)) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    // Delete file confirmation
    showDeleteFileConfirm?.let { fileName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteFileConfirm = null },
            title = { Text(stringResource(R.string.skills_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.skills_delete_text, fileName.removeSuffix(".md"))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.skillManager.deleteFile(fileName)
                        refresh()
                        showDeleteFileConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteFileConfirm = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    // File Editor Dialog
    showFileEditor?.let { fileName ->
        var editFileName by remember { mutableStateOf(fileName.removeSuffix(".md")) }
        var editContent by remember { mutableStateOf(fileEditorContent) }
        var editDesc by remember { mutableStateOf(fileEditorDesc) }

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showFileEditor = null
                fileEditorContent = ""
                fileEditorDesc = ""
            },
            title = { Text(stringResource(R.string.skills_edit), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editFileName,
                        onValueChange = { editFileName = it },
                        label = { Text(stringResource(R.string.skills_title_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text(stringResource(R.string.skills_desc_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text(stringResource(R.string.skills_content_hint)) },
                        shape = RoundedCornerShape(16.dp),
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
                    if (editFileName.isNotBlank() && editFileName != fileName.removeSuffix(".md")) {
                        viewModel.skillManager.deleteFile(fileName)
                        viewModel.skillManager.createFile(editFileName, editContent, editDesc)
                    } else {
                        viewModel.skillManager.editFile(fileName, editContent, description = editDesc)
                    }
                    refresh()
                    showFileEditor = null
                    fileEditorContent = ""
                    fileEditorDesc = ""
                }) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFileEditor = null
                    fileEditorContent = ""
                    fileEditorDesc = ""
                }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog) {
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showNewFileDialog = false },
            title = { Text(stringResource(R.string.skills_add_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text(stringResource(R.string.skills_title_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileDesc,
                        onValueChange = { newFileDesc = it },
                        label = { Text(stringResource(R.string.skills_desc_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text(stringResource(R.string.skills_content_hint)) },
                        shape = RoundedCornerShape(16.dp),
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
                            viewModel.skillManager.createFile(newFileName, newFileContent, newFileDesc)
                            refresh()
                        } catch (_: Exception) {}
                    }
                    showNewFileDialog = false
                    newFileName = ""
                    newFileContent = ""
                    newFileDesc = ""
                }) { Text(stringResource(R.string.skills_create)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewFileDialog = false
                    newFileName = ""
                    newFileContent = ""
                    newFileDesc = ""
                }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }
}
