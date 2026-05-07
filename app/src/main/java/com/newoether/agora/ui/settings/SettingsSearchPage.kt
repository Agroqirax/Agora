package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File

private data class SearchMethodOption(val key: String, @androidx.annotation.StringRes val labelRes: Int)

private val searchMethods = listOf(
    SearchMethodOption("keyword", R.string.search_method_keyword),
    SearchMethodOption("rag", R.string.search_method_rag)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val accessPastConversations by viewModel.accessPastConversations.collectAsState()
    val modelSearchMethod by viewModel.modelSearchMethod.collectAsState()
    val manualSearchMethod by viewModel.manualSearchMethod.collectAsState()
    val embeddingModels by viewModel.embeddingModels.collectAsState()
    val activeEmbeddingModelId by viewModel.activeEmbeddingModelId.collectAsState()
    var showRemoteDialog by remember { mutableStateOf(false) }
    var showLocalDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var cachingModelId by remember { mutableStateOf<String?>(null) }
    var remoteName by remember { mutableStateOf("") }
    var remoteModelName by remember { mutableStateOf("") }
    var remoteBaseUrl by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var localFilePath by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    LaunchedEffect(embeddingModels) {
        if (cachingModelId != null && embeddingModels.find { it.id == cachingModelId }?.cached == true) {
            cachingModelId = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            SettingsGroup(title = stringResource(R.string.memory_access_title)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.memory_access_past)) },
                    supportingContent = { Text(stringResource(R.string.memory_access_past_desc)) },
                    leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = accessPastConversations, onCheckedChange = { viewModel.setAccessPastConversations(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setAccessPastConversations(!accessPastConversations) }
                )
            }

            SettingsGroup(title = stringResource(R.string.search_methods_title)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.model_search_method)) },
                    supportingContent = { Text(stringResource(R.string.model_search_method_desc)) },
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) }
                )
                searchMethods.forEach { method ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(method.labelRes)) },
                        leadingContent = {
                            RadioButton(
                                selected = modelSearchMethod == method.key,
                                onClick = { viewModel.setModelSearchMethod(method.key) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setModelSearchMethod(method.key) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.manual_search_method)) },
                    supportingContent = { Text(stringResource(R.string.manual_search_method_desc)) },
                    leadingContent = { Icon(Icons.Default.ManageSearch, null, tint = MaterialTheme.colorScheme.primary) }
                )
                searchMethods.forEach { method ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(method.labelRes)) },
                        leadingContent = {
                            RadioButton(
                                selected = manualSearchMethod == method.key,
                                onClick = { viewModel.setManualSearchMethod(method.key) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setManualSearchMethod(method.key) }
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.embedding_title)) {
                if (embeddingModels.isEmpty()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.no_embedding_models)) },
                        leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) }
                    )
                } else {
                    embeddingModels.forEach { model ->
                        val isActive = model.id == activeEmbeddingModelId
                        val isCaching = cachingModelId == model.id
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(model.name) },
                            supportingContent = {
                                val typeLabel = if (model.type == com.newoether.agora.data.EmbeddingModelType.REMOTE)
                                    stringResource(R.string.embedding_type_remote)
                                else stringResource(R.string.embedding_type_local)
                                val cacheLabel = if (isCaching) stringResource(R.string.caching)
                                else if (model.cached) stringResource(R.string.cached)
                                else stringResource(R.string.not_cached)
                                Text("$typeLabel · $cacheLabel")
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = isActive,
                                    onClick = { viewModel.setActiveEmbeddingModel(model.id) }
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    if (!model.cached && !isCaching) {
                                        TextButton(onClick = {
                                            cachingModelId = model.id
                                            viewModel.cacheMessagesForModel(model.id)
                                        }) { Text(stringResource(R.string.cache_action)) }
                                    }
                                    if (isCaching) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    IconButton(onClick = { showDeleteDialog = model.id }) {
                                        Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            modifier = Modifier.clickable { viewModel.setActiveEmbeddingModel(model.id) }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        remoteName = ""
                        remoteModelName = ""
                        remoteBaseUrl = ""
                        showRemoteDialog = true
                    }) { Text(stringResource(R.string.add_remote_model)) }
                    TextButton(onClick = {
                        localName = ""
                        localFilePath = ""
                        showLocalDialog = true
                    }) { Text(stringResource(R.string.add_local_model)) }
                }
            }
        }

        if (showRemoteDialog) {
            AlertDialog(
                onDismissRequest = { showRemoteDialog = false },
                title = { Text(stringResource(R.string.add_remote_model)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = remoteName,
                            onValueChange = { remoteName = it },
                            label = { Text(stringResource(R.string.model_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = remoteModelName,
                            onValueChange = { remoteModelName = it },
                            label = { Text(stringResource(R.string.embedding_model_label)) },
                            placeholder = { Text("text-embedding-3-small") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = remoteBaseUrl,
                            onValueChange = { remoteBaseUrl = it },
                            label = { Text(stringResource(R.string.embedding_base_url_label)) },
                            placeholder = { Text("https://api.openai.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (remoteName.isNotBlank() && remoteModelName.isNotBlank()) {
                            viewModel.addEmbeddingModel(
                                com.newoether.agora.data.EmbeddingModelConfig(
                                    name = remoteName,
                                    type = com.newoether.agora.data.EmbeddingModelType.REMOTE,
                                    remoteModelName = remoteModelName,
                                    remoteBaseUrl = remoteBaseUrl
                                )
                            )
                            showRemoteDialog = false
                        }
                    }) { Text(stringResource(R.string.add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRemoteDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showLocalDialog) {
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    isImporting = true
                    scope.launch {
                        try {
                            val destFile = File(context.filesDir, "embedding_${java.util.UUID.randomUUID()}.gguf")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            localFilePath = destFile.absolutePath
                        } catch (_: Exception) { }
                        isImporting = false
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { showLocalDialog = false },
                title = { Text(stringResource(R.string.add_local_model)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = localName,
                            onValueChange = { localName = it },
                            label = { Text(stringResource(R.string.model_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (localFilePath.isNotBlank()) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.local_model_ready)) },
                                leadingContent = {
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                        } else {
                            TextButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                                Text(stringResource(R.string.import_model))
                            }
                        }
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(start = 16.dp), strokeWidth = 2.dp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (localName.isNotBlank() && localFilePath.isNotBlank()) {
                            viewModel.addEmbeddingModel(
                                com.newoether.agora.data.EmbeddingModelConfig(
                                    name = localName,
                                    type = com.newoether.agora.data.EmbeddingModelType.LOCAL,
                                    localFilePath = localFilePath
                                )
                            )
                            showLocalDialog = false
                        }
                    }) { Text(stringResource(R.string.add)) }
                },
                dismissButton = {
                    TextButton(onClick = { showLocalDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showDeleteDialog != null) {
            val modelId = showDeleteDialog!!
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text(stringResource(R.string.delete_model_title)) },
                text = { Text(stringResource(R.string.delete_model_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteEmbeddingModel(modelId)
                        showDeleteDialog = null
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}
