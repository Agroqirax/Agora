package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/**
 * Settings for [com.newoether.agora.tool.WeatherToolProvider]. Lives on its own top-level
 * page, not nested inside [SettingsAndroidPage], because unlike Location/Calendar/Contacts/
 * Alarm/Media Control/Notifications/Torch, weather is a plain network API call (Open-Meteo)
 * with no Android permission or system-service dependency — same category as
 * [SettingsWebSearchPage], not the device-integration tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWeatherPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val weatherEnabled by viewModel.settings.weatherEnabled.collectAsState()
    val weatherUnits by viewModel.settings.weatherUnits.collectAsState()
    val weatherBaseUrl by viewModel.settings.weatherBaseUrl.collectAsState()
    val weatherGeocodingBaseUrl by viewModel.settings.weatherGeocodingBaseUrl.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.weather_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("android.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.weather_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.weather_enable)) },
                        supportingContent = { Text(stringResource(R.string.weather_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = weatherEnabled, onCheckedChange = { viewModel.settings.setWeatherEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setWeatherEnabled(!weatherEnabled) }
                    )
                }
                if (weatherEnabled) {
                    add {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.weather_units),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = weatherUnits == "metric",
                                    onClick = { viewModel.settings.setWeatherUnits("metric") },
                                    label = { Text(stringResource(R.string.weather_units_metric)) },
                                    shape = RoundedCornerShape(50)
                                )
                                FilterChip(
                                    selected = weatherUnits == "imperial",
                                    onClick = { viewModel.settings.setWeatherUnits("imperial") },
                                    label = { Text(stringResource(R.string.weather_units_imperial)) },
                                    shape = RoundedCornerShape(50)
                                )
                            }
                        }
                    }
                    add {
                        WeatherBaseUrlField(
                            label = stringResource(R.string.weather_base_url),
                            hint = com.newoether.agora.tool.WeatherToolProvider.DEFAULT_FORECAST_BASE_URL,
                            currentValue = weatherBaseUrl,
                            defaultValue = com.newoether.agora.tool.WeatherToolProvider.DEFAULT_FORECAST_BASE_URL,
                            onSave = { viewModel.settings.setWeatherBaseUrl(it) }
                        )
                    }
                    add {
                        WeatherBaseUrlField(
                            label = stringResource(R.string.weather_geocoding_base_url),
                            hint = com.newoether.agora.tool.WeatherToolProvider.DEFAULT_GEOCODING_BASE_URL,
                            currentValue = weatherGeocodingBaseUrl,
                            defaultValue = com.newoether.agora.tool.WeatherToolProvider.DEFAULT_GEOCODING_BASE_URL,
                            onSave = { viewModel.settings.setWeatherGeocodingBaseUrl(it) }
                        )
                    }
                }
            })
        }
    }
}

/**
 * Advanced, rarely-touched override for one of the two Open-Meteo endpoints
 * ([com.newoether.agora.tool.WeatherToolProvider] forecast/geocoding base URLs). Mirrors the
 * debounced-save Nominatim base URL field in [SettingsAndroidPage], since Open-Meteo is also
 * self-hostable (they publish a Docker image) even though the vast majority of users will
 * never change this.
 */
@Composable
private fun WeatherBaseUrlField(
    label: String,
    hint: String,
    currentValue: String,
    defaultValue: String,
    onSave: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(painter = painterResource(id = R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                // Don't key on currentValue — that causes TextFieldState to be recreated
                // every time the debounced save writes to DataStore.
                val urlState = remember { TextFieldState(currentValue) }
                LaunchedEffect(currentValue) {
                    val cur = urlState.text.toString()
                    if (currentValue.isNotEmpty() && currentValue != cur) {
                        urlState.edit { replace(0, length, currentValue) }
                    }
                }
                LaunchedEffect(urlState.text) {
                    delay(500)
                    val text = urlState.text.toString()
                    onSave(text.ifBlank { defaultValue })
                }
                Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                    OutlinedTextField(
                        state = urlState,
                        placeholder = { Text(hint) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        }
    }
}
