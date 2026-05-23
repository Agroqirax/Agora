package com.newoether.agora

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.ui.chat.ChatApp
import com.newoether.agora.ui.chat.FullScreenMediaViewer
import com.newoether.agora.ui.settings.SettingsScreen
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ChatViewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val langCode = kotlinx.coroutines.runBlocking {
            SettingsManager(newBase).appLanguage.first()
        }
        val locale = when (langCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> null
        }
        if (locale != null) {
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        com.newoether.agora.util.DebugLog.init(this)
        AgoraForegroundService.createChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val storedVersion = ChatDatabase.getStoredVersion(this)
        val needsErrorDialog = storedVersion > ChatDatabase.CURRENT_VERSION

        val memoryManager = MemoryManager(applicationContext)
        val settingsManager = SettingsManager(applicationContext)

        enableEdgeToEdge()
        setContent {
            val themeMode by settingsManager.themeMode.collectAsState(initial = "FOLLOW_DEVICE")
            val colorSchemeName by settingsManager.colorScheme.collectAsState(initial = "DEFAULT")
            val dynamicColor by settingsManager.dynamicColor.collectAsState(initial = true)

            val themeModeEnum = try { com.newoether.agora.ui.theme.ThemeMode.valueOf(themeMode) } catch (_: Exception) { com.newoether.agora.ui.theme.ThemeMode.FOLLOW_DEVICE }
            val colorSchemePreset = try { com.newoether.agora.ui.theme.ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { com.newoether.agora.ui.theme.ColorSchemePreset.DEFAULT }

            AgoraTheme(
                themeMode = themeModeEnum,
                colorSchemePreset = colorSchemePreset,
                dynamicColor = dynamicColor
            ) {
                val activity = LocalActivity.current

                if (needsErrorDialog) {
                    AlertDialog(
                        onDismissRequest = { activity?.finish() },
                        title = { Text(stringResource(R.string.database_incompatible)) },
                        text = { Text(stringResource(R.string.database_incompatible_desc)) },
                        dismissButton = {
                            TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.quit)) }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                applicationContext.deleteDatabase(ChatDatabase.DB_NAME)
                                activity?.recreate()
                            }) { Text(stringResource(R.string.clear_database)) }
                        }
                    )
                } else {
                    val database = ChatDatabase.build(this)
                    val factory = ChatViewModelFactory(application, settingsManager, database.chatDao(), memoryManager, this@MainActivity)
                    val viewModel: ChatViewModel = viewModel(factory = factory)
                    MainNavigation(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.isInForeground = true
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.isInForeground = false
    }
}

@Composable
fun MainNavigation(viewModel: ChatViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var fullScreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val pdfPages by viewModel.previewPdfPages.collectAsState()
    val pdfIndex by viewModel.previewPdfIndex.collectAsState()
    var savedPdfPages by remember { mutableStateOf<List<String>>(emptyList()) }
    if (pdfPages.isNotEmpty()) { savedPdfPages = pdfPages } else { savedPdfPages = emptyList() }
    val snackbarHostState = remember { SnackbarHostState() }
    var chatSnackbarOffset by remember { mutableStateOf(0.dp) }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val targetSnackbarPadding = if (showSettings) navBarPadding else chatSnackbarOffset
    val snackbarBottomPadding by animateDpAsState(
        targetValue = targetSnackbarPadding,
        animationSpec = spring(dampingRatio = 1.0f, stiffness = 1000f),
        label = "snackbarPadding"
    )
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                event.onAction?.invoke()
            }
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatApp(
                viewModel = viewModel,
                onOpenSettings = {
                    showSettings = true
                },
                onImageClick = { url ->
                    focusManager.clearFocus()
                    fullScreenImageUrl = url
                },
                onFileContentClick = { name, content ->
                    focusManager.clearFocus()
                    viewModel.showFilePreview(name, content)
                },
                onPdfPagesClick = { pages, idx ->
                    focusManager.clearFocus()
                    viewModel.showPdfPreview(pages, idx)
                    fullScreenImageUrl = pages[idx]
                },
                onSnackbarOffsetChanged = { chatSnackbarOffset = it }
            )

            // Scrim that fades in behind the settings page
            AnimatedVisibility(
                visible = showSettings,
                enter = fadeIn(animationSpec = tween(400)),
                exit = fadeOut(animationSpec = tween(400))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .pointerInput(Unit) {
                            detectTapGestures { showSettings = false }
                        }
                )
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(animationSpec = tween(400)) { it },
                exit = slideOutHorizontally(animationSpec = tween(400)) { it }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            showSettings = false
                        }
                    )

                }
            }

            // Full screen image preview
            AnimatedVisibility(
                visible = fullScreenImageUrl != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                // Keep the last URL for the duration of the exit animation
                var lastUrl by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(fullScreenImageUrl) {
                    if (fullScreenImageUrl != null) lastUrl = fullScreenImageUrl
                }
                
                val url = lastUrl ?: return@AnimatedVisibility
                FullScreenMediaViewer(
                    url = url,
                    pdfPages = savedPdfPages,
                    onClose = { viewModel.clearPreviews(); fullScreenImageUrl = null },
                    onNavigate = { fullScreenImageUrl = it }
                )
            }

            // Text file viewer
            val fileContent by viewModel.previewFileContent.collectAsState()
            val fileName by viewModel.previewFileName.collectAsState()
            var savedContent by remember { mutableStateOf(fileContent) }
            var savedName by remember { mutableStateOf(fileName) }
            if (fileContent != null) { savedContent = fileContent; savedName = fileName }
            AnimatedVisibility(
                visible = fileContent != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (savedContent != null && savedName != null) {
                    com.newoether.agora.ui.chat.TextFileViewer(content = savedContent!!, fileName = savedName!!, onClose = { viewModel.clearPreviews() })
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = snackbarBottomPadding)
            )
        }
    }
}
