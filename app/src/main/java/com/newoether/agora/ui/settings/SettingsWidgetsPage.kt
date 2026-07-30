package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Palette
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
fun SettingsWidgetsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.htmlWidgetsEnabled.collectAsState()
    val networkEnabled by viewModel.settings.htmlWidgetsNetworkEnabled.collectAsState()
    val themeEnabled by viewModel.settings.htmlWidgetsThemeEnabled.collectAsState()
    val mermaidEnabled by viewModel.settings.mermaidWidgetsEnabled.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_widgets),
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

                SettingsGroup(title = stringResource(R.string.html_widgets_theme_enable), items = listOf({
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.html_widgets_theme_enable)) },
                        supportingContent = { Text(stringResource(R.string.html_widgets_theme_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = themeEnabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsThemeEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsThemeEnabled(!themeEnabled) }
                    )
                }))
            }

            SettingsGroup(title = stringResource(R.string.settings_mermaid_widgets), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.mermaid_widgets_enable)) },
                    supportingContent = { Text(stringResource(R.string.mermaid_widgets_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = mermaidEnabled, onCheckedChange = { viewModel.settings.setMermaidWidgetsEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setMermaidWidgetsEnabled(!mermaidEnabled) }
                )
            }))
        }
    }
}
