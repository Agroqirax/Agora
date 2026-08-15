package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ShowChart
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
fun SettingsChatWidgetsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val htmlEnabled by viewModel.settings.htmlChatWidgetsEnabled.collectAsState()
    val htmlNetworkEnabled by viewModel.settings.htmlChatWidgetsNetworkEnabled.collectAsState()
    val htmlThemeEnabled by viewModel.settings.htmlChatWidgetsThemeEnabled.collectAsState()
    val htmlJsEnabled by viewModel.settings.htmlChatWidgetsJsEnabled.collectAsState()
    val svgEnabled by viewModel.settings.svgChatWidgetsEnabled.collectAsState()
    val svgNetworkEnabled by viewModel.settings.svgChatWidgetsNetworkEnabled.collectAsState()
    val svgJsEnabled by viewModel.settings.svgChatWidgetsJsEnabled.collectAsState()
    val svgThemeEnabled by viewModel.settings.svgChatWidgetsThemeEnabled.collectAsState()
    val mermaidEnabled by viewModel.settings.mermaidChatWidgetsEnabled.collectAsState()
    val vegaLiteEnabled by viewModel.settings.vegaLiteChatWidgetsEnabled.collectAsState()
    val vegaLiteNetworkEnabled by viewModel.settings.vegaLiteChatWidgetsNetworkEnabled.collectAsState()
    val chartJsEnabled by viewModel.settings.chartJsChatWidgetsEnabled.collectAsState()
    val chartJsNetworkEnabled by viewModel.settings.chartJsChatWidgetsNetworkEnabled.collectAsState()
    val geoJsonEnabled by viewModel.settings.geoJsonChatWidgetsEnabled.collectAsState()
    val geoJsonTileUrl by viewModel.settings.geoJsonTileUrl.collectAsState()
    val geoJsonThemeEnabled by viewModel.settings.geoJsonChatWidgetsThemeEnabled.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_chat_widgets),
        onBack = onBack
    ) {
        SettingsGroupColumn {
            // One group per widget kind: the enable toggle plus that widget's own sub-settings,
            // so each fence type reads as a single self-contained section.
            SettingsGroup(title = stringResource(R.string.settings_html_chat_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.html_chat_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.html_chat_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = htmlEnabled, onCheckedChange = { viewModel.settings.setHtmlChatWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setHtmlChatWidgetsEnabled(!htmlEnabled) }
                    )
                }
                if (htmlEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.html_chat_widgets_network_enable)) },
                            supportingContent = { Text(stringResource(R.string.html_chat_widgets_network_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = htmlNetworkEnabled, onCheckedChange = { viewModel.settings.setHtmlChatWidgetsNetworkEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHtmlChatWidgetsNetworkEnabled(!htmlNetworkEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.html_chat_widgets_theme_enable)) },
                            supportingContent = { Text(stringResource(R.string.html_chat_widgets_theme_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = htmlThemeEnabled, onCheckedChange = { viewModel.settings.setHtmlChatWidgetsThemeEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHtmlChatWidgetsThemeEnabled(!htmlThemeEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.html_chat_widgets_js_enable)) },
                            supportingContent = { Text(stringResource(R.string.html_chat_widgets_js_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = htmlJsEnabled, onCheckedChange = { viewModel.settings.setHtmlChatWidgetsJsEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHtmlChatWidgetsJsEnabled(!htmlJsEnabled) }
                        )
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.settings_svg_chat_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.svg_chat_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.svg_chat_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = svgEnabled, onCheckedChange = { viewModel.settings.setSvgChatWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setSvgChatWidgetsEnabled(!svgEnabled) }
                    )
                }
                if (svgEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.svg_chat_widgets_network_enable)) },
                            supportingContent = { Text(stringResource(R.string.svg_chat_widgets_network_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = svgNetworkEnabled, onCheckedChange = { viewModel.settings.setSvgChatWidgetsNetworkEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setSvgChatWidgetsNetworkEnabled(!svgNetworkEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.svg_chat_widgets_js_enable)) },
                            supportingContent = { Text(stringResource(R.string.svg_chat_widgets_js_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = svgJsEnabled, onCheckedChange = { viewModel.settings.setSvgChatWidgetsJsEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setSvgChatWidgetsJsEnabled(!svgJsEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.svg_chat_widgets_theme_enable)) },
                            supportingContent = { Text(stringResource(R.string.svg_chat_widgets_theme_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = svgThemeEnabled, onCheckedChange = { viewModel.settings.setSvgChatWidgetsThemeEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setSvgChatWidgetsThemeEnabled(!svgThemeEnabled) }
                        )
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.settings_mermaid_chat_widgets), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.mermaid_chat_widgets_enable)) },
                    supportingContent = { Text(stringResource(R.string.mermaid_chat_widgets_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = mermaidEnabled, onCheckedChange = { viewModel.settings.setMermaidChatWidgetsEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setMermaidChatWidgetsEnabled(!mermaidEnabled) }
                )
            }))

            SettingsGroup(title = stringResource(R.string.settings_vega_lite_chat_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.vega_lite_chat_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.vega_lite_chat_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.BarChart, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = vegaLiteEnabled, onCheckedChange = { viewModel.settings.setVegaLiteChatWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setVegaLiteChatWidgetsEnabled(!vegaLiteEnabled) }
                    )
                }
                if (vegaLiteEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.vega_lite_chat_widgets_network_enable)) },
                            supportingContent = { Text(stringResource(R.string.vega_lite_chat_widgets_network_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = vegaLiteNetworkEnabled, onCheckedChange = { viewModel.settings.setVegaLiteChatWidgetsNetworkEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setVegaLiteChatWidgetsNetworkEnabled(!vegaLiteNetworkEnabled) }
                        )
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.settings_chartjs_chat_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.chartjs_chat_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.chartjs_chat_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.ShowChart, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = chartJsEnabled, onCheckedChange = { viewModel.settings.setChartJsChatWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setChartJsChatWidgetsEnabled(!chartJsEnabled) }
                    )
                }
                if (chartJsEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.chartjs_chat_widgets_network_enable)) },
                            supportingContent = { Text(stringResource(R.string.chartjs_chat_widgets_network_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = chartJsNetworkEnabled, onCheckedChange = { viewModel.settings.setChartJsChatWidgetsNetworkEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setChartJsChatWidgetsNetworkEnabled(!chartJsNetworkEnabled) }
                        )
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.settings_geojson_chat_widgets), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.geojson_chat_widgets_enable)) },
                        supportingContent = { Text(stringResource(R.string.geojson_chat_widgets_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = geoJsonEnabled, onCheckedChange = { viewModel.settings.setGeoJsonChatWidgetsEnabled(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setGeoJsonChatWidgetsEnabled(!geoJsonEnabled) }
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
                            headlineContent = { Text(stringResource(R.string.geojson_theme_enable)) },
                            supportingContent = { Text(stringResource(R.string.geojson_theme_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = geoJsonThemeEnabled, onCheckedChange = { viewModel.settings.setGeoJsonChatWidgetsThemeEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setGeoJsonChatWidgetsThemeEnabled(!geoJsonThemeEnabled) }
                        )
                    }
                }
            })
        }
    }
}
