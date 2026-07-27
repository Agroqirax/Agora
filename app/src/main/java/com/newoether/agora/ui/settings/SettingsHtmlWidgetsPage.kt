package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHtmlWidgetsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.htmlWidgetsEnabled.collectAsState()
    val networkEnabled by viewModel.settings.htmlWidgetsNetworkEnabled.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_html_widgets),
        onBack = onBack
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.settings_html_widgets), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.html_widgets_enable)) },
                    supportingContent = { Text(stringResource(R.string.html_widgets_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = enabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsEnabled(!enabled) }
                )
            }))

            if (enabled) {
                SettingsGroup(title = stringResource(R.string.html_widgets_network_enable), items = listOf({
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.html_widgets_network_enable)) },
                        supportingContent = { Text(stringResource(R.string.html_widgets_network_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = networkEnabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsNetworkEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsNetworkEnabled(!networkEnabled) }
                    )
                }))
            }
        }
    }
}
