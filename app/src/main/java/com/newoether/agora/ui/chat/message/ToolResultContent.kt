package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Composable
internal fun ToolDetailContent(segment: MessageSegment) {
    val presentation = ToolPresentationResolver.resolve(segment)
    val args = presentation.rawArguments
    if (!args.isNullOrBlank() && args != "{}") {
        ToolSectionLabel(stringResource(R.string.arguments_label))
        Spacer(Modifier.height(5.dp))
        JsonOrPlainView(args)
        Spacer(Modifier.height(18.dp))
    }

    ToolSectionLabel(stringResource(R.string.result_label))
    Spacer(Modifier.height(6.dp))
    if (presentation.kind == ToolKind.SHELL_EXECUTE ||
        presentation.kind == ToolKind.SHELL_JOB_GET
    ) {
        ShellResult(presentation)
        return
    }
    when (presentation.state) {
        ToolPresentationState.CALLING -> ToolActiveContent(
            text = toolSummary(presentation),
            output = presentation.liveOutput,
        )
        ToolPresentationState.RUNNING,
        ToolPresentationState.BACKGROUND_RUNNING -> ToolActiveContent(
            text = toolSummary(presentation),
            output = presentation.liveOutput ?: resultOutput(presentation.result),
        )
        ToolPresentationState.FAILED -> {
            ToolErrorContent(
                presentation.errorMessage ?: stringResource(R.string.tool_call_failed),
            )
            if (!presentation.liveOutput.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                TerminalOutput(presentation.liveOutput)
            }
        }
        ToolPresentationState.STOPPED -> ToolMutedContent(
            stringResource(R.string.tool_execution_stopped),
        )
        ToolPresentationState.EMPTY,
        ToolPresentationState.SUCCEEDED -> ToolCompletedContent(presentation)
    }
}

@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text = text,
        style = ChatType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToolActiveContent(text: String, output: String?) {
    Text(
        text = text,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.primary,
    )
    if (!output.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        TerminalOutput(output)
    }
}

@Composable
private fun ToolErrorContent(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NoAutoScrollSelectionContainer {
            Text(
                text = message,
                style = ChatType.thoughtBody,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ToolMutedContent(message: String) {
    Text(
        text = message,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToolCompletedContent(
    presentation: ToolPresentation,
) {
    when (presentation.kind) {
        ToolKind.FILE_GLOB -> FileGlobResult(presentation)
        ToolKind.FILE_GREP -> FileGrepResult(presentation)
        ToolKind.FILE_READ -> FileReadResult(presentation)
        ToolKind.WEB_SEARCH -> WebSearchResult(presentation)
        else -> {
            val result = presentation.rawResult
            if (result.isNullOrEmpty()) {
                ToolMutedContent(toolSummary(presentation))
            } else {
                JsonOrPlainView(result)
            }
        }
    }
}

@Composable
private fun FileGlobResult(presentation: ToolPresentation) {
    val files = (presentation.result as? JsonObject)
        ?.get("files") as? JsonArray
    if (files.isNullOrEmpty()) {
        ToolMutedContent(stringResource(R.string.tool_found_no_files))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        files.forEachIndexed { index, value ->
            val path = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            IndexedCodeLine(index + 1, path)
        }
    }
}

private data class GrepUiMatch(
    val path: String,
    val line: Int?,
    val content: String,
)

@Composable
private fun FileGrepResult(presentation: ToolPresentation) {
    val matches = ((presentation.result as? JsonObject)?.get("matches") as? JsonArray)
        ?.mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            GrepUiMatch(
                path = item.string("path").orEmpty(),
                line = item.int("line"),
                content = item.string("content").orEmpty(),
            )
        }
        .orEmpty()
    if (matches.isEmpty()) {
        ToolMutedContent(stringResource(R.string.tool_found_no_matches))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        matches.groupBy { it.path }.forEach { (path, pathMatches) ->
            Text(
                text = path.ifBlank { stringResource(R.string.file_path_unknown) },
                style = ChatType.thoughtCodeLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pathMatches.forEach { match ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = match.line?.toString() ?: "–",
                                style = ChatType.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        NoAutoScrollSelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = match.content,
                                style = ChatType.thoughtCodeLarge,
                                fontFamily = MonoFamily,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellResult(
    presentation: ToolPresentation,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetaPill(
            text = shellStatusLabel(presentation),
            emphasized = true,
        )
        MetaPill(
            presentation.device
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.tool_unknown_device),
        )
    }
    if (presentation.state == ToolPresentationState.FAILED &&
        !presentation.errorMessage.isNullOrBlank()
    ) {
        Spacer(Modifier.height(8.dp))
        ToolErrorContent(presentation.errorMessage)
    }
    Spacer(Modifier.height(8.dp))
    TerminalOutput(
        shellOutputText(presentation)
            ?: stringResource(R.string.tool_no_output),
    )
}

@Composable
private fun shellStatusLabel(presentation: ToolPresentation): String {
    val resultState = (presentation.result as? JsonObject)
        .string("state")
        ?.replaceFirstChar { it.uppercaseChar() }
    return when (presentation.state) {
        ToolPresentationState.CALLING,
        ToolPresentationState.RUNNING -> stringResource(R.string.tool_state_executing)
        ToolPresentationState.BACKGROUND_RUNNING ->
            resultState ?: stringResource(R.string.tool_state_running)
        ToolPresentationState.FAILED -> presentation.exitCode?.let {
            stringResource(R.string.tool_exit_code, it)
        } ?: stringResource(R.string.tool_state_failed)
        ToolPresentationState.STOPPED -> stringResource(R.string.tool_state_stopped)
        ToolPresentationState.EMPTY,
        ToolPresentationState.SUCCEEDED -> presentation.exitCode?.let {
            stringResource(R.string.tool_exit_code, it)
        } ?: resultState ?: stringResource(R.string.tool_state_succeeded)
    }
}

internal fun shellOutputText(presentation: ToolPresentation): String? {
    val completedOutput = (presentation.result as? JsonObject)
        .string("output")
        ?.takeIf { it.isNotBlank() }
    if (completedOutput != null) return completedOutput

    return presentation.liveOutput
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { output ->
            output.startsWith("Connecting to ") ||
                output == "Starting durable background job"
        }
}

@Composable
private fun FileReadResult(presentation: ToolPresentation) {
    val result = presentation.result as? JsonObject
    val path = result.string("path") ?: presentation.subject
    val lines = result.int("lines")
    if (path != null || lines != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            path?.let { MetaPill(it) }
            lines?.let { MetaPill(stringResource(R.string.tool_line_count, it)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    val content = result.string("content").orEmpty()
    if (content.isEmpty()) {
        ToolMutedContent(
            if (path == null) {
                stringResource(R.string.tool_read_file_empty_default)
            } else {
                stringResource(R.string.tool_read_file_empty, path)
            },
        )
    } else {
        TerminalOutput(content)
    }
}

@Composable
private fun WebSearchResult(
    presentation: ToolPresentation,
) {
    val results = ((presentation.result as? JsonObject)?.get("results") as? JsonArray)
        .orEmpty()
    if (results.isEmpty()) {
        ToolMutedContent(toolSummary(presentation))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        results.forEachIndexed { index, value ->
            val item = value as? JsonObject
            val title = item.string("title") ?: stringResource(R.string.tool_web_result, index + 1)
            val url = item.string("url") ?: item.string("href")
            val snippet = item.string("snippet")
                ?: item.string("description")
                ?: item.string("content")
                ?: item.string("body")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(10.dp),
            ) {
                Text(
                    text = title,
                    style = ChatType.meta,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!url.isNullOrBlank()) {
                    Text(
                        text = url,
                        style = ChatType.metaNormal,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!snippet.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = snippet,
                        style = ChatType.thoughtBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndexedCodeLine(index: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = index.toString(),
            style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.width(28.dp),
        )
        NoAutoScrollSelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = ChatType.thoughtCodeLarge,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TerminalOutput(output: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NoAutoScrollSelectionContainer {
            Text(
                text = output,
                style = ChatType.thoughtCodeLarge,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun MetaPill(
    text: String,
    emphasized: Boolean = false,
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
    ) {
        Text(
            text = text,
            style = ChatType.meta,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

private fun resultOutput(result: JsonElement?): String? =
    (result as? JsonObject).string("output")

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull
