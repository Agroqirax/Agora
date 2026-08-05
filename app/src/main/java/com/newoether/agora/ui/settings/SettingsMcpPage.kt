package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.McpTransportType
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.mcp.McpServerSnapshot
import com.newoether.agora.mcp.isReservedMcpHeaderName
import com.newoether.agora.mcp.isValidMcpHeaderName
import com.newoether.agora.mcp.isValidMcpHeaderValue
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import java.util.Locale
import java.util.UUID

private data class McpEditorRoute(
    val initial: McpServerConfig,
    val isNew: Boolean,
)

private data class McpHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
    val revealValue: Boolean = false,
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
    var headerRows by remember(initial.id) {
        mutableStateOf(
            initial.headers.map { (name, value) ->
                McpHeaderDraft(name = name, value = value)
            },
        )
    }
    val parsedHeaders = remember(headerRows) { buildMcpHeaders(headerRows) }
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
                items = listOf(
                    {
                        SettingsIconContent(icon = Icons.Default.Http) {
                            Text(
                                stringResource(R.string.mcp_transport),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            val transports = McpTransportType.entries
                            PillTabSwitcher(
                                tabs = listOf(
                                    stringResource(R.string.mcp_transport_streamable_http),
                                    stringResource(R.string.mcp_transport_sse),
                                ),
                                selectedIndex = transports.indexOf(draft.transport).coerceAtLeast(0),
                                onSelect = { index ->
                                    transports.getOrNull(index)?.let { selected ->
                                        draft = draft.copy(transport = selected)
                                    }
                                },
                                allowLabelOverflow = true,
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Hub) {
                            McpLabeledField(
                                label = stringResource(R.string.mcp_name),
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Http) {
                            McpLabeledField(
                                label = stringResource(R.string.mcp_url),
                                value = draft.url,
                                onValueChange = { draft = draft.copy(url = it) },
                                isError = draft.url.isNotBlank() && !validUrl,
                                supportingText = if (draft.url.isNotBlank() && !validUrl) {
                                    stringResource(R.string.mcp_url_error)
                                } else {
                                    null
                                },
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    },
                ),
            )
            SettingsGroup(
                title = stringResource(R.string.mcp_headers),
                items = buildList {
                    headerRows.forEach { header ->
                        add {
                            key(header.id) {
                                val nameError = headerNameHasError(header, headerRows)
                                val valueError = !isValidMcpHeaderValue(header.value)
                                SettingsIconContent(icon = Icons.Default.Key) {
                                    McpLabeledField(
                                        label = stringResource(R.string.mcp_header_name),
                                        value = header.name,
                                        onValueChange = { updated ->
                                            headerRows = headerRows.map {
                                                if (it.id == header.id) {
                                                    it.copy(name = updated)
                                                } else {
                                                    it
                                                }
                                            }
                                        },
                                        isError = nameError,
                                        supportingText = if (nameError) {
                                            stringResource(R.string.mcp_header_name_error)
                                        } else {
                                            null
                                        },
                                        trailingContent = {
                                            IconButton(
                                                onClick = {
                                                    headerRows = headerRows.filterNot {
                                                        it.id == header.id
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    stringResource(R.string.mcp_delete_header),
                                                )
                                            }
                                        },
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    McpLabeledField(
                                        label = stringResource(R.string.mcp_header_value),
                                        value = header.value,
                                        onValueChange = { updated ->
                                            headerRows = headerRows.map {
                                                if (it.id == header.id) {
                                                    it.copy(value = updated)
                                                } else {
                                                    it
                                                }
                                            }
                                        },
                                        isError = valueError,
                                        supportingText = if (valueError) {
                                            stringResource(R.string.mcp_header_value_error)
                                        } else {
                                            null
                                        },
                                        password = !header.revealValue,
                                        trailingContent = {
                                            IconButton(
                                                onClick = {
                                                    headerRows = headerRows.map {
                                                        if (it.id == header.id) {
                                                            it.copy(revealValue = !it.revealValue)
                                                        } else {
                                                            it
                                                        }
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    if (header.revealValue) {
                                                        Icons.Default.VisibilityOff
                                                    } else {
                                                        Icons.Default.Visibility
                                                    },
                                                    stringResource(
                                                        if (header.revealValue) {
                                                            R.string.mcp_hide_header_value
                                                        } else {
                                                            R.string.mcp_show_header_value
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    add {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.mcp_headers_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        headerRows = headerRows + McpHeaderDraft()
                                    }
                                    .padding(vertical = 10.dp),
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
                                    stringResource(R.string.mcp_add_header),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                },
            )
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

@Composable
private fun McpLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = isError,
                supportingText = supportingText?.let { text -> { Text(text) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = trailingContent,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun headerNameHasError(
    header: McpHeaderDraft,
    allHeaders: List<McpHeaderDraft>,
): Boolean {
    val name = header.name.trim()
    if (!isValidMcpHeaderName(name) || isReservedMcpHeaderName(name)) return true
    val normalized = name.lowercase(Locale.ROOT)
    return allHeaders.count { it.name.trim().lowercase(Locale.ROOT) == normalized } != 1
}

private fun buildMcpHeaders(headers: List<McpHeaderDraft>): Map<String, String>? {
    if (headers.any { headerNameHasError(it, headers) || !isValidMcpHeaderValue(it.value) }) {
        return null
    }
    return buildMap {
        headers.forEach { header ->
            put(header.name.trim(), header.value.trim())
        }
    }
}

private fun isValidMcpUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        uri.host != null &&
        uri.userInfo == null &&
        uri.fragment == null
}
