package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.viewmodel.ChatViewModel

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

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportPreviewDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var invalidImportMessage by remember { mutableStateOf<String?>(null) }

    val isExporting = exportProgress != null
    val isImporting = importProgress != null

    LaunchedEffect(Unit) { viewModel.refreshDataCounts() }

    // Capture export selections so they survive the SAF picker flow
    var pendingExportCategories by remember { mutableStateOf<Set<DataExporter.ExportCategory>>(emptySet()) }
    var pendingExportIncludeApiKeys by remember { mutableStateOf(false) }

    // SAF launchers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
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

    // Show import preview dialog when preview is loaded
    LaunchedEffect(importPreview) {
        if (importPreview != null) {
            showImportPreviewDialog = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Export card
            SettingsGroup(title = stringResource(R.string.settings_data_control)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.data_export_title)) },
                    supportingContent = { Text(stringResource(R.string.data_export_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { showExportDialog = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.data_import_title)) },
                    supportingContent = { Text(stringResource(R.string.data_import_subtitle)) },
                    leadingContent = {
                        Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/zip", "*/*")) }
                )
            }
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
            onDismissRequest = { invalidImportMessage = null },
            title = { Text(stringResource(R.string.data_import_title)) },
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

    // Progress overlay
    if (isExporting || isImporting) {
        val progress = exportProgress ?: importProgress ?: 0f
        val label = if (isExporting) stringResource(R.string.exporting_label)
                    else stringResource(R.string.importing_label)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.padding(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
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
            }
        }
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
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_export_title), fontWeight = FontWeight.Normal) },
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
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_preview_title)) },
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
