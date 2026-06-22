package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProxyPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.proxyEnabled.collectAsState()
    val type by viewModel.settings.proxyType.collectAsState()
    val host by viewModel.settings.proxyHost.collectAsState()
    val port by viewModel.settings.proxyPort.collectAsState()
    val username by viewModel.settings.proxyUsername.collectAsState()
    val password by viewModel.settings.proxyPassword.collectAsState()
    val bypass by viewModel.settings.proxyBypass.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val proxyTypes = listOf("http" to "HTTP", "https" to "HTTPS", "socks5" to "SOCKS5")

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_proxy),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("proxy.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_proxy),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.proxy_enable)) },
                            supportingContent = { Text(stringResource(R.string.proxy_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.Lan, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = enabled, onCheckedChange = { viewModel.settings.setProxyEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.settings.setProxyEnabled(!enabled) }
                        )
                    }
                    if (enabled) {
                        add {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(stringResource(R.string.proxy_type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    proxyTypes.forEachIndexed { i, (value, label) ->
                                        SegmentedButton(
                                            selected = type == value,
                                            onClick = { viewModel.settings.setProxyType(value) },
                                            shape = SegmentedButtonDefaults.itemShape(index = i, count = proxyTypes.size)
                                        ) { Text(label) }
                                    }
                                }
                            }
                        }
                        add {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProxyField(
                                    label = stringResource(R.string.proxy_host),
                                    value = host,
                                    onChange = { viewModel.settings.setProxyHost(it) },
                                    modifier = Modifier.weight(2f)
                                )
                                ProxyField(
                                    label = stringResource(R.string.proxy_port),
                                    value = port,
                                    onChange = { viewModel.settings.setProxyPort(it.filter { c -> c.isDigit() }) },
                                    keyboard = KeyboardType.Number,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        add {
                            ProxyField(
                                label = stringResource(R.string.proxy_username),
                                value = username,
                                onChange = { viewModel.settings.setProxyUsername(it) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        add {
                            ProxyField(
                                label = stringResource(R.string.proxy_password),
                                value = password,
                                onChange = { viewModel.settings.setProxyPassword(it) },
                                password = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            )
            if (enabled) {
                SettingsGroup(
                    title = stringResource(R.string.proxy_bypass),
                    items = listOf({
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.proxy_bypass_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            ProxyField(
                                label = stringResource(R.string.proxy_bypass),
                                value = bypass,
                                onChange = { viewModel.settings.setProxyBypass(it) },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    })
                )
            }
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ProxyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true
) {
    var draft by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != draft) draft = value }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it; onChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    )
}
