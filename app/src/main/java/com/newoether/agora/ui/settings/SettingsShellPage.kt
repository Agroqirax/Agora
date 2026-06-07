package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsShellPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val shellEnabled by viewModel.shellEnabled.collectAsState()
    val shellDevices by viewModel.shellDevices.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var newlyAddedDeviceId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmDeviceId by remember { mutableStateOf<String?>(null) }
    val showDocFab by viewModel.showDocumentationFab.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("shell.md") },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        val fm = androidx.compose.ui.platform.LocalFocusManager.current
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.shell_title), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.shell_enable)) },
                    supportingContent = { Text(stringResource(R.string.shell_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = shellEnabled, onCheckedChange = { viewModel.setShellEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setShellEnabled(!shellEnabled) }
                )
            }))

            if (shellEnabled) {
                if (shellDevices.isEmpty()) {
                    Text(
                        stringResource(R.string.shell_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                } else {
                    val shellDeviceItems: List<@Composable () -> Unit> = buildList {
                        shellDevices.forEach { device ->
                            add {
                                val isNewlyAdded = device.id == newlyAddedDeviceId
                                var expanded by remember(device.id) { mutableStateOf(false) }
                                var nameInput by remember(device.id) { mutableStateOf(device.name) }
                                var descInput by remember(device.id) { mutableStateOf(device.description) }
                                var urlInput by remember(device.id) { mutableStateOf(device.serverUrl) }
                                var keyInput by remember(device.id) { mutableStateOf(device.apiKey) }
                                val nameFocusRequester = remember { FocusRequester() }

                                LaunchedEffect(device) {
                                    nameInput = device.name
                                    descInput = device.description
                                    urlInput = device.serverUrl
                                    keyInput = device.apiKey
                                }

                                LaunchedEffect(isNewlyAdded) {
                                    if (isNewlyAdded) {
                                        expanded = true
                                        delay(50)
                                        nameFocusRequester.requestFocus()
                                        val expandedH = (200 * density.density).toInt()
                                        scrollState.animateScrollTo(
                                            scrollState.maxValue + expandedH,
                                            animationSpec = tween(500)
                                        )
                                        newlyAddedDeviceId = null
                                    }
                                }

                                Column {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                device.name.ifBlank { stringResource(R.string.search_untitled) },
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        supportingContent = {
                                            if (device.description.isNotBlank()) Text(device.description)
                                        },
                                        leadingContent = {
                                            Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                                        },
                                        trailingContent = {
                                            IconButton(onClick = { expanded = !expanded }) {
                                                Icon(
                                                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                    stringResource(R.string.edit)
                                                )
                                            }
                                        },
                                        modifier = Modifier.clickable { expanded = !expanded }
                                    )

                                    AnimatedVisibility(
                                    visible = expanded,
                                    enter = expandVertically(),
                                    exit = shrinkVertically()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                    ) {
                                        Spacer(Modifier.height(8.dp))

                                        OutlinedTextField(
                                            value = nameInput,
                                            onValueChange = { nameInput = it },
                                            label = { Text(stringResource(R.string.shell_device_name)) },
                                            placeholder = { Text(stringResource(R.string.shell_device_name_hint)) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(nameFocusRequester)
                                        )

                                        Spacer(Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = descInput,
                                            onValueChange = { descInput = it },
                                            label = { Text(stringResource(R.string.shell_device_desc)) },
                                            placeholder = { Text(stringResource(R.string.shell_device_desc_hint)) },
                                            leadingIcon = { Icon(Icons.Default.Description, null) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = urlInput,
                                            onValueChange = { urlInput = it },
                                            label = { Text(stringResource(R.string.shell_device_url)) },
                                            placeholder = { Text(stringResource(R.string.shell_device_url_hint)) },
                                            leadingIcon = { Icon(Icons.Default.Link, null) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = keyInput,
                                            onValueChange = { keyInput = it },
                                            label = { Text(stringResource(R.string.shell_device_key)) },
                                            placeholder = { Text(stringResource(R.string.shell_device_key_hint)) },
                                            leadingIcon = { Icon(Icons.Default.Key, null) },
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            stringResource(R.string.shell_device_timeout),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                Icons.Default.Schedule, null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                stringResource(R.string.shell_timeout_value, device.timeout),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(48.dp)
                                            )
                                            Slider(
                                                value = device.timeout.toFloat(),
                                                onValueChange = { value ->
                                                    val snapped = (value / 5f).roundToInt() * 5
                                                    viewModel.updateShellDevice(device.copy(timeout = snapped))
                                                },
                                                valueRange = 5f..120f,
                                                steps = 22,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        Spacer(Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { deleteConfirmDeviceId = device.id },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.shell_remove_device))
                                            }
                                            Button(
                                                onClick = {
                                                    viewModel.updateShellDevice(device.copy(
                                                        name = nameInput.trim(),
                                                        description = descInput.trim(),
                                                        serverUrl = urlInput.trim(),
                                                        apiKey = keyInput.trim()
                                                    ))
                                                    expanded = false
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.save))
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                    SettingsGroup(title = stringResource(R.string.shell_devices), items = shellDeviceItems)
                }

                OutlinedButton(
                    onClick = {
                        val newId = UUID.randomUUID().toString()
                        newlyAddedDeviceId = newId
                        viewModel.addShellDevice(ShellDeviceConfig(
                            id = newId,
                            name = "",
                            description = ""
                        ))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shell_add_device))
                }
            }

            if (deleteConfirmDeviceId != null) {
                val deviceToDelete = shellDevices.find { it.id == deleteConfirmDeviceId }
                val deviceName = deviceToDelete?.name?.ifBlank { stringResource(R.string.search_untitled) } ?: ""
                AlertDialog(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    onDismissRequest = { deleteConfirmDeviceId = null },
                    title = { Text(stringResource(R.string.shell_delete_confirm_title), fontWeight = FontWeight.Bold) },
                    text = { Text(stringResource(R.string.shell_delete_confirm_message, deviceName)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.removeShellDevice(deleteConfirmDeviceId!!)
                                deleteConfirmDeviceId = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(R.string.delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirmDeviceId = null }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}
