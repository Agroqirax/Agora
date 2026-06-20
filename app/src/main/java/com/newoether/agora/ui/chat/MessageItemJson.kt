package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.ChatType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private fun parseJsonOrNull(text: String): JsonElement? {
    return try { Json.parseToJsonElement(text) } catch (_: Exception) { null }
}

@Composable
private fun JsonNodeView(json: JsonElement, depth: Int = 0) {
    when (json) {
        is kotlinx.serialization.json.JsonObject -> JsonObjectView(json, depth)
        is kotlinx.serialization.json.JsonArray -> JsonArrayView(json, depth)
        is JsonPrimitive -> JsonPrimitiveView(json)
        is kotlinx.serialization.json.JsonNull -> JsonNullView()
    }
}

@Composable
private fun JsonObjectView(obj: kotlinx.serialization.json.JsonObject, depth: Int) {
    Column {
        obj.entries.forEach { (key, value) ->
            Column(modifier = Modifier.padding(vertical = 1.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = key,
                            style = ChatType.meta,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    when (value) {
                        is JsonPrimitive -> JsonPrimitiveView(value)
                        is kotlinx.serialization.json.JsonNull -> JsonNullView()
                        is kotlinx.serialization.json.JsonObject -> Text(
                            "{…}", style = ChatType.thoughtBody,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is kotlinx.serialization.json.JsonArray -> Text(
                            "[…]", style = ChatType.thoughtBody,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                when (value) {
                    is kotlinx.serialization.json.JsonObject -> {
                        Box(modifier = Modifier.padding(start = ((depth + 1) * 16).dp).padding(top = 2.dp)) {
                            JsonObjectView(value, depth + 1)
                        }
                    }
                    is kotlinx.serialization.json.JsonArray -> {
                        Box(modifier = Modifier.padding(start = ((depth + 1) * 16).dp).padding(top = 2.dp)) {
                            JsonArrayView(value, depth + 1)
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun JsonArrayView(arr: kotlinx.serialization.json.JsonArray, depth: Int) {
    val allPrimitive = arr.all { it is JsonPrimitive || it is kotlinx.serialization.json.JsonNull }
    if (allPrimitive && arr.size <= 8) {
        Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text("[", style = ChatType.thoughtBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
            arr.forEachIndexed { i, item ->
                when (item) {
                    is JsonPrimitive -> JsonPrimitiveView(item, inline = true)
                    is kotlinx.serialization.json.JsonNull -> JsonNullView()
                    else -> {}
                }
                if (i < arr.lastIndex) {
                    Text(", ", style = ChatType.thoughtBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("]", style = ChatType.thoughtBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column {
            arr.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 1.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = "$i",
                            style = ChatType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    when (item) {
                        is JsonPrimitive -> JsonPrimitiveView(item)
                        is kotlinx.serialization.json.JsonNull -> JsonNullView()
                        is kotlinx.serialization.json.JsonObject -> JsonObjectView(item, depth)
                        is kotlinx.serialization.json.JsonArray -> JsonArrayView(item, depth)
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonPrimitiveView(primitive: JsonPrimitive, inline: Boolean = false) {
    val color = when {
        primitive.isString -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.tertiary
    }
    val style = if (primitive.isString && !inline) {
        ChatType.thoughtBody
    } else {
        ChatType.thoughtCodeLarge
    }
    Text(
        text = primitive.content,
        style = style,
        color = color
    )
}

@Composable
private fun JsonNullView() {
    Text(
        text = "—",
        style = ChatType.thoughtBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun JsonOrPlainView(text: String) {
    val json = parseJsonOrNull(text)
    if (json != null) {
        SelectionContainer { JsonNodeView(json) }
    } else {
        SelectionContainer {
            Text(
                text = text,
                style = ChatType.thoughtCodeLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
