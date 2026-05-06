package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModelsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabledModels by viewModel.enabledModels.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val modelAliases by viewModel.modelAliases.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    var showActiveModelDialog by remember { mutableStateOf(false) }
    var showModelAliasDialog by remember { mutableStateOf<String?>(null) }

    val noOpResponder = remember {
        object : androidx.compose.foundation.relocation.BringIntoViewResponder {
            override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) {}
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Models") },
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
            SettingsGroup(title = "DEFAULT MODEL") {
                val activeAlias = modelAliases[selectedModel]
                val cleanId = selectedModel.substringAfter(":")
                val providerName = selectedModel.substringBefore(":")
                val activeDisplayName = activeAlias ?: cleanId.removePrefix("models/")

                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            if (enabledModels.isEmpty()) "No models enabled" else activeDisplayName,
                            color = if (enabledModels.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = if (enabledModels.isNotEmpty()) {
                        { Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) }
                    } else null,
                    leadingContent = { Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = enabledModels.isNotEmpty()) { showActiveModelDialog = true }
                )
            }
            SettingsGroup(title = "AVAILABLE MODELS") {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Sync from All Providers") },
                    supportingContent = { Text("Fetch the latest model list for all configured APIs") },
                    leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { viewModel.fetchAvailableModels() }
                )

                if (availableModels.isNotEmpty()) {
                    val expandedProviders = remember { mutableStateMapOf<String, Boolean>() }

                    availableModels.forEach { (providerName, models) ->
                        if (models.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            val isExpanded = expandedProviders[providerName] ?: false

                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(providerName, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text("${models.size} models available") },
                                trailingContent = {
                                    Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                                },
                                modifier = Modifier.clickable { expandedProviders[providerName] = !isExpanded }
                            )

                            if (isExpanded) {
                                models.forEach { model ->
                                    val isEnabled = enabledModels.contains(model)
                                    val alias = modelAliases[model]
                                    val cleanId = model.substringAfter(":")
                                    val displayName = alias ?: cleanId.removePrefix("models/")

                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text(displayName) },
                                        supportingContent = if (alias != null) { { Text(cleanId.removePrefix("models/")) } } else null,
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(onClick = { showModelAliasDialog = model }) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                }
                                                Checkbox(checked = isEnabled, onCheckedChange = {
                                                    viewModel.setEnabledModels(if (it) enabledModels + model else enabledModels - model)
                                                })
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.setEnabledModels(if (!isEnabled) enabledModels + model else enabledModels - model)
                                        }.padding(start = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Active Model Dialog
    if (showActiveModelDialog) {
        AlertDialog(
            onDismissRequest = { showActiveModelDialog = false },
            title = { Text("Select Default Model") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(enabledModels.toList()) { model ->
                        val alias = modelAliases[model]
                        val cleanId = model.substringAfter(":")
                        val displayName = alias ?: cleanId.removePrefix("models/")

                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Text(displayName, fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal)
                            },
                            supportingContent = {
                                Text(model.substringBefore(":"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = model == selectedModel,
                                    onClick = {
                                        viewModel.setSelectedModel(model)
                                        showActiveModelDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.setSelectedModel(model)
                                showActiveModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActiveModelDialog = false }) { Text("Close") } }
        )
    }

    // Model Alias Dialog
    showModelAliasDialog?.let { model ->
        val aliasState = rememberTextFieldState(modelAliases[model] ?: "")

        AlertDialog(
            onDismissRequest = { showModelAliasDialog = null },
            title = { Text("Rename Model") },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    Text("Current ID: ${model.removePrefix("models/")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        OutlinedTextField(
                            state = aliasState,
                            label = { Text("Alias") },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(model.removePrefix("models/")) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateModelAlias(model, aliasState.text.toString())
                    showModelAliasDialog = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showModelAliasDialog = null }) { Text("Cancel") } }
        )
    }
}
