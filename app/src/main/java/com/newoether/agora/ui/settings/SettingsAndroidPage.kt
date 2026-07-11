package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Shield
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
 * Settings for on-device Android integrations exposed to the model as tools:
 * [com.newoether.agora.tool.LocationToolProvider], [com.newoether.agora.tool.CalendarToolProvider],
 * and [com.newoether.agora.tool.ContactsToolProvider]. Each "Enable X Tool" switch calls into
 * [com.newoether.agora.viewmodel.ChatViewModel] (not [com.newoether.agora.data.repository.SettingsRepository]
 * directly) because enabling a tool also proactively requests its runtime permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndroidPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val locationEnabled by viewModel.settings.locationEnabled.collectAsState()
    val locationConfirmEnabled by viewModel.settings.locationConfirmEnabled.collectAsState()
    val locationReverseGeocodeEnabled by viewModel.settings.locationReverseGeocodeEnabled.collectAsState()
    val locationNominatimBaseUrl by viewModel.settings.locationNominatimBaseUrl.collectAsState()
    val calendarEnabled by viewModel.settings.calendarEnabled.collectAsState()
    val calendarConfirmEnabled by viewModel.settings.calendarConfirmEnabled.collectAsState()
    val contactsEnabled by viewModel.settings.contactsEnabled.collectAsState()
    val contactsConfirmEnabled by viewModel.settings.contactsConfirmEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.android_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("android.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.location_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.location_enable)) },
                        supportingContent = { Text(stringResource(R.string.location_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = locationEnabled, onCheckedChange = { viewModel.setLocationEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setLocationEnabled(!locationEnabled) }
                    )
                }
                if (locationEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.location_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.location_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = locationConfirmEnabled, onCheckedChange = { viewModel.settings.setLocationConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setLocationConfirmEnabled(!locationConfirmEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.location_reverse_geocode_enable)) },
                            supportingContent = { Text(stringResource(R.string.location_reverse_geocode_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = locationReverseGeocodeEnabled, onCheckedChange = { viewModel.settings.setLocationReverseGeocodeEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setLocationReverseGeocodeEnabled(!locationReverseGeocodeEnabled) }
                        )
                    }
                    if (locationReverseGeocodeEnabled) {
                        add {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Icon(painter = painterResource(id = R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.location_nominatim_base_url),
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        // Don't key on locationNominatimBaseUrl — that causes TextFieldState to
                                        // be recreated every time the debounced save writes to DataStore.
                                        val urlState = remember { TextFieldState(locationNominatimBaseUrl) }
                                        // Sync external changes (e.g. import) back into the text field.
                                        LaunchedEffect(locationNominatimBaseUrl) {
                                            val cur = urlState.text.toString()
                                            if (locationNominatimBaseUrl.isNotEmpty() && locationNominatimBaseUrl != cur) {
                                                urlState.edit { replace(0, length, locationNominatimBaseUrl) }
                                            }
                                        }
                                        // Save user input with 500ms debounce.
                                        LaunchedEffect(urlState.text) {
                                            delay(500)
                                            val text = urlState.text.toString()
                                            viewModel.settings.setLocationNominatimBaseUrl(
                                                text.ifBlank { com.newoether.agora.data.SettingsManager.DEFAULT_NOMINATIM_BASE_URL }
                                            )
                                        }
                                        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                            OutlinedTextField(
                                                state = urlState,
                                                placeholder = { Text(stringResource(R.string.location_nominatim_base_url_hint)) },
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

            SettingsGroup(title = stringResource(R.string.calendar_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.calendar_enable)) },
                        supportingContent = { Text(stringResource(R.string.calendar_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = calendarEnabled, onCheckedChange = { viewModel.setCalendarEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setCalendarEnabled(!calendarEnabled) }
                    )
                }
                if (calendarEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.calendar_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.calendar_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = calendarConfirmEnabled, onCheckedChange = { viewModel.settings.setCalendarConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setCalendarConfirmEnabled(!calendarConfirmEnabled) }
                        )
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.contacts_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.contacts_enable)) },
                        supportingContent = { Text(stringResource(R.string.contacts_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Contacts, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = contactsEnabled, onCheckedChange = { viewModel.setContactsEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setContactsEnabled(!contactsEnabled) }
                    )
                }
                if (contactsEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.contacts_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.contacts_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = contactsConfirmEnabled, onCheckedChange = { viewModel.settings.setContactsConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setContactsConfirmEnabled(!contactsConfirmEnabled) }
                        )
                    }
                }
            })
        }
    }
}
