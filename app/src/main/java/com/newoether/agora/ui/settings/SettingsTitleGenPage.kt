package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTitleGenPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val titleGenEnabled by viewModel.titleGenerationEnabled.collectAsState()
    val titleGenModel by viewModel.titleGenerationModel.collectAsState()
    val modelAliases by viewModel.modelAliases.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    var showTitleModelDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title_gen), fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.settings_title_gen)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.title_gen_auto)) },
                    supportingContent = { Text(stringResource(R.string.title_gen_auto_desc)) },
                    leadingContent = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = titleGenEnabled, onCheckedChange = { viewModel.setTitleGenerationEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setTitleGenerationEnabled(!titleGenEnabled) }
                )

                if (titleGenEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.title_gen_model)) },
                        supportingContent = {
                            val displayName = if (titleGenModel == null) stringResource(R.string.title_gen_current_model) else {
                                val alias = modelAliases[titleGenModel!!]
                                alias ?: titleGenModel!!.substringAfter(":").removePrefix("models/")
                            }
                            Text(displayName)
                        },
                        leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { showTitleModelDialog = true }
                    )
                }
            }
        }
    }

    if (showTitleModelDialog) {
        val enabledModelsList = enabledModels.toList()
        AlertDialog(
            onDismissRequest = { showTitleModelDialog = false },
            title = { Text(stringResource(R.string.title_gen_select_model)) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.title_gen_current_model), fontWeight = if (titleGenModel == null) FontWeight.Bold else FontWeight.Normal) },
                            leadingContent = {
                                RadioButton(selected = titleGenModel == null, onClick = {
                                    viewModel.setTitleGenerationModel(null)
                                    showTitleModelDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                viewModel.setTitleGenerationModel(null)
                                showTitleModelDialog = false
                            }
                        )
                    }
                    items(enabledModelsList) { model ->
                        val alias = modelAliases[model]
                        val displayName = alias ?: model.substringAfter(":").removePrefix("models/")
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(displayName, fontWeight = if (titleGenModel == model) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text(model.substringBefore(":"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
                            leadingContent = {
                                RadioButton(selected = titleGenModel == model, onClick = {
                                    viewModel.setTitleGenerationModel(model)
                                    showTitleModelDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                viewModel.setTitleGenerationModel(model)
                                showTitleModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTitleModelDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
