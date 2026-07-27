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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.automation.CronExpression
import com.newoether.agora.data.local.TaskEntity
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
                // Armed → recurrence + live countdown. Not armed → "Manual only": the switch is
                // the single place that state is expressed, so the recurrence isn't shown as if
                // it were about to fire.
                val scheduleText = if (task.enabled && task.cronExpr.isNotBlank()) {
                    listOfNotNull(
                        scheduleLabelFor(task.cronExpr),
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
    var enabled by rememberSaveable(task.id) { mutableStateOf(task.enabled) }
    var showModelPicker by remember { mutableStateOf(false) }

    val isRunning = task.id in running
    val executions by viewModel.executionSummariesForTask(task.id).collectAsState(initial = emptyList())

    val cronValid = cronExpr.isBlank() || CronExpression.isValid(cronExpr)
    val isComplete = name.isNotBlank() && prompt.isNotBlank() && cronValid

    fun current() = task.copy(name = name.trim(), prompt = prompt, modelId = modelId, cronExpr = cronExpr, enabled = enabled)
    // Persist on the way out, unless this is an untouched new draft (nothing meaningful entered).
    fun leave() {
        if (isComplete) viewModel.saveTask(current())
        onBack()
    }

    BackHandler { leave() }

    CollapsingSettingsLazyScaffold(
        title = name.ifBlank { stringResource(if (isNew) R.string.task_new else R.string.task_edit) },
        onBack = { leave() },
        actions = {
            IconButton(
                enabled = isComplete && !isRunning,
                onClick = {
                    viewModel.saveTask(current())
                    viewModel.runTaskNow(current())
                }
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.task_run_now))
                }
            }
        }
    ) {
        item {
            SettingsGroup(
                title = stringResource(R.string.task_section_details),
                items = listOf(
                    {
                        LabeledField(
                            label = stringResource(R.string.task_name),
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.task_name_hint),
                            singleLine = true,
                        )
                    },
                    {
                        LabeledField(
                            label = stringResource(R.string.task_prompt),
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
                onCronChange = { cronExpr = it },
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
    isError: Boolean = false,
    supporting: String? = null,
    supportingIsError: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
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
}

/**
 * Recurrence presets. Deliberately NO "manual only" entry: manual is the absence of an armed
 * schedule, expressed once by the [R.string.task_enabled] switch. Having both a "Manual only"
 * preset and an off switch meant two controls for one state.
 */
private val SCHEDULE_PRESETS: List<Pair<String, Int>> = listOf(
    "0 * * * *" to R.string.task_schedule_hourly,
    "0 9 * * *" to R.string.task_schedule_daily,
    "0 9 * * 1" to R.string.task_schedule_weekly,
)

/** Human-readable recurrence: preset name, raw cron, or "Not set". */
@Composable
private fun scheduleLabelFor(cronExpr: String): String {
    if (cronExpr.isBlank()) return stringResource(R.string.task_schedule_not_set)
    val preset = SCHEDULE_PRESETS.firstOrNull { it.first == cronExpr }
    return if (preset != null) stringResource(preset.second) else cronExpr
}

private fun formatDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(millis))

/**
 * Schedule group: WHEN it recurs (a picker row) and WHETHER that recurrence is armed (a switch).
 * The two answer different questions, so neither restates the other — and "manual only" is simply
 * the switch being off. Custom cron reveals its field inline under the picker row, with live
 * validation and the resolved next-run time.
 */
@Composable
private fun ScheduleGroup(
    cronExpr: String,
    onCronChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val presetValues = SCHEDULE_PRESETS.map { it.first }
    var customMode by rememberSaveable { mutableStateOf(cronExpr.isNotBlank() && cronExpr !in presetValues) }
    var showPicker by remember { mutableStateOf(false) }
    val parsed = remember(cronExpr) { CronExpression.parse(cronExpr) }
    val invalid = cronExpr.isNotBlank() && parsed == null
    val armable = cronExpr.isNotBlank() && !invalid

    SettingsGroup(
        title = stringResource(R.string.task_schedule),
        items = buildList {
            add {
                SettingsItem(
                    modifier = Modifier.clickable { showPicker = true },
                    headlineContent = { Text(stringResource(R.string.task_schedule)) },
                    supportingContent = {
                        Text(if (customMode) stringResource(R.string.task_schedule_custom) else scheduleLabelFor(cronExpr))
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
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
            if (customMode) {
                add {
                    LabeledField(
                        label = stringResource(R.string.task_schedule_custom),
                        value = cronExpr,
                        onValueChange = onCronChange,
                        placeholder = stringResource(R.string.task_cron_hint),
                        singleLine = true,
                        isError = invalid,
                        supporting = if (invalid) stringResource(R.string.task_cron_invalid) else null,
                        supportingIsError = invalid,
                    )
                }
            }
            add {
                val nextRun = remember(cronExpr, enabled) {
                    if (enabled && !invalid) parsed?.next(System.currentTimeMillis()) else null
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
                                nextRun != null -> stringResource(R.string.task_next_run, formatDateTime(nextRun))
                                else -> stringResource(R.string.task_enabled_desc)
                            }
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

    if (showPicker) {
        SchedulePickerDialog(
            cronExpr = cronExpr,
            customMode = customMode,
            onPreset = { value -> customMode = false; onCronChange(value); showPicker = false },
            onCustom = {
                customMode = true
                if (cronExpr in presetValues) onCronChange("")
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun SchedulePickerDialog(
    cronExpr: String,
    customMode: Boolean,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.task_schedule_pick), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                SCHEDULE_PRESETS.forEach { (value, labelRes) ->
                    ChoiceRow(
                        label = stringResource(labelRes),
                        sub = null,
                        selected = !customMode && cronExpr == value,
                        onClick = { onPreset(value) },
                    )
                }
                ChoiceRow(
                    label = stringResource(R.string.task_schedule_custom),
                    sub = stringResource(R.string.task_cron_hint),
                    selected = customMode,
                    onClick = onCustom,
                )
            }
        },
        // Close, not Cancel: a tap applies immediately, so there is nothing to cancel.
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
