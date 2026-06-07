package com.newoether.agora.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGenerationPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val maxContextWindow by viewModel.maxContextWindow.collectAsState()
    val visualizeContextRollout by viewModel.visualizeContextRollout.collectAsState()
    val defaultTemperature by viewModel.defaultTemperature.collectAsState()
    val defaultMaxTokens by viewModel.defaultMaxTokens.collectAsState()
    val defaultTopP by viewModel.defaultTopP.collectAsState()
    val defaultFrequencyPenalty by viewModel.defaultFrequencyPenalty.collectAsState()
    val defaultPresencePenalty by viewModel.defaultPresencePenalty.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val thinkingLevel by viewModel.thinkingLevel.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.generation_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            // ── Section 1: Default Context Window ──
            SettingsGroup(
                title = stringResource(R.string.context_window_default),
                items = listOf(
                    {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.context_window),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.context_retain, maxContextWindow),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Slider(
                                        value = maxContextWindow.toFloat(),
                                        onValueChange = { viewModel.setMaxContextWindow(it.toInt()) },
                                        valueRange = 5f..100f,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_visualize)) },
                            supportingContent = { Text(stringResource(R.string.context_visualize_desc)) },
                            leadingContent = {
                                Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(checked = visualizeContextRollout, onCheckedChange = { viewModel.setVisualizeContextRollout(it) })
                            },
                            modifier = Modifier.clickable { viewModel.setVisualizeContextRollout(!visualizeContextRollout) }
                        )
                    }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 2: Default Thinking ──
            val thinkingLevels = listOf("low", "medium", "high")
            val thinkingLevelLabels = listOf(
                stringResource(R.string.gen_thinking_level_low),
                stringResource(R.string.gen_thinking_level_medium),
                stringResource(R.string.gen_thinking_level_high)
            )
            SettingsGroup(
                title = stringResource(R.string.default_thinking),
                items = listOf(
                    {
                        val icon = @Composable { Icon(painterResource(id = R.drawable.neurology_24), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.gen_thinking_enabled)) },
                            supportingContent = { Text(stringResource(R.string.gen_thinking_enabled_desc)) },
                            leadingContent = icon,
                            trailingContent = {
                                Switch(checked = thinkingEnabled, onCheckedChange = { viewModel.setThinkingEnabled(it) })
                            },
                            modifier = Modifier.clickable { viewModel.setThinkingEnabled(!thinkingEnabled) }
                        )
                    },
                    {
                        val icon = @Composable { Icon(painterResource(id = R.drawable.neurology_24), contentDescription = null, tint = if (thinkingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                icon()
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.gen_thinking_level),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (thinkingEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                    Text(
                                        text = stringResource(R.string.gen_thinking_level_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (thinkingEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        thinkingLevels.forEachIndexed { index, level ->
                                            val selected = thinkingLevel == level && thinkingEnabled
                                            val primary = MaterialTheme.colorScheme.primary
                                            val surface = MaterialTheme.colorScheme.surfaceContainerHigh
                                            val disabledBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            val startColor by animateColorAsState(
                                                when {
                                                    selected -> primary
                                                    thinkingEnabled -> surface
                                                    else -> disabledBg
                                                },
                                                tween(300)
                                            )
                                            val endColor by animateColorAsState(
                                                when {
                                                    selected -> primary
                                                    thinkingEnabled -> surface
                                                    else -> disabledBg
                                                },
                                                tween(300)
                                            )
                                            val textColor = when {
                                                selected -> MaterialTheme.colorScheme.onPrimary
                                                thinkingEnabled -> MaterialTheme.colorScheme.onSurface
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(50))
                                                    .background(Brush.horizontalGradient(listOf(startColor, endColor)))
                                                    .clickable(enabled = thinkingEnabled) { viewModel.setThinkingLevel(level) }
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    thinkingLevelLabels[index],
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                    color = textColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Section 3: Generation Parameters ──
            SettingsGroup(
                title = stringResource(R.string.generation_params),
                items = listOf(
                    {
                        GenParamSlider(
                            label = stringResource(R.string.gen_temperature),
                            desc = stringResource(R.string.gen_temperature_desc),
                            value = defaultTemperature,
                            valueRange = 0f..2f,
                            format = { v -> String.format(Locale.US, "%.2f", v) },
                            onValueChange = { viewModel.setDefaultTemperature(it) },
                            onReset = { viewModel.setDefaultTemperature(null) }
                        )
                    },
                    {
                        val maxTokensPresets = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768)
                        GenParamSlider(
                            label = stringResource(R.string.gen_max_tokens),
                            desc = stringResource(R.string.gen_max_tokens_desc),
                            value = defaultMaxTokens,
                            presets = maxTokensPresets,
                            format = { it.toString() },
                            onValueChange = { viewModel.setDefaultMaxTokens(it) },
                            onReset = { viewModel.setDefaultMaxTokens(null) }
                        )
                    },
                    {
                        GenParamSlider(
                            label = stringResource(R.string.gen_top_p),
                            desc = stringResource(R.string.gen_top_p_desc),
                            value = defaultTopP,
                            valueRange = 0f..1f,
                            format = { v -> String.format(Locale.US, "%.2f", v) },
                            onValueChange = { viewModel.setDefaultTopP(it) },
                            onReset = { viewModel.setDefaultTopP(null) }
                        )
                    },
                    {
                        GenParamSlider(
                            label = stringResource(R.string.gen_frequency_penalty),
                            desc = stringResource(R.string.gen_frequency_penalty_desc),
                            value = defaultFrequencyPenalty,
                            valueRange = -2f..2f,
                            format = { v -> String.format(Locale.US, "%.2f", v) },
                            onValueChange = { viewModel.setDefaultFrequencyPenalty(it) },
                            onReset = { viewModel.setDefaultFrequencyPenalty(null) }
                        )
                    },
                    {
                        GenParamSlider(
                            label = stringResource(R.string.gen_presence_penalty),
                            desc = stringResource(R.string.gen_presence_penalty_desc),
                            value = defaultPresencePenalty,
                            valueRange = -2f..2f,
                            format = { v -> String.format(Locale.US, "%.2f", v) },
                            onValueChange = { viewModel.setDefaultPresencePenalty(it) },
                            onReset = { viewModel.setDefaultPresencePenalty(null) }
                        )
                    }
                )
            )

        }
    }
}

/**
 * Generation parameter slider row.
 * Always shows the slider value. When at default, value is grey and "Default" text is shown beside it.
 * When set, value is primary-colored with a "Reset" link below the slider.
 */
@Composable
private fun GenParamSlider(
    label: String,
    desc: String,
    value: Float?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val isDefault = value == null
    val sliderPos = value ?: (valueRange.start + valueRange.endInclusive) / 2f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDefault) {
                        Text(
                            text = stringResource(R.string.gen_not_specified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Text(
                            text = format(sliderPos),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.gen_reset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.clickable(onClick = onReset)
                        )
                    }
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = sliderPos,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

/** Int slider variant with discrete preset values (used for max tokens). */
@Composable
private fun GenParamSlider(
    label: String,
    desc: String,
    value: Int?,
    presets: IntArray,
    format: (Int) -> String,
    onValueChange: (Int) -> Unit,
    onReset: () -> Unit
) {
    fun toIndex(v: Int) = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - v) } ?: 3
    val isDefault = value == null
    val index = if (value != null) toIndex(value) else 3 // default to 4096
    val sliderPos = index.toFloat()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDefault) {
                        Text(
                            text = stringResource(R.string.gen_not_specified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Text(
                            text = format(value!!),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.gen_reset),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.clickable(onClick = onReset)
                        )
                    }
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = sliderPos,
                    onValueChange = { onValueChange(presets[it.toInt().coerceIn(0, presets.lastIndex)]) },
                    valueRange = 0f..(presets.size - 1).toFloat(),
                    steps = presets.size - 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}
