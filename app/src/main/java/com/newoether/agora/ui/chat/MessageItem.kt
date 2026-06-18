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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
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
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
    val merged = mutableListOf<MessageSegment>()
    for (seg in segs) {
        val last = merged.lastOrNull()
        // Only continuous reasoning (thought) is merged into one flowing block.
        // Transcriptions stay separate: each describes a distinct image, so a
        // 1:1 image↔block correspondence must be preserved.
        if (last != null && last.type == seg.type && seg.type == "thought") {
            merged[merged.lastIndex] = last.copy(content = last.content + seg.content)
        } else {
            merged.add(seg)
        }
    }
    return merged
}

// Label a transcription segment; numbers them ("Image Transcription 1/2/…") only
// when more than one is present, so a single image keeps the clean unnumbered name.
private fun transcriptionLabel(segs: List<MessageSegment>, index: Int): String {
    val total = segs.count { it.type == "transcription" }
    if (total <= 1) return "Image Transcription"
    val ordinal = segs.take(index + 1).count { it.type == "transcription" }
    return "Image Transcription $ordinal"
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
    private fun JsonOrPlainView(text: String) {
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
        "list_conversations" -> stringResource(R.string.tool_list_conversations)
        "read_conversation" -> stringResource(R.string.tool_read_conversation)
        "file_read" -> stringResource(R.string.tool_file_read)
        "file_write" -> stringResource(R.string.tool_file_write)
        "file_edit" -> stringResource(R.string.tool_file_edit)
        "file_glob" -> stringResource(R.string.tool_file_glob)
        "file_grep" -> stringResource(R.string.tool_file_grep)
        "generate_image" -> stringResource(R.string.tool_generate_image)
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
            isError -> stringResource(R.string.tool_read_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_reading_memory, fileName)
            nameCount != null && nameCount > 1 -> stringResource(R.string.tool_read_memory_count, nameCount)
            fileName != null -> stringResource(R.string.tool_read_memory_name, fileName)
            else -> stringResource(R.string.tool_read_memory_success)
        }
        "create_memory_file" -> when {
            isError -> stringResource(R.string.tool_save_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_saving_memory, fileName)
            fileName != null -> stringResource(R.string.tool_save_memory_name, fileName)
            else -> stringResource(R.string.tool_save_memory_default)
        }
        "edit_memory_file" -> when {
            isError -> stringResource(R.string.tool_edit_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_updating_memory, fileName)
            fileName != null -> stringResource(R.string.tool_edit_memory_name, fileName)
            else -> stringResource(R.string.tool_edit_memory_default)
        }
        "delete_memory_file" -> when {
            isError -> stringResource(R.string.tool_delete_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_removing_memory, fileName)
            fileName != null -> stringResource(R.string.tool_delete_memory_name, fileName)
            else -> stringResource(R.string.tool_delete_memory_default)
        }
        "list_memory_files" -> {
            if (isError) stringResource(R.string.tool_lookup_failed)
            else if (content.isBlank()) stringResource(R.string.tool_looking_up_memories)
            else {
                val fileCount = try {
                    Json.parseToJsonElement(content).jsonObject["files"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (fileCount > 0) stringResource(R.string.tool_lookup_count, fileCount)
                else stringResource(R.string.tool_lookup_default)
            }
        }
        "update_active_memory" -> {
            if (isError) stringResource(R.string.tool_update_active_failed)
            else if (content.isBlank()) stringResource(R.string.tool_updating_active)
            else stringResource(R.string.tool_update_active_default)
        }
        "web_search" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            if (isError) {
                if (query != null) stringResource(R.string.tool_web_search_error, query)
                else stringResource(R.string.tool_search_failed)
            } else if (content.isBlank()) {
                if (query != null) stringResource(R.string.tool_searching_web, query)
                else stringResource(R.string.tool_web_search_done_default)
            } else {
                val resultCount = try {
                    Json.parseToJsonElement(content).jsonObject["results"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (resultCount > 0 && query != null) stringResource(R.string.tool_web_search_done, resultCount, query)
                else if (query != null) stringResource(R.string.tool_web_search_no_result, query)
                else stringResource(R.string.tool_web_search_done_default)
            }
        }
        "web_fetch" -> {
            val url = argsJson?.get("url")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_web_fetch_failed)
            else if (content.isEmpty()) stringResource(R.string.tool_web_fetching, url?.take(60)?.ifEmpty { "page" } ?: "web page")
            else if (url != null) stringResource(R.string.tool_web_fetch_done, url.take(60).ifEmpty { "page" })
            else stringResource(R.string.tool_web_fetch_default)
        }
        "search_conversations" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            if (isError) {
                if (query != null) stringResource(R.string.tool_conversation_search_error, query)
                else stringResource(R.string.tool_search_failed)
            } else if (content.isBlank()) {
                if (query != null) stringResource(R.string.tool_searching_for, query)
                else stringResource(R.string.tool_searching_conversations_default)
            } else {
                val convCount = try {
                    Json.parseToJsonElement(content).jsonObject["results"]?.jsonArray?.size ?: 0
                } catch (_: Exception) { 0 }
                if (convCount > 0 && query != null) stringResource(R.string.tool_conversation_search_done_for, convCount, query)
                else if (query != null) stringResource(R.string.tool_conversation_search_no_result, query)
                else stringResource(R.string.tool_searching_conversations_default)
            }
        }
        "list_shells" -> {
            if (isError) stringResource(R.string.tool_shell_listing)
            else if (content.isBlank()) stringResource(R.string.tool_listing_shells)
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
            else if (content.isBlank()) {
                if (command != null) stringResource(R.string.tool_executing_shell, command.take(80))
                else stringResource(R.string.tool_listing_shells)
            } else {
                if (command != null) stringResource(R.string.tool_executed_shell, command.take(80), content.length)
                else stringResource(R.string.tool_executed_no_output, command?.take(80) ?: "shell")
            }
        }
        "list_conversations" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_listing_conversations)
            else {
                val total = try {
                    (Json.parseToJsonElement(content).jsonObject["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                } catch (_: Exception) { 0 }
                if (total > 0) stringResource(R.string.tool_listed_conversations, total)
                else stringResource(R.string.tool_listed_no_conversations)
            }
        }
        "read_conversation" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_reading_conversation)
            else {
                val title = try {
                    (Json.parseToJsonElement(content).jsonObject["title"] as? JsonPrimitive)?.content
                } catch (_: Exception) { null }
                if (title != null) stringResource(R.string.tool_read_conversation_done, title.take(60))
                else stringResource(R.string.tool_read_conversation)
            }
        }
        "file_read" -> {
            val path = argsJson?.get("path")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_read_file_failed)
            else if (content.isBlank()) stringResource(R.string.tool_reading_file, path?.take(60) ?: "file")
            else stringResource(R.string.tool_read_file_done, path?.take(60) ?: "file")
        }
        "file_write" -> {
            val path = argsJson?.get("path")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_writing_file, path?.take(60) ?: "file")
            else stringResource(R.string.tool_wrote_file, path?.take(60) ?: "file")
        }
        "file_edit" -> {
            val path = argsJson?.get("path")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_editing_file, path?.take(60) ?: "file")
            else stringResource(R.string.tool_edited_file, path?.take(60) ?: "file")
        }
        "file_glob" -> {
            val pattern = argsJson?.get("pattern")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_finding_files, pattern?.take(60) ?: "files")
            else {
                val count = content.lines().filter { it.isNotBlank() }.size
                if (count > 0) stringResource(R.string.tool_found_files, count)
                else stringResource(R.string.tool_found_no_files)
            }
        }
        "file_grep" -> {
            val pattern = argsJson?.get("pattern")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_searching_file, pattern?.take(60) ?: "file")
            else {
                val matches = content.lines().filter { it.isNotBlank() }.size
                if (matches > 0) stringResource(R.string.tool_searched_file, matches)
                else stringResource(R.string.tool_found_no_files)
            }
        }
        "generate_image" -> when {
            isError -> stringResource(R.string.tool_call_failed)
            content.isEmpty() -> stringResource(R.string.tool_generating_image)
            else -> stringResource(R.string.tool_generated_image)
        }
        else -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else stringResource(R.string.tool_done)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { isFirstComposition = false }

    val isThoughtExpanded by remember(message.id) {
        derivedStateOf { thoughtExpandedStates[message.id] ?: false }
    }
    var showSegmentDetail by remember { mutableStateOf(false) }
    var selectedSegmentIndex by remember { mutableIntStateOf(-1) }
    var currentThoughtBlockHeight by remember { mutableIntStateOf(0) }
    var stableCollapsedThoughtHeight by remember { mutableIntStateOf(0) }
    // Capture the fully-settled collapsed height after collapse animation finishes.
    // This lets calculateReportedHeight immediately report the post-collapse height
    // even mid-animation, so extraPadding doesn't "chase" the shrinking thought block.
    LaunchedEffect(isThoughtExpanded) {
        if (!isThoughtExpanded) {
            delay(500) // slightly longer than the 400ms collapse tween + mergedBottomPadding tween
            stableCollapsedThoughtHeight = currentThoughtBlockHeight
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current

    if (showInfoDialog) {
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val dateString = sdf.format(Date(message.timestamp))
        val modelDisplay = if (message.modelName != null) {
            val parsed = message.modelName?.let { com.newoether.agora.model.ModelId.parse(it) }
            val modelId = parsed?.modelName?.removePrefix("models/") ?: message.modelName
            val provider = parsed?.providerName ?: "Unknown"
            modelAliases[message.modelName] ?: ("$modelId ($provider)")
        } else stringResource(R.string.unknown)

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.message_info), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.time_with_label, dateString), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    if (message.participant == Participant.MODEL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.model_with_label, modelDisplay), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(stringResource(R.string.provider_close)) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_message_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.delete_message_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        haptics.reject()
                        onDelete(message.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
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

    LaunchedEffect(message.text, message.status, isEditing, isThoughtExpanded) {
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
    // Chat-specific markdown scale — optimized for immersive reading.
    // Outfit's large x-height means 15sp reads like ~16sp Roboto.
    // Heading steps of 3sp (h1→h2→h3) and 2sp (h3→h4) create
    // a visible but not jarring hierarchy during long-form reading.
    val customTypography = markdownTypography(
        text = ChatType.body,
        paragraph = ChatType.body,
        ordered = ChatType.body,
        bullet = ChatType.body,
        list = ChatType.body,
        h1 = ChatType.mdH1,
        h2 = ChatType.mdH2,
        h3 = ChatType.mdH3,
        h4 = ChatType.mdH4,
        h5 = ChatType.mdH5,
        h6 = ChatType.mdH6,
        code = ChatType.code,
        inlineCode = ChatType.code,
        table = ChatType.body,
    )

    // Compact typography for thought blocks — subordinate to main chat body.
    // One tier below main markdown: body at 13sp (vs 15sp), headings similarly
    // stepped down. Readable for paragraph-level content but clearly secondary.
    val thoughtTypography = markdownTypography(
        text = ChatType.thoughtBody,
        paragraph = ChatType.thoughtBody,
        ordered = ChatType.thoughtBody,
        bullet = ChatType.thoughtBody,
        list = ChatType.thoughtBody,
        h1 = ChatType.thH1,
        h2 = ChatType.thH2,
        h3 = ChatType.thH3,
        h4 = ChatType.thH4,
        h5 = ChatType.thH5,
        h6 = ChatType.thH6,
        code = ChatType.thoughtCode,
        inlineCode = ChatType.thoughtCode,
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
    val customMarkdownPadding = markdownPadding(block = 8.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 5.dp)

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
    val markdownFlavour = remember { GFMFlavourDescriptor() }

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
                                TextButton(onClick = { onEdit(message.id, editState.text.toString()) }, enabled = !isLoading) { Text(stringResource(R.string.send)) }
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

                                // Collect all image/video URLs for the pager
                                val allMediaUrls = remember(displayItems) {
                                    displayItems.mapNotNull { (_, imagePath, metaItem) ->
                                        val t = resolveAttachmentType(imagePath, metaItem, ctx)
                                        when (t) {
                                            "image" -> if (imagePath.isNotEmpty()) imagePath else null
                                            "video" -> metaItem?.originalUri
                                            else -> null
                                        }
                                    }
                                }

                                androidx.compose.foundation.lazy.LazyRow(
                                    modifier = Modifier.padding(bottom = if (message.text.isNotEmpty()) 8.dp else 0.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(displayItems) { itemIdx, (index, imagePath, metaItem) ->
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

                                        val mediaIndex = allMediaUrls.indexOf(
                                            when (type) {
                                                "video" -> metaItem?.originalUri
                                                else -> imagePath
                                            }
                                        ).coerceAtLeast(0)

                                        AttachmentThumbnailItem(
                                            type = type,
                                            imagePath = imagePath,
                                            fileName = fileName,
                                            originalUri = metaItem?.originalUri,
                                            textContent = metaItem?.textContent,
                                            pdfPages = pdfPages,
                                            allMediaUrls = allMediaUrls,
                                            mediaIndex = mediaIndex,
                                            handlers = ThumbnailClickHandlers(
                                                onMediaClick = onMediaClick,
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
                                        style = ChatType.userBody,
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
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)); haptics.success() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { onStartEdit() }, enabled = isEditingAllowed, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(16.dp), tint = LocalContentColor.current.copy(alpha = if (isEditingAllowed) 0.6f else 0.3f))
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            DropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                                shape = RoundedCornerShape(12.dp),
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.info)) },
                                    onClick = { showMenu = false; showInfoDialog = true },
                                    leadingIcon = { Icon(Icons.Default.Info, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                    onClick = { showMenu = false; showDeleteConfirm = true },
                                    enabled = !isLoading,
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) }
                                )
                            }
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
                        val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                        val hasText = message.text.isNotEmpty()
                        LaunchedEffect(thinkingNow, hasText, message.status) {
                            heldLabel = when {
                                thinkingNow -> "thinking"
                                isToolCalling -> "calling"
                                isTranscribing -> "transcribing"
                                hasText -> "answering"
                                message.status == MessageStatus.SUCCESS || message.status == MessageStatus.ERROR || message.status == MessageStatus.STOPPED -> ""
                                message.status == MessageStatus.SENDING -> ""
                                else -> heldLabel
                            }
                        }
                        val toolCallingStatus = stringResource(R.string.tool_calling_ellipsis)
                        val transcribingStatus = stringResource(R.string.transcription_ellipsis)
                        val statusText = when {
                            message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) stringResource(R.string.cost_tokens, message.tokenCount) else null
                            isStreaming && isTranscribing -> transcribingStatus
                            isStreaming && isToolCalling -> toolCallingStatus
                            isStreaming && thinkingNow -> thinkingStatus
                            isStreaming && hasText -> answeringStatus
                            isStreaming -> when (heldLabel) {
                                "thinking" -> thinkingStatus
                                "calling" -> toolCallingStatus
                                "transcribing" -> transcribingStatus
                                "answering" -> answeringStatus
                                else -> stringResource(R.string.sending_ellipsis)
                            }
                            else -> null
                        }.let { base ->
                            if (base != null && message.retryText != null) "$base (${message.retryText})"
                            else base
                        }
                        // Hold the last non-null label so the status bar doesn't collapse
                        // during the timing gap between isStreaming→false and the DB
                        // emitting the updated message status.
                        val displayText = when {
                            statusText != null -> statusText.also { heldStatusText = it }
                            message.status == MessageStatus.SENDING || message.status == MessageStatus.THINKING || message.status == MessageStatus.TOOL_CALLING || message.status == MessageStatus.TRANSCRIBING -> heldStatusText.takeIf { it.isNotEmpty() }
                            else -> null.also { heldStatusText = "" }
                        }

                        AnimatedVisibility(
                            visible = displayText != null,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            val text = displayText ?: return@AnimatedVisibility
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                    if (isStreaming) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
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
                                        Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (message.status == MessageStatus.SUCCESS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text, style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    var debouncedText by remember(isStreaming) { mutableStateOf(if (isStreaming) "" else message.text) }
                    if (!isStreaming) {
                        debouncedText = message.text
                    } else {
                        var lastUpdateMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        var flushJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        LaunchedEffect(message.text) {
                            if (message.text.isEmpty()) return@LaunchedEffect
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
                            val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                            val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
                            val thoughtMs = message.thoughtTimeMs
                            val hasThought = thoughtMs != null && thoughtMs > 0
                            val collapsedTitle = when {
                                isThinking -> message.thoughtTitle ?: stringResource(R.string.thinking_ellipsis)
                                isTranscribing -> message.thoughtTitle ?: stringResource(R.string.transcription_ellipsis)
                                isToolCalling || isToolInProgress -> toolDisplayName(lastSeg.toolName)
                                else -> {
                                    if (hasThought) {
                                        val s = (thoughtMs / 1000).toInt()
                                        if (toolCount > 0) {
                                            if (s >= 60) stringResource(R.string.thought_for_minutes_called_tools, s / 60, s % 60, toolCount)
                                            else stringResource(R.string.thought_for_seconds_called_tools, s, toolCount)
                                        } else {
                                            if (s >= 60) stringResource(R.string.thought_for_minutes, s / 60, s % 60)
                                            else stringResource(R.string.thought_for_seconds, s)
                                        }
                                    } else if (toolCount > 0) {
                                        stringResource(R.string.called_n_tools, toolCount)
                                    } else if (message.thoughtTitle != null) {
                                        message.thoughtTitle
                                    } else if (segs.any { it.type == "transcription" }) {
                                        "Image Transcription"
                                    } else {
                                        stringResource(R.string.thinking_complete)
                                    }
                                }
                            }
                            val mergedBottomPadding by animateDpAsState(
                                targetValue = if (isThoughtExpanded) 12.dp else 4.dp,
                                animationSpec = tween(500), label = "mergedPad"
                            )
                            Surface(
                                tonalElevation = 2.dp,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = mergedBottomPadding + 6.dp)
                                    .noOpBringIntoView()
                                    .onSizeChanged { currentThoughtBlockHeight = it.height }
                            ) {
                                Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { thoughtExpandedStates[message.id] = !isThoughtExpanded }
                                        .padding(10.dp)
                                ) {
                                    if (isToolCalling || isToolInProgress) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else if (!isThinking && !hasThought && toolCount > 0) {
                                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else if (isTranscribing || collapsedTitle == "Image Transcription") {
                                        Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    } else {
                                        Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        collapsedTitle, style = ChatType.thoughtTitle,
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
                                    enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                                    exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
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
                                            if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(18.dp))
                                                        .clickable { selectedSegmentIndex = idx; showSegmentDetail = true }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Text(
                                                        if (seg.type == "transcription") transcriptionLabel(segs, idx) else stringResource(R.string.tool_thinking),
                                                        style = ChatType.meta,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    if (seg.content.isNotBlank()) {
                                                        val flat = seg.content.replace('\n', ' ')
                                                        val preview = if (isStreaming && idx == segs.lastIndex) {
                                                            if (flat.length > 60) "…${flat.takeLast(60)}" else flat
                                                        } else flat
                                                        Text(
                                                            text = preview,
                                                            style = ChatType.metaNormal,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    } else {
                                                        Text(
                                                            text = "Image transcription is empty.",
                                                            style = ChatType.metaNormal,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        )
                                                    }
                                                }
                                            } else if (seg.type == "tool") {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(18.dp))
                                                        .clickable { selectedSegmentIndex = idx; showSegmentDetail = true }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                                ) {
                                                    Text(
                                                        toolDisplayName(seg.toolName),
                                                        style = ChatType.meta,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = toolSummary(seg),
                                                        style = ChatType.metaNormal,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                                                style = ChatType.errorBody,
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
                                                val markdownParser = remember(text) { MarkdownParser(markdownFlavour) }
                                                val referenceLinkHandler = remember(text) { ReferenceLinkHandlerImpl() }
                                                Markdown(
                                                    content = text.escapeForMarkdown(),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = customMarkdownColors,
                                                    typography = customTypography,
                                                    padding = customMarkdownPadding,
                                                    components = customMarkdownComponents,
                                                    imageTransformer = latexImageTransformer,
                                                    flavour = markdownFlavour,
                                                    parser = markdownParser,
                                                    referenceLinkHandler = referenceLinkHandler,
                                                    animations = markdownAnimations { this }
                                                )
                                            } else {
                                                // Convert LaTeX spans to image markdown so Markdown composable
                                                // can render tables, lists, etc. with formulas as inline images.
                                                val reassembled = remember(spans) {
                                                    spans.joinToString("") { span ->
                                                        if (span.isLatex) latexToMarkdown(span.content, span.display)
                                                        else span.content
                                                    }
                                                }
                                                val markdownParser = remember(reassembled) { MarkdownParser(markdownFlavour) }
                                                val referenceLinkHandler = remember(reassembled) { ReferenceLinkHandlerImpl() }
                                                Markdown(
                                                    content = reassembled.escapeForMarkdown(),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = customMarkdownColors,
                                                    typography = customTypography,
                                                    padding = customMarkdownPadding,
                                                    components = customMarkdownComponents,
                                                    imageTransformer = latexImageTransformer,
                                                    flavour = markdownFlavour,
                                                    parser = markdownParser,
                                                    referenceLinkHandler = referenceLinkHandler,
                                                    animations = markdownAnimations { this }
                                                )
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
                        if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                            val genImages = message.images
                            // Generated images are primary output, not input references:
                            // render as a full-width square card, image cropped to fill
                            // with rounded corners, tap to view fullscreen.
                            Column(
                                modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                genImages.forEachIndexed { idx, path ->
                                    coil.compose.AsyncImage(
                                        model = path,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = { onMediaClick(genImages, idx) },
                                                onLongClick = { haptics.longPress() }
                                            )
                                    )
                                }
                            }
                        }
                        if (!isStreaming && message.status == MessageStatus.STOPPED) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.generation_stopped), style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)); haptics.success() }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                    IconButton(onClick = { onRegenerate(message.id) }, enabled = !isLoading, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(19.dp), tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                        DropdownMenu(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            shape = RoundedCornerShape(12.dp),
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.info)) },
                                                onClick = { showMenu = false; showInfoDialog = true },
                                                leadingIcon = { Icon(Icons.Default.Info, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.delete), color = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                                onClick = { showMenu = false; showDeleteConfirm = true },
                                                enabled = !isLoading,
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) }
                                            )
                                        }
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
                        
                        Spacer(modifier = Modifier.height(16.dp))
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
        val FULL = 0.94f

        // ── Finite state machine ──
        // Collapsed = 0, Half = PARTIAL, Full = FULL
        // Full is only entered when animateTo(FULL) completes naturally.
        val PHASE_COLLAPSED = 0; val PHASE_HALF = 1; val PHASE_FULL = 2
        var phase by remember { mutableIntStateOf(PHASE_HALF) }

        var rawFraction by remember { mutableFloatStateOf(0f) }
        val visualFraction = remember { Animatable(0f) }
        var snapJob by remember { mutableStateOf<Job?>(null) }
        var dismissing by remember { mutableStateOf(false) }

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

        fun dismiss() { dismissing = true; animateTo(0f) }

        // ── Grab: interrupt animation, sync raw to current visual position ──
        fun grabSheet() {
            if (dismissing) return
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
            if (dismissing || snapJob?.isActive == true) return@LaunchedEffect
            val pos = rawFraction
            delay(80)
            if (dismissing || pos != rawFraction || snapJob?.isActive == true) return@LaunchedEffect
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
                    if (!dismissing && phase != PHASE_FULL) {
                        grabSheet()
                        val delta = -available.y / screenHeightPx
                        rawFraction = (rawFraction + delta).coerceIn(0f, FULL)
                        coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                        if (rawFraction >= FULL && available.y < 0f) phase = PHASE_FULL
                        return available.copy(x = 0f)
                    }
                    return Offset.Zero // Full: let content scroll
                }

                override fun onPostScroll(
                    consumed: Offset, available: Offset, source: NestedScrollSource
                ): Offset {
                    if (dismissing) return Offset.Zero
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
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindowRef.value = dialogWindow }

            Box(modifier = Modifier.fillMaxSize()) {
                // Transparent click-catcher — dim is handled by native Window.dimAmount.
                // Uses pointerInput to avoid reading visualFraction in composition.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (visualFraction.value > 0.02f) dismiss()
                                }
                            )
                        }
                )

                // Sheet height via Modifier.layout (layout phase) to avoid
                // recomposition on every spring animation frame.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val h = (screenHeightPx * visualFraction.value).roundToInt().coerceAtLeast(0)
                            val placeable = measurable.measure(
                                constraints.copy(minHeight = h, maxHeight = h)
                            )
                            layout(placeable.width, h) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
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
                                            if (dismissing) return@detectVerticalDragGestures
                                            velEma = 0f
                                            grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dismissing) return@detectVerticalDragGestures
                                            change.consume()
                                            velEma = velEma * 0.5f + (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            rawFraction = (rawFraction - dragAmount / screenHeightPx)
                                                .coerceIn(0f, FULL)
                                            coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                                            if (rawFraction >= FULL && dragAmount < 0f) phase = PHASE_FULL
                                        },
                                        onDragEnd = {
                                            if (dismissing) return@detectVerticalDragGestures
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
                                    else if (seg.type == "transcription") transcriptionLabel(liveSegs, selectedSegmentIndex)
                                    else stringResource(R.string.tool_thinking),
                                style = ChatType.detailTitle,
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
                                .noOpBringIntoView()
                                .padding(horizontal = 24.dp)
                                // Markdown content (thinking, transcription) hugs its title with a
                                // unified 4dp; tool detail leads with an Arguments/Result label, so it
                                // gets a slightly larger 6dp.
                                .padding(top = if (seg.type == "tool") 6.dp else 4.dp)
                                .navigationBarsPadding()
                                .padding(bottom = 32.dp)
                        ) {
                            if (seg.type == "tool") {
                                val args = seg.toolArgs
                                if (!args.isNullOrBlank() && args != "{}") {
                                    Text(
                                        stringResource(R.string.arguments_label),
                                        style = ChatType.meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    JsonOrPlainView(args)
                                    Spacer(modifier = Modifier.height(16.dp))
                                }

                                Text(
                                    stringResource(R.string.result_label),
                                    style = ChatType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val result = seg.toolResult
                                if (result != null && result.isNotEmpty()) {
                                    JsonOrPlainView(result)
                                } else {
                                    Text(
                                        text = stringResource(R.string.tool_calling_ellipsis),
                                        style = ChatType.meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (seg.type == "transcription" && seg.content.isBlank()) {
                                Text(
                                    text = "Image transcription is empty.",
                                    style = ChatType.body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            } else {
                                var debouncedThoughtContent by remember { mutableStateOf(seg.content) }
                                if (!isStreaming) {
                                    debouncedThoughtContent = seg.content
                                } else {
                                    var lastUpdateMs by remember { mutableLongStateOf(0L) }
                                    var flushJob by remember { mutableStateOf<Job?>(null) }
                                    LaunchedEffect(seg.content) {
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastUpdateMs
                                        if (elapsed >= 500) {
                                            flushJob?.cancel()
                                            debouncedThoughtContent = seg.content
                                            lastUpdateMs = now
                                        } else {
                                            flushJob?.cancel()
                                            flushJob = launch {
                                                delay(500 - elapsed)
                                                debouncedThoughtContent = seg.content
                                                lastUpdateMs = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                }
                                if (!isStreaming) {
                                    SelectionContainer {
                                        RecomposeSafeMarkdown(
                                            content = debouncedThoughtContent,
                                            isStreaming = isStreaming
                                        ) { text ->
                                            val markdownParser = remember(text) { MarkdownParser(markdownFlavour) }
                                            val referenceLinkHandler = remember(text) { ReferenceLinkHandlerImpl() }
                                            Markdown(
                                                content = text.escapeForMarkdown(),
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = customMarkdownColors,
                                                typography = thoughtTypography,
                                                padding = thoughtMarkdownPadding,
                                                components = customMarkdownComponents,
                                                flavour = markdownFlavour,
                                                parser = markdownParser,
                                                referenceLinkHandler = referenceLinkHandler,
                                                animations = markdownAnimations { this }
                                            )
                                        }
                                    }
                                } else {
                                    RecomposeSafeMarkdown(
                                        content = debouncedThoughtContent,
                                        isStreaming = isStreaming
                                    ) { text ->
                                        val markdownParser = remember(text) { MarkdownParser(markdownFlavour) }
                                        val referenceLinkHandler = remember(text) { ReferenceLinkHandlerImpl() }
                                        Markdown(
                                            content = text.escapeForMarkdown(),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = customMarkdownColors,
                                            typography = thoughtTypography,
                                            padding = thoughtMarkdownPadding,
                                            components = customMarkdownComponents,
                                            flavour = markdownFlavour,
                                            parser = markdownParser,
                                            referenceLinkHandler = referenceLinkHandler,
                                            animations = markdownAnimations { this }
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
    }
}

/**
 * Renders Markdown with a double-buffer crossfade during streaming to mask
 * the flash from composable node destruction/recreation on AST re-parse.
 * On streaming end, stableText is updated to the final content in a hidden
 * layer while the live layer stays visible, then a brief crossfade swaps
 * them — both layers share the same content so height never jumps.
 */
private fun String.escapeForMarkdown(): String =
    replace("<think>", "<​think>").replace("</think>", "</​think>").escapeDollarForMarkdown()
