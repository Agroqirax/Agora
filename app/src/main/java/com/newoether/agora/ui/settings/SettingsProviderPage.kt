package com.newoether.agora.ui.settings

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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.viewmodel.ChatViewModel

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

    val providers = listOf("Google", "OpenAI", "Anthropic", "DeepSeek", "Qwen", "Ollama", "Open Router")

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
                title = { Text("Provider") },
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
            SettingsGroup(title = "PROVIDER") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("API Provider") },
                    supportingContent = { Text(viewingProvider) },
                    leadingContent = {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { showProviderDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
                                text = "Base URL",
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
                    headlineContent = { Text(if (viewingProvider == "Ollama") "API Keys (Optional)" else "API Keys") },
                    supportingContent = {
                        val providerKeys = apiKeys.filter { it.provider == viewingProvider }
                        Text(if (providerKeys.isEmpty()) "No keys configured for $viewingProvider" else "${providerKeys.size} key(s) configured")
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
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, shape = RoundedCornerShape(12.dp)) {
                                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showKeyDialog = entry })
                                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteKeyConfirm = entry })
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
                    Text("Add New Key")
                }
            }
        }
    }

    // Provider Selection Dialog
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("Select AI Provider") },
            text = {
                Column {
                    providers.forEach { p ->
                        val isConfigured = if (p == "Ollama") {
                            !providerBaseUrls[p].isNullOrBlank()
                        } else {
                            apiKeys.any { it.provider == p }
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
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text("Close") } }
        )
    }

    // API Key Dialog
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        val keyState = rememberTextFieldState(entry.key)
        val isEdit = apiKeys.any { it.id == entry.id }

        AlertDialog(
            onDismissRequest = { showKeyDialog = null },
            title = { Text(if (isEdit) "Edit API Key" else "Add API Key") },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name (e.g. Workspace)") },
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
                }) { Text(if (isEdit) "Save" else "Add") }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text("Cancel") } }
        )
    }

    // Delete Key Confirmation
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteKeyConfirm = null },
            title = { Text("Delete API Key?") },
            text = { Text("Are you sure you want to delete '${entry.name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteApiKey(entry.id)
                        showDeleteKeyConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text("Cancel") } }
        )
    }
}
