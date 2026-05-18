package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.ChatViewModel
import kotlin.math.roundToInt
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsShellPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val shellEnabled by viewModel.shellEnabled.collectAsState()
    val shellDevices by viewModel.shellDevices.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
        }
    ) { padding ->
        val fm = androidx.compose.ui.platform.LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.shell_title)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.shell_enable)) },
                    supportingContent = { Text(stringResource(R.string.shell_enable_desc)) },
                    leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = shellEnabled, onCheckedChange = { viewModel.setShellEnabled(it) })
                    },
                    modifier = Modifier.clickable { viewModel.setShellEnabled(!shellEnabled) }
                )
            }

            if (shellEnabled) {
                if (shellDevices.isEmpty()) {
                    Text(
                        stringResource(R.string.shell_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                } else {
                    SettingsGroup(title = "") {
                        shellDevices.forEachIndexed { index, device ->
                            val isFirst = index == 0
                            val isLast = index == shellDevices.lastIndex
                            var expanded by remember(device.id) { mutableStateOf(false) }
                            var nameInput by remember(device.id) { mutableStateOf(device.name) }
                            var descInput by remember(device.id) { mutableStateOf(device.description) }
                            var urlInput by remember(device.id) { mutableStateOf(device.serverUrl) }
                            var keyInput by remember(device.id) { mutableStateOf(device.apiKey) }

                            LaunchedEffect(device) {
                                nameInput = device.name
                                descInput = device.description
                                urlInput = device.serverUrl
                                keyInput = device.apiKey
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = if (isFirst) 16.dp else 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        device.name.ifBlank { stringResource(R.string.shell_device_name) },
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = {
                                    if (device.description.isNotBlank()) Text(device.description)
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
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
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    Spacer(Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = nameInput,
                                        onValueChange = { nameInput = it },
                                        label = { Text(stringResource(R.string.shell_device_name)) },
                                        placeholder = { Text(stringResource(R.string.shell_device_name_hint)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = descInput,
                                        onValueChange = { descInput = it },
                                        label = { Text(stringResource(R.string.shell_device_desc)) },
                                        placeholder = { Text(stringResource(R.string.shell_device_desc_hint)) },
                                        leadingIcon = { Icon(Icons.Default.Description, null) },
                                        singleLine = true,
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
                                            onClick = {
                                                viewModel.removeShellDevice(device.id)
                                                expanded = false
                                            },
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

                            if (isLast) {
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.addShellDevice(ShellDeviceConfig(
                            id = UUID.randomUUID().toString(),
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
        }
    }
}
