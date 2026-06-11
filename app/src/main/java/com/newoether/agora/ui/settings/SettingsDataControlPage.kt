package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.newoether.agora.R
import com.newoether.agora.data.ClaudeChatImporter
import com.newoether.agora.data.GptChatImporter
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.settings.ImportStrategy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataControlPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val conversationCount by viewModel.conversationCount.collectAsState()
    val memoryCount by viewModel.memoryCount.collectAsState()
    val promptCount by viewModel.systemPromptCount.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val importManifest by viewModel.importManifest.collectAsState()
    val importPreview by viewModel.importPreview.collectAsState()

    val claudeImportPreview by viewModel.claudeImportPreview.collectAsState()
    val claudeImportProgress by viewModel.claudeImportProgress.collectAsState()
    val claudeImportResult by viewModel.claudeImportResult.collectAsState()

    val gptImportPreview by viewModel.gptImportPreview.collectAsState()
    val gptImportProgress by viewModel.gptImportProgress.collectAsState()
    val gptImportResult by viewModel.gptImportResult.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportPreviewDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var invalidImportMessage by remember { mutableStateOf<String?>(null) }

    var showClaudeImportDialog by remember { mutableStateOf(false) }
    var claudeFileUri by remember { mutableStateOf<Uri?>(null) }
    var claudeFileName by remember { mutableStateOf<String?>(null) }
    var showClaudeSuccessDialog by remember { mutableStateOf(false) }

    var showGptImportDialog by remember { mutableStateOf(false) }
    var gptFileUri by remember { mutableStateOf<Uri?>(null) }
    var gptFileName by remember { mutableStateOf<String?>(null) }
    var showGptSuccessDialog by remember { mutableStateOf(false) }

    // Auto Backup
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val autoBackupPeriodHours by viewModel.autoBackupPeriodHours.collectAsState()
    val autoBackupCategories by viewModel.autoBackupCategories.collectAsState()
    val autoBackupDirectory by viewModel.autoBackupDirectory.collectAsState()
    val autoDeleteEnabled by viewModel.autoDeleteEnabled.collectAsState()
    val autoDeletePeriodHours by viewModel.autoDeletePeriodHours.collectAsState()

    // SAF directory picker for auto backup
    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setAutoBackupDirectory(uri.toString())
        }
    }

    val isExporting = exportProgress != null
    val isImporting = importProgress != null

    LaunchedEffect(Unit) { viewModel.refreshDataCounts() }

    // Capture export selections so they survive the SAF picker flow
    var pendingExportCategories by remember { mutableStateOf<Set<DataExporter.ExportCategory>>(emptySet()) }
    var pendingExportIncludeApiKeys by remember { mutableStateOf(false) }

    // SAF launchers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingExportCategories.isNotEmpty()) {
            viewModel.exportData(uri, pendingExportCategories, pendingExportIncludeApiKeys)
            pendingExportCategories = emptySet()
            pendingExportIncludeApiKeys = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importUri = uri
            viewModel.previewImport(uri)
        }
    }

    // Claude chat file picker launcher
    val claudeChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            claudeFileUri = uri
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
            claudeFileName = name
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val importer = ClaudeChatImporter()
                        val jsonResult = importer.extractJsonFromBytes(bytes)
                        if (jsonResult.isSuccess) {
                            viewModel.previewClaudeChat(jsonResult.getOrThrow())
                        } else {
                            viewModel.setClaudeImportError(jsonResult.exceptionOrNull()?.localizedMessage ?: "Failed to read file")
                        }
                    }
                } catch (e: Exception) {
                    viewModel.setClaudeImportError(e.localizedMessage ?: "Unknown error")
                }
            }
        }
    }

    // GPT chat file picker launcher
    val gptChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            gptFileUri = uri
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
            gptFileName = name
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        viewModel.previewGptChat(bytes)
                    } else {
                        viewModel.setGptImportError("Failed to read file")
                    }
                } catch (e: Exception) {
                    viewModel.setGptImportError(e.localizedMessage ?: "Unknown error")
                }
            }
        }
    }

    // Show import preview dialog when preview is loaded
    LaunchedEffect(importPreview) {
        if (importPreview != null) {
            showImportPreviewDialog = true
        }
    }

    val isClaudeImporting = claudeImportProgress != null
    val isGptImporting = gptImportProgress != null
    val isProgressVisible = isExporting || isImporting || isClaudeImporting || isGptImporting

    val showDocFab by viewModel.showDocumentationFab.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_data_control), fontWeight = FontWeight.Bold) },
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
            },
            floatingActionButton = { if (showDocFab) DocumentationFab("import-export.md") },
            floatingActionButtonPosition = FabPosition.Center,
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // Import/Export group
                SettingsGroup(title = stringResource(R.string.settings_data_control), items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.data_import_title)) },
                            supportingContent = { Text(stringResource(R.string.data_import_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/zip", "*/*")) }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.data_export_title)) },
                            supportingContent = { Text(stringResource(R.string.data_export_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { showExportDialog = true }
                        )
                    }
                ))

                // Third party group
                SettingsGroup(title = stringResource(R.string.third_party_import), items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.gpt_import_title)) },
                            supportingContent = { Text(stringResource(R.string.gpt_import_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { gptChatLauncher.launch(arrayOf("application/zip", "*/*")) }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.claude_import_title)) },
                            supportingContent = { Text(stringResource(R.string.claude_import_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { claudeChatLauncher.launch(arrayOf("application/json", "*/*")) }
                        )
                    }
                ))

                // ═══════════════════════════════════════════════
                // Auto Backup group
                // ═══════════════════════════════════════════════
                AutoBackupSection(viewModel)

                // Show Claude import dialog when preview is loaded
                LaunchedEffect(claudeImportPreview) {
                    if (claudeImportPreview != null) {
                        showClaudeImportDialog = true
                    }
                }

                // Show Claude import success dialog when result is available
                LaunchedEffect(claudeImportResult) {
                    if (claudeImportResult != null) {
                        showClaudeSuccessDialog = true
                    }
                }

                // Show GPT import dialog when preview is loaded
                LaunchedEffect(gptImportPreview) {
                    if (gptImportPreview != null) {
                        showGptImportDialog = true
                    }
                }

                // Show GPT import success dialog when result is available
                LaunchedEffect(gptImportResult) {
                    if (gptImportResult != null) {
                        showGptSuccessDialog = true
                    }
                }
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
        }

        // Progress dialog
        if (isProgressVisible) {
            val progress = claudeImportProgress ?: gptImportProgress ?: exportProgress ?: importProgress ?: 0f
            val label = if (isClaudeImporting) stringResource(R.string.claude_import_progress)
                        else if (isGptImporting) stringResource(R.string.gpt_import_progress)
                        else if (isExporting) stringResource(R.string.exporting_label)
                        else stringResource(R.string.importing_label)

            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { },
                title = { Text(label, fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = { }
            )
        }
    }

    // Export dialog
    if (showExportDialog) {
        ExportDataDialog(
            conversationCount = conversationCount,
            memoryCount = memoryCount,
            promptCount = promptCount,
            onDismiss = { showExportDialog = false },
            onExport = { categories, includeApiKeys ->
                showExportDialog = false
                pendingExportCategories = categories
                pendingExportIncludeApiKeys = includeApiKeys
                val filename = "Agora_export_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.agora"
                exportLauncher.launch(filename)
            }
        )
    }

    // Invalid import error
    if (invalidImportMessage != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { invalidImportMessage = null },
            title = { Text(stringResource(R.string.data_import_title), fontWeight = FontWeight.Bold) },
            text = { Text(invalidImportMessage!!) },
            confirmButton = {
                TextButton(onClick = { invalidImportMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // Import preview dialog
    if (showImportPreviewDialog && importPreview != null && importManifest != null) {
        ImportPreviewDialog(
            manifest = importManifest!!,
            preview = importPreview!!,
            onDismiss = {
                showImportPreviewDialog = false
                viewModel.clearImportState()
            },
            onImport = { decisions ->
                showImportPreviewDialog = false
                importUri?.let { viewModel.importData(it, decisions) }
            }
        )
    }

    // Claude import preview dialog
    if (showClaudeImportDialog && claudeImportPreview != null) {
        val preview = claudeImportPreview!!
        var dialogSelectedIds by remember { mutableStateOf(preview.conversations.map { it.uuid }.toSet()) }
        val allIds = preview.conversations.map { it.uuid }.toSet()
        val allSelected = dialogSelectedIds.size == allIds.size
        val selectedConvCount = preview.conversations.count { it.uuid in dialogSelectedIds }
        val selectedMsgCount = preview.conversations.filter { it.uuid in dialogSelectedIds }.sumOf { it.messageCount }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showClaudeImportDialog = false
                viewModel.clearClaudeImportState()
            },
            title = { Text(stringResource(R.string.claude_import_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    // Fixed header
                    Text(
                        "$selectedConvCount ${stringResource(R.string.claude_import_conversations)}, $selectedMsgCount ${stringResource(R.string.claude_import_messages)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            dialogSelectedIds = if (allSelected) emptySet() else allIds
                        }) {
                            Text(
                                if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider()
                    // Scrollable list
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(preview.conversations.size) { index ->
                            val conv = preview.conversations[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        dialogSelectedIds = if (conv.uuid in dialogSelectedIds)
                                            dialogSelectedIds - conv.uuid else dialogSelectedIds + conv.uuid
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = conv.uuid in dialogSelectedIds,
                                    onCheckedChange = {
                                        dialogSelectedIds = if (it)
                                            dialogSelectedIds + conv.uuid else dialogSelectedIds - conv.uuid
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        conv.title.ifEmpty { "Untitled" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${conv.messageCount} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalIds = dialogSelectedIds
                        showClaudeImportDialog = false
                        viewModel.clearClaudeImportState()
                        claudeFileUri?.let {
                            scope.launch {
                                viewModel.importClaudeChat(it, ImportStrategy.MERGE, finalIds)
                            }
                        }
                    },
                    enabled = dialogSelectedIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.claude_import_import))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClaudeImportDialog = false
                    viewModel.clearClaudeImportState()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Claude import success dialog
    if (showClaudeSuccessDialog && claudeImportResult != null) {
        val result = claudeImportResult!!
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showClaudeSuccessDialog = false
                viewModel.clearClaudeImportState()
            },
            title = { Text(stringResource(R.string.claude_import_success), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.claude_import_success_detail, result.conversationsImported, result.messagesImported))
                    if (result.errors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Errors: ${result.errors.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClaudeSuccessDialog = false
                    viewModel.clearClaudeImportState()
                }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // GPT import preview dialog
    if (showGptImportDialog && gptImportPreview != null) {
        val preview = gptImportPreview!!
        var dialogSelectedIds by remember { mutableStateOf(preview.conversations.map { it.uuid }.toSet()) }
        val allIds = preview.conversations.map { it.uuid }.toSet()
        val allSelected = dialogSelectedIds.size == allIds.size
        val selectedConvCount = preview.conversations.count { it.uuid in dialogSelectedIds }
        val selectedMsgCount = preview.conversations.filter { it.uuid in dialogSelectedIds }.sumOf { it.messageCount }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showGptImportDialog = false
                viewModel.clearGptImportState()
            },
            title = { Text(stringResource(R.string.gpt_import_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "$selectedConvCount ${stringResource(R.string.gpt_import_conversations)}, $selectedMsgCount ${stringResource(R.string.gpt_import_messages)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            dialogSelectedIds = if (allSelected) emptySet() else allIds
                        }) {
                            Text(
                                if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(preview.conversations.size) { index ->
                            val conv = preview.conversations[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        dialogSelectedIds = if (conv.uuid in dialogSelectedIds)
                                            dialogSelectedIds - conv.uuid else dialogSelectedIds + conv.uuid
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = conv.uuid in dialogSelectedIds,
                                    onCheckedChange = {
                                        dialogSelectedIds = if (it)
                                            dialogSelectedIds + conv.uuid else dialogSelectedIds - conv.uuid
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        conv.title.ifEmpty { "Untitled" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${conv.messageCount} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalIds = dialogSelectedIds
                        showGptImportDialog = false
                        viewModel.clearGptImportState()
                        gptFileUri?.let {
                            scope.launch {
                                viewModel.importGptChat(it, ImportStrategy.MERGE, finalIds)
                            }
                        }
                    },
                    enabled = dialogSelectedIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.gpt_import_import))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGptImportDialog = false
                    viewModel.clearGptImportState()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // GPT import success dialog
    if (showGptSuccessDialog && gptImportResult != null) {
        val result = gptImportResult!!
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showGptSuccessDialog = false
                viewModel.clearGptImportState()
            },
            title = { Text(stringResource(R.string.gpt_import_success), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.gpt_import_success_detail, result.conversationsImported, result.messagesImported))
                    if (result.errors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Errors: ${result.errors.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGptSuccessDialog = false
                    viewModel.clearGptImportState()
                }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }
}

@Composable
private fun ExportDataDialog(
    conversationCount: Int,
    memoryCount: Int,
    promptCount: Int,
    onDismiss: () -> Unit,
    onExport: (categories: Set<DataExporter.ExportCategory>, includeApiKeys: Boolean) -> Unit
) {
    var exportConversations by remember { mutableStateOf(true) }
    var exportMemories by remember { mutableStateOf(true) }
    var exportPrompts by remember { mutableStateOf(true) }
    var exportSettings by remember { mutableStateOf(true) }
    var exportApiKeys by remember { mutableStateOf(false) }

    val anyChecked = exportConversations || exportMemories || exportPrompts || exportSettings || exportApiKeys

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_export_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                CheckRow(exportConversations, { exportConversations = it },
                    "${stringResource(R.string.export_category_conversations)} ($conversationCount)")
                CheckRow(exportMemories, { exportMemories = it },
                    "${stringResource(R.string.export_category_memories)} ($memoryCount)")
                CheckRow(exportPrompts, { exportPrompts = it },
                    "${stringResource(R.string.export_category_system_prompts)} ($promptCount)")
                CheckRow(exportSettings, { exportSettings = it },
                    stringResource(R.string.export_category_settings))
                CheckRow(exportApiKeys, { exportApiKeys = it },
                    stringResource(R.string.export_category_api_keys))
                if (exportApiKeys) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.export_api_keys_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                    val cats = mutableSetOf<DataExporter.ExportCategory>()
                    if (exportConversations) cats.add(DataExporter.ExportCategory.CONVERSATIONS)
                    if (exportMemories) cats.add(DataExporter.ExportCategory.MEMORIES)
                    if (exportPrompts) cats.add(DataExporter.ExportCategory.SYSTEM_PROMPTS)
                    if (exportSettings) cats.add(DataExporter.ExportCategory.SETTINGS)
                    if (exportApiKeys) cats.add(DataExporter.ExportCategory.API_KEYS)
                    onExport(cats, exportApiKeys)
                }, enabled = anyChecked) {
                Text(stringResource(R.string.export_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CheckRow(checked: Boolean, onToggle: (Boolean) -> Unit, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ImportPreviewDialog(
    manifest: DataImporter.ImportManifest,
    preview: DataImporter.ImportPreview,
    onDismiss: () -> Unit,
    onImport: (Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) -> Unit
) {
    var convStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var memStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var promptStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var settingsStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var keysStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.SKIP) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_preview_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.import_from, manifest.exportedAt.take(19).replace("T", " "), manifest.appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                if (preview.conversationCount > 0) {
                    StrategyRow(
                        "${stringResource(R.string.export_category_conversations)} (${preview.conversationCount})",
                        convStrategy, { convStrategy = it })
                    Spacer(Modifier.height(8.dp))
                }
                if (preview.memoryCount > 0) {
                    StrategyRow(
                        "${stringResource(R.string.export_category_memories)} (${preview.memoryCount})",
                        memStrategy, { memStrategy = it })
                    Spacer(Modifier.height(8.dp))
                }
                if (preview.systemPromptCount > 0) {
                    StrategyRow(
                        "${stringResource(R.string.export_category_system_prompts)} (${preview.systemPromptCount})",
                        promptStrategy, { promptStrategy = it })
                    Spacer(Modifier.height(8.dp))
                }
                if (preview.settingsPresent) {
                    StrategyRow(
                        stringResource(R.string.export_category_settings),
                        settingsStrategy, { settingsStrategy = it })
                    Spacer(Modifier.height(8.dp))
                }
                if (preview.apiKeysPresent) {
                    StrategyRow(
                        stringResource(R.string.export_category_api_keys),
                        keysStrategy, { keysStrategy = it })
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.import_api_keys_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val decisions = mutableMapOf<DataExporter.ExportCategory, DataImporter.ImportStrategy>()
                if (preview.conversationCount > 0) decisions[DataExporter.ExportCategory.CONVERSATIONS] = convStrategy
                if (preview.memoryCount > 0) decisions[DataExporter.ExportCategory.MEMORIES] = memStrategy
                if (preview.systemPromptCount > 0) decisions[DataExporter.ExportCategory.SYSTEM_PROMPTS] = promptStrategy
                if (preview.settingsPresent) decisions[DataExporter.ExportCategory.SETTINGS] = settingsStrategy
                if (preview.apiKeysPresent) decisions[DataExporter.ExportCategory.API_KEYS] = keysStrategy
                onImport(decisions)
            }) { Text(stringResource(R.string.import_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun StrategyRow(
    label: String,
    strategy: DataImporter.ImportStrategy,
    onSelect: (DataImporter.ImportStrategy) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StrategyChip(stringResource(R.string.import_strategy_merge), strategy == DataImporter.ImportStrategy.MERGE) { onSelect(DataImporter.ImportStrategy.MERGE) }
            StrategyChip(stringResource(R.string.import_strategy_replace), strategy == DataImporter.ImportStrategy.REPLACE) { onSelect(DataImporter.ImportStrategy.REPLACE) }
            StrategyChip(stringResource(R.string.import_strategy_skip), strategy == DataImporter.ImportStrategy.SKIP) { onSelect(DataImporter.ImportStrategy.SKIP) }
        }
    }
}

@Composable
private fun StrategyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        shape = RoundedCornerShape(50)
    )
}

// ═══════════════════════════════════════════════════════════════
// Auto Backup section
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AutoBackupSection(viewModel: ChatViewModel) {
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState()
    val autoBackupPeriodHours by viewModel.autoBackupPeriodHours.collectAsState()
    val autoDeleteEnabled by viewModel.autoDeleteEnabled.collectAsState()
    val autoDeletePeriodHours by viewModel.autoDeletePeriodHours.collectAsState()

    SettingsGroup(title = stringResource(R.string.auto_backup_title), items = buildList {
        // Toggle
        add {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.auto_backup_title)) },
                supportingContent = { Text(stringResource(R.string.auto_backup_subtitle)) },
                leadingContent = {
                    Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Switch(checked = autoBackupEnabled, onCheckedChange = { viewModel.setAutoBackupEnabled(it) })
                },
                modifier = Modifier.clickable { viewModel.setAutoBackupEnabled(!autoBackupEnabled) }
            )
        }

        if (autoBackupEnabled) {
            // Backup period
            add {
                AutoBackupPeriodDropdown(
                    currentHours = autoBackupPeriodHours,
                    onSelect = { viewModel.setAutoBackupPeriodHours(it) }
                )
            }

            // Categories
            add { AutoBackupCategoriesItem(viewModel) }

            // Directory
            add { AutoBackupDirectoryItem(viewModel) }

            // Auto delete toggle
            add {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.auto_delete_title)) },
                    supportingContent = { Text(stringResource(R.string.auto_delete_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Default.AutoDelete, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(checked = autoDeleteEnabled, onCheckedChange = { viewModel.setAutoDeleteEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setAutoDeleteEnabled(!autoDeleteEnabled) }
                )
            }

            // Auto delete period (constrained)
            if (autoDeleteEnabled) {
                add {
                    AutoDeletePeriodDropdown(
                        currentHours = autoDeletePeriodHours,
                        backupHours = autoBackupPeriodHours,
                        onSelect = { viewModel.setAutoDeletePeriodHours(it) }
                    )
                }
            }
        }
    })
}

@Composable
private fun AutoBackupPeriodDropdown(currentHours: Int, onSelect: (Int) -> Unit) {
    val periods = listOf(24 to R.string.auto_backup_period_1d,
        72 to R.string.auto_backup_period_3d,
        120 to R.string.auto_backup_period_5d,
        168 to R.string.auto_backup_period_1w,
        720 to R.string.auto_backup_period_1mo)
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = periods.find { it.first == currentHours }?.second ?: R.string.auto_backup_period_1d

    Box {
        SettingsItem(
            headlineContent = { Text(stringResource(R.string.auto_backup_period_label)) },
            supportingContent = { Text(stringResource(currentLabel), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = {
                Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            periods.forEach { (hours, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onSelect(hours); expanded = false },
                    leadingIcon = if (hours == currentHours) {{ Icon(Icons.Default.Check, null) }} else {{}}
                )
            }
        }
    }
}

@Composable
private fun AutoDeletePeriodDropdown(currentHours: Int, backupHours: Int, onSelect: (Int) -> Unit) {
    val allPeriods = listOf(168 to R.string.auto_delete_period_1w,
        720 to R.string.auto_delete_period_1mo,
        8760 to R.string.auto_delete_period_1y)
    // Only show periods >= backup period
    val validPeriods = allPeriods.filter { it.first >= backupHours }
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = validPeriods.find { it.first == currentHours }?.second ?: validPeriods.firstOrNull()?.second ?: R.string.auto_delete_period_1w

    Box {
        SettingsItem(
            headlineContent = { Text(stringResource(R.string.auto_delete_period_label)) },
            supportingContent = { Text(stringResource(currentLabel), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = {
                Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            validPeriods.forEach { (hours, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onSelect(hours); expanded = false },
                    leadingIcon = if (hours == currentHours) {{ Icon(Icons.Default.Check, null) }} else {{}}
                )
            }
        }
    }
}

@Composable
private fun AutoBackupDirectoryItem(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val directory by viewModel.autoBackupDirectory.collectAsState()
    var showPickerDialog by remember { mutableStateOf(false) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setAutoBackupDirectory(uri.toString())
        }
    }

    SettingsItem(
        headlineContent = { Text(stringResource(R.string.auto_backup_directory_label)) },
        supportingContent = {
            val displayPath = if (directory.startsWith("content://")) {
                directory.substringAfterLast("/").ifEmpty { directory.substringAfterLast("%2F").ifEmpty { "Selected folder" } }
            } else {
                directory.ifBlank { "Download/Agora/Backup" }
            }
            Text(displayPath, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.Default.FolderOpen, stringResource(R.string.edit), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        },
        modifier = Modifier.clickable { dirPickerLauncher.launch(null) }
    )
}

@Composable
private fun AutoBackupCategoriesItem(viewModel: ChatViewModel) {
    val categories by viewModel.autoBackupCategories.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    val selectedKeys = categories.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    val displaySummary = when {
        selectedKeys.isEmpty() -> stringResource(R.string.auto_backup_categories_desc)
        selectedKeys.size >= 4 -> stringResource(R.string.auto_backup_categories_desc)
        else -> selectedKeys.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } }
    }

    SettingsItem(
        headlineContent = { Text(stringResource(R.string.auto_backup_categories_label)) },
        supportingContent = { Text(displaySummary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = {
            Icon(Icons.Default.Topic, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, stringResource(R.string.edit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        AutoBackupCategoriesDialog(
            selectedKeys = selectedKeys,
            onDismiss = { showDialog = false },
            onConfirm = { newKeys ->
                viewModel.setAutoBackupCategories(newKeys.joinToString(","))
                showDialog = false
            }
        )
    }
}

@Composable
private fun AutoBackupCategoriesDialog(
    selectedKeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val categoryOptions = listOf(
        "conversations" to "对话",
        "memories" to "记忆",
        "system_prompts" to "系统提示词",
        "settings" to "设置",
        "api_keys" to "API 密钥"
    )
    var current by remember { mutableStateOf(selectedKeys) }
    val apiKeysChecked = "api_keys" in current
    val anyChecked = current.isNotEmpty()

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_backup_categories_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                categoryOptions.forEach { (key, defaultName) ->
                    // Try to get localized name; fallback to hardcoded
                    val label = defaultName // Could use stringResource per key, but keep it simple
                    CheckRow(
                        checked = key in current,
                        onToggle = { checked ->
                            current = if (checked) current + key else current - key
                        },
                        label = label
                    )
                }
                if (apiKeysChecked) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.auto_backup_api_key_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current) }, enabled = anyChecked) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
