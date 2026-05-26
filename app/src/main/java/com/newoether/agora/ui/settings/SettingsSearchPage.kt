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
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
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
    val autoCacheEnabled by viewModel.autoCacheEnabled.collectAsState()
    val modelSearchMethod by viewModel.modelSearchMethod.collectAsState()
    val manualSearchMethod by viewModel.manualSearchMethod.collectAsState()
    val embeddingModels by viewModel.embeddingModels.collectAsState()
    val activeEmbeddingModelId by viewModel.activeEmbeddingModelId.collectAsState()
    val cachingProgress by viewModel.cachingProgress.collectAsState()
    val cacheCounts by viewModel.cacheCounts.collectAsState()
    val ragThreshold by viewModel.ragThreshold.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadCacheCounts() }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var showLocalDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showRecacheConfirm by remember { mutableStateOf<String?>(null) }
    var showMenuForModel by remember { mutableStateOf<String?>(null) }
    var localThreshold by remember { mutableFloatStateOf(ragThreshold) }
    LaunchedEffect(ragThreshold) { localThreshold = ragThreshold }
    var renameText by remember { mutableStateOf("") }
    var remoteName by remember { mutableStateOf("") }
    var remoteModelName by remember { mutableStateOf("") }
    var remoteBaseUrl by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var localFilePath by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    LaunchedEffect(embeddingModels.size) {
        showMenuForModel = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(
                title = stringResource(R.string.memory_access_title),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.memory_access_past)) },
                            supportingContent = { Text(stringResource(R.string.memory_access_past_desc)) },
                            leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = accessPastConversations, onCheckedChange = { viewModel.setAccessPastConversations(it) })
                            },
                            modifier = Modifier.clickable { viewModel.setAccessPastConversations(!accessPastConversations) }
                        )
                    }
                )
            )

            SettingsGroup(
                title = stringResource(R.string.auto_cache_title),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.auto_cache)) },
                            supportingContent = { Text(stringResource(R.string.auto_cache_desc)) },
                            leadingContent = { Icon(Icons.Default.Cached, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = autoCacheEnabled, onCheckedChange = { viewModel.setAutoCacheEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.setAutoCacheEnabled(!autoCacheEnabled) }
                        )
                    }
                )
            )

            SettingsGroup(
                title = stringResource(R.string.search_methods_title),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.model_search_method)) },
                            supportingContent = { Text(stringResource(R.string.model_search_method_desc)) },
                            leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                    }
                    searchMethods.forEach { method ->
                        add {
                            SettingsItem(
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
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.manual_search_method)) },
                            supportingContent = { Text(stringResource(R.string.manual_search_method_desc)) },
                            leadingContent = { Icon(Icons.Default.ManageSearch, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                    }
                    searchMethods.forEach { method ->
                        add {
                            SettingsItem(
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
                }
            )

            SettingsGroup(
                title = stringResource(R.string.embedding_title),
                items = buildList {
                    if (embeddingModels.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.no_embedding_models)) },
                                leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                    } else {
                        embeddingModels.forEach { model ->
                            add {
                                val isActive = model.id == activeEmbeddingModelId
                                val progress = cachingProgress[model.id]
                                val isCaching = progress != null
                                val counts = cacheCounts[model.id]
                                val allCached = counts != null && counts.second > 0 && counts.first >= counts.second
                                SettingsItem(
                                    headlineContent = { Text(model.name) },
                                    supportingContent = {
                                        val typeLabel = if (model.type == com.newoether.agora.data.EmbeddingModelType.REMOTE)
                                            stringResource(R.string.embedding_type_remote)
                                        else stringResource(R.string.embedding_type_local)
                                        val cacheLabel = if (isCaching) {
                                            "${progress!!.second - progress!!.first} ${stringResource(R.string.not_cached)} (${progress!!.first}/${progress!!.second})"
                                        } else if (counts != null && counts.second > 0) {
                                            val notCached = (counts.second - counts.first).coerceAtLeast(0)
                                            if (notCached == 0) stringResource(R.string.cached)
                                            else "${notCached} ${stringResource(R.string.not_cached)} (${counts.first}/${counts.second})"
                                        } else {
                                            stringResource(R.string.not_cached)
                                        }
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
                                            if (!isCaching) {
                                                TextButton(onClick = {
                                                    if (allCached) {
                                                        showRecacheConfirm = model.id
                                                    } else {
                                                        viewModel.cacheMessagesForModel(model.id)
                                                    }
                                                }) { Text(if (allCached) stringResource(R.string.recache_action) else stringResource(R.string.cache_action)) }
                                            }
                                            if (isCaching) {
                                                val ratio = progress!!.first.toFloat() / progress.second.toFloat()
                                                CircularProgressIndicator(
                                                    progress = { ratio },
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                            Box {
                                                IconButton(onClick = { showMenuForModel = model.id }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options))
                                                }
                                                DropdownMenu(
                                                    expanded = showMenuForModel == model.id,
                                                    onDismissRequest = { showMenuForModel = null },
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.rename)) },
                                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                                        onClick = {
                                                            showMenuForModel = null
                                                            renameText = model.name
                                                            showRenameDialog = model.id
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                        onClick = {
                                                            showMenuForModel = null
                                                            showDeleteDialog = model.id
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { viewModel.setActiveEmbeddingModel(model.id) }
                                )
                            }
                        }
                    }
                    add {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = {
                                remoteName = ""
                                remoteModelName = ""
                                remoteBaseUrl = ""
                                testStatus = null
                                isTesting = false
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
            )

            SettingsGroup(
                title = stringResource(R.string.advanced_title),
                items = listOf(
                    {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.Top
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.text_compare_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.rag_threshold_label),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "≥ ${"%.2f".format(localThreshold)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Slider(
                                        value = localThreshold,
                                        onValueChange = { localThreshold = it },
                                        onValueChangeFinished = { viewModel.setRagThreshold(localThreshold) },
                                        valueRange = 0f..1f,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                )
            )
        }

        if (showRemoteDialog) {
            AlertDialog(
                onDismissRequest = { showRemoteDialog = false; testStatus = null },
                title = { Text(stringResource(R.string.add_remote_model)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = remoteName,
                            onValueChange = { remoteName = it },
                            label = { Text(stringResource(R.string.model_name_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = remoteModelName,
                            onValueChange = { remoteModelName = it },
                            label = { Text(stringResource(R.string.embedding_model_label)) },
                            placeholder = { Text("text-embedding-3-small") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = remoteBaseUrl,
                            onValueChange = { remoteBaseUrl = it },
                            label = { Text(stringResource(R.string.embedding_base_url_label)) },
                            placeholder = { Text("https://api.openai.com/v1") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        testStatus?.let { status ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    val scope = rememberCoroutineScope()
                    TextButton(
                        onClick = {
                            if (remoteName.isBlank() || remoteModelName.isBlank()) return@TextButton
                            isTesting = true
                            testStatus = null
                            scope.launch {
                                val result = viewModel.testRemoteEmbedding(remoteModelName, remoteBaseUrl)
                                if (result != null && result.startsWith("OK")) {
                                    viewModel.addEmbeddingModel(
                                        com.newoether.agora.data.EmbeddingModelConfig(
                                            name = remoteName,
                                            type = com.newoether.agora.data.EmbeddingModelType.REMOTE,
                                            remoteModelName = remoteModelName,
                                            remoteBaseUrl = remoteBaseUrl
                                        )
                                    )
                                    showRemoteDialog = false
                                } else {
                                    testStatus = result ?: "Failed"
                                }
                                isTesting = false
                            }
                        },
                        enabled = !isTesting && remoteName.isNotBlank() && remoteModelName.isNotBlank()
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.add))
                    }
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
                onDismissRequest = {
                    if (localFilePath.isNotBlank()) File(localFilePath).delete()
                    showLocalDialog = false
                },
                title = { Text(stringResource(R.string.add_local_model)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = localName,
                            onValueChange = { localName = it },
                            label = { Text(stringResource(R.string.model_name_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (localFilePath.isNotBlank()) {
                            SettingsItem(
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
                    TextButton(onClick = {
                        if (localFilePath.isNotBlank()) File(localFilePath).delete()
                        showLocalDialog = false
                    }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showRecacheConfirm != null) {
            val modelId = showRecacheConfirm!!
            AlertDialog(
                onDismissRequest = { showRecacheConfirm = null },
                title = { Text(stringResource(R.string.recache_confirm_title)) },
                text = { Text(stringResource(R.string.recache_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.cacheMessagesForModel(modelId, recache = true)
                        showRecacheConfirm = null
                    }) { Text(stringResource(R.string.recache_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRecacheConfirm = null }) { Text(stringResource(R.string.cancel)) }
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

        if (showRenameDialog != null) {
            val modelId = showRenameDialog!!
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                title = { Text(stringResource(R.string.rename)) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.model_name_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.renameEmbeddingModel(modelId, renameText)
                            showRenameDialog = null
                        }
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}
