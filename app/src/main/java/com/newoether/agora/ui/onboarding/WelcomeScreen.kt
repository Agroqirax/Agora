package com.newoether.agora.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.viewmodel.ChatViewModel
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class WelcomePage(
    val title: String,
    val description: String,
    val darkVideoResId: Int? = null,
    val lightVideoResName: String? = null
)

private fun resolveVideoRes(isDarkTheme: Boolean, darkResId: Int?, lightResName: String?, context: android.content.Context): Int? {
    if (isDarkTheme) return darkResId
    if (lightResName != null) {
        val id = context.resources.getIdentifier(lightResName, "raw", context.packageName)
        if (id != 0) return id
    }
    return darkResId
}

// Page indices
private const val PAGE_PROVIDER = 2
private const val PAGE_API_KEY = 3
private const val PAGE_MODEL_CONFIG = 5

@Composable
fun WelcomeScreen(
    onComplete: () -> Unit,
    isDarkTheme: Boolean = true,
    viewModel: ChatViewModel
) {
    val context = LocalContext.current

    // ── Onboarding state ──
    val builtInProviders = listOf("Google", "OpenAI", "Anthropic", "DeepSeek", "Qwen", "Ollama", "Open Router")
    val customProviders by viewModel.customProviders.collectAsState()
    val allProviders = builtInProviders + customProviders.map { it.name } + "Local"
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var apiKeyText by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    val availableModels by viewModel.availableModels.collectAsState()
    val localChatModels by viewModel.localChatModels.collectAsState()
    val existingApiKeys by viewModel.apiKeys.collectAsState()
    val existingProviderUrls by viewModel.providerBaseUrls.collectAsState()

    // Pre-fill API key / URL when switching to a configured provider
    LaunchedEffect(selectedProvider) {
        val p = selectedProvider ?: return@LaunchedEffect
        when (p) {
            "Ollama" -> {
                val url = existingProviderUrls["Ollama"]
                if (!url.isNullOrBlank()) apiKeyText = url
            }
            "Local" -> { /* no pre-fill */ }
            else -> {
                val key = existingApiKeys.find { it.provider == p }?.key
                if (!key.isNullOrBlank()) apiKeyText = key
            }
        }
    }

    // ── GGUF import ──
    var showGgufError by remember { mutableStateOf(false) }
    var isImportingGGUF by remember { mutableStateOf(false) }
    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isImportingGGUF = true
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dest = File(context.filesDir, "chat_model_${UUID.randomUUID()}.gguf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    val magic = ByteArray(4); dest.inputStream().use { it.read(magic) }
                    if (magic[0] != 'G'.code.toByte() || magic[1] != 'G'.code.toByte()
                        || magic[2] != 'U'.code.toByte() || magic[3] != 'F'.code.toByte()
                    ) { dest.delete(); withContext(Dispatchers.Main) { showGgufError = true } }
                    else {
                        withContext(Dispatchers.Main) {
                            localChatModels.forEach { viewModel.deleteLocalChatModel(it.id) }
                            viewModel.addLocalChatModel(LocalChatModelConfig(
                                modelId = dest.nameWithoutExtension,
                                alias = dest.nameWithoutExtension,
                                localFilePath = dest.absolutePath
                            ))
                        }
                    }
                } catch (_: Exception) { withContext(Dispatchers.Main) { showGgufError = true } }
                finally { withContext(Dispatchers.Main) { isImportingGGUF = false } }
            }
        }
    }

    // ── Pages ──
    val pages = listOf(
        WelcomePage(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_desc),
            R.raw.welcome_video_1, "welcome_video_1_light"),
        WelcomePage(stringResource(R.string.onboarding_byok_title), stringResource(R.string.onboarding_byok_desc),
            R.raw.welcome_video_2, "welcome_video_2_light"),
        WelcomePage(stringResource(R.string.onboarding_provider_title), stringResource(R.string.onboarding_provider_desc)),
        WelcomePage(stringResource(R.string.onboarding_api_key_title), stringResource(R.string.onboarding_api_key_desc)),
        WelcomePage(stringResource(R.string.onboarding_model_video_title), stringResource(R.string.onboarding_model_video_desc),
            R.raw.welcome_video_3, "welcome_video_3_light"),
        WelcomePage(stringResource(R.string.onboarding_model_select_title), stringResource(R.string.onboarding_model_select_desc)),
        WelcomePage(stringResource(R.string.onboarding_done_title), stringResource(R.string.onboarding_done_desc),
            R.raw.welcome_video_4, "welcome_video_4_light")
    )

    // ── Video players (null for config pages) ──
    val players = remember {
        pages.map { page ->
            val resId = resolveVideoRes(isDarkTheme, page.darkVideoResId, page.lightVideoResName, context)
            resId?.let {
                val uri = "android.resource://${context.packageName}/$it"
                ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ALL; playWhenReady = false
                    setMediaItem(MediaItem.fromUri(uri)); prepare()
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { players.forEach { it?.release() } } }

    val visitedPages = remember { mutableSetOf<Int>() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var exiting by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(if (showContent) 1f else 0f, tween(600))

    val fm = LocalFocusManager.current
    LaunchedEffect(pagerState.currentPage) {
        fm.clearFocus()
        if (pagerState.currentPage !in visitedPages) {
            visitedPages.add(pagerState.currentPage)
            players[pagerState.currentPage]?.playWhenReady = true
        }
        if (pagerState.currentPage == 4 && selectedProvider != null && selectedProvider != "Local") {
            viewModel.fetchAvailableModels()
        }
    }

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); showContent = true }

    LaunchedEffect(exiting) { if (exiting) { kotlinx.coroutines.delay(300); onComplete() } }

    // GGUF error dialog
    if (showGgufError) AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { showGgufError = false },
        title = { Text(stringResource(R.string.onboarding_invalid_gguf_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.onboarding_invalid_gguf_desc)) },
        confirmButton = { TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) } }
    )

    val defocusInteractionSource = remember { MutableInteractionSource() }
    AnimatedVisibility(visible = !exiting, exit = fadeOut(tween(300))) {
        val fm = LocalFocusManager.current
        Box(modifier = Modifier.fillMaxSize().clickable(indication = null, interactionSource = defocusInteractionSource) { fm.clearFocus() }) {
            Column(modifier = Modifier.fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {

                // Skip button
                Box(Modifier.fillMaxWidth().padding(top = 48.dp, end = 16.dp).alpha(contentAlpha), contentAlignment = Alignment.CenterEnd) {
                    TextButton(
                        onClick = { if (pagerState.currentPage < pages.size - 1) exiting = true },
                        enabled = showContent && pagerState.currentPage < pages.size - 1,
                        modifier = Modifier.alpha(if (pagerState.currentPage < pages.size - 1) 1f else 0f)
                    ) { Text(stringResource(R.string.onboarding_skip)) }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), userScrollEnabled = showContent, beyondViewportPageCount = 1) { index ->
                    val pageOffset = (index - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                    val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)
                    Column(
                        Modifier.fillMaxSize().graphicsLayer { scaleX = 1f - absOffset * 0.12f; scaleY = 1f - absOffset * 0.12f; alpha = 1f - absOffset * 0.4f },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Main content area (video or config card)
                        Box(Modifier.fillMaxWidth().weight(1.8f), contentAlignment = Alignment.Center) {
                            when (index) {
                                PAGE_PROVIDER -> ProviderPage(
                                    providers = allProviders,
                                    selected = selectedProvider,
                                    onSelect = { selectedProvider = it; apiKeyText = "" },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha),
                                    configuredProviders = existingApiKeys.map { it.provider }.toSet() + existingProviderUrls.filter { it.value.isNotBlank() }.keys
                                )
                                PAGE_API_KEY -> ApiKeyPage(
                                    provider = selectedProvider,
                                    apiKeyText = apiKeyText,
                                    onApiKeyChange = { apiKeyText = it },
                                    apiKeyVisible = apiKeyVisible,
                                    onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
                                    isImporting = isImportingGGUF,
                                    onImportGGUF = { ggufPicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).alpha(contentAlpha),
                                    localModels = localChatModels
                                )
                                PAGE_MODEL_CONFIG -> {
                                    val pModels = if (selectedProvider != null) availableModels[selectedProvider] ?: emptyList() else emptyList()
                                    val lModels = localChatModels.map { "Local:${it.modelId}" }
                                    val models = if (selectedProvider == "Local") lModels else pModels
                                    LaunchedEffect(models) {
                                        if (selectedModelId == null && models.isNotEmpty()) {
                                            selectedModelId = models.first()
                                        }
                                    }
                                    ModelPage(
                                        models = models,
                                        selectedId = selectedModelId,
                                        onSelect = { selectedModelId = it },
                                        onFetch = { viewModel.fetchAvailableModels() },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).alpha(contentAlpha)
                                    )
                                }
                                else -> {
                                    Box(Modifier.fillMaxSize()) {
                                        players[index]?.let { LoopVideo(it) }
                                        if (index == 0) FirstVideoScrim(isDarkTheme)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Title + description
                        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).alpha(contentAlpha)) {
                            AnimatedContent(index, transitionSpec = { fadeIn() togetherWith fadeOut() }) { idx ->
                                val page = pages[idx]
                                val title = when {
                                    idx == PAGE_API_KEY && selectedProvider == "Local" -> stringResource(R.string.onboarding_gguf_title)
                                    idx == PAGE_API_KEY && selectedProvider == "Ollama" -> stringResource(R.string.onboarding_server_url_title)
                                    else -> page.title
                                }
                                val desc = when {
                                    idx == PAGE_API_KEY && selectedProvider == "Local" -> stringResource(R.string.onboarding_gguf_desc)
                                    idx == PAGE_API_KEY && selectedProvider == "Ollama" -> stringResource(R.string.onboarding_ollama_desc)
                                    idx == PAGE_API_KEY && selectedProvider != null -> stringResource(R.string.onboarding_api_key_for, selectedProvider!!)
                                    else -> page.description
                                }
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(Modifier.height(8.dp))
                                    Text(desc, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(Modifier.height(48.dp))
                    }
                }

                // Dot indicators
                Row(Modifier.padding(bottom = 16.dp).alpha(contentAlpha), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    repeat(pages.size) { idx ->
                        val sel = pagerState.currentPage == idx
                        val sz by animateDpAsState(if (sel) 10.dp else 8.dp, spring(0.7f, 400f))
                        val cl by animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, spring(0.7f, 400f))
                        Box(Modifier.padding(horizontal = 4.dp).size(sz).clip(CircleShape).background(cl))
                    }
                }

                // Continue / Get Started
                Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 48.dp).navigationBarsPadding().alpha(contentAlpha)) {
                    val last = pagerState.currentPage == pages.size - 1
                    Button(onClick = {
                        if (last) { exiting = true }
                        else {
                            when (pagerState.currentPage) {
                                PAGE_PROVIDER -> { if (selectedProvider != null && selectedProvider != "Local") apiKeyText = "" }
                                PAGE_API_KEY -> {
                                    if (selectedProvider != null && apiKeyText.isNotBlank()) {
                                        when (selectedProvider) {
                                            "Local" -> { /* handled by GGUF import */ }
                                            "Ollama" -> viewModel.setProviderBaseUrl("Ollama", apiKeyText)
                                            else -> viewModel.addApiKey(selectedProvider!!, apiKeyText, selectedProvider!!)
                                        }
                                    }
                                }
                                PAGE_MODEL_CONFIG -> {
                                    if (selectedModelId != null) {
                                        viewModel.setSelectedModel(selectedModelId!!)
                                        viewModel.setEnabledModels(setOf(selectedModelId!!))
                                    }
                                }
                            }
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween<Float>(500, easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f))) }
                        }
                    }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), enabled = showContent, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Text(if (last) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_continue), modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
//  Scrim
// ═════════════════════════════════════════════════════════════

@Composable
private fun FirstVideoScrim(isDarkTheme: Boolean) {
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(500))
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); visible = false }
    val scrimColor = if (isDarkTheme) Color.Black else Color.White
    Box(Modifier.fillMaxSize().background(scrimColor.copy(alpha = alpha)))
}

// ═════════════════════════════════════════════════════════════
//  Config pages
// ═════════════════════════════════════════════════════════════

private fun providerIconRes(name: String): Int = when (name.lowercase()) {
    "google" -> R.drawable.provider_google
    "openai" -> R.drawable.provider_openai
    "anthropic" -> R.drawable.provider_anthropic
    "deepseek" -> R.drawable.provider_deepseek
    "qwen" -> R.drawable.provider_qwen
    "ollama" -> R.drawable.provider_ollama
    "open router" -> R.drawable.provider_openrouter
    else -> 0
}

@Composable
private fun ProviderPage(providers: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier, configuredProviders: Set<String> = emptySet()) {
    val scrollState = rememberScrollState()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Surface(
        modifier = modifier.heightIn(max = 340.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Box(Modifier.fillMaxWidth().drawBehind {
            if (scrollState.maxValue > 0) {
                val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                val barW = 4.dp.toPx()
                val barX = size.width - barW - 8.dp.toPx()
                val barH = size.height - 40.dp.toPx()
                val barY = 20.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                val thumbH = barH * 0.35f
                val thumbY = barY + (barH - thumbH) * progress
                drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }) {
            Column(Modifier.verticalScroll(scrollState)) {
                Spacer(Modifier.height(10.dp))
            providers.forEach { p ->
                val iconRes = providerIconRes(p)
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp).clip(RoundedCornerShape(28.dp)).clickable { onSelect(p) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == p, onClick = { onSelect(p) })
                    Spacer(Modifier.width(8.dp))
                    when {
                        iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        p == "Local" -> Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        else -> Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(p, style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected == p) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
                Spacer(Modifier.height(10.dp))
        }
        }
    }
}

@Composable
private fun ApiKeyPage(
    provider: String?, apiKeyText: String, onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean, onToggleVisibility: () -> Unit,
    isImporting: Boolean, onImportGGUF: () -> Unit, modifier: Modifier,
    localModels: List<com.newoether.agora.data.LocalChatModelConfig> = emptyList()
) {
    Surface(modifier, RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        if (provider == null) {
            Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_no_provider), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (provider == "Local") {
            val label = if (isImporting) stringResource(R.string.onboarding_importing)
                else localModels.lastOrNull()?.alias ?: stringResource(R.string.onboarding_import_gguf)
            Column(Modifier.padding(32.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onImportGGUF, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), enabled = !isImporting) {
                    Text(label, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else if (provider == "Ollama") {
            val fm = LocalFocusManager.current
            Column(Modifier.padding(32.dp).fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                val iconRes = providerIconRes(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_ollama_hint)) },
                    singleLine = true, shape = RoundedCornerShape(50)
                )
            }
        } else {
            val fm = LocalFocusManager.current
            Column(Modifier.padding(32.dp).fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }) {
                val iconRes = providerIconRes(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (apiKeyVisible) R.string.onboarding_hide_key else R.string.onboarding_show_key), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    singleLine = true, shape = RoundedCornerShape(50)
                )
            }
        }
    }
}

@Composable
private fun ModelPage(models: List<String>, selectedId: String?, onSelect: (String) -> Unit, onFetch: () -> Unit, modifier: Modifier) {
    Surface(modifier, RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        if (models.isEmpty()) {
            Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_no_models), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val scrollState = rememberScrollState()
            val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            Box(Modifier.fillMaxWidth().heightIn(max = 340.dp).drawBehind {
                if (scrollState.maxValue > 0) {
                    val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                    val barW = 4.dp.toPx()
                    val barX = size.width - barW - 8.dp.toPx()
                    val barH = size.height - 24.dp.toPx()
                    val barY = 12.dp.toPx()
                    drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                    val thumbH = barH * 0.35f
                    val thumbY = barY + (barH - thumbH) * progress
                    drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }) {
                Column(Modifier.verticalScroll(scrollState)) {
                    Spacer(Modifier.height(10.dp))
                    models.forEach { m ->
                        val name = m.substringAfter(":").removePrefix("models/")
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp).clip(RoundedCornerShape(28.dp)).clickable { onSelect(m) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedId == m, onClick = { onSelect(m) })
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selectedId == m) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun LoopVideo(player: ExoPlayer) {
    val context = LocalContext.current
    var isReady by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (isReady) 1f else 0f, tween(400))

    DisposableEffect(player) {
        if (player.playbackState == Player.STATE_READY) isReady = true
        val l = object : Player.Listener { override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) isReady = true } }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    AndroidView(
        factory = { PlayerView(context).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
        modifier = Modifier.fillMaxSize().aspectRatio(1f).alpha(a)
    )
}
