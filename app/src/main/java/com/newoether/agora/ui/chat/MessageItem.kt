package com.newoether.agora.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow

private fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
    val merged = mutableListOf<MessageSegment>()
    for (seg in segs) {
        val last = merged.lastOrNull()
        if (last != null && last.type == seg.type && seg.type == "thought") {
            merged[merged.lastIndex] = last.copy(content = last.content + seg.content)
        } else {
            merged.add(seg)
        }
    }
    return merged
}

private val prettyPrinter = Json { prettyPrint = true }

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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        when (value) {
                            is JsonPrimitive -> JsonPrimitiveView(value)
                            is kotlinx.serialization.json.JsonNull -> JsonNullView()
                            is kotlinx.serialization.json.JsonObject -> Text(
                                "{…}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            is kotlinx.serialization.json.JsonArray -> Text(
                                "[…]", style = MaterialTheme.typography.bodySmall,
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
                Text("[", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                arr.forEachIndexed { i, item ->
                    when (item) {
                        is JsonPrimitive -> JsonPrimitiveView(item, inline = true)
                        is kotlinx.serialization.json.JsonNull -> JsonNullView()
                        else -> {}
                    }
                    if (i < arr.lastIndex) {
                        Text(", ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                style = MaterialTheme.typography.labelSmall,
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
            MaterialTheme.typography.bodySmall
        } else {
            MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily)
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    @Composable
    private fun JsonOrPlainView(text: String) {
        val json = parseJsonOrNull(text)
        if (json != null) {
            SelectionContainer { JsonNodeView(json) }
        } else {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    private fun formatJsonOrPlain(text: String): String {
    return try {
        val element = Json.parseToJsonElement(text)
        prettyPrinter.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        text
    }
}

@Composable
private fun toolDisplayName(toolName: String?): String {
    return when (toolName) {
        "list_memory_files" -> stringResource(R.string.tool_look_up_memories)
        "read_memory_file" -> stringResource(R.string.tool_read_memory)
        "create_memory_file" -> stringResource(R.string.tool_add_memory)
        "edit_memory_file" -> stringResource(R.string.tool_edit_memory)
        "delete_memory_file" -> stringResource(R.string.tool_delete_memory)
        "update_active_memory" -> stringResource(R.string.tool_update_active_memory)
        "web_search" -> stringResource(R.string.tool_web_search)
        "web_fetch" -> stringResource(R.string.tool_web_fetch)
        "search_conversations" -> stringResource(R.string.tool_search_conversations)
        "list_shells" -> stringResource(R.string.tool_list_shells)
        "execute_shell_command" -> stringResource(R.string.tool_execute_shell)
        else -> (toolName ?: stringResource(R.string.tool_context)).split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }
}

@Composable
private fun toolSummary(seg: MessageSegment): String {
    val name = seg.toolName ?: ""
    val argsJson = try { Json.parseToJsonElement(seg.toolArgs ?: "{}").jsonObject } catch (_: Exception) { null }
    val fileName = argsJson?.get("name")?.let { (it as? JsonPrimitive)?.content }
        ?: argsJson?.get("names")?.let { names ->
            val arr = names as? kotlinx.serialization.json.JsonArray
            if (arr != null && arr.size == 1) (arr[0] as? JsonPrimitive)?.content else null
        }
    val nameCount = argsJson?.get("names")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size }
    val content = seg.toolResult ?: ""
    val isError = content.startsWith("Error")
    return when (name) {
        "read_memory_file" -> when {
            isError -> stringResource(R.string.tool_read_memory_success)
            nameCount != null && nameCount > 1 -> stringResource(R.string.tool_read_memory_count, nameCount)
            fileName != null -> stringResource(R.string.tool_read_memory_name, fileName)
            else -> stringResource(R.string.tool_read_memory_success)
        }
        "create_memory_file" -> when {
            isError -> stringResource(R.string.tool_save_memory_failed)
            fileName != null -> stringResource(R.string.tool_save_memory_name, fileName)
            else -> stringResource(R.string.tool_save_memory_default)
        }
        "edit_memory_file" -> when {
            isError -> stringResource(R.string.tool_edit_memory_failed)
            fileName != null -> stringResource(R.string.tool_edit_memory_name, fileName)
            else -> stringResource(R.string.tool_edit_memory_default)
        }
        "delete_memory_file" -> when {
            isError -> stringResource(R.string.tool_delete_memory_failed)
            fileName != null -> stringResource(R.string.tool_delete_memory_name, fileName)
            else -> stringResource(R.string.tool_delete_memory_default)
        }
        "list_memory_files" -> {
            if (isError) stringResource(R.string.tool_lookup_failed)
            else {
                val fileCount = try {
                    Json.parseToJsonElement(content).jsonObject["files"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (fileCount > 0) stringResource(R.string.tool_lookup_count, fileCount)
                else stringResource(R.string.tool_lookup_default)
            }
        }
        "update_active_memory" -> if (isError) stringResource(R.string.tool_update_active_failed) else stringResource(R.string.tool_update_active_default)
        "web_search" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_search_failed)
            else {
                val resultCount = try {
                    Json.parseToJsonElement(content).jsonObject["results"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (resultCount > 0 && query != null) stringResource(R.string.tool_web_search_done, resultCount, query)
                else if (query != null) stringResource(R.string.tool_searching_web, query)
                else stringResource(R.string.tool_web_search_done_default)
            }
        }
        "web_fetch" -> {
            val url = argsJson?.get("url")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_web_fetch_failed)
            else if (url != null) stringResource(R.string.tool_web_fetch_done, url.take(60).ifEmpty { "page" })
            else stringResource(R.string.tool_web_fetch_default)
        }
        "search_conversations" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_search_failed)
            else {
                val convCount = try {
                    Json.parseToJsonElement(content).jsonObject["results"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (convCount > 0 && query != null) stringResource(R.string.tool_conversation_search_done_for, convCount, query)
                else if (query != null) stringResource(R.string.tool_searching_conversations, query)
                else stringResource(R.string.tool_searching_conversations_default)
            }
        }
        "list_shells" -> {
            if (isError) stringResource(R.string.tool_shell_listing)
            else {
                val deviceCount = try {
                    Json.parseToJsonElement(content).jsonObject["devices"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (deviceCount > 0) stringResource(R.string.tool_shell_list_count, deviceCount)
                else stringResource(R.string.tool_shell_list_done)
            }
        }
        "execute_shell_command" -> {
            val command = argsJson?.get("command")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_shell_failed)
            else if (command != null) stringResource(R.string.tool_shell_executing, command.take(80))
            else stringResource(R.string.tool_shell_done)
        }
        else -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else stringResource(R.string.tool_done)
        }
    }
}

@Composable
private fun toolResultSummary(toolName: String, toolArgs: String, result: String = ""): String {
    val isError = result.startsWith("Error")
    if (isError) return stringResource(R.string.tool_call_failed)
    val argsJson = try { Json.parseToJsonElement(toolArgs.ifBlank { "{}" }).jsonObject } catch (_: Exception) { null }
    val fileName = argsJson?.get("name")?.let { (it as? JsonPrimitive)?.content }
        ?: argsJson?.get("names")?.let { names ->
            val arr = names as? kotlinx.serialization.json.JsonArray
            if (arr != null && arr.size == 1) (arr[0] as? JsonPrimitive)?.content else null
        }
    val nameCount = argsJson?.get("names")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size }
    return when (toolName) {
        "read_memory_file" -> when {
            nameCount != null && nameCount > 1 -> stringResource(R.string.tool_read_memory_count, nameCount)
            fileName != null -> stringResource(R.string.tool_read_memory_name, fileName)
            else -> stringResource(R.string.tool_read_memory_success)
        }
        "create_memory_file" -> if (fileName != null) stringResource(R.string.tool_save_memory_name, fileName) else stringResource(R.string.tool_save_memory_default)
        "edit_memory_file" -> if (fileName != null) stringResource(R.string.tool_edit_memory_name, fileName) else stringResource(R.string.tool_edit_memory_default)
        "delete_memory_file" -> if (fileName != null) stringResource(R.string.tool_delete_memory_name, fileName) else stringResource(R.string.tool_delete_memory_default)
        "list_memory_files" -> {
            val fileCount = try {
                Json.parseToJsonElement(result).jsonObject["files"]?.jsonArray?.size ?: 0
            } catch (_: Exception) { 0 }
            if (fileCount > 0) stringResource(R.string.tool_lookup_count, fileCount)
            else stringResource(R.string.tool_lookup_default)
        }
        "update_active_memory" -> stringResource(R.string.tool_update_active_default)
        "web_search" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            val resultCount = try {
                Json.parseToJsonElement(result).jsonObject["results"]?.jsonArray?.size ?: 0
            } catch (_: Exception) { 0 }
            if (resultCount > 0 && query != null) stringResource(R.string.tool_web_search_done, resultCount, query)
            else if (query != null) stringResource(R.string.tool_searching_web, query)
            else stringResource(R.string.tool_web_search_done_default)
        }
        "web_fetch" -> {
            val url = argsJson?.get("url")?.let { (it as? JsonPrimitive)?.content }
            if (url != null) stringResource(R.string.tool_web_fetch_done, url.take(60).ifEmpty { "page" })
            else stringResource(R.string.tool_web_fetch_default)
        }
        "search_conversations" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            val convCount = try {
                Json.parseToJsonElement(result).jsonObject["results"]?.jsonArray?.size ?: 0
            } catch (_: Exception) { 0 }
            if (convCount > 0 && query != null) stringResource(R.string.tool_conversation_search_done_for, convCount, query)
            else if (query != null) stringResource(R.string.tool_searching_conversations, query)
            else stringResource(R.string.tool_searching_conversations_default)
        }
        "list_shells" -> {
            val deviceCount = try {
                Json.parseToJsonElement(result).jsonObject["devices"]?.jsonArray?.size ?: 0
            } catch (_: Exception) { 0 }
            if (deviceCount > 0) stringResource(R.string.tool_shell_list_count, deviceCount)
            else stringResource(R.string.tool_shell_list_done)
        }
        "execute_shell_command" -> {
            val command = argsJson?.get("command")?.let { (it as? JsonPrimitive)?.content }
            if (command != null) stringResource(R.string.tool_shell_executing, command.take(80))
            else stringResource(R.string.tool_shell_done)
        }
        else -> stringResource(R.string.tool_done)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageItem(
    message: ChatMessage, 
    onEdit: (String, String) -> Unit, 
    isStreaming: Boolean = false,
    isLoading: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    modelAliases: Map<String, String> = emptyMap(),
    visualizeContextRollout: Boolean = false,
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {}
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    var isThoughtExpanded by rememberSaveable { mutableStateOf(false) }
    var showSegmentDetail by remember { mutableStateOf(false) }
    var selectedSegmentIndex by remember { mutableIntStateOf(-1) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    if (showInfoDialog) {
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val dateString = sdf.format(Date(message.timestamp))
        val modelDisplay = if (message.modelName != null) {
            val modelId = message.modelName.removePrefix("models/").substringAfter(":")
            val provider = message.modelName.removePrefix("models/").substringBefore(":")
            modelAliases[message.modelName] ?: ("$modelId ($provider)")
        } else stringResource(R.string.unknown)

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.message_info)) },
            text = {
                Column {
                    Text(stringResource(R.string.time_with_label, dateString), style = MaterialTheme.typography.bodyMedium)
                    if (message.participant == Participant.MODEL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.model_with_label, modelDisplay), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(stringResource(R.string.provider_close)) }
            }
        )
    }

    var currentTotalHeight by remember { mutableIntStateOf(0) }
    // No-op modifier that suppresses bring-into-view auto-scrolling on focus

    fun calculateReportedHeight(totalPx: Int, thoughtPx: Int): Int {
        // When we are NOT expanded, the thought block is animating down from its large height 
        // to its stableCollapsedThoughtHeight. We want the outer list padding to behave as if
        // the thought block INSTANTLY hit stableCollapsedThoughtHeight, avoiding the final "jump".
        // But we ONLY do this if the thought block is currently larger than its collapsed height 
        // AND we know what the collapsed height is.
        if (!isThoughtExpanded && stableCollapsedThoughtHeight > 0 && thoughtPx > stableCollapsedThoughtHeight) {
            val excessHeight = thoughtPx - stableCollapsedThoughtHeight
            return totalPx - excessHeight
        }
        return totalPx
    }

    LaunchedEffect(message.text, message.status, isEditing) {
        kotlinx.coroutines.delay(50)
        onHeightChanged(calculateReportedHeight(currentTotalHeight, currentThoughtBlockHeight))
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val backgroundColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.primaryContainer
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Participant.MODEL -> MaterialTheme.colorScheme.onSurface
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val shape = when (message.participant) {
        Participant.USER -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
        Participant.MODEL -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        Participant.ERROR -> RoundedCornerShape(12.dp)
    }

    val currentTypography = MaterialTheme.typography
    val customTypography = markdownTypography(
        text = currentTypography.bodyMedium,
        h1 = currentTypography.headlineSmall,
        h2 = currentTypography.titleLarge,
        h3 = currentTypography.titleMedium,
        h4 = currentTypography.titleSmall,
        h5 = currentTypography.titleSmall,
        h6 = currentTypography.titleSmall,
        code = currentTypography.bodyMedium.copy(
            fontFamily = MonoFamily,
            fontSize = 13.sp
        ),
        inlineCode = currentTypography.bodyMedium.copy(
            fontFamily = MonoFamily,
            fontSize = 13.sp
        ),
    )
    
    // Dedicated compact typography for thoughts
    val thoughtTypography = markdownTypography(
        text = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        paragraph = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        ordered = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bullet = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        list = currentTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        h1 = currentTypography.bodyMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        h2 = currentTypography.bodyMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        h3 = currentTypography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        h4 = currentTypography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        h5 = currentTypography.bodyMedium.copy(fontSize = 13.sp),
        h6 = currentTypography.bodyMedium.copy(fontSize = 12.sp),
        code = currentTypography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 12.sp),
        inlineCode = currentTypography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 12.sp),
    )

    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.surface
    // Composite fg at 0.1 alpha over bg to produce the exact opaque equivalent
    val codeBg = remember(fg, bg) {
        Color(
            red   = fg.red   * 0.1f + bg.red   * 0.9f,
            green = fg.green * 0.1f + bg.green * 0.9f,
            blue  = fg.blue  * 0.1f + bg.blue  * 0.9f,
        )
    }
    val customMarkdownColors = markdownColor(
        codeBackground = codeBg,
        inlineCodeBackground = Color.Transparent,
    )
    val customMarkdownPadding = markdownPadding(block = 7.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 4.dp)

    val customMarkdownComponents = remember {
        markdownComponents(
            table = { model ->
                MarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                    headerBlock = { content, header, tableWidth, style ->
                        MarkdownTableHeader(
                            content = content,
                            header = header,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                    rowBlock = { content, row, tableWidth, style ->
                        MarkdownTableRow(
                            content = content,
                            header = row,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                )
            }
        )
    }

    val latexImageTransformer = remember(textColor) {
        LatexImageTransformer(
            textSize = 56f,
            color = textColor.toArgb(),
        )
    }

    val shouldAnimate = !isFirstComposition && !isSwitching

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged {
                currentTotalHeight = it.height
                onHeightChanged(calculateReportedHeight(it.height, currentThoughtBlockHeight))
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
        val contextAlpha = if (visualizeContextRollout && !isInContext) Modifier.alpha(0.38f) else Modifier
        if (message.participant == Participant.USER) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = shape,
                    color = backgroundColor,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .then(contextAlpha)
                        .then(if (shouldAnimate) Modifier.animateContentSize(animationSpec = tween(500)) else Modifier)
                ) {
                    if (isEditing) {
                        val editState = rememberTextFieldState(message.text)
                        val editScrollState = rememberScrollState()
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(modifier = Modifier.noOpBringIntoView()) {
                                TextField(
                                    state = editState,
                                    scrollState = editScrollState,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onCancelEdit() }) { Text(stringResource(R.string.cancel)) }
                                TextButton(onClick = { onEdit(message.id, editState.text.toString()) }) { Text(stringResource(R.string.send)) }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(16.dp).noOpBringIntoView(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            val hasMetaItems = message.attachmentMeta?.items?.isNotEmpty() == true
                        if (message.images.isNotEmpty() || hasMetaItems) {
                                val ctx = LocalContext.current
                                val meta = remember(message.attachmentMeta) {
                                    message.attachmentMeta
                                }
                                // Build display items: skip non-first video/PDF frames, add meta-only items
                                val displayItems = remember(message.images, meta) {
                                    val skipIndices = mutableSetOf<Int>()
                                    if (meta != null) {
                                        for (item in meta.items) {
                                            val count = item.pageCount ?: 1
                                            if (item.imageIndex != null && count > 1 && (item.type == "video" || item.type == "pdf")) {
                                                for (i in item.imageIndex + 1 until item.imageIndex + count) {
                                                    skipIndices.add(i)
                                                }
                                            }
                                        }
                                    }
                                    // Image-backed items
                                    val imageItems = message.images.mapIndexedNotNull { index, path ->
                                        if (index in skipIndices) null
                                        else {
                                            val item = findMetaForIndex(meta, index)
                                            Triple(index, path, item)
                                        }
                                    }
                                    // Meta-only items (file/PDF without image representation)
                                    val metaOnlyItems = meta?.items
                                        ?.filter { it.imageIndex == null && (it.type == "file" || it.type == "pdf" || it.type == "image") }
                                        ?.map { Triple(-1, "", it) }
                                        ?: emptyList()
                                    imageItems + metaOnlyItems
                                }

                                androidx.compose.foundation.lazy.LazyRow(
                                    modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(displayItems) { (index, imagePath, metaItem) ->
                                        val type = remember(imagePath, metaItem?.type) {
                                            resolveAttachmentType(imagePath, metaItem, ctx)
                                        }
                                        val isVideo = type == "video"
                                        val isPdf = type == "pdf"
                                        val isFileType = type == "file"

                                        val fileName = metaItem?.fileName ?: imagePath.substringAfterLast("/")
                                        val pdfPages = if (type == "pdf") {
                                            metaItem?.imageIndex?.let { start ->
                                                val count = metaItem.pageCount ?: 1
                                                val end = (start + count).coerceAtMost(message.images.size)
                                                if (start in 0 until message.images.size) message.images.subList(start, end) else emptyList()
                                            } ?: emptyList()
                                        } else emptyList()

                                        AttachmentThumbnailItem(
                                            type = type,
                                            imagePath = imagePath,
                                            fileName = fileName,
                                            originalUri = metaItem?.originalUri,
                                            textContent = metaItem?.textContent,
                                            pdfPages = pdfPages,
                                            handlers = ThumbnailClickHandlers(
                                                onImageClick = onImageClick,
                                                onVideoClick = { onImageClick(it) },
                                                onFileClick = onFileContentClick,
                                                onPdfClick = onPdfPagesClick
                                            )
                                        )
                                        if (type == "pdf" && metaItem?.warning != null) {
                                            Text(metaItem.warning, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE53935), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            if (message.text.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        text = message.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (totalBranches > 1 && !isEditing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .then(contextAlpha)
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(100))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp)
                    ) {
                        IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                        }
                        Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (!isEditing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.then(contextAlpha)
                    ) {
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { onStartEdit() }, enabled = isEditingAllowed, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.info), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        } else {
            // During generation, eat horizontal nested-scroll so code blocks
            // cannot be panned. Vertical scroll and taps (thinking header,
            // stop button) pass through normally. Text selection is already
            // prevented during streaming — SelectionContainer is only in the
            // else (!isStreaming) branch.
            val horizontalScrollEater = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                        Offset(available.x, 0f)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .then(contextAlpha)
                    .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
            ) {
                Column {
                    // Status Header
                    if (message.participant == Participant.MODEL) {
                        val thinkingStatus = stringResource(R.string.thinking_ellipsis)
                        val answeringStatus = stringResource(R.string.answering_ellipsis)
                        // Hold the last non-fallback label so transitions between
                        // "Thinking… → Answering…" don't flash "Sending…" while
                        // the first answer token is still in-flight.
                        var heldLabel by remember { mutableStateOf("") }
                        var heldStatusText by remember { mutableStateOf("") }
                        // Update heldLabel after composition to avoid double-recomposition flash
                        val thinkingNow = message.status == MessageStatus.THINKING
                        val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                        val hasText = message.text.isNotEmpty()
                        LaunchedEffect(thinkingNow, hasText, message.status) {
                            heldLabel = when {
                                thinkingNow -> "thinking"
                                isToolCalling -> "calling"
                                hasText -> "answering"
                                message.status == MessageStatus.SUCCESS || message.status == MessageStatus.ERROR || message.status == MessageStatus.STOPPED -> ""
                                message.status == MessageStatus.SENDING -> ""
                                else -> heldLabel
                            }
                        }
                        val toolCallingStatus = stringResource(R.string.tool_calling_ellipsis)
                        val statusText = when {
                            message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) stringResource(R.string.cost_tokens, message.tokenCount) else null
                            isStreaming && isToolCalling -> toolCallingStatus
                            isStreaming && thinkingNow -> thinkingStatus
                            isStreaming && hasText -> answeringStatus
                            isStreaming -> when (heldLabel) {
                                "thinking" -> thinkingStatus
                                "calling" -> toolCallingStatus
                                "answering" -> answeringStatus
                                else -> stringResource(R.string.sending_ellipsis)
                            }
                            else -> null
                        }
                        // Hold the last non-null label so the status bar doesn't collapse
                        // during the timing gap between isStreaming→false and the DB
                        // emitting the updated message status.
                        val displayText = when {
                            statusText != null -> statusText.also { heldStatusText = it }
                            message.status == MessageStatus.SENDING || message.status == MessageStatus.THINKING || message.status == MessageStatus.TOOL_CALLING -> heldStatusText.takeIf { it.isNotEmpty() }
                            else -> null.also { heldStatusText = "" }
                        }

                        AnimatedVisibility(
                            visible = displayText != null,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            val text = displayText ?: return@AnimatedVisibility
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                    if (isStreaming || message.status == MessageStatus.SENDING || message.status == MessageStatus.THINKING || message.status == MessageStatus.TOOL_CALLING) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = if (text == thinkingStatus) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    } else {
                                        val icon = when (message.status) {
                                            MessageStatus.SUCCESS -> Icons.Default.CheckCircle
                                            MessageStatus.STOPPED -> Icons.Default.Stop
                                            else -> Icons.Default.Info
                                        }
                                        Icon(icon, null, modifier = Modifier.size(16.dp), tint = if (message.status == MessageStatus.SUCCESS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    var debouncedText by remember { mutableStateOf(message.text) }
                    if (!isStreaming) {
                        debouncedText = message.text
                    } else {
                        var lastUpdateMs by remember { mutableLongStateOf(0L) }
                        var flushJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        LaunchedEffect(message.text) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastUpdateMs
                            if (elapsed >= 500) {
                                flushJob?.cancel()
                                debouncedText = message.text
                                lastUpdateMs = now
                            } else {
                                flushJob?.cancel()
                                flushJob = launch {
                                    kotlinx.coroutines.delay(500 - elapsed)
                                    debouncedText = message.text
                                    lastUpdateMs = System.currentTimeMillis()
                                }
                            }
                        }
                    }

                    // Level 1: anti-shrink for text and thinking content (kept after streaming ends)
                    var streamingMaxHeightPx by remember { mutableIntStateOf(0) }
                    var thinkingContentMaxHeightPx by remember { mutableIntStateOf(0) }

                    // Reset anti-shrink heights when streaming restarts (e.g. regeneration)
                    LaunchedEffect(isStreaming) {
                        if (isStreaming) {
                            streamingMaxHeightPx = 0
                            thinkingContentMaxHeightPx = 0
                        }
                    }

                    Column {
                        val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                        // Only zero out thought height when legacy thought block is not shown
                        if (message.segments != null || message.thoughts.isNullOrBlank()) {
                            currentThoughtBlockHeight = 0
                        }

                        // Merged segment block: single block, newest title/icon when collapsed
                        val segmentsOrNull = message.segments
                        AnimatedVisibility(
                            visible = segmentsOrNull != null && segmentsOrNull.isNotEmpty(),
                            enter = fadeIn(tween(500)) + expandVertically(tween(500)),
                            exit = fadeOut(tween(500)) + shrinkVertically(tween(500))
                        ) {
                            val segs = mergeAdjacentSegments(segmentsOrNull ?: return@AnimatedVisibility)
                            val lastSeg = segs.last()
                            val isLastTool = lastSeg.type == "tool"
                            val isToolInProgress = isLastTool && lastSeg.toolResult == null
                            val isThinking = message.status == MessageStatus.THINKING
                            val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                            val collapsedTitle = when {
                                isThinking -> message.thoughtTitle ?: stringResource(R.string.thinking_ellipsis)
                                isToolCalling || isToolInProgress -> toolDisplayName(lastSeg.toolName)
                                else -> {
                                    if (message.thoughtTimeMs != null && message.thoughtTimeMs > 0) {
                                        val seconds = message.thoughtTimeMs / 1000
                                        if (seconds >= 60) stringResource(R.string.thought_for_minutes, seconds / 60, seconds % 60)
                                        else stringResource(R.string.thought_for_seconds, seconds)
                                    } else if (message.thoughtTitle != null) message.thoughtTitle
                                    else stringResource(R.string.thinking_complete)
                                }
                            }
                            val mergedBottomPadding by animateDpAsState(
                                targetValue = if (isThoughtExpanded) 12.dp else 4.dp,
                                animationSpec = tween(500), label = "mergedPad"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = mergedBottomPadding + 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .noOpBringIntoView()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { isThoughtExpanded = !isThoughtExpanded }
                                        .padding(10.dp)
                                ) {
                                    if (isToolCalling || isToolInProgress) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else {
                                        Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        collapsedTitle, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (isThoughtExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        null, modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                AnimatedVisibility(
                                    visible = isThoughtExpanded,
                                    enter = fadeIn(tween(500)) + expandVertically(tween(500)),
                                    exit = fadeOut(tween(500)) + shrinkVertically(tween(500))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .then(
                                                if (thinkingContentMaxHeightPx > 0)
                                                    Modifier.heightIn(min = with(LocalDensity.current) { thinkingContentMaxHeightPx.toDp() })
                                                else Modifier
                                            )
                                            .onSizeChanged { size ->
                                                if (isStreaming) {
                                                    thinkingContentMaxHeightPx = maxOf(thinkingContentMaxHeightPx, size.height)
                                                }
                                            }
                                    ) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        segs.forEachIndexed { idx, seg ->
                                            if (seg.type == "thought" && seg.content.isNotBlank()) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { selectedSegmentIndex = idx; showSegmentDetail = true }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Text(
                                                        stringResource(R.string.tool_thinking), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Box {
                                                        if (!isStreaming) {
                                                            SelectionContainer {
                                                                RecomposeSafeMarkdown(
                                                                    content = seg.content,
                                                                    isStreaming = isStreaming
                                                                ) { text ->
                                                                    Markdown(
                                                                        content = text.escapeForMarkdown(),
                                                                        modifier = Modifier.fillMaxWidth(),
                                                                        colors = customMarkdownColors,
                                                                        typography = thoughtTypography,
                                                                        padding = thoughtMarkdownPadding,
                                                                        components = customMarkdownComponents
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            RecomposeSafeMarkdown(
                                                                content = seg.content,
                                                                isStreaming = isStreaming
                                                            ) { text ->
                                                                Markdown(
                                                                    content = text.escapeForMarkdown(),
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    colors = customMarkdownColors,
                                                                    typography = thoughtTypography,
                                                                    padding = thoughtMarkdownPadding,
                                                                    components = customMarkdownComponents
                                                                )
                                                            }
                                                        }

                                                    }
                                                }
                                            } else if (seg.type == "tool") {
                                                val isToolError = (seg.toolResult ?: "").startsWith("Error")
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { selectedSegmentIndex = idx; showSegmentDetail = true }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Text(
                                                        toolDisplayName(seg.toolName),
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                        color = if (isToolError) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = toolSummary(seg),
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                                                        color = if (isToolError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            if (idx < segs.lastIndex) {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (streamingMaxHeightPx > 0)
                                        Modifier.heightIn(min = with(LocalDensity.current) { streamingMaxHeightPx.toDp() })
                                    else Modifier
                                )
                                .onSizeChanged { size ->
                                    if (isStreaming) {
                                        streamingMaxHeightPx = maxOf(streamingMaxHeightPx, size.height)
                                    }
                                }
                                .noOpBringIntoView()
                        ) {
                            if (isError) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        SelectionContainer {
                                            Text(
                                                debouncedText.ifEmpty { stringResource(R.string.failed_to_generate) },
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, lineHeight = 18.sp),
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            } else if (debouncedText.isNotEmpty()) {
                                Box {
                                    SelectionContainer {
                                        RecomposeSafeMarkdown(
                                            content = debouncedText,
                                            isStreaming = isStreaming
                                        ) { text ->
                                            val spans = remember(text) { parseLatexSpans(text) }
                                            if (spans.all { !it.isLatex }) {
                                                Markdown(
                                                    content = text.escapeForMarkdown(),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = customMarkdownColors,
                                                    typography = customTypography,
                                                    padding = customMarkdownPadding,
                                                    components = customMarkdownComponents,
                                                    imageTransformer = latexImageTransformer
                                                )
                                            } else {
                                                val paragraphs = remember(text) { splitParagraphs(text) }
                                                Column {
                                                    paragraphs.forEachIndexed { paraIdx, paragraph ->
                                                        if (paraIdx > 0) Spacer(Modifier.height(8.dp))
                                                        val paraSpans = remember(paragraph) {
                                                            val spans = parseLatexSpans(paragraph)
                                                            val soloInline = spans.count { it.isLatex } == 1 &&
                                                                spans.all { s -> s.isLatex || s.content.isBlank() }
                                                            if (soloInline) {
                                                                spans.map { s -> if (s.isLatex && !s.display) s.copy(display = true) else s }
                                                            } else {
                                                                spans
                                                            }
                                                        }
                                                        if (paraSpans.all { !it.isLatex }) {
                                                            Markdown(
                                                                content = paragraph.escapeForMarkdown(),
                                                                modifier = Modifier.fillMaxWidth(),
                                                                typography = customTypography,
                                                                padding = customMarkdownPadding,
                                                                components = customMarkdownComponents,
                                                                imageTransformer = latexImageTransformer
                                                            )
                                                        } else {
                                                            var pendingSpans = mutableListOf<LatexSpan>()
                                                            for (span in paraSpans) {
                                                                if (span.isLatex && span.display) {
                                                                    if (pendingSpans.isNotEmpty()) {
                                                                        LatexAwareText(
                                                                            spans = pendingSpans.toList(),
                                                                            modifier = Modifier.fillMaxWidth(),
                                                                            textStyle = customTypography.paragraph.copy(color = textColor),
                                                                            latexTextSize = 56f,
                                                                            latexColor = textColor.toArgb(),
                                                                            codeSpanStyle = customTypography.inlineCode.toSpanStyle(),
                                                                        )
                                                                        pendingSpans.clear()
                                                                    }
                                                                    val latexColor = if (message.participant == Participant.USER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                                    val bmp = remember(span.content, latexColor) { renderLatexToBitmap(span.content, color = latexColor.toArgb()) }
                                                                    if (bmp != null) {
                                                                        Image(bitmap = bmp.asImageBitmap(), contentDescription = span.content, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(vertical = 12.dp))
                                                                    } else {
                                                                        Markdown(content = "```\n${span.content}\n```", modifier = Modifier.fillMaxWidth(), colors = customMarkdownColors, typography = customTypography, padding = customMarkdownPadding, components = customMarkdownComponents, imageTransformer = latexImageTransformer)
                                                                    }
                                                                } else {
                                                                    pendingSpans.add(span)
                                                                }
                                                            }
                                                            if (pendingSpans.isNotEmpty()) {
                                                                LatexAwareText(
                                                                    spans = pendingSpans.toList(),
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    textStyle = customTypography.paragraph.copy(color = textColor),
                                                                    latexTextSize = 56f,
                                                                    latexColor = textColor.toArgb(),
                                                                    codeSpanStyle = customTypography.inlineCode.toSpanStyle(),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (isStreaming) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .pointerInput(Unit) {
                                                    detectTapGestures(onLongPress = { })
                                                }
                                        )
                                    }
                                }
                            }
                        }
                        if (!isStreaming && message.status == MessageStatus.STOPPED) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.generation_stopped), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                        
                        if (message.participant == Participant.MODEL) {
                            AnimatedVisibility(
                                visible = !isStreaming,
                                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                    IconButton(onClick = { onRegenerate(message.id) }, enabled = !isLoading, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp), tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                    IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }

                                    if (totalBranches > 1) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clip(RoundedCornerShape(100))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .padding(horizontal = 4.dp)
                                        ) {
                                            IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                                            }
                                            Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                                            IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    // Segment detail bottom sheet (custom implementation)
    if (showSegmentDetail && selectedSegmentIndex >= 0) {
        val liveSegs = remember(message) { mergeAdjacentSegments(message.segments.orEmpty()) }
        val seg = liveSegs.getOrNull(selectedSegmentIndex)
        if (seg == null) {
            showSegmentDetail = false
        } else {
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        val PARTIAL = 0.45f
        val FULL = 0.92f

        // ── Finite state machine ──
        // Collapsed = 0, Half = PARTIAL, Full = FULL
        // Full is only entered when animateTo(FULL) completes naturally.
        val PHASE_COLLAPSED = 0; val PHASE_HALF = 1; val PHASE_FULL = 2
        var phase by remember { mutableIntStateOf(PHASE_HALF) }

        var rawFraction by remember { mutableFloatStateOf(0f) }
        val visualFraction = remember { Animatable(0f) }
        var snapJob by remember { mutableStateOf<Job?>(null) }

        val snapSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 350f, visibilityThreshold = 0.001f)

        // ── Snap target: midline (0.5) × velocity direction ──
        // velSign > 0 = upward (expanding), velSign < 0 = downward (collapsing)
        fun snapTarget(pos: Float, velSign: Float): Float {
            val goingUp = velSign >= 0f
            return when {
                pos > 0.5f && goingUp -> FULL      // upper half + up → full
                pos > 0.5f && !goingUp -> PARTIAL  // upper half + down → half
                pos <= 0.5f && goingUp -> PARTIAL  // lower half + up → half
                else -> 0f                          // lower half + down → collapsed
            }
        }

        // ── Single animation entry point. Sets phase after animation completes. ──
        fun animateTo(target: Float) {
            snapJob?.cancel()
            snapJob = coroutineScope.launch {
                visualFraction.animateTo(target, snapSpring)
                rawFraction = visualFraction.value
                phase = when (target) {
                    FULL -> PHASE_FULL
                    PARTIAL -> PHASE_HALF
                    else -> PHASE_COLLAPSED
                }
                if (target == 0f) showSegmentDetail = false
            }
        }

        fun dismiss() { animateTo(0f) }

        // ── Grab: interrupt animation, sync raw to current visual position ──
        fun grabSheet() {
            if (snapJob?.isActive == true) {
                snapJob?.cancel()
                rawFraction = visualFraction.value
            }
        }

        // ── Initial appearance ──
        LaunchedEffect(Unit) {
            animateTo(PARTIAL)
            snapJob?.join()
            rawFraction = PARTIAL
        }

        // ── Safety-net snap: if drag ends without fling (velocity ≈ 0) ──
        LaunchedEffect(rawFraction) {
            if (snapJob?.isActive == true) return@LaunchedEffect
            val pos = rawFraction
            delay(80)
            if (pos != rawFraction || snapJob?.isActive == true) return@LaunchedEffect
            val target = snapTarget(pos, 0f)
            if (abs(target - pos) > 0.01f) animateTo(target)
        }

        // ── Dim: per-frame poll of visualFraction → native Window.dimAmount ──
        val dialogWindowRef = remember { mutableStateOf<android.view.Window?>(null) }

        LaunchedEffect(dialogWindowRef.value) {
            val window = dialogWindowRef.value ?: return@LaunchedEffect
            while (isActive) {
                window.attributes = window.attributes.also {
                    it.dimAmount = (0.32f * visualFraction.value).coerceIn(0f, 1f)
                }
                withFrameNanos { }
            }
        }

        // ── NestedScrollConnection ──
        // Half: content does NOT scroll — all delta goes to sheet expansion.
        // Full: content scrolls normally. Exit Full ONLY when content at top
        //       and finger still dragging down (source == Drag).
        val sheetScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (phase != PHASE_FULL) {
                        grabSheet()
                        val delta = -available.y / screenHeightPx
                        rawFraction = (rawFraction + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero // Full: let content scroll
                }

                override fun onPostScroll(
                    consumed: Offset, available: Offset, source: NestedScrollSource
                ): Offset {
                    // Exit Full → Half: content at top + finger dragging down
                    if (phase == PHASE_FULL
                        && available.y > 0f
                        && scrollState.value == 0
                        && source == NestedScrollSource.UserInput
                    ) {
                        phase = PHASE_HALF
                        val delta = -available.y / screenHeightPx
                        rawFraction = (FULL + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (phase != PHASE_FULL && available.y != 0f) {
                        val velSign = if (available.y < 0f) 1f else -1f
                        animateTo(snapTarget(rawFraction, velSign))
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Dialog(
            onDismissRequest = { dismiss() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindowRef.value = dialogWindow }

            Box(modifier = Modifier.fillMaxSize()) {
                // Transparent click-catcher — dim is handled by native Window.dimAmount
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (visualFraction.value > 0.02f) {
                                Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { dismiss() }
                            } else Modifier
                        )
                )

                // Sheet
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height((configuration.screenHeightDp.dp * visualFraction.value).coerceAtLeast(0.dp)),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Draggable header: drag handle + title + divider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    var velEma = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            velEma = 0f
                                            grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            velEma = velEma * 0.5f + (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            rawFraction = (rawFraction - dragAmount / screenHeightPx)
                                                .coerceIn(0f, FULL)
                                            coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                                        },
                                        onDragEnd = {
                                            animateTo(snapTarget(rawFraction, velEma))
                                        }
                                    )
                                }
                        ) {
                            // Drag handle
                            Box(
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp).height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                )
                            }

                            // Fixed title
                            Text(
                                text = if (seg.type == "tool") toolDisplayName(seg.toolName)
                                    else stringResource(R.string.tool_thinking),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }

                        // Scrollable detail content
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(sheetScrollConnection)
                                .verticalScroll(scrollState)
                                .padding(horizontal = 24.dp)
                                .padding(top = 12.dp, bottom = 32.dp)
                        ) {
                            if (seg.type == "tool") {
                                val args = seg.toolArgs
                                if (!args.isNullOrBlank() && args != "{}") {
                                    Text(
                                        stringResource(R.string.arguments_label),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    JsonOrPlainView(args)
                                    Spacer(modifier = Modifier.height(16.dp))
                                }

                                Text(
                                    stringResource(R.string.result_label),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val result = seg.toolResult
                                if (result != null && result.isNotEmpty()) {
                                    JsonOrPlainView(result)
                                } else {
                                    Text(
                                        text = stringResource(R.string.tool_calling_ellipsis),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                SelectionContainer {
                                    Text(
                                        text = seg.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Renders Markdown with a double-buffer crossfade during streaming to mask
 * the flash from composable node destruction/recreation on AST re-parse.
 * On streaming end, stableText is updated to the final content in a hidden
 * layer while the live layer stays visible, then a brief crossfade swaps
 * them — both layers share the same content so height never jumps.
 */
@Composable
private fun RecomposeSafeMarkdown(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    render: @Composable (text: String) -> Unit
) {
    var buf0 by remember { mutableStateOf(content) }
    var buf1 by remember { mutableStateOf("") }
    var front by remember { mutableStateOf(0) }
    var fading by remember { mutableStateOf(false) }
    var fadeAlpha by remember { mutableFloatStateOf(0f) }
    var fadeKey by remember { mutableIntStateOf(0) }
    var wasStreaming by remember { mutableStateOf(false) }
    var waitingForFade by remember { mutableStateOf(false) }
    // State machine
    if (isStreaming) {
        waitingForFade = false
        val cur = if (front == 0) buf0 else buf1
        if (content != cur && !fading) {
            if (front == 0) buf1 = content else buf0 = content
            fadeKey++
            fading = true
            fadeAlpha = 0f
        }
    } else {
        if (wasStreaming) {
            waitingForFade = true
        }
        if (waitingForFade) {
            if (!fading) {
                if (front == 0) buf1 = content else buf0 = content
                waitingForFade = false
                fadeKey++
                fading = true
                fadeAlpha = 0f
            }
        }
        if (!waitingForFade && !fading) {
            if (front == 0) {
                if (buf0 != content) buf0 = content
            } else {
                if (buf1 != content) buf1 = content
            }
        }
    }
    wasStreaming = isStreaming

    // Fade animation — keyed by fadeKey so every fade gets a fresh LaunchedEffect
    LaunchedEffect(fadeKey) {
        if (!fading) return@LaunchedEffect
        withFrameNanos { }
        val startNs = withFrameNanos { it }
        val durationNs = 180_000_000L
        while (true) {
            val nowNs = withFrameNanos { it }
            val p = ((nowNs - startNs).toFloat() / durationNs).coerceAtMost(1f)
            fadeAlpha = p
            if (p >= 1f) break
        }
        front = 1 - front
        fading = false
        fadeAlpha = 0f
    }

    // Visibility / z-order: symmetric for both buffers
    val incoming = 1 - front
    val z0 = when { fading && incoming == 0 -> 2f; fading && front == 0 -> 0f; front == 0 -> 2f; else -> 0f }
    val a0 = when { fading && incoming == 0 -> fadeAlpha; fading && front == 0 -> 1f; front == 0 -> 1f; else -> 0f }
    val z1 = when { fading && incoming == 1 -> 2f; fading && front == 1 -> 0f; front == 1 -> 2f; else -> 0f }
    val a1 = when { fading && incoming == 1 -> fadeAlpha; fading && front == 1 -> 1f; front == 1 -> 1f; else -> 0f }

    Box(modifier = modifier) {
        if (buf0.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().zIndex(z0).alpha(a0)) { render(buf0) }
        }
        if (buf1.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().zIndex(z1).alpha(a1)) { render(buf1) }
        }
    }
}

private fun String.escapeForMarkdown(): String =
    replace("<think>", "<​think>").replace("</think>", "</​think>").escapeDollarForMarkdown()

