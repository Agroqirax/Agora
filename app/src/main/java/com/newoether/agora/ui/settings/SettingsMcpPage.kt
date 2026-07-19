package com.newoether.agora.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMcpPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val mcpEnabled by viewModel.settings.mcpEnabled.collectAsState()
    val mcpConfirmEnabled by viewModel.settings.mcpConfirmEnabled.collectAsState()
    val mcpServers by viewModel.settings.mcpServers.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var newlyAddedServerId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmServerId by remember { mutableStateOf<String?>(null) }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val scrollState = rememberScrollState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.mcp_title),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("mcp.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.mcp_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.mcp_enable)) },
                        supportingContent = { Text(stringResource(R.string.mcp_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Api, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = mcpEnabled, onCheckedChange = { viewModel.settings.setMcpEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setMcpEnabled(!mcpEnabled) }
                    )
                }
                if (mcpEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.mcp_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.mcp_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = mcpConfirmEnabled, onCheckedChange = { viewModel.setMcpConfirmEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.setMcpConfirmEnabled(!mcpConfirmEnabled) }
                        )
                    }
                }
            })

            if (mcpEnabled) {
                SettingsGroup(title = stringResource(R.string.mcp_servers), items = buildList {
                    if (mcpServers.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.mcp_no_servers), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                leadingContent = { Icon(Icons.Default.Api, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        mcpServers.forEach { server ->
                            add {
                                ServerEditor(
                                    viewModel, server, scrollState, density, newlyAddedServerId,
                                    onNewServerId = { newlyAddedServerId = it },
                                    onDeleteConfirm = { deleteConfirmServerId = it }
                                )
                            }
                        }
                    }
                    add {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                                val newId = UUID.randomUUID().toString()
                                newlyAddedServerId = newId
                                viewModel.addMcpServer(McpServerConfig(id = newId, name = "", description = ""))
                            }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.mcp_add_server), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                })
            }
        }

        // ── Delete confirm dialog ──
        deleteConfirmServerId?.let { serverId ->
            val server = mcpServers.find { it.id == serverId }
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { deleteConfirmServerId = null },
                title = { Text(stringResource(R.string.mcp_delete_confirm_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.mcp_delete_confirm_message, server?.name?.ifBlank { stringResource(R.string.search_untitled) } ?: "")) },
                confirmButton = {
                    TextButton(onClick = { viewModel.settings.removeMcpServer(serverId); deleteConfirmServerId = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = { TextButton(onClick = { deleteConfirmServerId = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        if (showDocFab) Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// Server Editor (extracted for readability)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditor(
    viewModel: ChatViewModel,
    server: McpServerConfig,
    scrollState: androidx.compose.foundation.ScrollState,
    density: androidx.compose.ui.unit.Density,
    newlyAddedServerId: String?,
    onNewServerId: (String?) -> Unit,
    onDeleteConfirm: (String?) -> Unit
) {
    val isNewlyAdded = server.id == newlyAddedServerId
    var expanded by remember(server.id) { mutableStateOf(false) }
    var enabledInput by remember(server.id) { mutableStateOf(server.enabled) }
    var confirmEnabledInput by remember(server.id) { mutableStateOf(server.confirmEnabled) }
    var nameInput by remember(server.id) { mutableStateOf(server.name) }
    var descInput by remember(server.id) { mutableStateOf(server.description) }
    var urlInput by remember(server.id) { mutableStateOf(server.url) }
    var tokenInput by remember(server.id) { mutableStateOf(server.bearerToken) }
    var headersInput by remember(server.id) { mutableStateOf(headersToText(server.headers)) }
    var timeoutInput by remember(server.id) { mutableStateOf(server.timeout) }
    val urlFocusRequester = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var testing by remember(server.id) { mutableStateOf(false) }
    var testResult by remember(server.id) { mutableStateOf<Result<List<String>>?>(null) }
    val mcpServerInfoMap by viewModel.mcpServerInfo.collectAsState()

    LaunchedEffect(server) {
        enabledInput = server.enabled; confirmEnabledInput = server.confirmEnabled; nameInput = server.name; descInput = server.description
        urlInput = server.url; tokenInput = server.bearerToken
        headersInput = headersToText(server.headers); timeoutInput = server.timeout
    }

    LaunchedEffect(isNewlyAdded) {
        if (isNewlyAdded) {
            expanded = true; delay(50); urlFocusRequester.requestFocus()
            scrollState.animateScrollTo(scrollState.maxValue + (250 * density.density).toInt(), tween(500))
            onNewServerId(null)
        }
    }

    Column {
        SettingsItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name.ifBlank { stringResource(R.string.search_untitled) }, fontWeight = FontWeight.Medium)
                    val info = mcpServerInfoMap[server.id]
                    if (info != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            // Bounded so basicMarquee has something to overflow against — some
                            // servers (e.g. GitHub's) report a commit hash/UUID as their version
                            // instead of a short semver, which would otherwise blow out the pill.
                            modifier = Modifier.widthIn(max = 110.dp)
                        ) {
                            Text(
                                listOf(info.name, info.version).filter { it.isNotBlank() }.joinToString(" "),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp).basicMarquee(iterations = Int.MAX_VALUE),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            },
            supportingContent = {
                val text = server.description.ifBlank { hostLabel(server.url) }
                if (text.isNotBlank()) Text(text)
            },
            leadingContent = {
                Icon(
                    Icons.Default.Api, null,
                    tint = if (server.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            },
            trailingContent = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.edit))
                }
            },
            modifier = Modifier.clickable { expanded = !expanded }
        )

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { enabledInput = !enabledInput }
                ) {
                    Checkbox(checked = enabledInput, onCheckedChange = { enabledInput = it })
                    Text(stringResource(R.string.mcp_server_enabled), style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { confirmEnabledInput = !confirmEnabledInput }
                ) {
                    Checkbox(checked = confirmEnabledInput, onCheckedChange = { confirmEnabledInput = it })
                    Text(stringResource(R.string.mcp_server_confirm_enabled), style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text(stringResource(R.string.mcp_server_name)) },
                    placeholder = { Text(stringResource(R.string.mcp_server_name_hint)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = descInput, onValueChange = { descInput = it }, label = { Text(stringResource(R.string.mcp_server_desc)) },
                    placeholder = { Text(stringResource(R.string.mcp_server_desc_hint)) }, leadingIcon = { Icon(Icons.Default.Description, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = urlInput, onValueChange = { urlInput = it; testResult = null }, label = { Text(stringResource(R.string.mcp_server_url)) },
                    placeholder = { Text(stringResource(R.string.mcp_server_url_hint)) }, leadingIcon = { Icon(Icons.Default.Link, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().focusRequester(urlFocusRequester))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it }, label = { Text(stringResource(R.string.mcp_server_bearer_token)) },
                    placeholder = { Text(stringResource(R.string.mcp_server_bearer_token_hint)) }, leadingIcon = { Icon(Icons.Default.Key, null) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = headersInput, onValueChange = { headersInput = it }, label = { Text(stringResource(R.string.mcp_server_headers)) },
                    placeholder = { Text(stringResource(R.string.mcp_server_headers_hint)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    supportingText = { Text(stringResource(R.string.mcp_server_headers_desc)) },
                    minLines = 2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())

                // Timeout
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.mcp_server_timeout), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mcp_timeout_value, timeoutInput), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                    Slider(value = timeoutInput.toFloat(), onValueChange = { timeoutInput = (it / 5f).roundToInt() * 5 }, valueRange = 5f..120f, steps = 22, modifier = Modifier.weight(1f))
                }

                // Test connection
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        testResult = null; testing = true
                        scope.launch {
                            val probe = McpServerConfig(
                                id = server.id, name = nameInput.trim().ifBlank { server.name }, description = descInput.trim(),
                                url = urlInput.trim(), enabled = true, bearerToken = tokenInput.trim(),
                                headers = textToHeaders(headersInput), timeout = timeoutInput
                            )
                            val result = viewModel.testMcpServer(probe)
                            testing = false
                            testResult = result
                        }
                    },
                    enabled = !testing && urlInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mcp_test_connection_testing))
                    } else {
                        Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mcp_test_connection))
                    }
                }
                testResult?.let { result ->
                    Spacer(Modifier.height(6.dp))
                    result.fold(
                        onSuccess = { tools ->
                            Text(
                                if (tools.isEmpty()) stringResource(R.string.mcp_test_connection_success_none)
                                else stringResource(R.string.mcp_test_connection_success, tools.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onFailure = { e ->
                            Text(
                                stringResource(R.string.mcp_test_connection_failure, e.message ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDeleteConfirm(server.id) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mcp_remove_server))
                    }
                    Button(onClick = {
                        viewModel.updateMcpServer(server.copy(
                            enabled = enabledInput, confirmEnabled = confirmEnabledInput, name = nameInput.trim(), description = descInput.trim(),
                            url = urlInput.trim(), bearerToken = tokenInput.trim(),
                            headers = textToHeaders(headersInput), timeout = timeoutInput
                        )); expanded = false
                    }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

private fun headersToText(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

private fun textToHeaders(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val k = line.substring(0, idx).trim()
        val v = line.substring(idx + 1).trim()
        if (k.isBlank()) null else k to v
    }.toMap()

/** Derives "scheme://host[:port]" from [url], deliberately dropping the path and
 *  query — some MCP servers embed a long-lived secret token there (e.g.
 *  "https://host/mcp/sk-abc123..."), and this label is shown unconditionally in
 *  the collapsed server list, so it must never include anything sensitive. */
private fun hostLabel(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    try {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme != null && host != null) {
            return if (uri.port in 0..65535) "$scheme://$host:${uri.port}" else "$scheme://$host"
        }
    } catch (_: Exception) { /* fall through to the regex fallback below */ }
    val match = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://([^/?#]+)").find(trimmed) ?: return ""
    return "${match.groupValues[1]}://${match.groupValues[2]}"
}
