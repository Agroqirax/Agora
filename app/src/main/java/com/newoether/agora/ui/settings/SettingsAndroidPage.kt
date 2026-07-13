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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Shield
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
 * and [com.newoether.agora.tool.ContactsToolProvider]. Each "Enable X Tool" switch calls into
 * [com.newoether.agora.viewmodel.ChatViewModel] (not [com.newoether.agora.data.repository.SettingsRepository]
 * directly) because enabling a tool also proactively requests its runtime permission.
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
