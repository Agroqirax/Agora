package com.newoether.agora.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Calculate
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
 * [com.newoether.agora.tool.ContactsToolProvider], [com.newoether.agora.tool.AlarmToolProvider],
 * [com.newoether.agora.tool.MediaControlToolProvider], [com.newoether.agora.tool.TorchToolProvider],
 * and [com.newoether.agora.tool.CalculatorToolProvider].
 * (Weather has its own page, [SettingsWeatherPage] — it's a plain network tool with no Android
 * permission or system-service dependency, unlike everything else here.)
 * Each "Enable X Tool" switch calls into [com.newoether.agora.viewmodel.ChatViewModel] (not
 * [com.newoether.agora.data.repository.SettingsRepository] directly) because enabling a tool
 * also proactively requests its runtime permission where one is needed (alarms need none).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndroidPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val deviceInfoEnabled by viewModel.settings.deviceInfoEnabled.collectAsState()
    val packageQueryEnabled by viewModel.settings.packageQueryEnabled.collectAsState()
    val locationEnabled by viewModel.settings.locationEnabled.collectAsState()
    val locationConfirmEnabled by viewModel.settings.locationConfirmEnabled.collectAsState()
    val locationReverseGeocodeEnabled by viewModel.settings.locationReverseGeocodeEnabled.collectAsState()
    val locationNominatimBaseUrl by viewModel.settings.locationNominatimBaseUrl.collectAsState()
    val calendarEnabled by viewModel.settings.calendarEnabled.collectAsState()
    val calendarConfirmEnabled by viewModel.settings.calendarConfirmEnabled.collectAsState()
    val contactsEnabled by viewModel.settings.contactsEnabled.collectAsState()
    val contactsConfirmEnabled by viewModel.settings.contactsConfirmEnabled.collectAsState()
    val alarmEnabled by viewModel.settings.alarmEnabled.collectAsState()
    val alarmConfirmEnabled by viewModel.settings.alarmConfirmEnabled.collectAsState()
    val appLaunchEnabled by viewModel.settings.appLaunchEnabled.collectAsState()
    val mediaControlEnabled by viewModel.settings.mediaControlEnabled.collectAsState()
    val notificationsEnabled by viewModel.settings.notificationsEnabled.collectAsState()
    val notificationsConfirmEnabled by viewModel.settings.notificationsConfirmEnabled.collectAsState()
    val torchEnabled by viewModel.settings.torchEnabled.collectAsState()
    val calculatorEnabled by viewModel.settings.calculatorEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.android_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("android.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.device_info_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.device_info_enable)) },
                        supportingContent = { Text(stringResource(R.string.device_info_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = deviceInfoEnabled, onCheckedChange = { viewModel.settings.setDeviceInfoEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setDeviceInfoEnabled(!deviceInfoEnabled) }
                    )
                }
                // Falls back to this branch only if flavor detection itself failed (the
                // reflective provider lookup in AppContainer came back null) — shouldn't
                // happen on a normal build of either flavor. See tool/PackageQueryProvider.kt.
                add {
                    if (viewModel.isPackageQueryAvailable) {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.package_query_enable)) },
                            supportingContent = { Text(stringResource(R.string.package_query_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = packageQueryEnabled, onCheckedChange = { viewModel.settings.setPackageQueryEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setPackageQueryEnabled(!packageQueryEnabled) }
                        )
                    } else {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.package_query_not_supported)) },
                            supportingContent = { Text(stringResource(R.string.package_query_not_supported_desc)) },
                            leadingContent = {
                                Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        )
                    }
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.app_launch_enable)) },
                        supportingContent = { Text(stringResource(R.string.app_launch_enable_desc)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = appLaunchEnabled, onCheckedChange = { viewModel.setAppLaunchEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setAppLaunchEnabled(!appLaunchEnabled) }
                    )
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.torch_enable)) },
                        supportingContent = { Text(stringResource(R.string.torch_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.FlashOn, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = torchEnabled, onCheckedChange = { viewModel.settings.setTorchEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setTorchEnabled(!torchEnabled) }
                    )
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.calculator_enable)) },
                        supportingContent = { Text(stringResource(R.string.calculator_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Calculate, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = calculatorEnabled, onCheckedChange = { viewModel.settings.setCalculatorEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setCalculatorEnabled(!calculatorEnabled) }
                    )
                }
            })

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

            SettingsGroup(title = stringResource(R.string.alarm_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.alarm_enable)) },
                        supportingContent = { Text(stringResource(R.string.alarm_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Alarm, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = alarmEnabled, onCheckedChange = { viewModel.setAlarmEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setAlarmEnabled(!alarmEnabled) }
                    )
                }
                if (alarmEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.alarm_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.alarm_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = alarmConfirmEnabled, onCheckedChange = { viewModel.settings.setAlarmConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setAlarmConfirmEnabled(!alarmConfirmEnabled) }
                        )
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.media_control_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.media_control_enable)) },
                        supportingContent = { Text(stringResource(R.string.media_control_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = mediaControlEnabled, onCheckedChange = { viewModel.setMediaControlEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setMediaControlEnabled(!mediaControlEnabled) }
                    )
                }
            })

            SettingsGroup(title = stringResource(R.string.notifications_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.notifications_enable)) },
                        supportingContent = { Text(stringResource(R.string.notifications_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = notificationsEnabled, onCheckedChange = { viewModel.setNotificationsEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.setNotificationsEnabled(!notificationsEnabled) }
                    )
                }
                if (notificationsEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.notifications_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.notifications_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = notificationsConfirmEnabled, onCheckedChange = { viewModel.settings.setNotificationsConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setNotificationsConfirmEnabled(!notificationsConfirmEnabled) }
                        )
                    }
                }
            })
        }
    }
}

