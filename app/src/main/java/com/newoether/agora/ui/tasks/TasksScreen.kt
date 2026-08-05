package com.newoether.agora.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.automation.CronExpression
import com.newoether.agora.automation.ScheduleType
import com.newoether.agora.automation.TaskSchedule
import com.newoether.agora.automation.hasSchedule
import com.newoether.agora.data.local.TaskEntity
import java.util.Calendar
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.ui.chat.ChatDeleteConfirmDialog
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.settings.AnimatedActionFab
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import com.newoether.agora.ui.settings.GuardedAnimatedContent
import com.newoether.agora.ui.settings.SettingsGroup
import com.newoether.agora.ui.settings.SettingsIconContent
import com.newoether.agora.ui.settings.SettingsItem
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import java.text.DateFormatSymbols
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Tasks feature root: a saved prompt + model you can run on demand or on a schedule.
 *
 * List ↔ detail is an in-overlay switch driven by [GuardedAnimatedContent] — the SAME transition
 * Settings uses for its sub-pages, so entering the Tasks page and entering a task feel identical.
 * The open task is tracked by ID (not entity) so live Room updates — countdown, run status — flow
 * into the detail page without restarting the transition.
 */
@Composable
fun TasksScreen(
    viewModel: ChatViewModel,
    initialTaskId: String? = null,
    onInitialTaskHandled: () -> Unit = {},
    onBack: () -> Unit,
    onOpenConversation: (taskId: String, conversationId: String) -> Unit,
) {
    val tasks by viewModel.tasks.collectAsState()
    var openTaskId by remember { mutableStateOf<String?>(null) }
    // A brand-new task only reaches Room once it has a name + prompt, so backing out of an
    // untouched draft leaves nothing behind. Until then it lives here.
    var draft by remember { mutableStateOf<TaskEntity?>(null) }

    LaunchedEffect(initialTaskId) {
        val id = initialTaskId ?: return@LaunchedEffect
        viewModel.getTask(id)?.let {
            draft = null
            openTaskId = it.id
        }
        onInitialTaskHandled()
    }

    GuardedAnimatedContent(
        targetState = openTaskId,
        forward = openTaskId != null,
    ) { taskId ->
        if (taskId == null) {
            TasksListPage(
                viewModel = viewModel,
                tasks = tasks,
                onBack = onBack,
                onNewTask = {
                    val newTask = TaskEntity(
                        id = UUID.randomUUID().toString(),
                        name = "", prompt = "", cronExpr = "", nextRunAt = 0L
                    )
                    draft = newTask
                    openTaskId = newTask.id
                },
                onOpenTask = { draft = null; openTaskId = it.id },
            )
        } else {
            val task = tasks.firstOrNull { it.id == taskId } ?: draft?.takeIf { it.id == taskId }
            if (task == null) {
                // Deleted (or never persisted) while open — fall back to the list instead of
                // rendering an empty editor.
                LaunchedEffect(taskId) { openTaskId = null }
            } else {
                TaskDetailPage(
                    viewModel = viewModel,
                    task = task,
                    isNew = draft?.id == taskId,
                    onBack = { openTaskId = null },
                    onOpenConversation = onOpenConversation,
                )
            }
        }
    }
}

// ── List ────────────────────────────────────────────────────────────────────

@Composable
private fun TasksListPage(
    viewModel: ChatViewModel,
    tasks: List<TaskEntity>,
    onBack: () -> Unit,
    onNewTask: () -> Unit,
    onOpenTask: (TaskEntity) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    BackHandler { onBack() }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.tasks),
        onBack = onBack,
    ) {
        val totalRows = tasks.size + 1
        if (tasks.isEmpty()) {
            item(key = "tasks_empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = stackedShape(0, 2),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.task_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.task_empty_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Repeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                    )
                }
                Spacer(Modifier.height(STACK_GAP))
            }
        } else {
            itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                val executions by viewModel.executionSummariesForTask(task.id)
                    .collectAsState(initial = emptyList())
                TaskCard(
                    task = task,
                    isRunning = task.id in running,
                    lastRunAt = executions.firstOrNull()?.timestamp?.takeIf { it > 0L },
                    shape = stackedShape(index, totalRows),
                    onClick = { onOpenTask(task) },
                    onRun = { viewModel.runTaskNow(task) },
                    onToggleEnabled = { enabled -> viewModel.saveTask(task.copy(enabled = enabled)) },
                    onDelete = { pendingDelete = task },
                )
                Spacer(Modifier.height(STACK_GAP))
            }
        }
        item(key = "new_automation") {
            NewAutomationRow(
                shape = stackedShape(if (tasks.isEmpty()) 1 else tasks.size, if (tasks.isEmpty()) 2 else totalRows),
                onClick = onNewTask,
            )
        }
    }

    pendingDelete?.let { task ->
        val displayName = task.name.ifBlank { stringResource(R.string.task_name_hint) }
        // Identical shape to MessageDeleteDialog — the app's one destructive-confirm style.
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.task_delete), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.task_delete_confirm, displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTask(task.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    isRunning: Boolean,
    lastRunAt: Long?,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var now by remember(task.id, task.nextRunAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id, task.enabled, task.nextRunAt) {
        if (task.enabled && task.nextRunAt > 0L) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }
    // Same surface language as a SettingsGroup card: surface + 1dp tonal elevation, stacked corners.
    // Surface(onClick=) — NOT Modifier.clickable on the passed-in modifier, which sits outside the
    // Surface's own clip and lets the ripple bleed out to a rectangle.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name.ifBlank { stringResource(R.string.task_name_hint) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (task.prompt.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = task.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                // Armed → recurrence summary + live countdown. Not armed → "Manual only": the
                // switch is the single place that state is expressed, so the recurrence isn't
                // shown as if it were about to fire.
                val scheduleText = if (task.enabled && task.hasSchedule()) {
                    listOfNotNull(
                        taskRepeatSummary(task),
                        if (task.nextRunAt > 0L) {
                            stringResource(R.string.task_next_run, formatTaskCountdown(task.nextRunAt - now))
                        } else null,
                    ).joinToString(" · ")
                } else {
                    stringResource(R.string.task_schedule_manual)
                }
                Text(
                    text = scheduleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (task.enabled) 1f else 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when {
                        isRunning -> stringResource(R.string.task_running)
                        lastRunAt != null -> stringResource(R.string.task_last_run_at, formatDateTime(lastRunAt))
                        else -> stringResource(R.string.task_never_run)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_run_now)) },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = { menuOpen = false; onRun() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewAutomationRow(
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.task_new_task),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Corner treatment for a vertically stacked list of cards — identical to what [SettingsGroup]
 * applies to its items (24dp on the outer edges, 5dp where two cards meet, 2dp between them),
 * so task rows and execution rows read as the same component as every settings card.
 */
private fun stackedShape(index: Int, count: Int): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(24.dp)
    index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
    index == count - 1 -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
    else -> RoundedCornerShape(5.dp)
}

private val STACK_GAP = 2.dp

internal fun formatTaskCountdown(remainingMs: Long): String {
    val clampedMs = remainingMs.coerceAtLeast(0L)
    val totalSeconds = clampedMs / 1_000L + if (clampedMs % 1_000L == 0L) 0L else 1L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * The schedule editor mode is explicit UI state. In particular, CUSTOM must not be inferred from
 * whether the current text parses: a partially typed cron is expected to be invalid for a moment,
 * but that must not make the editor jump back to Daily.
 */
internal enum class ScheduleEditorMode {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM,
}

internal fun initialScheduleEditorMode(cronExpr: String, runAt: Long?): ScheduleEditorMode {
    val parsed = TaskSchedule.parse(cronExpr, runAt)
    return parsed?.type?.toEditorMode()
        ?: if (cronExpr.isNotBlank()) ScheduleEditorMode.CUSTOM else ScheduleEditorMode.DAILY
}

internal fun isScheduleDraftValid(mode: ScheduleEditorMode, cronExpr: String): Boolean =
    if (mode == ScheduleEditorMode.CUSTOM) {
        cronExpr.isNotBlank() && CronExpression.isValid(cronExpr)
    } else {
        cronExpr.isBlank() || CronExpression.isValid(cronExpr)
    }

private fun ScheduleType.toEditorMode(): ScheduleEditorMode = when (this) {
    ScheduleType.ONCE -> ScheduleEditorMode.ONCE
    ScheduleType.DAILY -> ScheduleEditorMode.DAILY
    ScheduleType.WEEKLY -> ScheduleEditorMode.WEEKLY
    ScheduleType.MONTHLY -> ScheduleEditorMode.MONTHLY
    ScheduleType.YEARLY -> ScheduleEditorMode.YEARLY
}

private fun ScheduleEditorMode.toScheduleType(): ScheduleType? = when (this) {
    ScheduleEditorMode.ONCE -> ScheduleType.ONCE
    ScheduleEditorMode.DAILY -> ScheduleType.DAILY
    ScheduleEditorMode.WEEKLY -> ScheduleType.WEEKLY
    ScheduleEditorMode.MONTHLY -> ScheduleType.MONTHLY
    ScheduleEditorMode.YEARLY -> ScheduleType.YEARLY
    ScheduleEditorMode.CUSTOM -> null
}

/**
 * Preserve a literal time when leaving a custom expression. The rest of a custom cron may be too
 * rich for the structured editor, but its `minute hour` prefix is still useful and lossless.
 */
private fun scheduleSeedFromCron(cronExpr: String): TaskSchedule {
    val fields = cronExpr.trim().split(Regex("\\s+"))
    val minute = fields.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..59 }
    val hour = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..23 }
    val default = TaskSchedule.default()
    return default.copy(
        hour = hour ?: default.hour,
        minute = minute ?: default.minute,
    )
}

// ── Detail ──────────────────────────────────────────────────────────────────

/**
 * Task editor, structured as three Settings-style groups — Details / Schedule / Execution log —
 * so a task reads top-to-bottom as "what it says, when it fires, what it did". Everything a run
 * depends on lives above the log; nothing is hidden behind a dialog except the model list.
 */
@Composable
private fun TaskDetailPage(
    viewModel: ChatViewModel,
    task: TaskEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onOpenConversation: (taskId: String, conversationId: String) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()

    var name by rememberSaveable(task.id) { mutableStateOf(task.name) }
    var prompt by rememberSaveable(task.id) { mutableStateOf(task.prompt) }
    var modelId by rememberSaveable(task.id) { mutableStateOf(task.modelId) }
    var cronExpr by rememberSaveable(task.id) { mutableStateOf(task.cronExpr) }
    var runAt by rememberSaveable(task.id) { mutableStateOf(task.runAt) }
    var scheduleEditorModeName by rememberSaveable(task.id) {
        mutableStateOf(initialScheduleEditorMode(task.cronExpr, task.runAt).name)
    }
    var enabled by rememberSaveable(task.id) { mutableStateOf(task.enabled) }
    var showModelPicker by remember { mutableStateOf(false) }
    var executionToDelete by remember { mutableStateOf<com.newoether.agora.automation.TaskManager.ExecutionSummary?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    val isRunning = task.id in running
    val executions by viewModel.executionSummariesForTask(task.id).collectAsState(initial = emptyList())

    val scheduleEditorMode = ScheduleEditorMode.valueOf(scheduleEditorModeName)
    val cronValid = isScheduleDraftValid(scheduleEditorMode, cronExpr)
    val isComplete = name.isNotBlank() && prompt.isNotBlank() && cronValid

    fun current() = task.copy(
        name = name.trim(), prompt = prompt, modelId = modelId,
        cronExpr = cronExpr, runAt = runAt, enabled = enabled,
    )
    val saved = current() == task
    fun save() { if (isComplete) viewModel.saveTask(current()) }
    // Back still saves — an editor that silently discards work on the system back gesture is a
    // trap. The explicit Save button exists to make the commit point visible, not to gate it.
    fun leave() { save(); onBack() }

    BackHandler { leave() }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }

    CollapsingSettingsLazyScaffold(
        title = name.ifBlank { stringResource(if (isNew) R.string.task_new else R.string.task_edit) },
        onBack = { leave() },
        modifier = Modifier.clearFocusOnTap(),
        listState = listState,
        actions = {
            IconButton(enabled = isComplete && !saved, onClick = { leave() }) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.task_save))
            }
        },
        floatingActionButton = {
            AnimatedActionFab(
                label = stringResource(if (isRunning) R.string.task_running else R.string.task_run_now),
                icon = Icons.Default.PlayArrow,
                onClick = {
                    viewModel.runTaskNow(current())
                },
                enabled = isComplete && !isRunning,
                loading = isRunning,
            )
        },
    ) {
        item {
            SettingsGroup(
                title = stringResource(R.string.task_section_details),
                items = listOf(
                    {
                        LabeledField(
                            label = stringResource(R.string.task_name),
                            icon = Icons.Default.Label,
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.task_name_hint),
                            singleLine = true,
                        )
                    },
                    {
                        LabeledField(
                            label = stringResource(R.string.task_prompt),
                            icon = Icons.Default.Psychology,
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = stringResource(R.string.task_prompt_hint),
                            singleLine = false,
                        )
                    },
                    {
                        SettingsItem(
                            modifier = Modifier.clickable { showModelPicker = true },
                            headlineContent = { Text(stringResource(R.string.task_model)) },
                            supportingContent = {
                                Text(
                                    modelId?.let { modelAliases[it] ?: ModelId.parse(it).apiModelName }
                                        ?: stringResource(R.string.task_model_default)
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    },
                ),
            )
            Spacer(Modifier.height(24.dp))
        }

        item {
            ScheduleGroup(
                cronExpr = cronExpr,
                runAt = runAt,
                onScheduleChange = { newCron, newRunAt -> cronExpr = newCron; runAt = newRunAt },
                editorMode = scheduleEditorMode,
                onEditorModeChange = { scheduleEditorModeName = it.name },
                enabled = enabled,
                onEnabledChange = { enabled = it },
            )
            Spacer(Modifier.height(24.dp))
        }

        item {
            Text(
                stringResource(R.string.task_execution_log),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (executions.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.task_no_executions),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.task_no_executions_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                    )
                }
            }
        } else {
            itemsIndexed(executions, key = { _, e -> e.conversation.id }) { index, execution ->
                ExecutionRow(
                    execution = execution,
                    shape = stackedShape(index, executions.size),
                    onClick = { onOpenConversation(task.id, execution.conversation.id) },
                    menuEnabled = !isRunning,
                    onDelete = { executionToDelete = execution },
                )
                if (index < executions.lastIndex) Spacer(Modifier.height(STACK_GAP))
            }
        }
        item(key = "task_detail_fab_spacing") {
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            selected = modelId,
            onSelect = { modelId = it; showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
    executionToDelete?.let { execution ->
        ChatDeleteConfirmDialog(
            onConfirm = {
                viewModel.deleteConversation(execution.conversation.id)
                executionToDelete = null
            },
            onDismiss = { executionToDelete = null },
        )
    }
}

/** A group row whose value is typed in place. Icon-bearing fields use the same leading-icon
 *  content column as the Proxy settings page, so the label and field share its left inset. */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isError: Boolean = false,
    supporting: String? = null,
    supportingIsError: Boolean = false,
) {
    val fieldContent: @Composable () -> Unit = {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            isError = isError,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        if (supporting != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (supportingIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (icon != null) {
        SettingsIconContent(icon = icon) {
            fieldContent()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            fieldContent()
        }
    }
}

private fun formatDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(millis))

private fun formatTimeOfDay(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

/** One-line recurrence summary for a task card ("Daily", "Weekly", a raw cron, …). */
@Composable
private fun taskRepeatSummary(task: TaskEntity): String {
    val schedule = TaskSchedule.parse(task.cronExpr, task.runAt)
    return if (schedule != null) repeatLabel(schedule.type)
    else task.cronExpr.ifBlank { stringResource(R.string.task_schedule_not_set) }
}

@Composable
private fun repeatLabel(type: ScheduleType): String = stringResource(
    when (type) {
        ScheduleType.ONCE -> R.string.task_repeat_once
        ScheduleType.DAILY -> R.string.task_repeat_daily
        ScheduleType.WEEKLY -> R.string.task_repeat_weekly
        ScheduleType.MONTHLY -> R.string.task_repeat_monthly
        ScheduleType.YEARLY -> R.string.task_repeat_yearly
    }
)

@Composable
private fun repeatLabel(mode: ScheduleEditorMode): String =
    if (mode == ScheduleEditorMode.CUSTOM) {
        stringResource(R.string.task_schedule_custom)
    } else {
        repeatLabel(checkNotNull(mode.toScheduleType()))
    }

/** Short weekday names in the user's locale, indexed 0=Sunday..6=Saturday to match cron. */
@Composable
private fun weekdayNames(): List<String> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val cal = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("EEE", locale)
        (0..6).map { dow ->
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY + dow)
            fmt.format(cal.time)
        }
    }
}

/**
 * Schedule group: WHAT recurrence (Repeat), WHICH date within it (On), and WHAT time (At) — plus
 * whether the whole thing is armed (the switch). "Manual only" is not a repeat option; it is the
 * switch being off, so no two controls express the same state.
 *
 * The On row's editor depends on the repeat type, because "which date" means something different
 * for each: daily has no On row at all, weekly picks weekdays, monthly picks a day number, yearly
 * and once pick a calendar date. Once additionally stores an absolute epoch instead of a cron —
 * a 5-field cron has no year, so "once on March 3rd" would silently repeat every year.
 *
 * A cron this model cannot express (a legacy hourly preset, a hand-written step expression) is
 * left untouched and shown as a custom expression until the user picks a repeat type.
 */
@Composable
private fun ScheduleGroup(
    cronExpr: String,
    runAt: Long?,
    onScheduleChange: (cron: String, runAt: Long?) -> Unit,
    editorMode: ScheduleEditorMode,
    onEditorModeChange: (ScheduleEditorMode) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val parsedSchedule = remember(cronExpr, runAt) { TaskSchedule.parse(cronExpr, runAt) }
    val isCustomCron = editorMode == ScheduleEditorMode.CUSTOM
    val schedule = parsedSchedule ?: remember(cronExpr) { scheduleSeedFromCron(cronExpr) }

    var showRepeatMenu by remember { mutableStateOf(false) }
    var showWeekdayDialog by remember { mutableStateOf(false) }
    var showDayOfMonthDialog by remember { mutableStateOf(false) }
    var showMonthDayDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }

    fun apply(next: TaskSchedule) = onScheduleChange(next.toCron(), next.toRunAt())
    fun selectMode(nextMode: ScheduleEditorMode) {
        onEditorModeChange(nextMode)
        if (nextMode == ScheduleEditorMode.CUSTOM) {
            // ONCE has no cron to preserve. Seed Custom with the same time-of-day as a daily cron.
            val seedCron = cronExpr.ifBlank {
                schedule.copy(type = ScheduleType.DAILY, onceAtMillis = 0L).toCron()
            }
            onScheduleChange(seedCron, null)
        } else {
            apply(schedule.switchedTo(checkNotNull(nextMode.toScheduleType())))
        }
    }

    val armable = cronExpr.isNotBlank() || (runAt != null && runAt > 0L)
    val scheduleDraftValid = isScheduleDraftValid(editorMode, cronExpr)
    val oncePast = schedule.type == ScheduleType.ONCE &&
        (runAt ?: 0L) in 1 until System.currentTimeMillis()
    val canToggleSchedule = armable && scheduleDraftValid && (!oncePast || enabled)

    SettingsGroup(
        title = stringResource(R.string.task_schedule),
        items = buildList {
            // ── Repeat ──
            add {
                Box {
                    SettingsItem(
                        modifier = Modifier.clickable { showRepeatMenu = true },
                        headlineContent = { Text(stringResource(R.string.task_repeat)) },
                        supportingContent = {
                            Text(repeatLabel(editorMode))
                        },
                        leadingContent = {
                            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                    DropdownMenu(
                        expanded = showRepeatMenu,
                        onDismissRequest = { showRepeatMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        ScheduleEditorMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(repeatLabel(mode)) },
                                leadingIcon = {
                                    if (editorMode == mode) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    showRepeatMenu = false
                                    selectMode(mode)
                                },
                            )
                        }
                    }
                }
            }

            // ── On (absent for DAILY, which has no date to choose) ──
            if (!isCustomCron && schedule.type != ScheduleType.DAILY) {
                add {
                    val names = weekdayNames()
                    val onValue = when (schedule.type) {
                        ScheduleType.WEEKLY ->
                            if (schedule.daysOfWeek.isEmpty()) stringResource(R.string.task_schedule_not_set)
                            else schedule.daysOfWeek.sorted().joinToString(", ") { names[it] }
                        ScheduleType.MONTHLY -> stringResource(R.string.task_day_ordinal, schedule.dayOfMonth)
                        ScheduleType.YEARLY, ScheduleType.ONCE -> schedule.formatOnDate()
                        ScheduleType.DAILY -> ""
                    }
                    SettingsItem(
                        modifier = Modifier.clickable {
                            when (schedule.type) {
                                ScheduleType.WEEKLY -> showWeekdayDialog = true
                                ScheduleType.MONTHLY -> showDayOfMonthDialog = true
                                ScheduleType.YEARLY -> showMonthDayDialog = true
                                ScheduleType.ONCE -> showDateDialog = true
                                ScheduleType.DAILY -> Unit
                            }
                        },
                        headlineContent = {
                            Text(
                                when (schedule.type) {
                                    ScheduleType.WEEKLY -> stringResource(R.string.task_days_of_week)
                                    ScheduleType.MONTHLY -> stringResource(R.string.task_day_of_month)
                                    else -> stringResource(R.string.task_on)
                                }
                            )
                        },
                        supportingContent = { Text(onValue) },
                        leadingContent = {
                            Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // ── At ──
            if (!isCustomCron) {
                add {
                    SettingsItem(
                        modifier = Modifier.clickable { showTimeDialog = true },
                        headlineContent = { Text(stringResource(R.string.task_at)) },
                        supportingContent = { Text(formatTimeOfDay(schedule.hour, schedule.minute)) },
                        leadingContent = {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // ── Custom cron passthrough ──
            if (isCustomCron) {
                add {
                    LabeledField(
                        label = stringResource(R.string.task_schedule_custom),
                        icon = Icons.Default.Code,
                        value = cronExpr,
                        onValueChange = { onScheduleChange(it, null) },
                        placeholder = stringResource(R.string.task_cron_hint),
                        singleLine = true,
                        isError = cronExpr.isBlank() || !CronExpression.isValid(cronExpr),
                        supporting = if (cronExpr.isBlank() || !CronExpression.isValid(cronExpr)) {
                            stringResource(R.string.task_cron_invalid)
                        } else {
                            null
                        },
                        supportingIsError = true,
                    )
                }
            }

            // ── Armed switch ──
            add {
                val nextRun = remember(cronExpr, runAt, enabled) {
                    when {
                        !enabled -> null
                        runAt != null && runAt > System.currentTimeMillis() -> runAt
                        cronExpr.isNotBlank() ->
                            CronExpression.parse(cronExpr)?.next(System.currentTimeMillis())
                        else -> null
                    }
                }
                SettingsItem(
                    modifier = Modifier.clickable(enabled = canToggleSchedule) {
                        onEnabledChange(!enabled)
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.task_enabled),
                            color = if (armable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    },
                    supportingContent = {
                        Text(
                            when {
                                !armable -> stringResource(R.string.task_enabled_needs_schedule)
                                oncePast -> stringResource(R.string.task_once_past)
                                nextRun != null -> stringResource(R.string.task_next_run, formatDateTime(nextRun))
                                else -> stringResource(R.string.task_enabled_desc)
                            },
                            color = if (oncePast) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled && armable,
                            enabled = canToggleSchedule,
                            onCheckedChange = onEnabledChange,
                        )
                    },
                )
            }
        },
    )

    if (showWeekdayDialog) {
        WeekdayDialog(
            selected = schedule.daysOfWeek,
            onConfirm = { days -> apply(schedule.copy(daysOfWeek = days)); showWeekdayDialog = false },
            onDismiss = { showWeekdayDialog = false },
        )
    }
    if (showDayOfMonthDialog) {
        DayOfMonthDialog(
            selected = schedule.dayOfMonth,
            onSelect = { day -> apply(schedule.copy(dayOfMonth = day)); showDayOfMonthDialog = false },
            onDismiss = { showDayOfMonthDialog = false },
        )
    }
    if (showMonthDayDialog) {
        TaskMonthDayPickerDialog(
            schedule = schedule,
            onConfirm = {
                apply(it)
                showMonthDayDialog = false
            },
            onDismiss = { showMonthDayDialog = false },
        )
    }
    if (showDateDialog) {
        TaskDatePickerDialog(
            schedule = schedule,
            onConfirm = {
                apply(it)
                showDateDialog = false
            },
            onDismiss = { showDateDialog = false },
        )
    }
    if (showTimeDialog) {
        TaskTimePickerDialog(
            schedule = schedule,
            use24HourFormat = android.text.format.DateFormat.is24HourFormat(context),
            onConfirm = {
                apply(it)
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false },
        )
    }
}

internal fun daysInYearlyMonth(month: Int): Int = when (month) {
    2 -> 29 // A yearly cron may intentionally target leap day.
    4, 6, 9, 11 -> 30
    else -> 31
}

/** A yearless picker for YEARLY schedules: month plus day are the entire persisted date. */
@Composable
private fun TaskMonthDayPickerDialog(
    schedule: TaskSchedule,
    onConfirm: (TaskSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val monthNames = remember(locale) { DateFormatSymbols(locale).months.take(12) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(schedule.month.coerceIn(1, 12)) }
    var selectedDay by rememberSaveable {
        mutableIntStateOf(schedule.dayOfMonth.coerceIn(1, daysInYearlyMonth(selectedMonth)))
    }
    var showMonthMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                stringResource(R.string.task_select_month_day),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    TextButton(onClick = { showMonthMenu = true }) {
                        Text(
                            monthNames[selectedMonth - 1],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showMonthMenu,
                        onDismissRequest = { showMonthMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        monthNames.forEachIndexed { index, monthName ->
                            val month = index + 1
                            DropdownMenuItem(
                                text = { Text(monthName) },
                                leadingIcon = {
                                    if (month == selectedMonth) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedMonth = month
                                    selectedDay = selectedDay.coerceAtMost(daysInYearlyMonth(month))
                                    showMonthMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                (1..daysInYearlyMonth(selectedMonth)).chunked(7).forEach { rowDays ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(7) { column ->
                            val day = rowDays.getOrNull(column)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (day != null) {
                                    val selected = day == selectedDay
                                    Surface(
                                        onClick = { selectedDay = day },
                                        modifier = Modifier.size(40.dp),
                                        shape = CircleShape,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer
                                        },
                                        contentColor = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(day.toString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        schedule.copy(
                            month = selectedMonth,
                            dayOfMonth = selectedDay,
                            onceAtMillis = 0L,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Full date picker for ONCE. IME exit and calendar expansion are serialized to avoid remeasure. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskDatePickerDialog(
    schedule: TaskSchedule,
    onConfirm: (TaskSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialLocalDate = remember(schedule) {
        Calendar.getInstance().apply {
            if (schedule.type == ScheduleType.ONCE && schedule.onceAtMillis > 0L) {
                timeInMillis = schedule.onceAtMillis
            } else {
                set(Calendar.MONTH, schedule.month - 1)
                set(Calendar.DAY_OF_MONTH, schedule.dayOfMonth)
            }
        }
    }
    val initialUtcMillis = remember(initialLocalDate) {
        utcDateMillis(
            initialLocalDate.get(Calendar.YEAR),
            initialLocalDate.get(Calendar.MONTH),
            initialLocalDate.get(Calendar.DAY_OF_MONTH),
        )
    }
    val todayUtcMillis = remember {
        val today = Calendar.getInstance()
        utcDateMillis(
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH),
            today.get(Calendar.DAY_OF_MONTH),
        )
    }
    val selectableDates = remember(schedule.type, todayUtcMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                schedule.type != ScheduleType.ONCE || utcTimeMillis >= todayUtcMillis
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMillis,
        selectableDates = selectableDates,
    )
    val pickerColors = DatePickerDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val dateFormatter = remember { DatePickerDefaults.dateFormatter() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var pendingCalendarMode by remember { mutableStateOf(false) }

    LaunchedEffect(pendingCalendarMode, imeVisible) {
        if (pendingCalendarMode && !imeVisible) {
            pickerState.displayMode = DisplayMode.Picker
            pendingCalendarMode = false
        }
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = pickerState.selectedDateMillis ?: return@TextButton
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = selected
                    }
                    val year = utc.get(Calendar.YEAR)
                    val month = utc.get(Calendar.MONTH) + 1
                    val day = utc.get(Calendar.DAY_OF_MONTH)
                    val next = schedule.copy(dayOfMonth = day, month = month)
                    onConfirm(
                        if (schedule.type == ScheduleType.ONCE) next.withOnceAt(year, month, day)
                        else next
                    )
                },
                enabled = pickerState.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = pickerColors,
    ) {
        DatePicker(
            state = pickerState,
            dateFormatter = dateFormatter,
            colors = pickerColors,
            title = {
                ProvideTextStyle(
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                ) {
                    DatePickerDefaults.DatePickerTitle(
                        displayMode = pickerState.displayMode,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 20.dp),
                        contentColor = pickerColors.titleContentColor,
                    )
                }
            },
            headline = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DatePickerDefaults.DatePickerHeadline(
                        selectedDateMillis = pickerState.selectedDateMillis,
                        displayMode = pickerState.displayMode,
                        dateFormatter = dateFormatter,
                        modifier = Modifier.weight(1f),
                        contentColor = pickerColors.headlineContentColor,
                    )
                    IconButton(
                        enabled = !pendingCalendarMode,
                        onClick = {
                            if (pickerState.displayMode == DisplayMode.Picker) {
                                pickerState.displayMode = DisplayMode.Input
                            } else {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                if (imeVisible) {
                                    pendingCalendarMode = true
                                } else {
                                    pickerState.displayMode = DisplayMode.Picker
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (pickerState.displayMode == DisplayMode.Picker) {
                                Icons.Default.Edit
                            } else {
                                Icons.Default.CalendarMonth
                            },
                            contentDescription = stringResource(
                                if (pickerState.displayMode == DisplayMode.Picker) {
                                    R.string.task_switch_to_date_input
                                } else {
                                    R.string.task_switch_to_calendar
                                }
                            ),
                        )
                    }
                }
            },
            showModeToggle = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskTimePickerDialog(
    schedule: TaskSchedule,
    use24HourFormat: Boolean,
    onConfirm: (TaskSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = schedule.hour,
        initialMinute = schedule.minute,
        is24Hour = use24HourFormat,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(stringResource(R.string.task_at), fontWeight = FontWeight.Bold)
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(schedule.withTime(pickerState.hour, pickerState.minute))
                },
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun utcDateMillis(year: Int, zeroBasedMonth: Int, day: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, zeroBasedMonth, day)
    }.timeInMillis

@Composable
private fun WeekdayDialog(
    selected: Set<Int>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val names = weekdayNames()
    var working by remember { mutableStateOf(selected) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_days_of_week), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(7) { dow ->
                    val checked = dow in working
                    SettingsItem(
                        modifier = Modifier.clickable {
                            working = if (checked) working - dow else working + dow
                        },
                        headlineContent = {
                            Text(names[dow], fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                        },
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { working = if (checked) working - dow else working + dow },
                            )
                        },
                    )
                }
            }
        },
        // Multi-select needs an explicit commit — unlike the single-choice pickers, one tap here
        // is not the final answer.
        confirmButton = {
            TextButton(enabled = working.isNotEmpty(), onClick = { onConfirm(working) }) {
                Text(stringResource(R.string.provider_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DayOfMonthDialog(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_day_of_month), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(31) { index ->
                    val day = index + 1
                    ChoiceRow(
                        label = stringResource(R.string.task_day_ordinal, day),
                        sub = null,
                        selected = day == selected,
                        onClick = { onSelect(day) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) } },
    )
}

@Composable
private fun ExecutionRow(
    execution: com.newoether.agora.automation.TaskManager.ExecutionSummary,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    menuEnabled: Boolean,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(execution.conversation.id) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        val statusText = when (execution.status) {
            MessageStatus.SUCCESS -> stringResource(R.string.task_status_success)
            MessageStatus.ERROR -> stringResource(R.string.task_status_failed)
            MessageStatus.SENDING, MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING -> stringResource(R.string.task_running)
            MessageStatus.STOPPED -> stringResource(R.string.task_status_stopped)
            else -> stringResource(R.string.task_status_unknown)
        }
        val formattedTime = remember(execution.timestamp) {
            if (execution.timestamp == 0L) "" else formatDateTime(execution.timestamp)
        }
        SettingsItem(
            headlineContent = {
                Text(
                    text = execution.conversation.title.ifBlank {
                        execution.preview.ifBlank { statusText }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = listOf(statusText, formattedTime)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = when (execution.status) {
                            MessageStatus.ERROR -> MaterialTheme.colorScheme.error
                            MessageStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (execution.preview.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = execution.preview,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            trailingContent = {
                Box {
                    IconButton(
                        enabled = menuEnabled,
                        onClick = { menuOpen = true },
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.options),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 16.dp,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    enabledModels: List<String>,
    modelAliases: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_model), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ChoiceRow(
                        label = stringResource(R.string.task_model_default),
                        sub = null,
                        selected = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(enabledModels, key = { it }) { model ->
                    val parsed = ModelId.parse(model)
                    ChoiceRow(
                        label = modelAliases[model] ?: parsed.apiModelName,
                        sub = parsed.providerName,
                        selected = selected == model,
                        onClick = { onSelect(model) },
                    )
                }
            }
        },
        // Close, not Cancel: a tap applies immediately, so there is nothing to cancel.
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) } },
    )
}

/** The app's standard selection row (Settings model/prompt dialogs): a [SettingsItem] whose
 *  leading slot is the radio, with the selected label in bold. Shared by both Task pickers so
 *  they are indistinguishable from every other picker in the app. */
@Composable
private fun ChoiceRow(label: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    SettingsItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        },
        supportingContent = sub?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
    )
}
