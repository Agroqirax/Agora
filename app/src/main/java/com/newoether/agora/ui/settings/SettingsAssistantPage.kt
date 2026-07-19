package com.newoether.agora.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Settings for [com.newoether.agora.assistant.AgoraVoiceInteractionSession] — Agora's
 * role as the system's digital assistant. Its own top-level page, not nested inside
 * [SettingsAndroidPage] or grouped with any other tool: this isn't a model tool at all
 * (nothing here is a [com.newoether.agora.tool.ToolProvider] the model calls), it's how
 * the *system* launches Agora and what it hands over when it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAssistantPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val attachScreenTextEnabled by viewModel.settings.assistAttachScreenTextEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_assistant),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("android.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.settings_assistant), items = buildList {
                add { DigitalAssistantSettingsItem() }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.assist_attach_screen_text)) },
                        supportingContent = { Text(stringResource(R.string.assist_attach_screen_text_desc)) },
                        leadingContent = { Icon(Icons.Default.TextFields, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(
                                checked = attachScreenTextEnabled,
                                onCheckedChange = { viewModel.settings.setAssistAttachScreenTextEnabled(it) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.settings.setAssistAttachScreenTextEnabled(!attachScreenTextEnabled) }
                    )
                }
            })
        }
    }
}

/**
 * Shows whether Agora currently holds [RoleManager.ROLE_ASSISTANT] and deep-links to the
 * system picker to change it. Moved here verbatim from the old `SettingsAndroidPage` —
 * see [SettingsAssistantPage]'s doc for why this tool-less system-role setting has its
 * own page instead.
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
