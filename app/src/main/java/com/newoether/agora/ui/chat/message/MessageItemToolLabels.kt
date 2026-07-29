package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment

/**
 * The only localization layer for tool cards. Parsing and lifecycle inference live in
 * [ToolPresentationResolver]; compact, timeline and detail surfaces all call these functions.
 */
@Composable
internal fun toolDisplayName(segment: MessageSegment): String {
    val presentation = ToolPresentationResolver.resolve(segment)
    val state = when (presentation.state) {
        ToolPresentationState.CALLING,
        ToolPresentationState.RUNNING -> stringResource(R.string.tool_state_calling)
        ToolPresentationState.BACKGROUND_RUNNING -> stringResource(R.string.tool_state_running)
        ToolPresentationState.SUCCEEDED,
        ToolPresentationState.EMPTY -> stringResource(R.string.tool_state_called)
        ToolPresentationState.FAILED -> stringResource(R.string.tool_state_failed)
        ToolPresentationState.STOPPED -> stringResource(R.string.tool_state_stopped)
    }
    return stringResource(
        R.string.tool_state_title,
        state,
        toolBaseDisplayName(presentation),
    )
}

@Composable
private fun toolBaseDisplayName(presentation: ToolPresentation): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> stringResource(R.string.tool_look_up_memories)
    ToolKind.MEMORY_READ -> stringResource(R.string.tool_read_memory)
    ToolKind.MEMORY_CREATE -> stringResource(R.string.tool_add_memory)
    ToolKind.MEMORY_EDIT -> stringResource(R.string.tool_edit_memory)
    ToolKind.MEMORY_DELETE -> stringResource(R.string.tool_delete_memory)
    ToolKind.MEMORY_UPDATE_ACTIVE -> stringResource(R.string.tool_update_active_memory)
    ToolKind.WEB_SEARCH -> stringResource(R.string.tool_web_search)
    ToolKind.WEB_FETCH -> stringResource(R.string.tool_web_fetch)
    ToolKind.CONVERSATION_SEARCH -> stringResource(R.string.tool_search_conversations)
    ToolKind.CONVERSATION_LIST -> stringResource(R.string.tool_list_conversations)
    ToolKind.CONVERSATION_READ -> stringResource(R.string.tool_read_conversation)
    ToolKind.SHELL_LIST -> stringResource(R.string.tool_list_shells)
    ToolKind.SHELL_EXECUTE -> stringResource(R.string.tool_execute_shell)
    ToolKind.SHELL_JOB_LIST -> stringResource(R.string.tool_shell_jobs)
    ToolKind.SHELL_JOB_GET -> stringResource(R.string.tool_shell_job)
    ToolKind.SHELL_JOB_STOP -> stringResource(R.string.tool_stop_shell_job)
    ToolKind.FILE_READ -> stringResource(R.string.tool_file_read)
    ToolKind.FILE_WRITE -> stringResource(R.string.tool_file_write)
    ToolKind.FILE_EDIT -> stringResource(R.string.tool_file_edit)
    ToolKind.FILE_GLOB -> stringResource(R.string.tool_file_glob)
    ToolKind.FILE_GREP -> stringResource(R.string.tool_file_grep)
    ToolKind.IMAGE_GENERATE -> stringResource(R.string.tool_generate_image)
    ToolKind.TASK_CREATE -> stringResource(R.string.tool_create_task)
    ToolKind.TASK_LIST -> stringResource(R.string.tool_list_tasks)
    ToolKind.TASK_DELETE -> stringResource(R.string.tool_delete_task)
    ToolKind.LOOP_START -> stringResource(R.string.tool_start_loop)
    ToolKind.LOOP_STOP -> stringResource(R.string.tool_stop_loop)
    ToolKind.UNKNOWN -> presentation.toolName
        .ifBlank { stringResource(R.string.tool_context) }
        .split("_")
        .joinToString(" ") { word ->
            word.replaceFirstChar { char -> char.uppercaseChar() }
        }
}

@Composable
internal fun toolSummary(segment: MessageSegment): String {
    val presentation = ToolPresentationResolver.resolve(segment)
    val subject = presentation.subject
    return when (presentation.state) {
        ToolPresentationState.FAILED ->
            presentation.errorMessage?.take(160) ?: stringResource(R.string.tool_call_failed)
        ToolPresentationState.STOPPED -> stringResource(R.string.tool_execution_stopped)
        ToolPresentationState.BACKGROUND_RUNNING -> stringResource(
            R.string.tool_background_job_running,
            presentation.jobId ?: subject ?: stringResource(R.string.tool_execute_shell),
        )
        ToolPresentationState.CALLING,
        ToolPresentationState.RUNNING -> runningSummary(presentation, subject)
        ToolPresentationState.EMPTY -> emptySummary(presentation, subject)
        ToolPresentationState.SUCCEEDED -> completedSummary(presentation, subject)
    }
}

@Composable
private fun runningSummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> stringResource(R.string.tool_looking_up_memories)
    ToolKind.MEMORY_READ -> stringResource(R.string.tool_reading_memory, subject ?: "memory")
    ToolKind.MEMORY_CREATE -> stringResource(R.string.tool_saving_memory, subject ?: "memory")
    ToolKind.MEMORY_EDIT -> stringResource(R.string.tool_updating_memory, subject ?: "memory")
    ToolKind.MEMORY_DELETE -> stringResource(R.string.tool_removing_memory, subject ?: "memory")
    ToolKind.MEMORY_UPDATE_ACTIVE -> stringResource(R.string.tool_updating_active)
    ToolKind.WEB_SEARCH -> stringResource(R.string.tool_searching_web, subject ?: "web")
    ToolKind.WEB_FETCH -> stringResource(R.string.tool_web_fetching, subject ?: "web page")
    ToolKind.CONVERSATION_SEARCH -> stringResource(R.string.tool_searching_for, subject ?: "conversation")
    ToolKind.CONVERSATION_LIST -> stringResource(R.string.tool_listing_conversations)
    ToolKind.CONVERSATION_READ -> stringResource(R.string.tool_reading_conversation)
    ToolKind.SHELL_LIST -> stringResource(R.string.tool_listing_shells)
    ToolKind.SHELL_EXECUTE -> presentation.liveOutput
        ?.lineSequence()
        ?.lastOrNull()
        ?.take(120)
        ?: stringResource(R.string.tool_executing_shell, subject ?: "shell")
    ToolKind.SHELL_JOB_LIST -> stringResource(R.string.tool_listing_shell_jobs)
    ToolKind.SHELL_JOB_GET -> stringResource(R.string.tool_reading_shell_job, subject ?: "job")
    ToolKind.SHELL_JOB_STOP -> stringResource(R.string.tool_stopping_shell_job, subject ?: "job")
    ToolKind.FILE_READ -> stringResource(R.string.tool_reading_file, subject ?: "file")
    ToolKind.FILE_WRITE -> stringResource(R.string.tool_writing_file, subject ?: "file")
    ToolKind.FILE_EDIT -> stringResource(R.string.tool_editing_file, subject ?: "file")
    ToolKind.FILE_GLOB -> stringResource(R.string.tool_finding_files, subject ?: "files")
    ToolKind.FILE_GREP -> stringResource(R.string.tool_searching_file, subject ?: "files")
    ToolKind.IMAGE_GENERATE -> stringResource(R.string.tool_generating_image)
    ToolKind.TASK_CREATE -> stringResource(R.string.tool_creating_task)
    ToolKind.TASK_LIST -> stringResource(R.string.tool_listing_tasks)
    ToolKind.TASK_DELETE -> stringResource(R.string.tool_deleting_task)
    ToolKind.LOOP_START -> stringResource(R.string.tool_starting_loop)
    ToolKind.LOOP_STOP -> stringResource(R.string.tool_stopping_loop)
    ToolKind.UNKNOWN -> stringResource(R.string.tool_calling_ellipsis)
}

@Composable
private fun emptySummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> stringResource(R.string.tool_lookup_default)
    ToolKind.WEB_SEARCH -> stringResource(R.string.tool_web_search_no_result, subject ?: "")
    ToolKind.CONVERSATION_SEARCH -> stringResource(
        R.string.tool_conversation_search_no_result,
        subject ?: "",
    )
    ToolKind.CONVERSATION_LIST -> stringResource(R.string.tool_listed_no_conversations)
    ToolKind.SHELL_LIST -> stringResource(R.string.tool_shell_list_done)
    ToolKind.SHELL_EXECUTE -> stringResource(
        R.string.tool_executed_no_output,
        subject ?: "shell",
    )
    ToolKind.SHELL_JOB_LIST -> stringResource(R.string.tool_no_shell_jobs)
    ToolKind.FILE_READ -> stringResource(R.string.tool_read_file_empty, subject ?: "file")
    ToolKind.FILE_GLOB -> stringResource(R.string.tool_found_no_files)
    ToolKind.FILE_GREP -> stringResource(R.string.tool_found_no_matches)
    ToolKind.TASK_LIST -> stringResource(R.string.tool_listed_tasks)
    else -> completedSummary(presentation, subject)
}

@Composable
private fun completedSummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> stringResource(
        R.string.tool_lookup_count,
        presentation.count ?: 0,
    )
    ToolKind.MEMORY_READ -> stringResource(R.string.tool_read_memory_name, subject ?: "memory")
    ToolKind.MEMORY_CREATE -> stringResource(R.string.tool_save_memory_name, subject ?: "memory")
    ToolKind.MEMORY_EDIT -> stringResource(R.string.tool_edit_memory_name, subject ?: "memory")
    ToolKind.MEMORY_DELETE -> stringResource(R.string.tool_delete_memory_name, subject ?: "memory")
    ToolKind.MEMORY_UPDATE_ACTIVE -> stringResource(R.string.tool_update_active_default)
    ToolKind.WEB_SEARCH -> stringResource(
        R.string.tool_web_search_done,
        presentation.count ?: 0,
        subject ?: "",
    )
    ToolKind.WEB_FETCH -> stringResource(R.string.tool_web_fetch_done, subject ?: "web page")
    ToolKind.CONVERSATION_SEARCH -> stringResource(
        R.string.tool_conversation_search_done_for,
        presentation.count ?: 0,
        subject ?: "",
    )
    ToolKind.CONVERSATION_LIST -> stringResource(
        R.string.tool_listed_conversations,
        presentation.count ?: 0,
    )
    ToolKind.CONVERSATION_READ -> stringResource(
        R.string.tool_read_conversation_done,
        subject ?: "conversation",
    )
    ToolKind.SHELL_LIST -> stringResource(
        R.string.tool_shell_list_count,
        presentation.count ?: 0,
    )
    ToolKind.SHELL_EXECUTE -> stringResource(
        R.string.tool_shell_exit_summary,
        presentation.exitCode ?: -1,
        presentation.outputLength ?: 0,
    )
    ToolKind.SHELL_JOB_LIST -> stringResource(
        R.string.tool_shell_job_count,
        presentation.count ?: 0,
    )
    ToolKind.SHELL_JOB_GET -> stringResource(
        R.string.tool_shell_job_status,
        presentation.jobId ?: subject ?: "job",
    )
    ToolKind.SHELL_JOB_STOP -> stringResource(
        R.string.tool_stopped_shell_job,
        presentation.jobId ?: subject ?: "job",
    )
    ToolKind.FILE_READ -> stringResource(R.string.tool_read_file_done, subject ?: "file")
    ToolKind.FILE_WRITE -> stringResource(R.string.tool_wrote_file, subject ?: "file")
    ToolKind.FILE_EDIT -> stringResource(R.string.tool_edited_file, subject ?: "file")
    ToolKind.FILE_GLOB -> stringResource(R.string.tool_found_files, presentation.count ?: 0)
    ToolKind.FILE_GREP -> stringResource(R.string.tool_searched_file, presentation.count ?: 0)
    ToolKind.IMAGE_GENERATE -> stringResource(R.string.tool_generated_image)
    ToolKind.TASK_CREATE -> stringResource(R.string.tool_created_task)
    ToolKind.TASK_LIST -> stringResource(R.string.tool_listed_tasks)
    ToolKind.TASK_DELETE -> stringResource(R.string.tool_deleted_task)
    ToolKind.LOOP_START -> stringResource(R.string.tool_started_loop)
    ToolKind.LOOP_STOP -> stringResource(R.string.tool_stopped_loop)
    ToolKind.UNKNOWN -> stringResource(R.string.tool_done)
}
