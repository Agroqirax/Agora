package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

private data class SettingsCategory(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector
)

private data class SettingsGroupData(
    val items: List<SettingsCategory>
)

private val settingsGroups = listOf(
    SettingsGroupData(listOf(
        SettingsCategory("provider", R.string.settings_provider, R.string.settings_provider_desc, Icons.Default.Cloud),
        SettingsCategory("models", R.string.settings_models, R.string.settings_models_desc, Icons.Default.Chat),
    )),
    SettingsGroupData(listOf(
        SettingsCategory("prompts", R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.Psychology),
        SettingsCategory("context", R.string.settings_context, R.string.settings_context_desc, Icons.Default.Memory),
        SettingsCategory("titlegen", R.string.settings_title_gen, R.string.settings_title_gen_desc, Icons.Default.Edit),
    )),
    SettingsGroupData(listOf(
        SettingsCategory("websearch", R.string.settings_web_search, R.string.settings_web_search_desc, Icons.Default.Language),
        SettingsCategory("search", R.string.search_title, R.string.search_desc, Icons.Default.Search),
        SettingsCategory("shell", R.string.shell_title, R.string.shell_desc, Icons.Default.Terminal),
    )),
    SettingsGroupData(listOf(
        SettingsCategory("memory", R.string.settings_memory, R.string.settings_memory_desc, Icons.Default.Description),
        SettingsCategory("datacontrol", R.string.settings_data_control, R.string.settings_data_control_desc, Icons.Default.Storage),
        SettingsCategory("language", R.string.language_title, R.string.language_desc, Icons.Default.Translate),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val isSyncingModels by viewModel.isSyncingModels.collectAsState()
    val fetchingModelsMessage = stringResource(R.string.snackbar_fetching_models)

    LaunchedEffect(isSyncingModels) {
        if (isSyncingModels) {
            viewModel.emitSnackbar(fetchingModelsMessage)
        }
    }

    BackHandler {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedCategory,
            transitionSpec = {
                if (targetState == null) {
                    slideInHorizontally(initialOffsetX = { -it }) togetherWith slideOutHorizontally(targetOffsetX = { it })
                } else {
                    slideInHorizontally(initialOffsetX = { it }) togetherWith slideOutHorizontally(targetOffsetX = { -it })
                }
            }
        ) { category ->
            when (category) {
                "provider" -> SettingsProviderPage(viewModel, onBack = { selectedCategory = null })
                "prompts" -> SettingsPromptsPage(viewModel, onBack = { selectedCategory = null })
                "models" -> SettingsModelsPage(viewModel, onBack = { selectedCategory = null })
                "context" -> SettingsContextPage(viewModel, onBack = { selectedCategory = null })
                "websearch" -> SettingsWebSearchPage(viewModel, onBack = { selectedCategory = null })
                "shell" -> SettingsShellPage(viewModel, onBack = { selectedCategory = null })
                "language" -> SettingsLanguagePage(viewModel, onBack = { selectedCategory = null })
                "titlegen" -> SettingsTitleGenPage(viewModel, onBack = { selectedCategory = null })
                "search" -> SettingsSearchPage(viewModel, onBack = { selectedCategory = null })
                "memory" -> SettingsMemoryPage(viewModel, onBack = { selectedCategory = null })
                "datacontrol" -> SettingsDataControlPage(viewModel, onBack = { selectedCategory = null })
                else -> {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0.dp),
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
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
                        LazyColumn(
                            modifier = Modifier
                                .padding(padding)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp)
                                .padding(top = 12.dp)
                        ) {
                            items(settingsGroups.size) { groupIndex ->
                                val group = settingsGroups[groupIndex]
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    group.items.forEachIndexed { index, cat ->
                                        if (index > 0) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                        }
                                        val isFirst = index == 0
                                        val isLast = index == group.items.lastIndex
                                        val shape = when {
                                            group.items.size == 1 -> RoundedCornerShape(24.dp)
                                            isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 3.dp, bottomEnd = 3.dp)
                                            isLast -> RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                                            else -> RoundedCornerShape(3.dp)
                                        }
                                        Surface(
                                            shape = shape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(shape)
                                                .clickable { selectedCategory = cat.key }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    cat.icon,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(cat.titleRes),
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                    Text(
                                                        text = stringResource(cat.descriptionRes),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Icon(
                                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (groupIndex < settingsGroups.size - 1) {
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}
