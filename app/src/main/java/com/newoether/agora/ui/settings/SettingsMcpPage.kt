package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.mcp.McpServerSnapshot
import com.newoether.agora.viewmodel.ChatViewModel

private data class McpEditorRoute(
    val initial: McpServerConfig,
    val isNew: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMcpPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val servers by viewModel.settings.mcpServers.collectAsState()
    val snapshots by viewModel.mcpServerSnapshots.collectAsState()
    var editorRoute by remember { mutableStateOf<McpEditorRoute?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = editorRoute != null) {
        editorRoute = null
    }

    GuardedAnimatedContent(
        targetState = editorRoute,
        forward = editorRoute != null,
    ) { route ->
        if (route != null) {
            val target = route.initial
            McpServerEditor(
                initial = target,
                snapshot = snapshots[target.id],
                isNew = route.isNew,
                onBack = { editorRoute = null },
                onSave = { saved ->
                    if (route.isNew) {
                        viewModel.settings.addMcpServer(saved)
                    } else {
                        viewModel.settings.updateMcpServer(saved)
                    }
                    editorRoute = null
                },
                onRefresh = { viewModel.refreshMcpServer(target.id) },
                onDelete = {
                    deleteId = target.id
                    editorRoute = null
                },
            )
        } else {
            val scrollState = rememberScrollState()
            CollapsingSettingsScaffold(
                title = stringResource(R.string.mcp_title),
                onBack = onBack,
                scrollState = scrollState,
            ) {
                SettingsGroupColumn {
                    SettingsGroup(
                        title = stringResource(R.string.mcp_servers),
                        items = buildList {
                            if (servers.isEmpty()) {
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                stringResource(R.string.mcp_no_servers),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        supportingContent = {
                                            Text(stringResource(R.string.mcp_no_servers_desc))
                                        },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.Hub,
                                                null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                }
                            } else {
                                servers.forEach { server ->
                                    add {
                                        val snapshot = snapshots[server.id]
                                        SettingsItem(
                                            headlineContent = {
                                                Text(server.name.ifBlank { server.url })
                                            },
                                            supportingContent = {
                                                Column {
                                                    Text(server.url)
                                                    McpStatusText(snapshot)
                                                }
                                            },
                                            leadingContent = {
                                                McpStatusIcon(snapshot?.status ?: McpConnectionStatus.IDLE)
                                            },
                                            trailingContent = {
                                                Switch(
                                                    checked = server.enabled,
                                                    onCheckedChange = {
                                                        viewModel.settings.updateMcpServer(
                                                            server.copy(enabled = it),
                                                        )
                                                    },
                                                )
                                            },
                                            modifier = Modifier.clickable {
                                                editorRoute = McpEditorRoute(
                                                    initial = server,
                                                    isNew = false,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            add {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            editorRoute = McpEditorRoute(
                                                initial = McpServerConfig(),
                                                isNew = true,
                                            )
                                        }
                                        .padding(horizontal = 16.dp, vertical = 18.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.mcp_add_server),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    deleteId?.let { id ->
        val server = servers.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mcp_delete_message,
                        server?.name?.ifBlank { server.url }.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.settings.removeMcpServer(id)
                        deleteId = null
                    },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditor(
    initial: McpServerConfig,
    snapshot: McpServerSnapshot?,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var headersText by remember(initial.id) {
        mutableStateOf(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    val parsedHeaders = remember(headersText) { parseHeaders(headersText) }
    val validUrl = remember(draft.url) { isValidMcpUrl(draft.url) }
    val canSave = draft.name.isNotBlank() && validUrl && parsedHeaders != null
    val scrollState = rememberScrollState()
    fun save() {
        if (!canSave) return
        onSave(
            draft.copy(
                name = draft.name.trim(),
                url = draft.url.trim(),
                headers = checkNotNull(parsedHeaders),
            ),
        )
    }

    CollapsingSettingsScaffold(
        title = stringResource(if (isNew) R.string.mcp_add_server else R.string.mcp_edit_server),
        onBack = onBack,
        scrollState = scrollState,
        actions = {
            IconButton(
                onClick = ::save,
                enabled = canSave,
            ) {
                Icon(Icons.Default.Save, stringResource(R.string.save))
            }
        },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.mcp_connection),
                items = listOf {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { draft = draft.copy(name = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.mcp_name)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = { Icon(Icons.Default.Hub, null) },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = draft.url,
                            onValueChange = { draft = draft.copy(url = it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.mcp_url)) },
                            supportingText = {
                                if (draft.url.isNotBlank() && !validUrl) {
                                    Text(stringResource(R.string.mcp_url_error))
                                }
                            },
                            isError = draft.url.isNotBlank() && !validUrl,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            leadingIcon = { Icon(Icons.Default.Http, null) },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = headersText,
                            onValueChange = { headersText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.mcp_headers)) },
                            placeholder = { Text("Authorization: Bearer …") },
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (parsedHeaders == null) {
                                            R.string.mcp_headers_error
                                        } else {
                                            R.string.mcp_headers_desc
                                        },
                                    ),
                                )
                            },
                            isError = parsedHeaders == null,
                            minLines = 2,
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = { Icon(Icons.Default.Key, null) },
                        )
                    }
                })
            SettingsGroup(
                title = stringResource(R.string.mcp_status),
                items = listOf {
                    SettingsItem(
                        headlineContent = { McpStatusText(snapshot) },
                        supportingContent = {
                            snapshot?.error?.takeIf(String::isNotBlank)?.let { Text(it) }
                        },
                        leadingContent = {
                            McpStatusIcon(snapshot?.status ?: McpConnectionStatus.IDLE)
                        },
                        trailingContent = {
                            IconButton(onClick = onRefresh, enabled = !isNew) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.mcp_refresh))
                            }
                        },
                    )
                },
            )
            if (snapshot?.tools?.isNotEmpty() == true) {
                SettingsGroup(
                    title = stringResource(R.string.mcp_tools_count, snapshot.tools.size),
                    items = snapshot.tools.sortedBy { it.remote.name }.map { tool ->
                        {
                            val enabled = tool.remote.name !in draft.disabledTools
                            SettingsItem(
                                headlineContent = { Text(tool.remote.name) },
                                supportingContent = {
                                    Text(
                                        tool.remote.description.ifBlank {
                                            stringResource(R.string.mcp_tool_no_description)
                                        },
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Sync, null) },
                                trailingContent = {
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { checked ->
                                            draft = draft.copy(
                                                disabledTools = if (checked) {
                                                    draft.disabledTools - tool.remote.name
                                                } else {
                                                    draft.disabledTools + tool.remote.name
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
            if (!isNew) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = onDelete,
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun McpStatusText(snapshot: McpServerSnapshot?) {
    val status = snapshot?.status ?: McpConnectionStatus.IDLE
    val label = when (status) {
        McpConnectionStatus.IDLE -> stringResource(R.string.mcp_status_idle)
        McpConnectionStatus.CONNECTING -> stringResource(R.string.mcp_status_connecting)
        McpConnectionStatus.CONNECTED -> stringResource(
            R.string.mcp_status_connected,
            snapshot?.tools?.count { it.enabled } ?: 0,
        )
        McpConnectionStatus.ERROR -> stringResource(R.string.mcp_status_error)
    }
    Text(
        text = label,
        color = when (status) {
            McpConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
            McpConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun McpStatusIcon(status: McpConnectionStatus) {
    when (status) {
        McpConnectionStatus.IDLE -> Icon(Icons.Default.CloudOff, null)
        McpConnectionStatus.CONNECTING -> CircularProgressIndicator(
            modifier = Modifier.width(22.dp).height(22.dp),
            strokeWidth = 2.dp,
        )
        McpConnectionStatus.CONNECTED -> Icon(
            Icons.Default.CheckCircle,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        McpConnectionStatus.ERROR -> Icon(
            Icons.Default.Error,
            null,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun parseHeaders(text: String): Map<String, String>? {
    val entries = linkedMapOf<String, String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (name.isEmpty() || value.isEmpty() || name.any { it <= ' ' || it == ':' }) return null
        entries[name] = value
    }
    return entries
}

private fun isValidMcpUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        uri.host != null &&
        uri.userInfo == null &&
        uri.fragment == null
}
