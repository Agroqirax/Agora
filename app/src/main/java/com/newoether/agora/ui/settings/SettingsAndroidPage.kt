package com.newoether.agora.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/**
 * Settings for on-device Android integrations exposed to the model as tools:
 * [com.newoether.agora.tool.LocationToolProvider], [com.newoether.agora.tool.CalendarToolProvider],
 * [com.newoether.agora.tool.ContactsToolProvider], [com.newoether.agora.tool.AlarmToolProvider],
 * [com.newoether.agora.tool.MediaControlToolProvider], [com.newoether.agora.tool.TorchToolProvider],
 * and [com.newoether.agora.tool.WeatherToolProvider].
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
    val mediaControlEnabled by viewModel.settings.mediaControlEnabled.collectAsState()
    val notificationsEnabled by viewModel.settings.notificationsEnabled.collectAsState()
    val notificationsConfirmEnabled by viewModel.settings.notificationsConfirmEnabled.collectAsState()
    val notificationsReadConfirmEnabled by viewModel.settings.notificationsReadConfirmEnabled.collectAsState()
    val notificationsInteractAllowedApps by viewModel.notificationsInteractAllowedApps.collectAsState()
    val torchEnabled by viewModel.settings.torchEnabled.collectAsState()
    val weatherEnabled by viewModel.settings.weatherEnabled.collectAsState()
    val weatherUnits by viewModel.settings.weatherUnits.collectAsState()
    val weatherBaseUrl by viewModel.settings.weatherBaseUrl.collectAsState()
    val weatherGeocodingBaseUrl by viewModel.settings.weatherGeocodingBaseUrl.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.android_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("android.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.digital_assistant_title), items = buildList {
                add { DigitalAssistantSettingsItem() }
            })

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
                // Only ever functional on fdroid/GitHub builds — QUERY_ALL_PACKAGES isn't
                // declared in the play flavor's manifest at all. Shown greyed-out rather
                // than hidden on play, same treatment as the Local Sandbox row when
                // !isSandboxFlavor. See tool/PackageQueryProvider.kt.
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
                if (mediaControlEnabled) {
                    add { MediaControlAccessSettingsItem() }
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
                    add { NotificationAccessSettingsItem() }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.notifications_read_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.notifications_read_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = notificationsReadConfirmEnabled, onCheckedChange = { viewModel.settings.setNotificationsReadConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setNotificationsReadConfirmEnabled(!notificationsReadConfirmEnabled) }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.notifications_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.notifications_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = notificationsConfirmEnabled, onCheckedChange = { viewModel.settings.setNotificationsConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.settings.setNotificationsConfirmEnabled(!notificationsConfirmEnabled) }
                        )
                    }
                    if (notificationsInteractAllowedApps.isNotEmpty()) {
                        val context = LocalContext.current
                        notificationsInteractAllowedApps.sorted().forEach { pkg ->
                            val label = remember(pkg) {
                                try {
                                    val pm = context.packageManager
                                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                                } catch (e: Exception) { pkg }
                            }
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.notifications_allowed_app_row, label)) },
                                    supportingContent = { Text(stringResource(R.string.notifications_allowed_app_row_desc)) },
                                    leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = {
                                        TextButton(onClick = { viewModel.revokeNotificationInteractAppAllowed(pkg) }) {
                                            Text(stringResource(R.string.notifications_allowed_app_revoke))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            })

            SettingsGroup(title = stringResource(R.string.torch_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.torch_enable)) },
                        supportingContent = { Text(stringResource(R.string.torch_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.FlashOn, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = torchEnabled, onCheckedChange = { viewModel.settings.setTorchEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setTorchEnabled(!torchEnabled) }
                    )
                }
            })

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
 * debounced-save Nominatim base URL field above it, since Open-Meteo is also self-hostable
 * (they publish a Docker image) even though the vast majority of users will never change this.
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

/**
 * Lets the user set Agora as the system digital assistant (android.app.role.ASSISTANT),
 * which is what makes the assist gesture (long-press home/power, assist swipe) launch
 * [com.newoether.agora.assistant.AgoraVoiceInteractionSession] instead of whatever
 * assistant is currently assigned. Role granting itself needs API 29+ (RoleManager);
 * below that we just point at the manual Settings path, since some AOSP-derived ROMs
 * still support it via the old ACTION_ASSIST-handling mechanism.
 */
@Composable
private fun DigitalAssistantSettingsItem() {
    val context = LocalContext.current

    // isRoleHeld() only gets re-read on recomposition, and nothing triggers one when the
    // user comes back from the system Settings screen — so without this, the status line
    // shows stale info until something else happens to recompose the page. ON_RESUME
    // fires right as this Activity comes back to the foreground, which is exactly when
    // the role may have just changed.
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val isHeld = remember(refreshTrigger) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }

    SettingsItem(
        modifier = Modifier.clickable { openManualAssistantSettings(context) },
        headlineContent = { Text(stringResource(R.string.digital_assistant_title)) },
        supportingContent = {
            Text(
                if (isHeld) stringResource(R.string.digital_assistant_status_active)
                else stringResource(R.string.digital_assistant_desc)
            )
        },
        leadingContent = {
            Icon(
                if (isHeld) Icons.Default.CheckCircle else Icons.Default.Assistant,
                null,
                tint = if (isHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/**
 * Deep-links as close as possible to Settings → Apps → Default apps → Digital assistant
 * app. ACTION_VOICE_INPUT_SETTINGS is the closest direct hit on most AOSP-derived builds;
 * ACTION_MANAGE_DEFAULT_APPS_SETTINGS (the general "Default apps" list, one tap further)
 * is the broader-compatibility fallback.
 */
private fun openManualAssistantSettings(context: android.content.Context) {
    val candidates = listOf(
        android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
        android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
    )
    for (action in candidates) {
        try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (e: Exception) {
            // Try the next candidate.
        }
    }
}

/**
 * Shows whether notification access — the special access [MediaControlToolProvider]
 * needs for [android.media.session.MediaSessionManager.getActiveSessions] — is
 * currently granted, and links to the system settings screen to enable it. Same
 * resume-refresh trick as [DigitalAssistantSettingsItem]: there's no callback for when
 * the user comes back from that settings screen, so re-read the live status on
 * ON_RESUME instead.
 */
@Composable
private fun MediaControlAccessSettingsItem() {
    val context = LocalContext.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasAccess = remember(refreshTrigger) {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    SettingsItem(
        modifier = Modifier.clickable {
            com.newoether.agora.tool.MediaControlToolProvider.openNotificationAccessSettings(
                context.applicationContext as android.app.Application
            )
        },
        headlineContent = { Text(stringResource(R.string.media_control_notification_access_title)) },
        supportingContent = {
            Text(
                if (hasAccess) stringResource(R.string.media_control_notification_access_granted)
                else stringResource(R.string.media_control_notification_access_needed)
            )
        },
        leadingContent = {
            Icon(
                if (hasAccess) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                null,
                tint = if (hasAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun NotificationAccessSettingsItem() {
    val context = LocalContext.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Checks the enabled-listener grant only (not liveness/connection) so the UI reflects
    // what the user actually toggled in system settings, even a moment before the service
    // finishes (re)connecting.
    val hasAccess = remember(refreshTrigger) {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    SettingsItem(
        modifier = Modifier.clickable {
            com.newoether.agora.service.AgoraNotificationAccessService.openNotificationAccessSettings(
                context.applicationContext as android.app.Application
            )
        },
        headlineContent = { Text(stringResource(R.string.notifications_access_title)) },
        supportingContent = {
            Text(
                if (hasAccess) stringResource(R.string.notifications_access_granted)
                else stringResource(R.string.notifications_access_needed)
            )
        },
        leadingContent = {
            Icon(
                if (hasAccess) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                null,
                tint = if (hasAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
