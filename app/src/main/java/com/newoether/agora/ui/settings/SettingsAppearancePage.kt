package com.newoether.agora.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.colorSchemeForPreset
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearancePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.themeMode.collectAsState()
    val colorSchemeName by viewModel.colorScheme.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()

    val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val currentPreset = try { ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { ColorSchemePreset.DEFAULT }
    val isDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> false // system — use light for swatch preview
    }
    val schemePair = colorSchemeForPreset(currentPreset)
    val previewScheme = if (isDark) schemePair.dark else schemePair.light

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title), fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // ── Theme Mode ──
            SettingsGroup(title = stringResource(R.string.theme_mode)) {
                ThemeModeOption(
                    label = stringResource(R.string.theme_mode_light),
                    icon = Icons.Default.LightMode,
                    selected = themeMode == "LIGHT",
                    onClick = { viewModel.setThemeMode("LIGHT") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ThemeModeOption(
                    label = stringResource(R.string.theme_mode_dark),
                    icon = Icons.Default.DarkMode,
                    selected = themeMode == "DARK",
                    onClick = { viewModel.setThemeMode("DARK") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ThemeModeOption(
                    label = stringResource(R.string.theme_mode_follow_device),
                    icon = Icons.Default.SettingsBrightness,
                    selected = themeMode != "LIGHT" && themeMode != "DARK",
                    onClick = { viewModel.setThemeMode("FOLLOW_DEVICE") }
                )
            }

            // ── Color Scheme ──
            val schemeAlpha = if (dynamicColor && isDynamicAvailable) 0.38f else 1f
            SettingsGroup(title = stringResource(R.string.color_scheme)) {
                ColorSchemePreset.entries.forEachIndexed { index, preset ->
                    val presetPrimary = colorSchemeForPreset(preset).light.primary
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = presetDisplayName(preset),
                                fontWeight = if (preset == currentPreset) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        supportingContent = if (dynamicColor && isDynamicAvailable) {
                            { Text(stringResource(R.string.dynamic_color_overrides_scheme)) }
                        } else null,
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(presetPrimary)
                            )
                        },
                        trailingContent = {
                            RadioButton(
                                selected = preset == currentPreset,
                                onClick = { viewModel.setColorScheme(preset.name) }
                            )
                        },
                        modifier = Modifier
                            .alpha(schemeAlpha)
                            .clickable(enabled = schemeAlpha > 0.5f) { viewModel.setColorScheme(preset.name) }
                    )
                    if (preset != ColorSchemePreset.entries.last()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            // ── Dynamic Color ──
            if (isDynamicAvailable) {
                SettingsGroup(title = stringResource(R.string.dynamic_color)) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.dynamic_color_desc)) },
                        trailingContent = {
                            Switch(
                                checked = dynamicColor,
                                onCheckedChange = { viewModel.setDynamicColor(it) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun presetDisplayName(preset: ColorSchemePreset): String = when (preset) {
    ColorSchemePreset.DEFAULT -> stringResource(R.string.color_scheme_default)
    ColorSchemePreset.OCEAN -> stringResource(R.string.color_scheme_ocean)
    ColorSchemePreset.FOREST -> stringResource(R.string.color_scheme_forest)
    ColorSchemePreset.SUNSET -> stringResource(R.string.color_scheme_sunset)
    ColorSchemePreset.ROSE -> stringResource(R.string.color_scheme_rose)
    ColorSchemePreset.SLATE -> stringResource(R.string.color_scheme_slate)
}
