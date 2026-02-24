package com.newoether.agora.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val provider by viewModel.provider.collectAsState()
    val apiKeys by viewModel.apiKeys.collectAsState()
    val activeApiKeyId by viewModel.activeApiKeyId.collectAsState()
    val systemPrompts by viewModel.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.activeSystemPromptId.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val modelAliases by viewModel.modelAliases.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val maxContextWindow by viewModel.maxContextWindow.collectAsState()
    val visualizeContextRollout by viewModel.visualizeContextRollout.collectAsState()

    val noOpResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showPromptDialog by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeletePromptConfirm by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showModelAliasDialog by remember { mutableStateOf<String?>(null) }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("General", "Model Selection")
    
    var expandedProvider by remember { mutableStateOf(false) }
    val providers = listOf("Google", "OpenAI (Soon)", "Anthropic (Soon)")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
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
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (selectedTab == 0) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Provider Selection
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("AI Providers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        ExposedDropdownMenuBox(expanded = expandedProvider, onExpandedChange = { expandedProvider = it }) {
                            TextField(
                                value = provider, onValueChange = {}, readOnly = true,
                                label = { Text("Select Provider") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProvider) },
                                modifier = Modifier.fillMaxWidth().menuAnchor().clip(MaterialTheme.shapes.medium),
                                shape = MaterialTheme.shapes.medium,
                                colors = ExposedDropdownMenuDefaults.textFieldColors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                            )
                            ExposedDropdownMenu(expanded = expandedProvider, onDismissRequest = { expandedProvider = false }) {
                                providers.forEach { p ->
                                    DropdownMenuItem(text = { Text(p) }, onClick = { viewModel.setProvider(p); expandedProvider = false }, enabled = p == "Google")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // API Key Management
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(), 
                            horizontalArrangement = Arrangement.SpaceBetween, 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("API Keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                                TextButton(
                                    onClick = { showKeyDialog = ApiKeyEntry(name = "", key = "") },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        
                        if (apiKeys.isEmpty()) {
                            Text("No API keys added yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            apiKeys.forEach { entry ->
                                var showMenu by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { viewModel.setActiveApiKey(entry.id) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = entry.id == activeApiKeyId, onClick = { viewModel.setActiveApiKey(entry.id) })
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(entry.key.take(4) + "****" + entry.key.takeLast(4), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(20.dp))
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Edit") },
                                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                                onClick = { showMenu = false; showKeyDialog = entry }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = { showMenu = false; showDeleteKeyConfirm = entry }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // System Prompt Management
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(), 
                            horizontalArrangement = Arrangement.SpaceBetween, 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("System Instructions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                                TextButton(
                                    onClick = { showPromptDialog = SystemPromptEntry(title = "", content = "") },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        
                        if (systemPrompts.isEmpty()) {
                            Text("No custom prompts added.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            systemPrompts.forEach { entry ->
                                var showMenu by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { viewModel.setActiveSystemPrompt(entry.id) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = entry.id == activeSystemPromptId, onClick = { viewModel.setActiveSystemPrompt(entry.id) })
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(entry.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(20.dp))
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Edit") },
                                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                                onClick = { showMenu = false; showPromptDialog = entry }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = { showMenu = false; showDeletePromptConfirm = entry }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Context Window Management
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Context Window", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Maximum number of recent messages to include in context ($maxContextWindow messages)", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Slider(
                            value = maxContextWindow.toFloat(),
                            onValueChange = { viewModel.setMaxContextWindow(it.toInt()) },
                            valueRange = 5f..100f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setVisualizeContextRollout(!visualizeContextRollout) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Visualize Context Roll-Out", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text("Highlight messages that are currently within the context window.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = visualizeContextRollout, onCheckedChange = { viewModel.setVisualizeContextRollout(it) })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Model Management (Enabled/Disabled)
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(), 
                            horizontalArrangement = Arrangement.SpaceBetween, 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Model Visibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                                IconButton(
                                    onClick = { viewModel.fetchAvailableModels() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                        
                        if (availableModels.isEmpty()) {
                            Text("Fetch models first.", color = MaterialTheme.colorScheme.error)
                        } else {
                            availableModels.forEach { model ->
                                val isEnabled = enabledModels.contains(model)
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.setEnabledModels(if (isEnabled) enabledModels - model else enabledModels + model)
                                    }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = isEnabled, onCheckedChange = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(model.removePrefix("models/"), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else {
            // Model Selection Tab
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Active Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        if (enabledModels.isEmpty()) {
                            Text("No models enabled. Enable models in the General tab.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        } else {
                            enabledModels.forEach { model ->
                                val alias = modelAliases[model]
                                val displayName = alias ?: model.removePrefix("models/")
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setSelectedModel(model) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = model == selectedModel,
                                        onClick = { viewModel.setSelectedModel(model) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (alias != null) {
                                            Text(
                                                model.removePrefix("models/"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showModelAliasDialog = model }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Rename",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Model Alias Dialog
    showModelAliasDialog?.let { model ->
        val aliasState = rememberTextFieldState(modelAliases[model] ?: "")
        val aliasScrollState = rememberScrollState()
        
        AlertDialog(
            onDismissRequest = { showModelAliasDialog = null },
            title = { Text("Rename Model") },
            text = {
                Column {
                    Text("Current ID: ${model.removePrefix("models/")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        TextField(
                            state = aliasState,
                            scrollState = aliasScrollState,
                            label = { Text("Alias") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            placeholder = { Text(model.removePrefix("models/")) },
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateModelAlias(model, aliasState.text.toString())
                    showModelAliasDialog = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showModelAliasDialog = null }) { Text("Cancel") }
            }
        )
    }

    // API Key Dialog (Add/Edit)
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        val keyState = rememberTextFieldState(entry.key)
        val keyScrollState = rememberScrollState()
        val isEdit = apiKeys.any { it.id == entry.id }
        
        AlertDialog(
            onDismissRequest = { showKeyDialog = null },
            title = { Text(if (isEdit) "Edit API Key" else "Add API Key") },
            text = {
                Column {
                    TextField(
                        value = name, onValueChange = { name = it }, 
                        label = { Text("Name (e.g. Workspace)") }, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewResponder(noOpResponder),
                        shape = MaterialTheme.shapes.large,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        TextField(
                            state = keyState,
                            scrollState = keyScrollState,
                            label = { Text("Google API Key") }, 
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { 
                    val key = keyState.text.toString()
                    if (name.isNotBlank() && key.isNotBlank()) {
                        if (isEdit) viewModel.updateApiKey(entry.id, name, key) else viewModel.addApiKey(name, key)
                        showKeyDialog = null 
                    } 
                }) { Text(if (isEdit) "Save" else "Add") } 
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text("Cancel") } }
        )
    }

    // System Prompt Dialog (Add/Edit)
    showPromptDialog?.let { entry ->
        var title by remember { mutableStateOf(entry.title) }
        val contentState = rememberTextFieldState(entry.content)
        val contentScrollState = rememberScrollState()
        val isEdit = systemPrompts.any { it.id == entry.id }

        AlertDialog(
            onDismissRequest = { showPromptDialog = null },
            title = { Text(if (isEdit) "Edit System Prompt" else "Add System Prompt") },
            text = {
                Column {
                    TextField(
                        value = title, onValueChange = { title = it }, 
                        label = { Text("Title (e.g. Translator)") }, 
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewResponder(noOpResponder),
                        shape = MaterialTheme.shapes.large,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.bringIntoViewResponder(noOpResponder)) {
                        TextField(
                            state = contentState,
                            scrollState = contentScrollState,
                            label = { Text("System Instruction") }, 
                            modifier = Modifier.fillMaxWidth(),
                            lineLimits = TextFieldLineLimits.MultiLine(1, 10),
                            shape = MaterialTheme.shapes.large,
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { 
                    val content = contentState.text.toString()
                    if (title.isNotBlank() && content.isNotBlank()) {
                        if (isEdit) viewModel.updateSystemPrompt(entry.id, title, content) else viewModel.addSystemPrompt(title, content)
                        showPromptDialog = null 
                    } 
                }) { Text(if (isEdit) "Save" else "Add") } 
            },
            dismissButton = { TextButton(onClick = { showPromptDialog = null }) { Text("Cancel") } }
        )
    }

    // API Key Delete Confirmation
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteKeyConfirm = null },
            title = { Text("Delete API Key?") },
            text = { Text("Are you sure you want to delete '${entry.name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteApiKey(entry.id)
                        showDeleteKeyConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text("Cancel") } }
        )
    }

    // System Prompt Delete Confirmation
    showDeletePromptConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeletePromptConfirm = null },
            title = { Text("Delete System Instruction?") },
            text = { Text("Are you sure you want to delete '${entry.title}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSystemPrompt(entry.id)
                        showDeletePromptConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeletePromptConfirm = null }) { Text("Cancel") } }
        )
    }
}
