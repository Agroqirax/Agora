package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWidgetsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val htmlEnabled by viewModel.settings.htmlWidgetsEnabled.collectAsState()
    val htmlNetworkEnabled by viewModel.settings.htmlWidgetsNetworkEnabled.collectAsState()
    val htmlThemeEnabled by viewModel.settings.htmlWidgetsThemeEnabled.collectAsState()
    val mermaidEnabled by viewModel.settings.mermaidWidgetsEnabled.collectAsState()
    val geoJsonEnabled by viewModel.settings.geoJsonWidgetsEnabled.collectAsState()
    val geoJsonNetworkEnabled by viewModel.settings.geoJsonWidgetsNetworkEnabled.collectAsState()
    val geoJsonRouteProvider by viewModel.settings.geoJsonRouteProvider.collectAsState()
    var showRouteProviderDialog by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_widgets),
        onBack = onBack
    ) {
        SettingsGroupColumn {
            // One group per widget kind: the enable toggle plus that widget's own sub-settings,
            // so each fence type reads as a single self-contained section.
            SettingsGroup(title = stringResource(R.string.settings_html_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.html_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.html_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = htmlEnabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsEnabled(!htmlEnabled) }
                    )
                }
                if (htmlEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.html_widgets_network_enable)) },
                            supportingContent = { Text(stringResource(R.string.html_widgets_network_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = htmlNetworkEnabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsNetworkEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsNetworkEnabled(!htmlNetworkEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.html_widgets_theme_enable)) },
                            supportingContent = { Text(stringResource(R.string.html_widgets_theme_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = htmlThemeEnabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsThemeEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsThemeEnabled(!htmlThemeEnabled) }
                        )
                    }
                }
            })

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

            SettingsGroup(title = stringResource(R.string.settings_geojson_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.geojson_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.geojson_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = geoJsonEnabled, onCheckedChange = { viewModel.settings.setGeoJsonWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setGeoJsonWidgetsEnabled(!geoJsonEnabled) }
                    )
                }
                if (geoJsonEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.geojson_widgets_network_enable)) },
                            supportingContent = { Text(stringResource(R.string.geojson_widgets_network_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = geoJsonNetworkEnabled, onCheckedChange = { viewModel.settings.setGeoJsonWidgetsNetworkEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setGeoJsonWidgetsNetworkEnabled(!geoJsonNetworkEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.geojson_route_provider_label)) },
                            supportingContent = {
                                Text(
                                    when (geoJsonRouteProvider) {
                                        "google" -> stringResource(R.string.geojson_route_provider_google)
                                        else -> stringResource(R.string.geojson_route_provider_osm)
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Directions, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { showRouteProviderDialog = true }
                        )
                    }
                }
            })
        }
    }

    if (showRouteProviderDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showRouteProviderDialog = false },
            title = { Text(stringResource(R.string.geojson_select_route_provider), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val providers = listOf(
                        "osm" to (R.string.geojson_route_provider_osm to R.string.geojson_route_provider_osm_desc),
                        "google" to (R.string.geojson_route_provider_google to R.string.geojson_route_provider_google_desc),
                    )
                    providers.forEach { (key, labels) ->
                        val (labelRes, descRes) = labels
                        SettingsItem(
                            headlineContent = { Text(stringResource(labelRes), fontWeight = if (geoJsonRouteProvider == key) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text(stringResource(descRes)) },
                            leadingContent = {
                                RadioButton(
                                    selected = geoJsonRouteProvider == key,
                                    onClick = {
                                        viewModel.settings.setGeoJsonRouteProvider(key)
                                        showRouteProviderDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setGeoJsonRouteProvider(key)
                                showRouteProviderDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRouteProviderDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
