package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var packages by remember { mutableStateOf<List<SandboxManager.PackageInfo>>(emptyList()) }
    var packagesLoading by remember { mutableStateOf(false) }

    // Install state
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    // Package install/remove state
    var installPkg by remember { mutableStateOf("") }
    var pkgInstalling by remember { mutableStateOf(false) }
    var pkgMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var installLog by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }

    fun refreshPackages() {
        scope.launch {
            packagesLoading = true
            try {
                packages = kotlinx.coroutines.withTimeout(5000L) { sandboxManager.apkList() }
            } catch (_: Throwable) {}
            packagesLoading = false
        }
    }

    fun installPackage(name: String) {
        scope.launch {
            pkgInstalling = true; pkgMsg = null; installPkg = name
            installLog = ""; showLog = true
            val log = StringBuilder()
            fun logLine(s: String) { log.appendLine(s); installLog = log.toString() }
            try {
                val ok = sandboxManager.apkInstall(name) { logLine(it) }
                logLine(if (ok) "✓ Installed $name" else "✗ ${sandboxManager.lastError ?: "Failed"}")
                pkgMsg = (if (ok) "Installed $name" else (sandboxManager.lastError ?: "Install failed")) to !ok
                if (ok) { refreshPackages(); installPkg = "" }
            } catch (e: Throwable) {
                logLine("✗ ${e.javaClass.simpleName}: ${e.message}")
                pkgMsg = ("${e.javaClass.simpleName}: ${e.message}") to true
            }
            pkgInstalling = false
        }
    }

    LaunchedEffect(Unit) {
        checking = true
        try { available = sandboxManager.isAvailable() } catch (_: Exception) {}
        checking = false
        if (available) refreshPackages()
    }

    val quickPkgs = listOf("python3", "git", "curl", "openssh-client", "nodejs", "build-base", "vim", "htop")
    val pkgCount = packages.size
    var diskUsageMB by remember { mutableStateOf(0L) }

    LaunchedEffect(packages) {
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
                                if (installing) {
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
                                if (installing) Icons.Default.HourglassTop else Icons.Default.Warning,
                                null,
                                tint = if (installing) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        },
                        trailingContent = {
                            if (!installing) {
                                TextButton(onClick = {
                                    scope.launch {
                                        installing = true; installError = null
                                        try {
                                            sandboxManager.reset()
                                            val ok = sandboxManager.install()
                                            if (ok) { available = true; refreshPackages() }
                                            else { installError = sandboxManager.lastError ?: "Install failed" }
                                        } catch (e: Exception) { installError = e.message }
                                        installing = false
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
                                        progress = { (diskUsageMB.toFloat() / 500f).coerceIn(0f, 1f) },
                                        modifier = Modifier.weight(0.3f).height(6.dp),
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "~${diskUsageMB.coerceAtLeast(1)} MB / 500 MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$pkgCount packages installed · aarch64",
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
                                onValueChange = { installPkg = it },
                                label = { Text(stringResource(R.string.sandbox_package_name)) },
                                placeholder = { Text("python3, git, curl...") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { installPackage(installPkg.trim()) },
                                enabled = installPkg.isNotBlank() && !pkgInstalling,
                                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 28.dp, bottomEnd = 28.dp),
                                modifier = Modifier.height(56.dp).widthIn(min = 110.dp).offset(y = 4.dp)
                            ) {
                                if (pkgInstalling) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(stringResource(R.string.sandbox_install))
                                }
                            }
                        }

                        // Package install result
                        pkgMsg?.let { (msg, isError) ->
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isError) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                                        null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        msg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        // Live log output (fixed-height, scrollable, monospace)
                        AnimatedVisibility(
                            visible = showLog && installLog.isNotBlank(),
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 160.dp)
                            ) {
                                Text(
                                    installLog,
                                    modifier = Modifier.padding(10.dp).fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                                    enabled = !pkgInstalling,
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
                        packagesLoading -> listOf({
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        })
                        packages.isEmpty() -> listOf({
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
                        else -> packages.map { pkg -> {
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
                        scope.launch {
                            try {
                                sandboxManager.apkDelete(pkgName)
                                refreshPackages()
                            } catch (_: Exception) {}
                        }
                        deleteConfirm = null
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
                        scope.launch { sandboxManager.reset(); available = false; packages = emptyList() }
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
