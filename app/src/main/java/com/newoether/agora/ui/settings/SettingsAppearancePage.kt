package com.newoether.agora.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.SchemeStyle
import com.newoether.agora.ui.theme.colorSchemeForPreset
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearancePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.settings.themeMode.collectAsState()
    val colorSchemeName by viewModel.settings.colorScheme.collectAsState()
    val schemeStyleName by viewModel.settings.schemeStyle.collectAsState()
    val dynamicColor by viewModel.settings.dynamicColor.collectAsState()
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val currentPreset = try { ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { ColorSchemePreset.MIDNIGHT }
    val currentStyle = try { SchemeStyle.valueOf(schemeStyleName) } catch (_: Exception) { SchemeStyle.TONAL_SPOT }
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemDark
    }
    CollapsingSettingsScaffold(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("appearance.md") }
    ) {
            // ── Theme Mode ──
            SettingsGroup(
                title = stringResource(R.string.theme_mode),
                items = listOf(
                    {
                        ThemeModeOption(
                            label = stringResource(R.string.theme_mode_light),
                            icon = Icons.Default.LightMode,
                            selected = themeMode == "LIGHT",
                            onClick = { viewModel.settings.setThemeMode("LIGHT") }
                        )
                    },
                    {
                        ThemeModeOption(
                            label = stringResource(R.string.theme_mode_dark),
                            icon = Icons.Default.DarkMode,
                            selected = themeMode == "DARK",
                            onClick = { viewModel.settings.setThemeMode("DARK") }
                        )
                    },
                    {
                        ThemeModeOption(
                            label = stringResource(R.string.theme_mode_follow_device),
                            icon = Icons.Default.SettingsBrightness,
                            selected = themeMode != "LIGHT" && themeMode != "DARK",
                            onClick = { viewModel.settings.setThemeMode("FOLLOW_DEVICE") }
                        )
                    }
                )
            )

            // ── Interface ──
            SettingsGroup(
                title = stringResource(R.string.appearance_interface),
                items = buildList {
                    if (isDynamicAvailable) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                                supportingContent = { Text(stringResource(R.string.dynamic_color_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = dynamicColor,
                                        onCheckedChange = { viewModel.settings.setDynamicColor(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setDynamicColor(!dynamicColor) }
                            )
                        }
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.blur_effects)) },
                            supportingContent = { Text(stringResource(R.string.blur_effects_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = blurEffectsEnabled,
                                    onCheckedChange = { viewModel.settings.setBlurEffectsEnabled(it) }
                                )
                            },
                            modifier = Modifier.clickable { viewModel.settings.setBlurEffectsEnabled(!blurEffectsEnabled) }
                        )
                    }
                    add {
                        var expanded by remember { mutableStateOf(false) }
                        val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                        val selectedLabel = when (normalizedToolCallDisplayMode) {
                            ToolCallDisplayModes.GROUPED_TIMELINE -> stringResource(R.string.tool_call_display_mode_grouped_timeline)
                            ToolCallDisplayModes.COMPACT -> stringResource(R.string.tool_call_display_mode_compact)
                            else -> stringResource(R.string.tool_call_display_mode_timeline)
                        }
                        val selectedDescription = when (normalizedToolCallDisplayMode) {
                            ToolCallDisplayModes.GROUPED_TIMELINE -> stringResource(R.string.tool_call_display_mode_grouped_timeline_desc)
                            ToolCallDisplayModes.COMPACT -> stringResource(R.string.tool_call_display_mode_compact_desc)
                            else -> stringResource(R.string.tool_call_display_mode_timeline_desc)
                        }
                        val options = listOf(
                            ToolCallDisplayModes.TIMELINE to stringResource(R.string.tool_call_display_mode_timeline),
                            ToolCallDisplayModes.GROUPED_TIMELINE to stringResource(R.string.tool_call_display_mode_grouped_timeline),
                            ToolCallDisplayModes.COMPACT to stringResource(R.string.tool_call_display_mode_compact)
                        )
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.tool_call_display_mode)) },
                            supportingContent = { Text(selectedDescription) },
                            trailingContent = {
                                Box {
                                    Text(
                                        selectedLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(96.dp),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    DropdownMenu(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        tonalElevation = 16.dp,
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        options.forEach { (mode, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = {
                                                    if (normalizedToolCallDisplayMode == mode) {
                                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.settings.setToolCallDisplayMode(mode)
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { expanded = true }
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.haptic_feedback)) },
                            supportingContent = { Text(stringResource(R.string.haptic_feedback_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = hapticsEnabled,
                                    onCheckedChange = { viewModel.settings.setHapticsEnabled(it) }
                                )
                            },
                            modifier = Modifier.clickable { viewModel.settings.setHapticsEnabled(!hapticsEnabled) }
                        )
                    }
                }
            )

            val schemeAlpha = if (dynamicColor && isDynamicAvailable) 0.38f else 1f
            SettingsGroup(
                title = stringResource(R.string.color_scheme),
                items = ColorSchemePreset.entries.map { preset ->
                    {
                        val presetPrimary = remember(preset, currentStyle, isDark) {
                            colorSchemeForPreset(preset, currentStyle, isDark).primary
                        }
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    text = presetDisplayName(preset),
                                    fontWeight = if (preset == currentPreset) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = preset == currentPreset,
                                    onClick = { viewModel.settings.setColorScheme(preset.name) },
                                    enabled = !dynamicColor || !isDynamicAvailable
                                )
                            },
                            trailingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(presetPrimary)
                                )
                            },
                            modifier = Modifier
                                .alpha(schemeAlpha)
                                .clickable(enabled = schemeAlpha > 0.5f) { viewModel.settings.setColorScheme(preset.name) },
                            leadingSpacing = 8.dp
                        )
                    }
                }
            )

            // ── Scheme Style ──
            SettingsGroup(
                title = stringResource(R.string.scheme_style),
                items = SchemeStyle.entries.map { style ->
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    text = styleDisplayName(style),
                                    fontWeight = if (style == currentStyle) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = style == currentStyle,
                                    onClick = { viewModel.settings.setSchemeStyle(style.name) },
                                    enabled = !dynamicColor || !isDynamicAvailable
                                )
                            },
                            modifier = Modifier
                                .alpha(schemeAlpha)
                                .clickable(enabled = schemeAlpha > 0.5f) { viewModel.settings.setSchemeStyle(style.name) },
                            leadingSpacing = 8.dp
                        )
                    }
                }
            )

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ThemeModeOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    SettingsItem(
        headlineContent = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        leadingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        trailingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable { onClick() },
        leadingSpacing = 8.dp
    )
}

@Composable
private fun presetDisplayName(preset: ColorSchemePreset): String = when (preset) {
    ColorSchemePreset.MIDNIGHT -> stringResource(R.string.color_scheme_midnight)
    ColorSchemePreset.NORDIC -> stringResource(R.string.color_scheme_nordic)
    ColorSchemePreset.FOREST -> stringResource(R.string.color_scheme_forest)
    ColorSchemePreset.SUNSET -> stringResource(R.string.color_scheme_sunset)
    ColorSchemePreset.ROSE -> stringResource(R.string.color_scheme_rose)
    ColorSchemePreset.LAVENDER -> stringResource(R.string.color_scheme_lavender)
    ColorSchemePreset.SLATE -> stringResource(R.string.color_scheme_slate)
    ColorSchemePreset.OCEAN -> stringResource(R.string.color_scheme_ocean)
}

@Composable
private fun styleDisplayName(style: SchemeStyle): String = when (style) {
    SchemeStyle.TONAL_SPOT -> stringResource(R.string.scheme_style_tonal_spot)
    SchemeStyle.EXPRESSIVE -> stringResource(R.string.scheme_style_expressive)
    SchemeStyle.VIBRANT -> stringResource(R.string.scheme_style_vibrant)
    SchemeStyle.NEUTRAL -> stringResource(R.string.scheme_style_neutral)
}
