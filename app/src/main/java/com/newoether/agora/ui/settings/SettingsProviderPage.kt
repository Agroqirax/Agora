package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProviderPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val apiKeys by viewModel.apiKeys.collectAsState()
    val providerBaseUrls by viewModel.providerBaseUrls.collectAsState()
    val customProviders by viewModel.customProviders.collectAsState()
    val localChatModels by viewModel.localChatModels.collectAsState()

    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddCustomDialog by remember { mutableStateOf(false) }
    val showDocFab by viewModel.showDocumentationFab.collectAsState()

    if (selectedProvider != null) {
        SettingsProviderDetailPage(
            providerName = selectedProvider!!,
            viewModel = viewModel,
            onBack = { selectedProvider = null }
        )
        return
    }

    val builtInNames = listOf("Google", "OpenAI", "Anthropic", "DeepSeek", "Qwen", "Ollama", "Open Router")

    @Composable
    fun isConfigured(name: String): Boolean = when (name) {
        "Local" -> localChatModels.isNotEmpty()
        else -> {
            val isCustom = customProviders.any { it.name == name }
            if (isCustom || name == "Ollama") !providerBaseUrls[name].isNullOrBlank()
            else apiKeys.any { it.provider == name }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_provider), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { showAddCustomDialog = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.custom_provider_add))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        val fm = LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
        ) {
            // Built-in providers
            SettingsGroup(title = stringResource(R.string.provider_built_in), items = builtInNames.map { name ->
                @Composable {
                    SettingsItem(
                        headlineContent = { Text(name) },
                        supportingContent = {
                            val configured = isConfigured(name)
                            val summary = when (name) {
                                "Ollama" -> providerBaseUrls[name]?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.not_configured)
                                else -> {
                                    val keyCount = apiKeys.count { it.provider == name }
                                    if (keyCount > 0) stringResource(R.string.provider_keys_summary, keyCount)
                                    else stringResource(R.string.not_configured)
                                }
                            }
                            Text(summary)
                        },
                        leadingContent = {
                            val configured = isConfigured(name)
                            Icon(
                                Icons.Default.Cloud, null,
                                tint = if (configured) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                        modifier = Modifier.clickable { selectedProvider = name }
                    )
                }
            })

            // Custom providers
            if (customProviders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsGroup(title = stringResource(R.string.custom_provider_section), items = customProviders.map { config ->
                    @Composable {
                        val configured = isConfigured(config.name)
                        SettingsItem(
                            headlineContent = { Text(config.name) },
                            supportingContent = {
                                Text(providerBaseUrls[config.name]?.takeIf { it.isNotBlank() } ?: stringResource(R.string.not_configured))
                            },
                            leadingContent = {
                                Icon(Icons.Default.Cloud, null, tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text(stringResource(R.string.custom_provider_badge), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            },
                            modifier = Modifier.clickable { selectedProvider = config.name }
                        )
                    }
                })
            }

            // Local
            Spacer(modifier = Modifier.height(8.dp))
            val localConfigured = localChatModels.isNotEmpty()
            SettingsGroup(title = "", items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.local_title)) },
                    supportingContent = {
                        Text(
                            if (localConfigured) stringResource(R.string.provider_local_models_summary, localChatModels.size)
                            else stringResource(R.string.not_configured)
                        )
                    },
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = if (localConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    modifier = Modifier.clickable { selectedProvider = "Local" }
                )
            })

            if (showDocFab) Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // Add Custom Provider Dialog
    if (showAddCustomDialog) {
        var customName by remember { mutableStateOf("") }
        var customBaseUrl by remember { mutableStateOf("") }
        var nameError by remember { mutableStateOf(false) }
        var urlError by remember { mutableStateOf(false) }
        val allNames = builtInNames + customProviders.map { it.name }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showAddCustomDialog = false },
            title = { Text(stringResource(R.string.custom_provider_add_title), fontWeight = FontWeight.Bold) },
            text = {
                val fm = LocalFocusManager.current
                Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                    OutlinedTextField(value = customName, onValueChange = { customName = it; nameError = false }, label = { Text(stringResource(R.string.custom_provider_name_label)) }, isError = nameError, supportingText = if (nameError) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = customBaseUrl, onValueChange = { customBaseUrl = it; urlError = false }, label = { Text(stringResource(R.string.provider_base_url)) }, isError = urlError, supportingText = if (urlError) {{ Text(stringResource(R.string.custom_provider_url_error)) }} else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val tn = customName.trim(); val tu = customBaseUrl.trim()
                    nameError = tn.isBlank() || tn in allNames; urlError = tu.isBlank()
                    if (!nameError && !urlError) { viewModel.addCustomProvider(tn, tu); showAddCustomDialog = false }
                }) { Text(stringResource(R.string.custom_provider_add)) }
            },
            dismissButton = { TextButton(onClick = { showAddCustomDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
