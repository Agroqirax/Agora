package com.newoether.agora.ui.settings

import com.newoether.agora.util.DebugLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
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
    var showAddCustomProviderDialog by remember { mutableStateOf(false) }
    val localChatModels by viewModel.localChatModels.collectAsState()

    val customProviders by viewModel.customProviders.collectAsState()
    val providers = listOf("Google", "OpenAI", "Anthropic", "DeepSeek", "Qwen", "Ollama", "Open Router", "Local") + customProviders.map { it.name }

    // No-op bring-into-view to prevent auto-scrolling on text field focus

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
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
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Hoisted state for local model and custom provider dialogs
            val activeLocalId by viewModel.activeLocalChatModelId.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var showAddDialog by remember { mutableStateOf(false) }
            var showEditDialog by remember { mutableStateOf<com.newoether.agora.data.LocalChatModelConfig?>(null) }
            var showDeleteConfirm by remember { mutableStateOf<com.newoether.agora.data.LocalChatModelConfig?>(null) }
            var importingModel by remember { mutableStateOf(false) }
            var copiedFilePath by remember { mutableStateOf<String?>(null) }
            var showDeleteProvider by remember { mutableStateOf(false) }
            var showRenameProvider by remember { mutableStateOf(false) }

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
                            // Validate GGUF magic bytes
                            val magic = ByteArray(4)
                            destFile.inputStream().use { it.read(magic) }
                            if (magic[0] != 'G'.code.toByte() || magic[1] != 'G'.code.toByte()
                                || magic[2] != 'U'.code.toByte() || magic[3] != 'F'.code.toByte()) {
                                destFile.delete()
                                DebugLog.e("SettingsProvider", "Imported file is not a valid GGUF")
                            } else {
                                copiedFilePath = destFile.absolutePath
                                showAddDialog = true
                            }
                        } catch (e: Exception) {
                            DebugLog.e("SettingsProvider", "Failed to import GGUF", e)
                        } finally {
                            importingModel = false
                        }
                    }
                }
            }

            // Build items list based on selected provider
            val isCustom = customProviders.any { it.name == viewingProvider }
            val providerItems: List<@Composable () -> Unit> = buildList {
                add {
                    var showProviderMenu by remember { mutableStateOf(false) }
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.provider_api_provider)) },
                        supportingContent = { Text(viewingProvider) },
                        leadingContent = {
                            Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = if (isCustom) {{
                            Box {
                                IconButton(onClick = { showProviderMenu = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options), modifier = Modifier.size(18.dp))
                                }
                                DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showProviderMenu = false; showRenameProvider = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showProviderMenu = false; showDeleteProvider = true })
                                }
                            }
                        }} else null,
                        modifier = Modifier.clickable { showProviderDialog = true }
                    )
                }

                if (viewingProvider == "Local") {
                    // Local chat model management
                    add {
                        SettingsItem(
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
                    }

                    localChatModels.forEach { model ->
                        add {
                            var showMenu by remember { mutableStateOf(false) }
                            SettingsItem(
                                headlineContent = { Text(model.alias, fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Text("${model.modelId}\nctx=${model.nCtx}  temp=${model.temperature}  topP=${model.topP}")
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = model.id == activeLocalId,
                                        onClick = { viewModel.setActiveLocalChatModel(model.id) },
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options), modifier = Modifier.size(18.dp))
                                        }
                                        DropdownMenu(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
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
                    }

                    add {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(enabled = !importingModel) { filePickerLauncher.launch(arrayOf("*/*")) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (importingModel) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.importing_model), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.import_model_chat), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
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
                        kotlinx.coroutines.delay(500)
                        viewModel.setProviderBaseUrl(viewingProvider, baseUrlState.text.toString())
                    }

                    add {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                                    Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                        OutlinedTextField(
                                            state = baseUrlState,
                                            placeholder = { Text(providerInstance.defaultBaseUrl, style = MaterialTheme.typography.bodyMedium) },
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }

                    add {
                        SettingsItem(
                            headlineContent = { Text(if (viewingProvider == "Ollama" || isCustom) stringResource(R.string.provider_api_keys_optional) else stringResource(R.string.provider_api_keys)) },
                            supportingContent = {
                                val providerKeys = apiKeys.filter { it.provider == viewingProvider }
                                Text(if (providerKeys.isEmpty()) stringResource(R.string.provider_no_keys, viewingProvider) else stringResource(R.string.provider_keys_count, providerKeys.size))
                            },
                            leadingContent = { Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        )
                    }

                    apiKeys.filter { it.provider == viewingProvider }.forEach { entry ->
                        add {
                            var showMenu by remember { mutableStateOf(false) }
                            SettingsItem(
                                headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(entry.key.take(4) + "••••••••" + entry.key.takeLast(4)) },
                                leadingContent = {
                                    RadioButton(selected = entry.id == activeApiKeyIds[viewingProvider], onClick = { viewModel.setActiveApiKey(viewingProvider, entry.id) }, modifier = Modifier.size(24.dp))
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options), modifier = Modifier.size(18.dp))
                                        }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showKeyDialog = entry })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteKeyConfirm = entry })
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.setActiveApiKey(viewingProvider, entry.id) }.padding(start = 16.dp)
                            )
                        }
                    }

                    add {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { showKeyDialog = ApiKeyEntry(name = "", key = "", provider = viewingProvider) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.provider_add_key), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                }
            }
            SettingsGroup(title = stringResource(R.string.settings_provider), items = providerItems)

            // Local chat model dialogs (moved outside SettingsGroup)
            if (viewingProvider == "Local") {
                // Add local model config dialog
                if (showAddDialog && copiedFilePath != null) {
                    var modelId by remember { mutableStateOf("") }
                    var modelAlias by remember { mutableStateOf("") }
                    var nCtx by remember { mutableStateOf("2048") }
                    var temperature by remember { mutableStateOf("0.7") }
                    var topP by remember { mutableStateOf("0.9") }
                    var maxTokens by remember { mutableStateOf("4096") }
                    var idError by remember { mutableStateOf<String?>(null) }
                    var formError by remember { mutableStateOf<String?>(null) }
                    val fm = LocalFocusManager.current
                    val idRegex = remember { Regex("^[a-z0-9._-]+\$") }

                    AlertDialog(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onDismissRequest = {
                            scope.launch(Dispatchers.IO) {
                                copiedFilePath?.let { java.io.File(it).delete() }
                            }
                            showAddDialog = false
                            copiedFilePath = null
                        },
                        title = { Text(stringResource(R.string.add_local_chat_model), fontWeight = FontWeight.Bold) },
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
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = modelAlias,
                                    onValueChange = { modelAlias = it },
                                    label = { Text(stringResource(R.string.model_alias_label)) },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = nCtx,
                                    onValueChange = { nCtx = it },
                                    label = { Text(stringResource(R.string.local_ctx_size)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = temperature,
                                    onValueChange = { temperature = it },
                                    label = { Text(stringResource(R.string.local_temperature)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = topP,
                                    onValueChange = { topP = it },
                                    label = { Text(stringResource(R.string.local_top_p)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = maxTokens,
                                    onValueChange = { maxTokens = it },
                                    label = { Text(stringResource(R.string.local_max_tokens)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                formError?.let { err ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val id = modelId.trim()
                                idError = null
                                formError = null
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
                                val nCtxVal = nCtx.toIntOrNull()
                                if (nCtxVal == null || nCtxVal <= 0) {
                                    formError = "Context size must be a positive integer"
                                    return@TextButton
                                }
                                val tempVal = temperature.toFloatOrNull()
                                if (tempVal == null || tempVal < 0f || tempVal > 2f) {
                                    formError = "Temperature must be 0–2"
                                    return@TextButton
                                }
                                val topPVal = topP.toFloatOrNull()
                                if (topPVal == null || topPVal < 0f || topPVal > 1f) {
                                    formError = "Top P must be 0–1"
                                    return@TextButton
                                }
                                val maxTokVal = maxTokens.toIntOrNull()
                                if (maxTokVal == null || maxTokVal <= 0) {
                                    formError = "Max tokens must be a positive integer"
                                    return@TextButton
                                }
                                val path = copiedFilePath ?: return@TextButton
                                val config = com.newoether.agora.data.LocalChatModelConfig(
                                    modelId = id,
                                    alias = modelAlias.ifBlank { id },
                                    localFilePath = path,
                                    nCtx = nCtxVal,
                                    temperature = tempVal,
                                    topP = topPVal,
                                    maxTokens = maxTokVal
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
                    var editFormError by remember { mutableStateOf<String?>(null) }
                    val fm = LocalFocusManager.current
                    val idRegex = remember { Regex("^[a-z0-9._-]+\$") }
                    AlertDialog(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onDismissRequest = { showEditDialog = null },
                        title = { Text(stringResource(R.string.edit), fontWeight = FontWeight.Bold) },
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
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editAlias,
                                    onValueChange = { editAlias = it },
                                    label = { Text(stringResource(R.string.model_alias_label)) },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editNCtx,
                                    onValueChange = { editNCtx = it },
                                    label = { Text(stringResource(R.string.local_ctx_size)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editTemp,
                                    onValueChange = { editTemp = it },
                                    label = { Text(stringResource(R.string.local_temperature)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editTopP,
                                    onValueChange = { editTopP = it },
                                    label = { Text(stringResource(R.string.local_top_p)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editMaxTokens,
                                    onValueChange = { editMaxTokens = it },
                                    label = { Text(stringResource(R.string.local_max_tokens)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                editFormError?.let { err ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val id = editModelId.trim()
                                editIdError = null
                                editFormError = null
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
                                val nCtxVal = editNCtx.toIntOrNull()
                                if (nCtxVal == null || nCtxVal <= 0) {
                                    editFormError = "Context size must be a positive integer"
                                    return@TextButton
                                }
                                val tempVal = editTemp.toFloatOrNull()
                                if (tempVal == null || tempVal < 0f || tempVal > 2f) {
                                    editFormError = "Temperature must be 0–2"
                                    return@TextButton
                                }
                                val topPVal = editTopP.toFloatOrNull()
                                if (topPVal == null || topPVal < 0f || topPVal > 1f) {
                                    editFormError = "Top P must be 0–1"
                                    return@TextButton
                                }
                                val maxTokVal = editMaxTokens.toIntOrNull()
                                if (maxTokVal == null || maxTokVal <= 0) {
                                    editFormError = "Max tokens must be a positive integer"
                                    return@TextButton
                                }
                                viewModel.updateLocalChatModel(
                                    model.id, id, editAlias.ifBlank { id },
                                    nCtxVal, tempVal, topPVal, maxTokVal
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
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onDismissRequest = { showDeleteConfirm = null },
                        title = { Text(stringResource(R.string.local_chat_delete_title), fontWeight = FontWeight.Bold) },
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
            }

            // Custom provider dialogs (moved outside SettingsGroup)
            if (viewingProvider != "Local" && customProviders.any { it.name == viewingProvider }) {
                if (showRenameProvider) {
                    var renameValue by remember { mutableStateOf(viewingProvider) }
                    var renameError by remember { mutableStateOf(false) }
                    AlertDialog(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onDismissRequest = { showRenameProvider = false },
                        title = { Text(stringResource(R.string.custom_provider_rename_title), fontWeight = FontWeight.Bold) },
                        text = {
                            Column(Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = renameValue, onValueChange = { renameValue = it; renameError = false },
                                    label = { Text(stringResource(R.string.custom_provider_name_label)) },
                                    isError = renameError,
                                    supportingText = if (renameError) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val trimmed = renameValue.trim()
                                renameError = trimmed.isBlank() || (trimmed != viewingProvider && trimmed in providers)
                                if (!renameError) {
                                    if (trimmed != viewingProvider) {
                                        viewModel.renameCustomProvider(viewingProvider, trimmed)
                                        viewingProvider = trimmed
                                    }
                                    showRenameProvider = false
                                }
                            }) { Text(stringResource(R.string.custom_provider_rename)) }
                        },
                        dismissButton = { TextButton(onClick = { showRenameProvider = false }) { Text(stringResource(R.string.provider_cancel)) } }
                    )
                }
                if (showDeleteProvider) {
                    AlertDialog(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onDismissRequest = { showDeleteProvider = false },
                        title = { Text(stringResource(R.string.custom_provider_delete_title), fontWeight = FontWeight.Bold) },
                        text = { Text(stringResource(R.string.custom_provider_delete_text, viewingProvider)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.deleteCustomProvider(viewingProvider)
                                    viewingProvider = "Google"
                                    showDeleteProvider = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text(stringResource(R.string.provider_delete)) }
                        },
                        dismissButton = { TextButton(onClick = { showDeleteProvider = false }) { Text(stringResource(R.string.provider_cancel)) } }
                    )
                }
            }
        }
    }

    // Provider Selection Dialog
    if (showProviderDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showProviderDialog = false },
            title = { Text(stringResource(R.string.provider_select_provider), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    providers.forEach { p ->
                        val isCustom = customProviders.any { it.name == p }
                        val isConfigured = when {
                            p == "Ollama" || isCustom -> !providerBaseUrls[p].isNullOrBlank()
                            p == "Local" -> localChatModels.isNotEmpty()
                            else -> apiKeys.any { it.provider == p }
                        }

                        SettingsItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isConfigured) {
                                        Text(
                                            "•",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                    }
                                    Text(p)
                                    if (isCustom) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Text(
                                                stringResource(R.string.custom_provider_badge),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .clickable {
                                    viewingProvider = p
                                    showProviderDialog = false
                                }
                                .padding(vertical = 3.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = {
                            showProviderDialog = false
                            showAddCustomProviderDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.custom_provider_add))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text(stringResource(R.string.provider_close)) } }
        )
    }

    // API Key Dialog
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        var key by remember { mutableStateOf(entry.key) }
        val isEdit = apiKeys.any { it.id == entry.id }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showKeyDialog = null },
            title = { Text(if (isEdit) stringResource(R.string.provider_edit_key) else stringResource(R.string.provider_add_key_title), fontWeight = FontWeight.Bold) },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.provider_key_name_hint)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .noOpBringIntoView()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("${entry.provider} API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
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
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteKeyConfirm = null },
            title = { Text(stringResource(R.string.provider_delete_key_title), fontWeight = FontWeight.Bold) },
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

    // Add Custom Provider Dialog
    if (showAddCustomProviderDialog) {
        var customName by remember { mutableStateOf("") }
        var customBaseUrl by remember { mutableStateOf("") }
        var nameError by remember { mutableStateOf(false) }
        var urlError by remember { mutableStateOf(false) }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showAddCustomProviderDialog = false },
            title = { Text(stringResource(R.string.custom_provider_add_title), fontWeight = FontWeight.Bold) },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                        value = customName, onValueChange = { customName = it; nameError = false },
                        label = { Text(stringResource(R.string.custom_provider_name_label)) },
                        isError = nameError,
                        supportingText = if (nameError) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().noOpBringIntoView(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            value = customBaseUrl, onValueChange = { customBaseUrl = it; urlError = false },
                            label = { Text(stringResource(R.string.provider_base_url)) },
                            isError = urlError,
                            supportingText = if (urlError) {{ Text(stringResource(R.string.custom_provider_url_error)) }} else null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmedName = customName.trim()
                    val trimmedUrl = customBaseUrl.trim()
                    nameError = trimmedName.isBlank() || trimmedName in providers
                    urlError = trimmedUrl.isBlank()
                    if (!nameError && !urlError) {
                        viewModel.addCustomProvider(trimmedName, trimmedUrl)
                        viewingProvider = trimmedName
                        showAddCustomProviderDialog = false
                    }
                }) { Text(stringResource(R.string.custom_provider_add)) }
            },
            dismissButton = { TextButton(onClick = { showAddCustomProviderDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
