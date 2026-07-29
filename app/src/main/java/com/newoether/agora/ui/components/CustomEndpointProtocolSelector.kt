package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.CustomEndpointProtocol

fun CustomEndpointProtocol.displayName(): String = when (this) {
    CustomEndpointProtocol.OPENAI -> "OpenAI"
    CustomEndpointProtocol.GOOGLE -> "Google"
    CustomEndpointProtocol.ANTHROPIC -> "Anthropic"
    CustomEndpointProtocol.UNKNOWN -> "Unsupported"
}

@Composable
fun CustomEndpointProtocolSelector(
    selected: CustomEndpointProtocol,
    onSelected: (CustomEndpointProtocol) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CustomEndpointProtocol.selectable.forEach { protocol ->
            FilterChip(
                selected = selected == protocol,
                onClick = { onSelected(protocol) },
                label = { Text(protocol.displayName(), maxLines = 1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
