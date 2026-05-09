package com.newoether.agora.ui.settings

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProviderPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    var viewingProvider by rememberSaveable { mutableStateOf("Google") }
    val apiKeys by viewModel.apiKeys.collectAsState()
    val activeApiKeyIds by viewModel.activeApiKeyIds.collectAsState()
    val providerBaseUrls by viewModel.providerBaseUrls.collectAsState()
    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showProviderDialog by remember { mutableStateOf(false) }
    val localChatModels by viewModel.localChatModels.collectAsState()

    val providers = listOf("Google", "OpenAI", "Anthropic", "DeepSeek", "Qwen", "Ollama", "Open Router", "Local")

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
                title = { Text(stringResource(R.string.settings_provider), fontWeight = FontWeight.Bold) },
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
            SettingsGroup(title = stringResource(R.string.settings_provider)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.provider_api_provider)) },
                    supportingContent = { Text(viewingProvider) },
                    leadingContent = {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { showProviderDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (viewingProvider == "Local") {
                    // Local chat model management
                    val activeLocalId by viewModel.activeLocalChatModelId.collectAsState()
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    var showAddDialog by remember { mutableStateOf(false) }
                    var showEditDialog by remember { mutableStateOf<com.newoether.agora.data.LocalChatModelConfig?>(null) }
                    var showDeleteConfirm by remember { mutableStateOf<com.newoether.agora.data.LocalChatModelConfig?>(null) }
                    var importingModel by remember { mutableStateOf(false) }
                    var copiedFilePath by remember { mutableStateOf<String?>(null) }

                    val filePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            importingModel = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val destFile = java.io.File(context.filesDir, "chat_model_${java.util.UUID.randomUUID()}.gguf")
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        destFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    copiedFilePath = destFile.absolutePath
                                    showAddDialog = true
                                } catch (e: Exception) {
                                    android.util.Log.e("SettingsProvider", "Failed to import GGUF", e)
                                } finally {
                                    importingModel = false
                                }
                            }
                        }
                    }

                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.local_chat_models)) },
                        supportingContent = { Text(stringResource(R.string.local_chat_models_count, localChatModels.size)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )

                    localChatModels.forEach { model ->
                        var showMenu by remember { mutableStateOf(false) }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(model.alias, fontWeight = FontWeight.Medium) },
                            supportingContent = {
                                Text("${model.modelId}  ctx=${model.nCtx}  temp=${model.temperature}  topP=${model.topP}")
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = model.id == activeLocalId,
                                    onClick = { viewModel.setActiveLocalChatModel(model.id) }
                                )
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options))
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.edit)) },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { showMenu = false; showEditDialog = model }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { showMenu = false; showDeleteConfirm = model }
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { viewModel.setActiveLocalChatModel(model.id) }.padding(start = 16.dp)
                        )
                    }

                    TextButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !importingModel,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                    ) {
                        if (importingModel) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.importing_model))
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.import_model_chat))
                        }
                    }

                    // Add local model config dialog
                    if (showAddDialog && copiedFilePath != null) {
                        var modelId by remember { mutableStateOf("") }
                        var modelAlias by remember { mutableStateOf("") }
                        var nCtx by remember { mutableStateOf("2048") }
                        var temperature by remember { mutableStateOf("0.7") }
                        var topP by remember { mutableStateOf("0.9") }
                        var maxTokens by remember { mutableStateOf("4096") }
                        var idError by remember { mutableStateOf<String?>(null) }
                        val fm = LocalFocusManager.current
                        val idRegex = remember { Regex("^[a-z0-9._-]+\$") }

                        AlertDialog(
                            onDismissRequest = {
                                scope.launch(Dispatchers.IO) {
                                    copiedFilePath?.let { java.io.File(it).delete() }
                                }
                                showAddDialog = false
                                copiedFilePath = null
                            },
                            title = { Text(stringResource(R.string.add_local_chat_model)) },
                            text = {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    OutlinedTextField(
                                        value = modelId,
                                        onValueChange = { modelId = it; idError = null },
                                        label = { Text(stringResource(R.string.model_id_label)) },
                                        supportingText = if (idError != null) {{ Text(idError!!, color = MaterialTheme.colorScheme.error) }} else null,
                                        isError = idError != null,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = modelAlias,
                                        onValueChange = { modelAlias = it },
                                        label = { Text(stringResource(R.string.model_alias_label)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = nCtx,
                                        onValueChange = { nCtx = it },
                                        label = { Text(stringResource(R.string.local_ctx_size)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = temperature,
                                        onValueChange = { temperature = it },
                                        label = { Text(stringResource(R.string.local_temperature)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = topP,
                                        onValueChange = { topP = it },
                                        label = { Text(stringResource(R.string.local_top_p)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = maxTokens,
                                        onValueChange = { maxTokens = it },
                                        label = { Text(stringResource(R.string.local_max_tokens)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val id = modelId.trim()
                                    if (id.isBlank()) {
                                        idError = "ID is required"
                                        return@TextButton
                                    }
                                    if (!idRegex.matches(id)) {
                                        idError = "Only lowercase a-z, 0-9, . _ - allowed"
                                        return@TextButton
                                    }
                                    if (viewModel.isLocalModelIdTaken(id)) {
                                        idError = "This ID is already in use"
                                        return@TextButton
                                    }
                                    val path = copiedFilePath ?: return@TextButton
                                    val config = com.newoether.agora.data.LocalChatModelConfig(
                                        modelId = id,
                                        alias = modelAlias.ifBlank { id },
                                        localFilePath = path,
                                        nCtx = nCtx.toIntOrNull() ?: 2048,
                                        temperature = temperature.toFloatOrNull() ?: 0.7f,
                                        topP = topP.toFloatOrNull() ?: 0.9f,
                                        maxTokens = maxTokens.toIntOrNull() ?: 4096
                                    )
                                    viewModel.addLocalChatModel(config)
                                    showAddDialog = false
                                    copiedFilePath = null
                                }) { Text(stringResource(R.string.add)) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        copiedFilePath?.let { java.io.File(it).delete() }
                                    }
                                    showAddDialog = false
                                    copiedFilePath = null
                                }) { Text(stringResource(R.string.cancel)) }
                            }
                        )
                    }

                    // Edit dialog
                    showEditDialog?.let { model ->
                        var editModelId by remember { mutableStateOf(model.modelId) }
                        var editAlias by remember { mutableStateOf(model.alias) }
                        var editNCtx by remember { mutableStateOf(model.nCtx.toString()) }
                        var editTemp by remember { mutableStateOf(model.temperature.toString()) }
                        var editTopP by remember { mutableStateOf(model.topP.toString()) }
                        var editMaxTokens by remember { mutableStateOf(model.maxTokens.toString()) }
                        var editIdError by remember { mutableStateOf<String?>(null) }
                        val fm = LocalFocusManager.current
                        val idRegex = remember { Regex("^[a-z0-9._-]+\$") }
                        AlertDialog(
                            onDismissRequest = { showEditDialog = null },
                            title = { Text(stringResource(R.string.edit)) },
                            text = {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    OutlinedTextField(
                                        value = editModelId,
                                        onValueChange = { editModelId = it; editIdError = null },
                                        label = { Text(stringResource(R.string.model_id_label)) },
                                        supportingText = if (editIdError != null) {{ Text(editIdError!!, color = MaterialTheme.colorScheme.error) }} else null,
                                        isError = editIdError != null,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = editAlias,
                                        onValueChange = { editAlias = it },
                                        label = { Text(stringResource(R.string.model_alias_label)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = editNCtx,
                                        onValueChange = { editNCtx = it },
                                        label = { Text(stringResource(R.string.local_ctx_size)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = editTemp,
                                        onValueChange = { editTemp = it },
                                        label = { Text(stringResource(R.string.local_temperature)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = editTopP,
                                        onValueChange = { editTopP = it },
                                        label = { Text(stringResource(R.string.local_top_p)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = editMaxTokens,
                                        onValueChange = { editMaxTokens = it },
                                        label = { Text(stringResource(R.string.local_max_tokens)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val id = editModelId.trim()
                                    if (id.isBlank()) {
                                        editIdError = "ID is required"
                                        return@TextButton
                                    }
                                    if (!idRegex.matches(id)) {
                                        editIdError = "Only lowercase a-z, 0-9, . _ - allowed"
                                        return@TextButton
                                    }
                                    if (viewModel.isLocalModelIdTaken(id, excludeId = model.id)) {
                                        editIdError = "This ID is already in use"
                                        return@TextButton
                                    }
                                    viewModel.updateLocalChatModel(
                                        model.id, id, editAlias.ifBlank { id },
                                        editNCtx.toIntOrNull() ?: model.nCtx,
                                        editTemp.toFloatOrNull() ?: model.temperature,
                                        editTopP.toFloatOrNull() ?: model.topP,
                                        editMaxTokens.toIntOrNull() ?: model.maxTokens
                                    )
                                    showEditDialog = null
                                }) { Text(stringResource(R.string.save)) }
                            },
                            dismissButton = { TextButton(onClick = { showEditDialog = null }) { Text(stringResource(R.string.cancel)) } }
                        )
                    }

                    // Delete confirmation
                    showDeleteConfirm?.let { model ->
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = null },
                            title = { Text(stringResource(R.string.delete_chat)) },
                            text = { Text(stringResource(R.string.local_chat_delete_text, model.alias)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        viewModel.deleteLocalChatModel(model.id)
                                        showDeleteConfirm = null
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text(stringResource(R.string.delete)) }
                            },
                            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) } }
                        )
                    }
                } else {
                val providerInstance = viewModel.getProviderInstance(viewingProvider)
                val baseUrlState = remember(viewingProvider) {
                    val saved = providerBaseUrls[viewingProvider]
                    val initial = if (saved.isNullOrBlank() && viewingProvider != "Ollama") {
                        providerInstance.defaultBaseUrl
                    } else {
                        saved ?: ""
                    }
                    androidx.compose.foundation.text.input.TextFieldState(initial)
                }

                LaunchedEffect(baseUrlState.text) {
                    viewModel.setProviderBaseUrl(viewingProvider, baseUrlState.text.toString())
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.link_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.provider_base_url),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(modifier = Modifier.bringIntoViewResponder(noOpResponder).padding(top = 8.dp)) {
                                OutlinedTextField(
                                    state = baseUrlState,
                                    placeholder = { Text(providerInstance.defaultBaseUrl, style = MaterialTheme.typography.bodyMedium) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(if (viewingProvider == "Ollama") stringResource(R.string.provider_api_keys_optional) else stringResource(R.string.provider_api_keys)) },
                    supportingContent = {
                        val providerKeys = apiKeys.filter { it.provider == viewingProvider }
                        Text(if (providerKeys.isEmpty()) stringResource(R.string.provider_no_keys, viewingProvider) else stringResource(R.string.provider_keys_count, providerKeys.size))
                    },
                    leadingContent = { Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                )

                apiKeys.filter { it.provider == viewingProvider }.forEach { entry ->
                    var showMenu by remember { mutableStateOf(false) }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(entry.key.take(4) + "••••••••" + entry.key.takeLast(4)) },
                        leadingContent = {
                            RadioButton(selected = entry.id == activeApiKeyIds[viewingProvider], onClick = { viewModel.setActiveApiKey(viewingProvider, entry.id) })
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options))
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, shape = RoundedCornerShape(12.dp)) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showKeyDialog = entry })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteKeyConfirm = entry })
                                }
                            }
                        },
                        modifier = Modifier.clickable { viewModel.setActiveApiKey(viewingProvider, entry.id) }.padding(start = 16.dp)
                    )
                }

                TextButton(
                    onClick = { showKeyDialog = ApiKeyEntry(name = "", key = "", provider = viewingProvider) },
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.provider_add_key))
                }
                }
            }
        }
    }

    // Provider Selection Dialog
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text(stringResource(R.string.provider_select_provider)) },
            text = {
                Column {
                    providers.forEach { p ->
                        val isConfigured = when (p) {
                            "Ollama" -> !providerBaseUrls[p].isNullOrBlank()
                            "Local" -> localChatModels.isNotEmpty()
                            else -> apiKeys.any { it.provider == p }
                        }

                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isConfigured) {
                                        Text(
                                            "• ",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                    Text(
                                        p,
                                        color = if (isConfigured) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                viewingProvider = p
                                showProviderDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text(stringResource(R.string.provider_close)) } }
        )
    }

    // API Key Dialog
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        val keyState = rememberTextFieldState(entry.key)
        val isEdit = apiKeys.any { it.id == entry.id }

        AlertDialog(
            onDismissRequest = { showKeyDialog = null },
            title = { Text(if (isEdit) stringResource(R.string.provider_edit_key) else stringResource(R.string.provider_add_key_title)) },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.provider_key_name_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewResponder(noOpResponder)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        OutlinedTextField(
                            state = keyState,
                            label = { Text("${entry.provider} API Key") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val key = keyState.text.toString()
                    if (name.isNotBlank() && key.isNotBlank()) {
                        if (isEdit) viewModel.updateApiKey(entry.id, name, key) else viewModel.addApiKey(name, key, entry.provider)
                        showKeyDialog = null
                    }
                }) { Text(if (isEdit) stringResource(R.string.provider_save) else stringResource(R.string.provider_add)) }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    // Delete Key Confirmation
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteKeyConfirm = null },
            title = { Text(stringResource(R.string.provider_delete_key_title)) },
            text = { Text(stringResource(R.string.provider_delete_key_text, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteApiKey(entry.id)
                        showDeleteKeyConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
