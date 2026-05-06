package com.newoether.agora.ui.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = title,
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
    val title: String,
    val description: String,
    val icon: ImageVector
)

private val categories = listOf(
    SettingsCategory("provider", "Provider", "API keys, base URL, and provider selection", Icons.Default.Cloud),
    SettingsCategory("prompts", "System Prompts", "Create and manage system instructions", Icons.Default.Psychology),
    SettingsCategory("models", "Models", "Enable, disable, and configure AI models", Icons.Default.Chat),
    SettingsCategory("context", "Context", "Context window size and visualization", Icons.Default.Memory),
    SettingsCategory("websearch", "Web Search", "Configure web search tool for all providers", Icons.Default.Language),
    SettingsCategory("titlegen", "Title Generation", "Auto-generate conversation titles", Icons.Default.Edit),
    SettingsCategory("memory", "Memory", "Access controls, active memory, and saved files", Icons.Default.Description)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val isSyncingModels by viewModel.isSyncingModels.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(isSyncingModels) {
        if (isSyncingModels) {
            snackbarHostState.showSnackbar(
                message = "Fetching available models...",
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
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
                "titlegen" -> SettingsTitleGenPage(viewModel, onBack = { selectedCategory = null })
                "memory" -> SettingsMemoryPage(viewModel, onBack = { selectedCategory = null })
                else -> {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            TopAppBar(
                                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                                navigationIcon = {
                                    IconButton(onClick = onBack) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            items(categories) { cat ->
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(cat.title, fontWeight = FontWeight.SemiBold) },
                                    supportingContent = { Text(cat.description, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    leadingContent = {
                                        Icon(cat.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    trailingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    },
                                    modifier = Modifier.clickable { selectedCategory = cat.key }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}
