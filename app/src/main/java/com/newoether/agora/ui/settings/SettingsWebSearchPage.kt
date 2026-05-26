package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWebSearchPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val webSearchProvider by viewModel.webSearchProvider.collectAsState()
    val webSearchApiKeys by viewModel.webSearchApiKeys.collectAsState()
    val webSearchBaseUrl by viewModel.webSearchBaseUrl.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var apiKeyText by remember(webSearchProvider) { mutableStateOf(webSearchApiKeys[webSearchProvider] ?: "") }
    LaunchedEffect(webSearchProvider) { apiKeyText = webSearchApiKeys[webSearchProvider] ?: "" }

    // No-op bring-into-view to prevent auto-scrolling on text field focus

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.web_search_title), fontWeight = FontWeight.Bold) },
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
        val fm = androidx.compose.ui.platform.LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.web_search_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.web_search_enable)) },
                        supportingContent = { Text(stringResource(R.string.web_search_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = webSearchEnabled, onCheckedChange = { viewModel.setWebSearchEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.setWebSearchEnabled(!webSearchEnabled) }
                    )
                }

                if (webSearchEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.web_search_provider_label)) },
                            supportingContent = {
                                Text(
                                    when (webSearchProvider) {
                                        "searxng" -> stringResource(R.string.web_search_searxng)
                                        "serper" -> stringResource(R.string.web_search_serper)
                                        "tavily" -> stringResource(R.string.web_search_tavily)
                                        else -> stringResource(R.string.web_search_brave)
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { showProviderDialog = true }
                        )
                    }

                    if (webSearchProvider != "searxng") {
                        add {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(
                                                when (webSearchProvider) {
                                                    "serper" -> R.string.web_search_serper_key
                                                    "tavily" -> R.string.web_search_tavily_key
                                                    else -> R.string.web_search_brave_key
                                                }
                                            ),
                                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                            OutlinedTextField(
                                                value = apiKeyText,
                                                onValueChange = { apiKeyText = it; viewModel.setWebSearchApiKey(webSearchProvider, it) },
                                                placeholder = {
                                                    Text(
                                                        stringResource(
                                                            when (webSearchProvider) {
                                                                "serper" -> R.string.web_search_serper_key_hint
                                                                "tavily" -> R.string.web_search_tavily_key_hint
                                                                else -> R.string.web_search_brave_key_hint
                                                            }
                                                        )
                                                    )
                                                },
                                                visualTransformation = PasswordVisualTransformation(),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        add {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(painter = painterResource(id = com.newoether.agora.R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.web_search_searxng_url), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                        val urlState = rememberTextFieldState(webSearchBaseUrl)
                                        LaunchedEffect(urlState.text) {
                                            viewModel.setWebSearchBaseUrl(urlState.text.toString())
                                        }
                                        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                            OutlinedTextField(
                                                state = urlState,
                                                placeholder = { Text(stringResource(R.string.web_search_searxng_url_hint)) },
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text(stringResource(R.string.web_search_select_provider)) },
            text = {
                Column {
                    val providers = listOf(
                        "brave" to R.string.web_search_brave,
                        "serper" to R.string.web_search_serper,
                        "tavily" to R.string.web_search_tavily,
                        "searxng" to R.string.web_search_searxng
                    )
                    providers.forEach { (key, labelRes) ->
                        SettingsItem(
                            headlineContent = { Text(stringResource(labelRes), fontWeight = if (webSearchProvider == key) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        when (key) {
                                            "brave" -> R.string.web_search_brave_desc
                                            "serper" -> R.string.web_search_serper_desc
                                            "tavily" -> R.string.web_search_tavily_desc
                                            "searxng" -> R.string.web_search_searxng_desc
                                            else -> R.string.web_search_brave_desc
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = webSearchProvider == key,
                                    onClick = {
                                        viewModel.setWebSearchProvider(key)
                                        showProviderDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.setWebSearchProvider(key)
                                showProviderDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
