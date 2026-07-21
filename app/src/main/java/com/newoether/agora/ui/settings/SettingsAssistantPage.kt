package com.newoether.agora.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
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
    val assistantModelId by viewModel.settings.assistantModelId.collectAsState()
    val assistantSystemPromptId by viewModel.settings.assistantSystemPromptId.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val systemPrompts by viewModel.settings.systemPrompts.collectAsState()
    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    val activeSystemPromptId by viewModel.settings.activeSystemPromptId.collectAsState()
    var showModelDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }

    // Same "Global Default (name)" phrasing ChatSystemPromptDialog uses for the
    // per-conversation prompt override, so the two null-means-follow-the-global-setting
    // pickers in the app read the same way.
    val globalDefaultModelTitle = stringResource(
        R.string.global_default_format,
        modelAliases[selectedModel] ?: ModelId.parse(selectedModel).apiModelName
    )
    val globalDefaultPromptTitle = stringResource(
        R.string.global_default_format,
        systemPrompts.find { it.id == activeSystemPromptId }?.title ?: stringResource(R.string.no_system_prompt)
    )

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
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.assistant_model)) },
                        supportingContent = {
                            val displayName = if (assistantModelId == null) globalDefaultModelTitle else {
                                val alias = modelAliases[assistantModelId!!]
                                alias ?: ModelId.parse(assistantModelId!!).apiModelName
                            }
                            Text(displayName)
                        },
                        leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { showModelDialog = true }
                    )
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.assistant_system_prompt)) },
                        supportingContent = {
                            val title = if (assistantSystemPromptId == null) globalDefaultPromptTitle
                            else systemPrompts.find { it.id == assistantSystemPromptId }?.title
                                ?: globalDefaultPromptTitle
                            Text(title)
                        },
                        leadingContent = { Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { showPromptDialog = true }
                    )
                }
            })
        }
    }

    if (showModelDialog) {
        val enabledModelsList = enabledModels.toList()
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelDialog = false },
            title = { Text(stringResource(R.string.assistant_select_model), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SettingsItem(
                            headlineContent = { Text(globalDefaultModelTitle, fontWeight = if (assistantModelId == null) FontWeight.Bold else FontWeight.Normal) },
                            leadingContent = {
                                RadioButton(selected = assistantModelId == null, onClick = {
                                    viewModel.settings.setAssistantModelId(null)
                                    showModelDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAssistantModelId(null)
                                showModelDialog = false
                            }
                        )
                    }
                    items(enabledModelsList) { model ->
                        val alias = modelAliases[model]
                        val parsed = ModelId.parse(model)
                        val displayName = alias ?: parsed.apiModelName
                        SettingsItem(
                            headlineContent = { Text(displayName, fontWeight = if (assistantModelId == model) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text(parsed.providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
                            leadingContent = {
                                RadioButton(selected = assistantModelId == model, onClick = {
                                    viewModel.settings.setAssistantModelId(model)
                                    showModelDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAssistantModelId(model)
                                showModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    if (showPromptDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showPromptDialog = false },
            title = { Text(stringResource(R.string.assistant_select_system_prompt), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SettingsItem(
                            headlineContent = { Text(globalDefaultPromptTitle, fontWeight = if (assistantSystemPromptId == null) FontWeight.Bold else FontWeight.Normal) },
                            leadingContent = {
                                RadioButton(selected = assistantSystemPromptId == null, onClick = {
                                    viewModel.settings.setAssistantSystemPromptId(null)
                                    showPromptDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAssistantSystemPromptId(null)
                                showPromptDialog = false
                            }
                        )
                    }
                    items(systemPrompts) { entry ->
                        SettingsItem(
                            headlineContent = { Text(entry.title, fontWeight = if (assistantSystemPromptId == entry.id) FontWeight.Bold else FontWeight.Normal) },
                            leadingContent = {
                                RadioButton(selected = assistantSystemPromptId == entry.id, onClick = {
                                    viewModel.settings.setAssistantSystemPromptId(entry.id)
                                    showPromptDialog = false
                                })
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAssistantSystemPromptId(entry.id)
                                showPromptDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPromptDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
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
