package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.AskUserController

/**
 * Dialog for the ask_user tool. Options (if any) are shown as tappable shortcuts that
 * resolve immediately on tap — never a hard constraint — since there's always a free-text
 * field as a fallback, mirroring how Claude Code's own AskUserQuestion tool behaves.
 */
@Composable
fun AskUserDialog(
    pending: AskUserController.PendingQuestion,
    onResolve: (answer: String?) -> Unit,
) {
    var text by remember(pending) { mutableStateOf("") }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { onResolve(null) },
        icon = {
            Icon(
                Icons.Default.QuestionAnswer, null,
                modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.ask_user_dialog_title)) },
        text = {
            Column {
                Text(pending.question, style = MaterialTheme.typography.bodyMedium)
                if (pending.options.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow {
                        items(pending.options) { option ->
                            Row {
                                OutlinedButton(onClick = { onResolve(option) }) { Text(option) }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.ask_user_dialog_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.ask_user_dialog_send))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(null) }) {
                Text(stringResource(R.string.ask_user_dialog_skip))
            }
        }
    )
}
