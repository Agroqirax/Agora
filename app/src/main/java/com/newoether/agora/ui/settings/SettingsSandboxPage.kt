package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSandboxPage(sandboxManager: SandboxManager, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fm = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Core state
    var available by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var backendPackagesLoading by remember { mutableStateOf(false) }
    var installingRootfs by remember { mutableStateOf(false) } // rootfs install (blocking, in UI scope)
    var installError by remember { mutableStateOf<String?>(null) }
    var installPkg by rememberSaveable { mutableStateOf(sandboxManager.pendingPkgName) }
    var lastInstallResult by remember { mutableStateOf<Boolean?>(null) } // local: success/fail for button state
    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }
    // Sync persisted text field back to sandbox manager
    LaunchedEffect(installPkg) { sandboxManager.pendingPkgName = installPkg }

    // Collected from sandbox manager backend
    val terminalOutput by sandboxManager.terminalOutput.collectAsState()
    val isBusy by sandboxManager.isBusy.collectAsState()
    val showTerminal = terminalOutput.isNotBlank()

    fun clearAllState() { installPkg = ""; installError = null; deleteConfirm = null; lastInstallResult = null }

    fun installPackage(name: String) {
        if (isBusy) return
        installPkg = name; lastInstallResult = null; sandboxManager.installPackage(name)
    }

    LaunchedEffect(Unit) {
        checking = true
        try {
            available = sandboxManager.isAvailable()
            if (available) sandboxManager.refreshPackageList()
        } catch (_: Exception) {}
        checking = false
    }

    // Package list comes directly from backend — always live
    val backendPackages by sandboxManager.packageList.collectAsState()

    val quickPkgs = listOf("python3", "git", "curl", "openssh", "nodejs", "build-base", "vim", "htop")
    val pkgCount = backendPackages.size
    var diskUsageMB by remember { mutableStateOf(0L) }
    LaunchedEffect(backendPackages.size) {
        try { diskUsageMB = sandboxManager.getDiskUsageMB() } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sandbox_mgmt_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fm.clearFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            if (checking) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // ═══ Dashboard ═══
            SettingsGroup(title = stringResource(R.string.sandbox_env), items = listOf({
                if (!available) {
                    // Not installed
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.sandbox_alpine_version),
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Column {
                                Text("Not installed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (installingRootfs) {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Extracting Alpine rootfs...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                installError?.let { err ->
                                    Spacer(Modifier.height(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.errorContainer
                                    ) {
                                        Text(
                                            err,
                                            modifier = Modifier.padding(12.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (installingRootfs) Icons.Default.HourglassTop else Icons.Default.Warning,
                                null,
                                tint = if (installingRootfs) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        },
                        trailingContent = {
                            if (!installingRootfs && !isBusy) {
                                TextButton(onClick = {
                                    scope.launch {
                                        installingRootfs = true; clearAllState()
                                        try {
                                            sandboxManager.reset()
                                            val ok = sandboxManager.install()
                                            if (ok) { available = true; sandboxManager.refreshPackageList() }
                                            else { installError = sandboxManager.lastError ?: "Install failed" }
                                        } catch (e: Exception) { installError = e.message }
                                        installingRootfs = false
                                    }
                                }) { Text("Install", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    )
                } else {
                    // Ready dashboard
                    SettingsItem(
                        headlineContent = {
                            Text(
                                "Alpine Linux 3.21",
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { (diskUsageMB.toFloat() / 2048f).coerceIn(0f, 1f) },
                                        modifier = Modifier.weight(0.3f).height(6.dp),
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "~${diskUsageMB.coerceAtLeast(1)} MB / 2 GB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$pkgCount backendPackages installed · aarch64",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }))

            if (available) {
                // ═══ Install Packages ═══
                SettingsGroup(title = stringResource(R.string.sandbox_install_packages), items = listOf({
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        // Input row
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = installPkg,
                                onValueChange = { installPkg = it; lastInstallResult = null },
                                label = { Text(stringResource(R.string.sandbox_package_name)) },
                                placeholder = { Text("python3, git, curl...") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            val installDone = installPkg.isNotBlank() && lastInstallResult != null && !isBusy
                            val btnBgColor by animateColorAsState(
                                targetValue = when { !installDone -> MaterialTheme.colorScheme.primary; lastInstallResult == true -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.errorContainer },
                                animationSpec = tween(400)
                            )
                            Button(
                                onClick = { installPackage(installPkg.trim()) },
                                enabled = installPkg.isNotBlank() && !installDone && !isBusy,
                                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 28.dp, bottomEnd = 28.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = btnBgColor),
                                modifier = Modifier.height(56.dp).widthIn(min = 110.dp).offset(y = 4.dp)
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                } else if (installDone) {
                                    Text(if (lastInstallResult == true) "Installed" else "Failed")
                                } else {
                                    Text(stringResource(R.string.sandbox_install))
                                }
                            }
                        }

                        // Terminal output (fixed-height, terminal theme, auto-scroll)
                        AnimatedVisibility(
                            visible = showTerminal && terminalOutput.isNotBlank(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            val termScroll = rememberScrollState()
                            LaunchedEffect(terminalOutput) { termScroll.animateScrollTo(termScroll.maxValue) }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0D1117),
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(260.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        terminalOutput,
                                        modifier = Modifier.padding(12.dp).fillMaxWidth()
                                            .verticalScroll(termScroll)
                                            .nestedScroll(object : NestedScrollConnection {
                                                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                                    // Consume all scroll so outer parent never receives it
                                                    return available
                                                }
                                                override suspend fun onPreFling(available: Velocity): Velocity = available
                                                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
                                            }),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                                            lineHeight = 18.sp
                                        ),
                                        color = Color(0xFFC9D1D9)
                                    )
                                }
                            }
                        }

                        // Quick install chips
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.sandbox_quick_install) + ":",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            quickPkgs.forEach { pkg ->
                                FilterChip(
                                    selected = false,
                                    onClick = { installPackage(pkg) },
                                    enabled = !isBusy,
                                    label = { Text(pkg, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }))

                // ═══ Installed Packages ═══
                SettingsGroup(
                    title = "${stringResource(R.string.sandbox_installed)} ($pkgCount)",
                    items = when {
                        backendPackagesLoading -> listOf({
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        })
                        backendPackages.isEmpty() -> listOf({
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.sandbox_no_packages),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Info,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            )
                        })
                        else -> backendPackages.map { pkg -> {
                            SettingsItem(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(pkg.name, fontWeight = FontWeight.Medium)
                                        if (pkg.version.isNotBlank()) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    "v${pkg.version}",
                                                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                },
                                supportingContent = {
                                    if (pkg.description.isNotBlank())
                                        Text(
                                            pkg.description.take(80),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Inventory2,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = { deleteConfirm = pkg.name },
                                        enabled = !isBusy,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            "Remove",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            )
                        }}
                    }
                )

                // ═══ Danger Zone ═══
                SettingsGroup(title = stringResource(R.string.sandbox_danger_zone), items = listOf({
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.sandbox_reset),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.sandbox_reset_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.DeleteForever,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable { resetConfirm = true }
                    )
                }))
            }
        }
    }

    // ── Delete confirm dialog ──
    deleteConfirm?.let { pkgName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Remove $pkgName", fontWeight = FontWeight.Bold) },
            text = { Text("Remove $pkgName from the sandbox? This will free its disk space.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBusy) return@TextButton
                        scope.launch {
                            deleteConfirm = null
                            sandboxManager.removePackage(pkgName)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── Reset confirm dialog ──
    if (resetConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { resetConfirm = false },
            title = { Text(stringResource(R.string.sandbox_reset), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.sandbox_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            sandboxManager.reset(); available = false
                            clearAllState()
                        }
                        resetConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
