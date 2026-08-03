package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.QueuedSend

/**
 * Queued messages waiting behind an in-progress generation (or behind a Stop that is still
 * winding down), shown as a compact gray banner that hugs the top of the text field — styled to
 * match [LoopControlBar] (the cron banner): full-width, secondaryContainer, asymmetric corners
 * (rounded top, near-flat bottom so it sits flush on the input). The Column grows one compact row
 * per queued message; each row is a read-only text preview + optional attachment-count badge + an
 * X to remove it. They flush together as consecutive bubbles when the slot frees, so there is no
 * "clear all" — per-item X is the only removal (bulk-discarding a typed message is too destructive
 * for a single tap).
 */
@Composable
internal fun QueuedMessagesBanner(
    queuedSends: List<QueuedSend>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queuedSends.isNotEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                queuedSends.forEach { queued ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = queued.text,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (queued.attachments.isNotEmpty()) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = queued.attachments.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = { onRemove(queued.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
