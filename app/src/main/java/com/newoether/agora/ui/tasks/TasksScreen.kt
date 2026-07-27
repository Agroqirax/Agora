package com.newoether.agora.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import com.newoether.agora.ui.settings.GuardedAnimatedContent
import com.newoether.agora.ui.settings.SettingsGroup
import com.newoether.agora.ui.settings.SettingsItem
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import java.util.Locale
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
    onOpenConversation: (String) -> Unit,
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
        actions = {
            IconButton(onClick = onNewTask) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.task_new))
            }
        }
    ) {
        if (tasks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.task_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                val executions by viewModel.executionSummariesForTask(task.id)
                    .collectAsState(initial = emptyList())
                TaskCard(
                    task = task,
                    isRunning = task.id in running,
                    lastRunAt = executions.firstOrNull()?.timestamp?.takeIf { it > 0L },
                    shape = stackedShape(index, tasks.size),
                    onClick = { onOpenTask(task) },
                    onRun = { viewModel.runTaskNow(task) },
                    onToggleEnabled = { enabled -> viewModel.saveTask(task.copy(enabled = enabled)) },
                    onDelete = { pendingDelete = task },
                )
                if (index < tasks.lastIndex) Spacer(Modifier.height(STACK_GAP))
            }
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
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
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
    onOpenConversation: (String) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()

    var name by rememberSaveable(task.id) { mutableStateOf(task.name) }
    var prompt by rememberSaveable(task.id) { mutableStateOf(task.prompt) }
    var modelId by rememberSaveable(task.id) { mutableStateOf(task.modelId) }
    var cronExpr by rememberSaveable(task.id) { mutableStateOf(task.cronExpr) }
    var runAt by rememberSaveable(task.id) { mutableStateOf(task.runAt) }
    var enabled by rememberSaveable(task.id) { mutableStateOf(task.enabled) }
    var showModelPicker by remember { mutableStateOf(false) }

    val isRunning = task.id in running
    val executions by viewModel.executionSummariesForTask(task.id).collectAsState(initial = emptyList())

    val cronValid = cronExpr.isBlank() || CronExpression.isValid(cronExpr)
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

    CollapsingSettingsLazyScaffold(
        title = name.ifBlank { stringResource(if (isNew) R.string.task_new else R.string.task_edit) },
        onBack = { leave() },
        actions = {
            IconButton(enabled = isComplete && !saved, onClick = { save() }) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.task_save))
            }
        },
        floatingActionButton = {
            // Run is the page's primary action, so it gets the FAB; Save is the confirming
            // secondary action in the bar.
            FloatingActionButton(
                onClick = {
                    if (!isComplete || isRunning) return@FloatingActionButton
                    viewModel.saveTask(current())
                    viewModel.runTaskNow(current())
                },
                containerColor = if (isComplete && !isRunning) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isComplete && !isRunning) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.task_run_now))
                }
            }
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
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                    )
                }
            }
        } else {
            itemsIndexed(executions, key = { _, e -> e.conversation.id }) { index, execution ->
                ExecutionRow(
                    execution = execution,
                    shape = stackedShape(index, executions.size),
                    onClick = { onOpenConversation(execution.conversation.id) },
                )
                if (index < executions.lastIndex) Spacer(Modifier.height(STACK_GAP))
            }
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
}

/** A group row whose value is typed in place — label on top, field below (the same shape the
 *  provider detail page uses for Base URL), so text entry doesn't break the card rhythm. */
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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

/** Short weekday names in the user's locale, indexed 0=Sunday..6=Saturday to match cron. */
@Composable
private fun weekdayNames(): List<String> {
    val locale = Locale.getDefault()
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
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val parsedSchedule = remember(cronExpr, runAt) { TaskSchedule.parse(cronExpr, runAt) }
    val cronOnlyValid = cronExpr.isNotBlank() && CronExpression.isValid(cronExpr)
    // Unmappable but valid cron: keep it, don't rewrite it behind the user's back.
    val isCustomCron = parsedSchedule == null && cronOnlyValid
    val schedule = parsedSchedule ?: TaskSchedule.default()

    var showRepeatMenu by remember { mutableStateOf(false) }
    var showWeekdayDialog by remember { mutableStateOf(false) }
    var showDayOfMonthDialog by remember { mutableStateOf(false) }

    fun apply(next: TaskSchedule) = onScheduleChange(next.toCron(), next.toRunAt())

    val armable = cronExpr.isNotBlank() || (runAt != null && runAt > 0L)
    val oncePast = schedule.type == ScheduleType.ONCE &&
        (runAt ?: 0L) in 1 until System.currentTimeMillis()

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
                            Text(
                                if (isCustomCron) stringResource(R.string.task_schedule_custom)
                                else repeatLabel(schedule.type)
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = showRepeatMenu,
                        onDismissRequest = { showRepeatMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        ScheduleType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(repeatLabel(type)) },
                                trailingIcon = {
                                    if (!isCustomCron && schedule.type == type) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    showRepeatMenu = false
                                    apply(schedule.switchedTo(type))
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
                                ScheduleType.YEARLY, ScheduleType.ONCE ->
                                    showDatePicker(context, schedule) { apply(it) }
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
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            // ── At ──
            if (!isCustomCron) {
                add {
                    SettingsItem(
                        modifier = Modifier.clickable { showTimePicker(context, schedule) { apply(it) } },
                        headlineContent = { Text(stringResource(R.string.task_at)) },
                        supportingContent = { Text(formatTimeOfDay(schedule.hour, schedule.minute)) },
                        leadingContent = {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    modifier = Modifier.clickable(enabled = armable) { onEnabledChange(!enabled) },
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
                            enabled = armable,
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
}

/** Native date picker. YEARLY ignores the picked year (cron has no year field); ONCE keeps it. */
private fun showDatePicker(
    context: android.content.Context,
    schedule: TaskSchedule,
    onPicked: (TaskSchedule) -> Unit,
) {
    val cal = Calendar.getInstance().apply {
        if (schedule.type == ScheduleType.ONCE && schedule.onceAtMillis > 0L) {
            timeInMillis = schedule.onceAtMillis
        } else {
            set(Calendar.MONTH, schedule.month - 1)
            set(Calendar.DAY_OF_MONTH, schedule.dayOfMonth)
        }
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            val next = schedule.copy(dayOfMonth = day, month = month + 1)
            onPicked(
                if (schedule.type == ScheduleType.ONCE) next.withOnceAt(year, month + 1, day)
                else next
            )
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    ).apply {
        // A one-shot in the past can never fire.
        if (schedule.type == ScheduleType.ONCE) {
            datePicker.minDate = System.currentTimeMillis() - 60_000L
        }
    }.show()
}

private fun showTimePicker(
    context: android.content.Context,
    schedule: TaskSchedule,
    onPicked: (TaskSchedule) -> Unit,
) {
    android.app.TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(schedule.withTime(hour, minute)) },
        schedule.hour,
        schedule.minute,
        android.text.format.DateFormat.is24HourFormat(context),
    ).show()
}

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
) {
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
                    text = listOf(statusText, formattedTime).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (execution.status) {
                        MessageStatus.ERROR -> MaterialTheme.colorScheme.error
                        MessageStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = if (execution.preview.isNotBlank()) {
                {
                    Text(
                        text = execution.preview,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else null,
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
