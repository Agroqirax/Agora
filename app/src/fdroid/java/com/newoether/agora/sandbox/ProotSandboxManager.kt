package com.newoether.agora.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileSystems
class ProotSandboxManager(private val context: Context) : SandboxManager {

    // Pin to the stable v3.21 branch to match the downloaded minirootfs (3.21.0). Using edge here
    // caused `apk upgrade` to pull divergent packages (e.g. yash-binsh vs busybox-binsh /bin/sh
    // conflict) and rotates signing keys; the stable branch avoids both.
    private val alpineMirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main"
    // community carries most non-base packages (uv, py3-pip, htop, etc.) — main alone
    // is missing a lot of what users actually want to install.
    private val alpineCommunityMirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/community"
    private val alpineRepos = listOf(alpineMirror, alpineCommunityMirror)
    // Base rootfs is fetched on-device at install time (not bundled in the APK), then verified
    // against this pinned SHA-256 before extraction. Stable v3.21 release URL.
    private val rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
    private val rootfsSha256 = "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1"
    private var sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _terminalOutput = MutableStateFlow("")
    override val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
    private val _isInstallingRootfs = MutableStateFlow(false)
    override val isInstallingRootfs: StateFlow<Boolean> = _isInstallingRootfs.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    private val _packageList = MutableStateFlow<List<SandboxManager.PackageInfo>>(emptyList())
    override val packageList: StateFlow<List<SandboxManager.PackageInfo>> = _packageList.asStateFlow()

    override suspend fun refreshPackageList() {
        if (isAvailable()) _packageList.value = apkList()
    }
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    override val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    override var pendingPkgName: String = ""

    private val rootfsDir: File = File(context.filesDir, "alpine-rootfs")
    private val homeMountDir: File = File(context.filesDir, "sandbox-home")
    private val homeMountPath = "/home/agora"
    private val metadataDir: File = File(rootfsDir, "etc/agora")
    private val baseWorldFile: File = File(metadataDir, "base-world")
    private val explicitPackagesFile: File = File(metadataDir, "explicit-packages")
    private val defaultBaseWorld = linkedSetOf("alpine-baselayout", "alpine-keys", "apk-tools", "busybox", "libc-utils")
    private val packageNameRegex = Regex("^[A-Za-z0-9][A-Za-z0-9+_.:-]*$")

    private val prootExecPath: String by lazy {
        // Force System.loadLibrary to trigger extraction from APK.
        // Without this, the .so may not be in nativeLibraryDir at runtime.
        try { System.loadLibrary("agora_proot") } catch (_: Throwable) {}
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    override var lastError: String? = null

    /**
     * Rewrite root's home entry in /etc/passwd from /root to /home/agora.
     * Some programs call getpwuid(0) instead of reading $HOME, so the passwd
     * entry must match the HOME env var for consistent behaviour (shell, git, SSH, etc.).
     * This is a direct file edit — no proot needed, idempotent, and fast.
     */
    private fun ensureRootHome() {
        val passwdFile = File(rootfsDir, "etc/passwd")
        if (!passwdFile.isFile) return
        val content = passwdFile.readText()
        if ("root:x:0:0:root:/home/agora:" in content) return // already correct
        val updated = content.replace(
            Regex("^(root:x:0:0:root:)/root(:)", RegexOption.MULTILINE),
            "$1/home/agora$2"
        )
        if (updated != content) {
            passwdFile.writeText(updated)
        }
    }

    /**
     * proot is ptrace-based and doesn't create a network namespace, so Alpine's processes
     * share the host app's UID and routing/VPN rules — but musl's resolver still reads its
     * own /etc/resolv.conf instead of asking Android's ConnectivityManager for DNS. Under a
     * VPN/proxy that redirects DNS per-app, a hardcoded public resolver gets blocked while
     * Android's own HTTP stack (which uses the active network's DNS) succeeds. Mirror
     * Android's actual active DNS servers into the rootfs so Alpine sees the same resolver
     * Android does.
     */
    private fun writeResolvConf() {
        val rc = File(rootfsDir, "etc/resolv.conf")
        rc.parentFile?.mkdirs()
        val nameservers = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val dns = cm?.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
            dns.joinToString("\n") { "nameserver ${it.hostAddress}" }
        } catch (_: Throwable) { "" }
        rc.writeText(nameservers.ifBlank { "nameserver 1.1.1.1" } + "\n")
    }

    private fun ensureShell(): Boolean {
        val sh = File(rootfsDir, "bin/sh")
        if (sh.exists()) return true
        try {
            val busybox = File(rootfsDir, "bin/busybox")
            if (busybox.isFile && busybox.canRead()) {
                // Delete broken symlink if present (exists()=false but symlink entry exists)
                sh.delete()
                busybox.copyTo(sh, false); sh.setExecutable(true)
                return true
            }
        } catch (_: Throwable) { sh.delete() }
        return false
    }

    override fun isAvailableSync(): Boolean {
        if (!rootfsDir.isDirectory) return false
        if (!File(rootfsDir, "bin/sh").exists()) return false
        return listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1")
            .map { File(rootfsDir, it) }.any { it.exists() }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!rootfsDir.isDirectory) { lastError = "rootfs not found: ${rootfsDir.absolutePath}"; return@withContext false }
        if (!ensureShell()) { lastError = "/bin/sh missing"; return@withContext false }
        val linker = listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1").map { File(rootfsDir, it) }.any { it.exists() }
        if (!linker) { lastError = "musl linker missing"; return@withContext false }
        ensureSandboxMountTargets()
        ensurePackageMetadata()
        ensureRootHome()
        true
    }

    override suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (rootfsDir.exists()) { rootfsDir.deleteRecursively(); if (rootfsDir.exists()) { error("Cannot delete stale rootfs") } }
            rootfsDir.mkdirs()

            val tmpTar = File(context.filesDir, "alpine-rootfs.tar.gz")
            try {
                // Fetch the base rootfs on-device (not shipped in the APK) and verify its checksum.
                _terminalOutput.value += "Downloading Alpine minirootfs…\n"
                downloadRootfs(rootfsUrl, tmpTar)
                // Switch the bar to indeterminate while we extract.
                _downloadProgress.value = null
                _terminalOutput.value += "Extracting rootfs…\n"
                java.util.zip.GZIPInputStream(tmpTar.inputStream()).use { gz ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar -> extractTarEntries(tar, rootfsDir) }
                }
            } finally { tmpTar.delete() }

            // Everything below fills in what the pinned minirootfs tarball itself doesn't
            // ship (verified against the exact tarball behind rootfsSha256): no resolv.conf,
            // no /home/agora mount point, root's passwd entry still says /root. tmp/, run/,
            // var/cache/apk/, /etc/apk/repositories, and every binary's executable bit are
            // already correct straight out of the tarball — don't rewrite what's already right.
            ensureSandboxMountTargets()
            writeResolvConf()
            // No auto `apk upgrade` here: the freshly-downloaded minirootfs is already a coherent
            // pinned release. Running upgrade immediately makes apk re-resolve /bin/sh and dead-locks
            // on the busybox-binsh vs yash-binsh `cmd:sh` conflict. Packages upgrade on demand.
            captureBaseWorld(force = true)
            writeExplicitPackages(emptySet())
            isAvailable()
        } catch (e: Throwable) { e.printStackTrace(); lastError = e.message; false }
    }

    override fun installRootfs() {
        if (_isInstallingRootfs.value) return
        sandboxScope.launch {
            _isInstallingRootfs.value = true
            _downloadProgress.value = null
            _terminalOutput.value = ""
            _packageList.value = emptyList()
            try {
                // NOTE: don't call reset() here — it cancels sandboxScope (i.e. this very
                // coroutine). install() already wipes any stale rootfs before extracting.
                val ok = install()
                if (ok) refreshPackageList()
            } catch (e: Throwable) {
                e.printStackTrace(); lastError = e.message
            } finally {
                _isInstallingRootfs.value = false
                _downloadProgress.value = null
            }
        }
    }

    /** Download [url] to [dest], streaming SHA-256 + progress, then verify against [rootfsSha256]. */
    private fun downloadRootfs(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} fetching rootfs")
            val total = conn.contentLengthLong
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        _downloadProgress.value = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(rootfsSha256, ignoreCase = true)) {
                dest.delete()
                error("rootfs checksum mismatch (expected $rootfsSha256, got $hex)")
            }
        } finally { conn.disconnect() }
    }

    override fun installPackages(names: List<String>) {
        if (names.isEmpty()) return
        // compareAndSet, not a plain check-then-launch: the check and the flip to `true`
        // must be atomic, otherwise two near-simultaneous calls (e.g. two quick-install
        // taps) can both pass the check before either coroutine actually runs, racing the
        // same shared download/tmp dir and corrupting one of the installs.
        if (!_isBusy.compareAndSet(false, true)) return
        val label = names.joinToString(", ")
        sandboxScope.launch {
            _terminalOutput.value = ""
            lastError = null
            try {
                val ok = apkInstall(names) { _terminalOutput.value += it + "\n" }
                ensureShell()
                _packageList.value = apkList()
                _terminalOutput.value += if (ok) "✓ Installed $label\n" else "✗ Failed\n"
                _snackbarMessage.value = if (ok) context.getString(R.string.sandbox_snackbar_installed, label) else context.getString(R.string.sandbox_snackbar_install_failed, label)
            } catch (e: Throwable) { ensureShell()
                _packageList.value = apkList()
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                _snackbarMessage.value = context.getString(R.string.sandbox_snackbar_error, e.message ?: "")
            } finally { _isBusy.value = false }
        }
    }

    override fun removePackage(name: String) {
        if (!_isBusy.compareAndSet(false, true)) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            lastError = null
            try {
                val ok = apkDelete(name)
                _terminalOutput.value += if (ok) "✓ Removed $name\n" else "✗ Failed to remove $name\n"
                _snackbarMessage.value = if (ok) context.getString(R.string.sandbox_snackbar_removed, name) else context.getString(R.string.sandbox_snackbar_remove_failed, name)
            } catch (e: Throwable) {
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                _snackbarMessage.value = context.getString(R.string.sandbox_snackbar_error, e.message ?: "")
            } finally { ensureShell(); _isBusy.value = false; _packageList.value = apkList() }
        }
    }

    override fun upgradePackages() {
        if (!_isBusy.compareAndSet(false, true)) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            lastError = null
            try {
                val upgraded = apkUpgrade { _terminalOutput.value += it + "\n" }
                ensureShell()
                _packageList.value = apkList()
                val ok = lastError == null
                _terminalOutput.value += when {
                    upgraded > 0 -> "✓ Upgraded $upgraded packages\n"
                    ok -> "✓ Packages already up to date\n"
                    else -> "✗ Upgrade failed\n"
                }
                _snackbarMessage.value = when {
                    upgraded > 0 -> context.getString(R.string.sandbox_snackbar_upgrade_done, upgraded)
                    ok -> context.getString(R.string.sandbox_snackbar_upgrade_none)
                    else -> context.getString(R.string.sandbox_snackbar_upgrade_failed)
                }
            } catch (e: Throwable) {
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                _snackbarMessage.value = context.getString(R.string.sandbox_snackbar_error, e.message ?: "")
            } finally { ensureShell(); _isBusy.value = false; _packageList.value = apkList() }
        }
    }

    override fun getSandboxHomeDir(): File? = homeMountDir

    override fun close() {
        sandboxScope.cancel()
    }
    override suspend fun reset(): Boolean = withContext(Dispatchers.IO) {
        sandboxScope.cancel(); sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _terminalOutput.value = ""
        _packageList.value = emptyList()
        try {
            for (i in 1..3) {
                rootfsDir.deleteRecursively()
                if (!rootfsDir.exists()) break
                kotlinx.coroutines.delay(200)
            }
            prootBin.delete()
            _snackbarMessage.value = context.getString(R.string.sandbox_snackbar_reset)
            true
        } catch (e: Throwable) { _snackbarMessage.value = context.getString(R.string.sandbox_snackbar_reset_failed); false }
    }

    // ── Shell Execution ─────────────────────────────────

    /** Path to proot binary, extracted from assets — Termux-style. */
    private val prootBin: File = File(context.filesDir, "bin/proot")

    private val prootPath: String by lazy {
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    // Copy libtalloc.so -> libtalloc.so.2 in writable dir for linker resolution.
    // Android linker searches by exact filename, not SONAME.
    // Kai's proot DT_NEEDED is "libtalloc.so.2" but jniLibs has "libtalloc.so".
    private val tallocDir: File by lazy {
        File(context.filesDir, "lib").apply { mkdirs() }
    }
    private fun ensureTalloc(): String {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")
        val dst = File(tallocDir, "libtalloc.so.2")
        if (!dst.exists() && src.exists()) {
            src.copyTo(dst)
        }
        return tallocDir.absolutePath
    }

    /** Builds the `proot`-wrapped `ProcessBuilder` shared by [executeRaw] (one-shot,
     *  output fully consumed) and [startProcess] (long-lived, streamed). [command] is
     *  run via `/bin/sh -c` so PATH lookup and shell scripts (e.g. `npx`) resolve the
     *  same way a real shell would. [extraEnv] is layered on top of the base proot env
     *  (e.g. stdio MCP servers configuring their own environment variables). */
    private fun buildProotProcessBuilder(command: String, workdir: String, extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        ensureShell()
        ensureSandboxMountTargets()
        writeResolvConf()
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        val resolvedWorkdir = workdir.ifBlank { homeMountPath }
        val args = listOf(prootPath,
            "--rootfs=" + rootfsDir.absolutePath,
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=${homeMountDir.absolutePath}:$homeMountPath",
            "-w", resolvedWorkdir,
            "-0", "--link2symlink", "--kill-on-exit", "-L",
            "/bin/sh", "-c", command
        )
        val libDir = context.applicationInfo.nativeLibraryDir
        val tallocLibDir = ensureTalloc()
        val ldPath = "$tallocLibDir:$libDir"
        val pb = ProcessBuilder(args)
        pb.environment()["LD_LIBRARY_PATH"] = ldPath
        pb.environment()["PROOT_LOADER"] = "$libDir/libproot_loader.so"
        pb.environment()["PROOT_TMP_DIR"] = tmpDir
        pb.environment()["HOME"] = homeMountPath
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        extraEnv.forEach { (k, v) -> if (k.isNotBlank()) pb.environment()[k] = v }
        return pb
    }

    private fun executeRaw(command: String, workdir: String = homeMountPath, timeoutMs: Int = 30000): SandboxManager.SandboxResult {
        return try {
            val pb = buildProotProcessBuilder(command, workdir).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok) { p.destroyForcibly(); SandboxManager.SandboxResult(out, "Timed out", -1) }
            else SandboxManager.SandboxResult(out, "", p.exitValue())
        } catch (e: Throwable) { SandboxManager.SandboxResult("", e.message ?: "proot failed", -1) }
    }

    override suspend fun executeCommand(cmd: String, wd: String, to: Int): SandboxManager.SandboxResult {
        if (!isAvailable()) return SandboxManager.SandboxResult("", "Sandbox not installed", -1)
        return executeRaw(cmd, wd.ifBlank { homeMountPath }, to)
    }

    /** A [Process] wrapped for line-oriented stdio protocols (e.g. MCP). stdout is read
     *  incrementally on a background thread into an unbounded [Channel]; stderr is
     *  drained separately into [DebugLog] so it never fills the pipe and blocks the
     *  child, and so it never corrupts the stdout JSON-RPC stream. */
    private class ProotSandboxProcess(private val process: Process) : SandboxManager.SandboxProcess {
        private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lines = Channel<String>(Channel.UNLIMITED)
        private val writeMutex = Mutex()
        private val stderrLines = ArrayDeque<String>()

        init {
            readerScope.launch {
                try {
                    process.inputStream.bufferedReader().forEachLine { lines.trySend(it) }
                } catch (_: Throwable) {
                } finally {
                    lines.close()
                }
            }
            readerScope.launch {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        DebugLog.e("ProotSandboxProcess", "stderr: ${line.take(500)}")
                        synchronized(stderrLines) {
                            stderrLines.addLast(line)
                            if (stderrLines.size > 20) stderrLines.removeFirst()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        override suspend fun writeLine(line: String) = writeMutex.withLock {
            withContext(Dispatchers.IO) {
                process.outputStream.write((line + "\n").toByteArray())
                process.outputStream.flush()
            }
        }

        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()

        override val isAlive: Boolean get() = process.isAlive

        override val stderrTail: String get() = synchronized(stderrLines) { stderrLines.joinToString("\n") }

        override fun destroy() {
            readerScope.cancel()
            lines.close()
            process.destroyForcibly()
        }
    }

    override suspend fun startProcess(command: String, env: Map<String, String>, workdir: String): SandboxManager.SandboxProcess = withContext(Dispatchers.IO) {
        val pb = buildProotProcessBuilder(command, workdir, env).redirectErrorStream(false)
        ProotSandboxProcess(pb.start())
    }

    // ── File Operations ────────────────────────────────

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String = withContext(Dispatchers.IO) {
        val f = resolvePath(path); if (!f.exists()) throw IllegalStateException("File not found: $path")
        val fileSize = f.length().toInt()
        val s = offset.coerceIn(0, fileSize.toLong()).toInt()
        val max = com.newoether.agora.util.Constants.MAX_TOOL_RESULT_LENGTH
        val e = if (limit > 0) minOf((s + limit).toInt(), fileSize)
                else minOf(s + max, fileSize)
        val len = e - s
        val buf = ByteArray(len)
        f.inputStream().use { it.skip(s.toLong()); it.read(buf) }
        String(buf, Charsets.UTF_8)
    }

    override suspend fun fileWrite(path: String, content: String): String? = withContext(Dispatchers.IO) {
        try { val f = resolvePath(path); f.parentFile?.mkdirs(); f.writeText(content, Charsets.UTF_8); null }
        catch (e: Throwable) { "Sandbox file write failed: ${e.message}" }
    }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): List<String> = withContext(Dispatchers.IO) {
        val base = resolveSandboxPath(if (basePath.isBlank()) "/" else basePath)
        val files = mutableListOf<String>()
        // null = legacy full recursion; <=0 = explicit unlimited; >=1 = max levels.
        val remaining = if (depth == null || depth <= 0) -1 else depth
        walkVirtualFiles(base.file, files, base.physicalRoot.canonicalPath, base.virtualRoot, remaining)
        globMatch(files, pattern)
    }

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<SandboxManager.GrepMatch>> = withContext(Dispatchers.IO) {
        try {
            val regex = try { Regex(pattern) } catch (e: Throwable) { Regex(java.util.regex.Pattern.quote(pattern)) }
            val files = if (fileGlob.isNotBlank()) fileGlob(fileGlob, basePath)
            else {
                val b = resolveSandboxPath(if (basePath.isBlank()) "/" else basePath)
                val a = mutableListOf<String>()
                walkVirtualFiles(b.file, a, b.physicalRoot.canonicalPath, b.virtualRoot)
                a
            }
            val matches = mutableListOf<SandboxManager.GrepMatch>()
            for (file in files) {
                try {
                    val resolved = if (file.startsWith("/")) resolvePath(file) else resolvePath("/$file")
                    if (!resolved.exists() || resolved.length() > 500_000L) continue
                    val text = resolved.readText(Charsets.UTF_8)
                    // Skip binary files: a NUL byte in the content is the standard
                    // heuristic grep itself uses to avoid emitting garbage matches.
                    if (text.contains('\u0000')) continue
                    text.lines().forEachIndexed { i, line ->
                        if (regex.containsMatchIn(line)) matches.add(SandboxManager.GrepMatch(path = file, line = i + 1, content = line.take(500)))
                    }
                } catch (_: Throwable) {}
            }
            Result.success(matches)
        } catch (e: Throwable) { Result.failure(e) }
    }

    override suspend fun fileEdit(path: String, oldString: String, newString: String, replaceAll: Boolean): SandboxManager.FileEditResult = withContext(Dispatchers.IO) {
        try {
            val f = resolvePath(path); if (!f.exists()) return@withContext SandboxManager.FileEditResult(0, "File not found: $path")
            if (f.length() > com.newoether.agora.util.Constants.MAX_FILE_CONTENT_READ_LENGTH) return@withContext SandboxManager.FileEditResult(0, "File too large to edit (>${com.newoether.agora.util.Constants.MAX_FILE_CONTENT_READ_LENGTH / 1000}KB)")
            val content = f.readText(Charsets.UTF_8); val count = content.split(oldString).size - 1
            if (count == 0) SandboxManager.FileEditResult(0, "old_string not found in file")
            else if (count > 1 && !replaceAll) SandboxManager.FileEditResult(0, "Found $count matches. Use replace_all=true.")
            else { f.writeText(content.replace(oldString, newString), Charsets.UTF_8); SandboxManager.FileEditResult(if (replaceAll) count else 1) }
        } catch (e: Throwable) { SandboxManager.FileEditResult(0, "Sandbox file edit failed: ${e.message}") }
    }

    // ── Package Management ──────────────────────────────
    // Real `apk` does its own network I/O, dependency resolution, and signature
    // verification. proot shares the host app's UID/routing (see writeResolvConf),
    // so as long as resolv.conf reflects Android's actual DNS, `apk` works exactly
    // like it would in a normal Alpine install.

    override suspend fun apkInstall(packageNames: List<String>, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (packageNames.isEmpty()) return@withContext false
        if (!isAvailable()) { onProgress("Sandbox not installed"); return@withContext false }
        val requested = try {
            packageNames.map { sanitizePackageName(it) }
        } catch (e: IllegalArgumentException) {
            onProgress("FAIL: ${e.message}")
            lastError = e.message
            return@withContext false
        }
        lastError = null
        ensurePackageMetadata()

        onProgress("apk update...")
        val updateResult = executeRaw("apk update", timeoutMs = 60000)
        onProgress(updateResult.stdout)

        // One `apk add` transaction for every requested package — apk's own solver
        // resolves them together, so a runtime + its package manager (e.g. nodejs +
        // npm) either both land or neither does, instead of two separate `apk add`
        // calls racing/half-completing independently.
        onProgress("apk add ${requested.joinToString(" ")}...")
        val result = executeRaw("apk add --no-cache ${requested.joinToString(" ") { shellQuote(it) }}", timeoutMs = 180000)
        onProgress(result.stdout)
        if (result.exitCode != 0) { lastError = result.stderr.ifBlank { result.stdout }; return@withContext false }
        requested.forEach { addExplicitPackage(it) }
        true
    }

    // apk's own verbosity counter (not our sanitized "-v" flag) gates what a plain,
    // argument-less `apk info` prints: name only at verbosity<=1, "name-version" at
    // verbosity==2, "name-version - description" at verbosity>=3. Passing -vvv forces
    // full output no matter what apk's baseline verbosity defaults to.
    private val packageNameVersionRegex = Regex("^(.+)-([0-9][^-\\s]*(?:-r[0-9]+)?)$")

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        if (!isAvailable()) { _terminalOutput.value += "[apkList: isAvailable=false]\n"; return@withContext emptyList() }
        try {
            val result = executeRaw("apk info -vvv", timeoutMs = 15000)
            if (result.exitCode != 0) { _terminalOutput.value += "[apkList: apk info failed: ${result.stderr.ifBlank { result.stdout }}]\n"; return@withContext emptyList() }
            result.stdout.lines().mapNotNull { line ->
                // Format: "python3-3.12.8-r0 - description text"
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val sepIdx = trimmed.indexOf(" - ")
                val pv = if (sepIdx >= 0) trimmed.substring(0, sepIdx) else trimmed
                val desc = if (sepIdx >= 0) trimmed.substring(sepIdx + 3).trim() else ""
                val m = packageNameVersionRegex.matchEntire(pv) ?: return@mapNotNull null
                SandboxManager.PackageInfo(name = m.groupValues[1], version = m.groupValues[2], description = desc)
            }
        } catch (e: Throwable) { _terminalOutput.value += "[apkList: ${e.message}]\n"; emptyList() }
    }

    override suspend fun apkDelete(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) { _terminalOutput.value += "Sandbox not available\n"; return@withContext false }
        val requested = try {
            sanitizePackageName(packageName)
        } catch (e: IllegalArgumentException) {
            _terminalOutput.value += "FAIL: ${e.message}\n"
            lastError = e.message
            return@withContext false
        }
        lastError = null
        ensurePackageMetadata()

        val baseNames = readBaseWorld().map { worldPackageName(it) }.toSet()
        if (requested in baseNames) {
            lastError = "Refusing to remove base package: $requested"
            _terminalOutput.value += "${lastError}\n"
            return@withContext false
        }

        val previousExplicit = readExplicitPackages()
        val nextExplicit = previousExplicit.toMutableSet().apply { remove(requested) }.toSet()
        writeExplicitPackages(nextExplicit)
        normalizeWorld(nextExplicit)

        _terminalOutput.value += "Running: apk del $requested\n"
        val result = executeRaw("apk del ${shellQuote(requested)}", timeoutMs = 60000)
        _terminalOutput.value += result.stdout
        _terminalOutput.value += if (result.exitCode == 0) "Exit: 0\n" else "Exit: ${result.exitCode}\n"
        if (result.exitCode != 0) {
            writeExplicitPackages(previousExplicit)
            normalizeWorld(previousExplicit)
            lastError = result.stderr.ifBlank { result.stdout }
            return@withContext false
        }
        normalizeWorld()
        true
    }

    override suspend fun apkUpgrade(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext 0
        lastError = null
        ensurePackageMetadata()
        val before = readInstalledVersions()

        onProgress("apk update...")
        val updateResult = executeRaw("apk update", timeoutMs = 60000)
        onProgress(updateResult.stdout)

        onProgress("apk upgrade...")
        val result = executeRaw("apk upgrade --no-cache", timeoutMs = 300000)
        onProgress(result.stdout)
        normalizeWorld()

        val after = readInstalledVersions()
        val upgradedCount = after.count { (name, version) -> before[name] != null && before[name] != version }
        if (result.exitCode != 0 && upgradedCount == 0) {
            lastError = result.stderr.ifBlank { result.stdout }.ifBlank { "Upgrade failed" }
            return@withContext 0
        }
        upgradedCount
    }

    override suspend fun getDiskUsageMB(): Long = withContext(Dispatchers.IO) {
        try { rootfsDir.walkTopDown().sumOf { it.length() } / (1024 * 1024) } catch (_: Throwable) { 0L }
    }

    // ── Tar Extraction ──────────────────────────────────

    private fun extractTarEntries(tar: org.apache.commons.compress.archivers.tar.TarArchiveInputStream, destDir: File) {
        val destPrefix = destDir.canonicalPath + File.separator
        // Reject any entry whose resolved path escapes destDir (Zip-Slip / path traversal).
        fun safeChild(name: String): File? {
            val f = File(destDir, name)
            return if (f.canonicalPath == destDir.canonicalPath || f.canonicalPath.startsWith(destPrefix)) f else null
        }
        val symlinks = mutableListOf<Pair<String, String>>()
        var entry = tar.nextEntry
        while (entry != null) {
            val outFile = safeChild(entry.name)
            if (outFile == null) { entry = tar.nextEntry; continue }
            when {
                entry.isDirectory -> outFile.mkdirs()
                entry.isSymbolicLink -> { outFile.parentFile?.mkdirs(); symlinks.add(entry.name to entry.linkName) }
                entry.isFile -> { outFile.parentFile?.mkdirs(); outFile.outputStream().use { tar.copyTo(it) }; if (entry.mode and 0x40 != 0) outFile.setExecutable(true, false) }
            }
            entry = tar.nextEntry
        }
        for ((name, target) in symlinks) {
            val outFile = safeChild(name) ?: continue; if (outFile.exists()) continue
            val src = if (target.startsWith("/")) File(destDir, target.trimStart('/'))
                      else File(outFile.parentFile ?: destDir, target)
            // Containment check on the symlink source too.
            if (src.canonicalPath != destDir.canonicalPath && !src.canonicalPath.startsWith(destPrefix)) continue
            if (!src.exists()) continue
            try {
                if (src.isDirectory) src.walkTopDown().forEach { f -> val rel = f.relativeTo(src).path; val dst = File(outFile, rel); if (f.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); f.copyTo(dst, true) } }
                else { outFile.parentFile?.mkdirs(); src.copyTo(outFile, true) }
            } catch (_: Throwable) {}
        }
    }

    // ── Helpers ────────────────────────────────────────

    private fun sanitizePackageName(packageName: String): String {
        val trimmed = packageName.trim()
        require(packageNameRegex.matches(trimmed)) { "Invalid package name: $packageName" }
        return trimmed
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun installedDbFile(): File = File(rootfsDir, "lib/apk/db/installed")

    private fun readInstalledVersions(): LinkedHashMap<String, String> {
        val installed = linkedMapOf<String, String>()
        val db = installedDbFile()
        if (!db.exists()) return installed
        var name = ""
        var version = ""
        db.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("P:") -> name = line.substring(2).trim()
                line.startsWith("V:") -> version = line.substring(2).trim()
                line.isBlank() -> {
                    if (name.isNotEmpty()) installed[name] = version
                    name = ""
                    version = ""
                }
            }
        }
        if (name.isNotEmpty()) installed[name] = version
        return installed
    }

    private fun worldFile(): File = File(rootfsDir, "etc/apk/world")

    private fun readWorldLines(): LinkedHashSet<String> {
        val world = worldFile()
        if (!world.exists()) return linkedSetOf()
        return world.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
    }

    private fun writeWorldLines(lines: Collection<String>) {
        val world = worldFile()
        world.parentFile?.mkdirs()
        world.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    private fun worldPackageName(line: String): String {
        val cleaned = line.trim().removePrefix("!")
        val end = cleaned.indexOfFirst { it == '@' || it == '<' || it == '>' || it == '=' || it == '~' }
        return if (end >= 0) cleaned.substring(0, end) else cleaned
    }

    private fun captureBaseWorld(force: Boolean = false) {
        metadataDir.mkdirs()
        if (!force && baseWorldFile.exists()) return
        val current = readWorldLines()
        val installed = readInstalledVersions().keys
        val inferredBase = current.filter { worldPackageName(it) in defaultBaseWorld && worldPackageName(it) in installed }
        val fallbackBase = defaultBaseWorld.filter { it in installed }
        val base = when {
            force && current.isNotEmpty() -> current
            force -> fallbackBase.ifEmpty { defaultBaseWorld }
            inferredBase.isNotEmpty() -> inferredBase
            else -> current
        }
        baseWorldFile.writeText(base.joinToString("\n", postfix = if (base.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    private fun readBaseWorld(): LinkedHashSet<String> {
        captureBaseWorld()
        return baseWorldFile.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
    }

    private fun readExplicitPackages(): LinkedHashSet<String> {
        if (!explicitPackagesFile.exists()) return linkedSetOf()
        return explicitPackagesFile.readLines(Charsets.UTF_8)
            .mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }
            .toCollection(linkedSetOf())
    }

    private fun writeExplicitPackages(packages: Collection<String>) {
        metadataDir.mkdirs()
        val clean = packages.mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }.toCollection(linkedSetOf())
        explicitPackagesFile.writeText(clean.joinToString("\n", postfix = if (clean.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    private fun ensurePackageMetadata() {
        if (!rootfsDir.isDirectory) return
        metadataDir.mkdirs()
        // Self-heals rootfs installs created before the community repo was added —
        // otherwise those sandboxes would be stuck with only "main" in this file forever.
        val repos = File(rootfsDir, "etc/apk/repositories")
        val wantRepos = alpineRepos.joinToString("\n", postfix = "\n")
        if (!repos.exists() || repos.readText() != wantRepos) {
            repos.parentFile?.mkdirs(); repos.writeText(wantRepos)
        }
        captureBaseWorld()
        if (!explicitPackagesFile.exists()) {
            val baseNames = readBaseWorld().map { worldPackageName(it) }.toSet()
            val migratedExplicit = readWorldLines()
                .map { worldPackageName(it) }
                .filter { it !in baseNames }
                .mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }
                .toCollection(linkedSetOf())
            writeExplicitPackages(migratedExplicit)
        }
        normalizeWorld()
    }

    private fun normalizeWorld(explicitPackages: Set<String> = readExplicitPackages()) {
        metadataDir.mkdirs()
        val base = readBaseWorld()
        val baseNames = base.map { worldPackageName(it) }.toSet()
        val normalized = linkedSetOf<String>()
        normalized.addAll(base)
        explicitPackages
            .mapNotNull { runCatching { sanitizePackageName(it) }.getOrNull() }
            .filter { it !in baseNames }
            .forEach { normalized.add(it) }
        writeWorldLines(normalized)
    }

    private fun addExplicitPackage(packageName: String) {
        val name = sanitizePackageName(packageName)
        ensurePackageMetadata()
        val next = readExplicitPackages().apply { add(name) }
        writeExplicitPackages(next)
        normalizeWorld(next)
    }

    private data class ResolvedSandboxPath(
        val file: File,
        val physicalRoot: File,
        val virtualRoot: String
    )

    private fun ensureSandboxMountTargets() {
        homeMountDir.mkdirs()
        File(rootfsDir, homeMountPath.trimStart('/')).mkdirs()
    }

    private fun normalizeVirtualPath(path: String): String {
        val raw = path.trim().replace('\\', '/')
        val absolute = if (raw.isBlank()) "/" else if (raw.startsWith("/")) raw else "/$raw"
        val collapsed = absolute.replace(Regex("/+"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }

    private fun resolveSandboxPath(path: String): ResolvedSandboxPath {
        val normalized = normalizeVirtualPath(path)
        if (normalized == homeMountPath || normalized.startsWith("$homeMountPath/")) {
            ensureSandboxMountTargets()
            val root = homeMountDir.canonicalFile
            val suffix = normalized.removePrefix(homeMountPath).trimStart('/')
            val resolved = File(root, suffix).canonicalFile
            require(resolved.absolutePath == root.absolutePath || resolved.absolutePath.startsWith(root.absolutePath + File.separator)) {
                "Path traversal: $path"
            }
            return ResolvedSandboxPath(resolved, root, homeMountPath)
        }

        val root = rootfsDir.canonicalFile
        val resolved = File(root, normalized.trimStart('/')).canonicalFile
        require(resolved.absolutePath == root.absolutePath || resolved.absolutePath.startsWith(root.absolutePath + File.separator)) {
            "Path traversal: $path"
        }
        return ResolvedSandboxPath(resolved, root, "/")
    }

    private fun resolvePath(path: String): File = resolveSandboxPath(path).file

    // remaining: levels still allowed including the current dir's files. -1 = unlimited;
    // 1 = only this dir's files (no descent); >1 = descend with one fewer level.
    private fun walkVirtualFiles(
        dir: File,
        result: MutableList<String>,
        physicalRootAbsPath: String,
        virtualRoot: String,
        remaining: Int = -1
    ) {
        try { dir.listFiles()?.forEach {
            if (it.isDirectory) {
                if (remaining < 0 || remaining > 1) {
                    walkVirtualFiles(it, result, physicalRootAbsPath, virtualRoot, if (remaining < 0) -1 else remaining - 1)
                }
            } else {
                val path = try { it.canonicalPath } catch (_: Exception) { it.absolutePath }
                val rel = path.removePrefix(physicalRootAbsPath).removePrefix(File.separator).replace(File.separatorChar, '/')
                val prefix = if (virtualRoot == "/") "" else virtualRoot.trimEnd('/')
                result.add("$prefix/$rel")
            }
        } } catch (_: Throwable) {}
    }

    private fun globMatch(files: List<String>, pattern: String): List<String> {
        val cleanPattern = pattern.trim().replace('\\', '/')
        val adjusted = when {
            cleanPattern.isBlank() -> "/**"
            cleanPattern.startsWith("/") -> cleanPattern
            cleanPattern.contains("/") -> "/$cleanPattern"
            else -> "/**/$cleanPattern"
        }
        val matcher = try { FileSystems.getDefault().getPathMatcher("glob:$adjusted") } catch (_: Throwable) { return emptyList() }
        return files.filter { f -> try { matcher.matches(java.nio.file.Paths.get(f)) } catch (_: Throwable) { false } }
    }
}
