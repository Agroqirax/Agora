package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

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
        }
    }
}
