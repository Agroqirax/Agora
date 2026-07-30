package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.DEFAULT_GEOJSON_TILE_URL
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWidgetsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val htmlEnabled by viewModel.settings.htmlWidgetsEnabled.collectAsState()
    val htmlNetworkEnabled by viewModel.settings.htmlWidgetsNetworkEnabled.collectAsState()
    val htmlThemeEnabled by viewModel.settings.htmlWidgetsThemeEnabled.collectAsState()
    val htmlJsEnabled by viewModel.settings.htmlWidgetsJsEnabled.collectAsState()
    val mermaidEnabled by viewModel.settings.mermaidWidgetsEnabled.collectAsState()
    val geoJsonEnabled by viewModel.settings.geoJsonWidgetsEnabled.collectAsState()
    val geoJsonTileUrl by viewModel.settings.geoJsonTileUrl.collectAsState()
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
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.html_widgets_js_enable)) },
                            supportingContent = { Text(stringResource(R.string.html_widgets_js_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = htmlJsEnabled, onCheckedChange = { viewModel.settings.setHtmlWidgetsJsEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHtmlWidgetsJsEnabled(!htmlJsEnabled) }
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
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Text(
                                stringResource(R.string.geojson_tile_url_label),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.geojson_tile_url_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                            )
                            // Don't key on geoJsonTileUrl — that would recreate TextFieldState every
                            // time the debounced save below writes it back to DataStore.
                            val urlState = remember { TextFieldState(geoJsonTileUrl) }
                            LaunchedEffect(geoJsonTileUrl) {
                                val cur = urlState.text.toString()
                                if (geoJsonTileUrl != cur) {
                                    urlState.edit { replace(0, length, geoJsonTileUrl) }
                                }
                            }
                            LaunchedEffect(urlState.text) {
                                delay(500)
                                val typed = urlState.text.toString()
                                viewModel.settings.setGeoJsonTileUrl(typed.ifBlank { DEFAULT_GEOJSON_TILE_URL })
                            }
                            OutlinedTextField(
                                state = urlState,
                                placeholder = { Text(DEFAULT_GEOJSON_TILE_URL) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().noOpBringIntoView(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
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
