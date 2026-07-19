package com.newoether.agora.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.viewmodel.ToolConfirmationController

/**
 * The one confirmation dialog every gated tool uses — shell commands, destructive MCP
 * tool calls, calendar/contacts/alarm writes, notification read/interact, and location
 * reads. Replaces 8 near-identical hand-written `AlertDialog` blocks in `MainActivity`
 * that differed only in icon, copy, and whether a summary box / per-key checkbox was
 * shown; pairs with [ToolConfirmationController] on the ViewModel side.
 *
 * Call this from a `pending?.let { ... }` block, same as before — it renders nothing
 * itself when there's no pending confirmation.
 *
 * @param pending the current pending confirmation (already null-checked by the caller).
 * @param onResolve forwards to the owning `ToolConfirmationController.resolve` — called
 *   with `allow=false` on both the deny button and dismiss-by-back/scrim.
 * @param icon leading icon; each tool keeps its own (Terminal, Api, LocationOn, ...).
 * @param title fully-resolved dialog title (build with `pending.keyLabel`/other args
 *   before calling, since `stringResource` isn't available inside this file's non-composable
 *   summary logic).
 * @param message optional plain-text message shown instead of a summary box — used only
 *   by the location dialog, which has no per-call summary to display.
 * @param monospaceSummary whether [pending]'s summary is rendered in a monospace font
 *   (shell/MCP command text) or the default body font (calendar/contacts/alarm/notification
 *   human-readable summaries).
 * @param alwaysAllowLabel copy for the "stop asking / always allow" checkbox — pass null
 *   to omit it (used by shell/MCP, which only offer the narrower per-server checkbox below).
 * @param alwaysAllowKeyLabel copy for a second, narrower "always allow just this
 *   server/app" checkbox — pass null to omit it entirely (most tools have no per-key
 *   trust list; shell, MCP, and notification-interact do).
 * @param allowLabel / denyLabel button copy.
 */
@Composable
fun <Key> ToolConfirmationDialog(
    pending: ToolConfirmationController.PendingConfirmation<Key>,
    onResolve: (allow: Boolean, alwaysAllow: Boolean, alwaysAllowKey: Boolean) -> Unit,
    icon: ImageVector,
    title: String,
    message: String? = null,
    monospaceSummary: Boolean = true,
    alwaysAllowLabel: String? = null,
    alwaysAllowKeyLabel: String? = null,
    allowLabel: String,
    denyLabel: String,
) {
    var alwaysAllow by remember(pending) { mutableStateOf(false) }
    var alwaysAllowKey by remember(pending) { mutableStateOf(false) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { onResolve(false, false, false) },
        icon = { Icon(icon, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (message != null) {
                    Text(message)
                } else {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            pending.summary,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (monospaceSummary) FontFamily.Monospace else FontFamily.Default
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (alwaysAllowLabel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) { detectTapGestures { alwaysAllow = !alwaysAllow } }
                    ) {
                        Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
                        Text(alwaysAllowLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (alwaysAllowKeyLabel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) { detectTapGestures { alwaysAllowKey = !alwaysAllowKey } }
                    ) {
                        Checkbox(checked = alwaysAllowKey, onCheckedChange = { alwaysAllowKey = it })
                        Text(alwaysAllowKeyLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true, alwaysAllow, alwaysAllowKey) }) {
                Text(allowLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onResolve(false, false, false) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(denyLabel) }
        }
    )
}
