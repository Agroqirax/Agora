package com.newoether.agora.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileSystems
class ProotSandboxManager(private val context: Context) : SandboxManager {

    private val alpineMirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main"
    private var sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _terminalOutput = MutableStateFlow("")
    override val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
    private val _packageList = MutableStateFlow<List<SandboxManager.PackageInfo>>(emptyList())
    override val packageList: StateFlow<List<SandboxManager.PackageInfo>> = _packageList.asStateFlow()

    override suspend fun refreshPackageList() {
        if (isAvailable()) _packageList.value = apkList()
    }
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    override val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    override var pendingPkgName: String = ""

    private val rootfsDir: File = File(context.filesDir, "alpine-rootfs")

    private val prootExecPath: String by lazy {
        // Force System.loadLibrary to trigger extraction from APK.
        // Without this, the .so may not be in nativeLibraryDir at runtime.
        try { System.loadLibrary("agora_proot") } catch (_: Throwable) {}
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    override var lastError: String? = null

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

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!rootfsDir.isDirectory) { lastError = "rootfs not found: ${rootfsDir.absolutePath}"; return@withContext false }
        if (!ensureShell()) { lastError = "/bin/sh missing"; return@withContext false }
        val linker = listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1").map { File(rootfsDir, it) }.any { it.exists() }
        if (!linker) { lastError = "musl linker missing"; return@withContext false }
        true
    }

    override suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (rootfsDir.exists()) { rootfsDir.deleteRecursively(); if (rootfsDir.exists()) { error("Cannot delete stale rootfs") } }
            rootfsDir.mkdirs()

            val tmpTar = File(context.filesDir, "alpine-rootfs.tar.gz")
            try {
                val assetFiles = context.assets.list("") ?: emptyArray()
                val assetName = if (assetFiles.any { it == "alpine-minirootfs.tar" }) "alpine-minirootfs.tar" else "alpine-minirootfs.tar.gz"
                context.assets.open(assetName).use { input -> tmpTar.outputStream().use { output -> input.copyTo(output) } }

                if (assetName.endsWith(".tar")) {
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(tmpTar.inputStream()).use { tar -> extractTarEntries(tar, rootfsDir) }
                } else {
                    java.util.zip.GZIPInputStream(tmpTar.inputStream()).use { gz ->
                        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar -> extractTarEntries(tar, rootfsDir) }
                    }
                }
            } finally { tmpTar.delete() }

            File(rootfsDir, "tmp").mkdirs()
            File(rootfsDir, "run").mkdirs()
            listOf("var/cache/apk", "etc/apk/cache", "var/lock").forEach { File(rootfsDir, it).mkdirs() }
            val rc = File(rootfsDir, "etc/resolv.conf"); rc.parentFile?.mkdirs()
            rc.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            // Alpine repository config
            val repos = File(rootfsDir, "etc/apk/repositories"); repos.parentFile?.mkdirs()
            repos.writeText("$alpineMirror\n")
            // Ensure all binaries are executable recursively
            listOf("bin", "usr/bin", "sbin", "usr/sbin", "usr/libexec").forEach { dir ->
                val d = File(rootfsDir, dir)
                if (d.isDirectory) d.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
            }
            isAvailable()
        } catch (e: Throwable) { e.printStackTrace(); lastError = e.message; false }
    }

    override fun installPackage(name: String) {
        if (_isBusy.value) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            _isBusy.value = true
            try {
                val ok = apkInstall(name) { _terminalOutput.value += it + "\n" }
                ensureShell()
                _packageList.value = apkList()
                _terminalOutput.value += if (ok) "✓ Installed $name\n" else "✗ Failed\n"
                _snackbarMessage.value = if (ok) "Installed $name" else "Failed to install $name"
            } catch (e: Throwable) { ensureShell()
                _packageList.value = apkList()
                _terminalOutput.value += "✗ Error: ${e.message}\n"
                _snackbarMessage.value = "Error: ${e.message}"
            } finally { _isBusy.value = false }
        }
    }

    override fun removePackage(name: String) {
        if (_isBusy.value) return
        sandboxScope.launch {
            _terminalOutput.value = ""
            _isBusy.value = true
            try {
                val ok = apkDelete(name)
                _terminalOutput.value += if (ok) "✓ Removed $name\n" else "✗ Failed to remove $name\n"
            } catch (e: Throwable) {
                _terminalOutput.value += "✗ Error: ${e.message}\n"
            } finally { ensureShell(); _isBusy.value = false; _packageList.value = apkList() }
        }
    }

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
            _snackbarMessage.value = "Sandbox reset"
            true
        } catch (e: Throwable) { false }
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

    private fun executeRaw(command: String, workdir: String = "/root", timeoutMs: Int = 30000): SandboxManager.SandboxResult {
        ensureShell()
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        val args = listOf(prootPath,
            "--rootfs=" + rootfsDir.absolutePath,
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "-w", workdir.ifBlank { "/root" },
            "-0", "--link2symlink", "--kill-on-exit", "-L",
            "/bin/sh", "-c", command
        )
        return try {
            val libDir = context.applicationInfo.nativeLibraryDir
            val tallocLibDir = ensureTalloc()
            val ldPath = "$tallocLibDir:$libDir"
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.environment()["PROOT_LOADER"] = "$libDir/libproot_loader.so"
            pb.environment()["PROOT_TMP_DIR"] = tmpDir
            pb.environment()["HOME"] = "/root"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok) { p.destroyForcibly(); SandboxManager.SandboxResult(out, "Timed out", -1) }
            else SandboxManager.SandboxResult(out, "", p.exitValue())
        } catch (e: Throwable) { SandboxManager.SandboxResult("", e.message ?: "proot failed", -1) }
    }

    override suspend fun executeCommand(cmd: String, wd: String, to: Int): SandboxManager.SandboxResult {
        if (!isAvailable()) return SandboxManager.SandboxResult("", "Sandbox not installed", -1)
        return executeRaw(cmd, wd.ifBlank { "/root" }, to)
    }

    // ── File Operations ────────────────────────────────

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String = withContext(Dispatchers.IO) {
        val f = resolvePath(path); if (!f.exists()) throw IllegalStateException("File not found: $path")
        val b = f.readBytes(); val s = offset.coerceIn(0, b.size.toLong()).toInt()
        val e = if (limit > 0) minOf(s + limit, b.size.toLong()).toInt() else b.size
        String(b, s, e - s, Charsets.UTF_8)
    }

    override suspend fun fileWrite(path: String, content: String): String? = withContext(Dispatchers.IO) {
        try { val f = resolvePath(path); f.parentFile?.mkdirs(); f.writeText(content, Charsets.UTF_8); null }
        catch (e: Throwable) { "Sandbox file write failed: ${e.message}" }
    }

    override suspend fun fileGlob(pattern: String, basePath: String): List<String> = withContext(Dispatchers.IO) {
        val base = resolvePath(if (basePath.isBlank()) "/" else basePath)
        val files = mutableListOf<String>(); walkFiles(base, files, rootfsDir.absolutePath)
        globMatch(files, rootfsDir.absolutePath, pattern)
    }

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<SandboxManager.GrepMatch>> = withContext(Dispatchers.IO) {
        try {
            val regex = try { Regex(pattern) } catch (e: Throwable) { Regex(java.util.regex.Pattern.quote(pattern)) }
            val files = if (fileGlob.isNotBlank()) fileGlob(fileGlob, basePath)
            else { val b = resolvePath(if (basePath.isBlank()) "/" else basePath); val a = mutableListOf<String>(); walkFiles(b, a, rootfsDir.absolutePath); a }
            val matches = mutableListOf<SandboxManager.GrepMatch>()
            for (file in files) {
                try {
                    val resolved = if (file.startsWith("/")) resolvePath(file) else resolvePath("/$file")
                    if (!resolved.exists() || resolved.length() > 500_000L) continue
                    resolved.readText(Charsets.UTF_8).lines().forEachIndexed { i, line ->
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
            val content = f.readText(Charsets.UTF_8); val count = content.split(oldString).size - 1
            if (count == 0) SandboxManager.FileEditResult(0, "old_string not found in file")
            else if (count > 1 && !replaceAll) SandboxManager.FileEditResult(0, "Found $count matches. Use replace_all=true.")
            else { f.writeText(content.replace(oldString, newString), Charsets.UTF_8); SandboxManager.FileEditResult(if (replaceAll) count else 1) }
        } catch (e: Throwable) { SandboxManager.FileEditResult(0, "Sandbox file edit failed: ${e.message}") }
    }

    // ── Package Management ──────────────────────────────
    // Downloads target + all transitive deps + stale base-package upgrades via
    // Android HTTP (works with VPN/Clash), then single apk add --no-network.

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) { onProgress("Sandbox not installed"); return@withContext false }

        // 1. Download + parse repo index
        onProgress("Fetching package index...")
        val indexUrl = "$alpineMirror/aarch64/APKINDEX.tar.gz"
        val indexFile = File(context.filesDir, "APKINDEX.tar.gz")
        try {
            val conn = URL(indexUrl).openConnection() as HttpURLConnection
            onProgress("Connecting to ${conn.url.host}...")
            val code = conn.responseCode
            onProgress("HTTP $code (${conn.contentLength} bytes)")
            if (code != 200) { onProgress("FAIL: HTTP $code"); lastError = "HTTP $code from $indexUrl"; return@withContext false }
            conn.inputStream.use { i -> indexFile.outputStream().use { o -> i.copyTo(o) } }
        }
        catch (e: Throwable) { onProgress("FAIL: ${e.javaClass.simpleName}: ${e.message}"); lastError = "${e.javaClass.simpleName}: ${e.message}"; return@withContext false }
        val (repoPkgs, soToPkg) = parseFullApkIndex(indexFile); indexFile.delete()

        if (packageName !in repoPkgs) { lastError = "Not found: $packageName"; return@withContext false }

        // 2. Read installed DB — don't reinstall/downgrade existing packages
        val installedDb = File(rootfsDir, "lib/apk/db/installed")
        val installed = mutableMapOf<String, String>() // name → version
        if (installedDb.exists()) {
            var n = ""; var v = ""
            installedDb.readLines(Charsets.UTF_8).forEach { line ->
                if (line.startsWith("P:")) n = line.substring(2).trim()
                else if (line.startsWith("V:")) v = line.substring(2).trim()
                else if (line.isBlank()) { if (n.isNotEmpty()) installed[n] = v; n = ""; v = "" }
            }
            if (n.isNotEmpty()) installed[n] = v
        }

        // 3. Recursively resolve target + transitive deps, skip already installed UNLESS version constraint forces upgrade
        val toInstall = linkedSetOf<String>()
        fun resolve(name: String, visited: MutableSet<String> = mutableSetOf()) {
            if (name in visited || name !in repoPkgs) return
            visited.add(name)
            val instVer = installed[name]
            if (instVer == null || instVer != repoPkgs[name]!!.version) toInstall.add(name)
            for (dep in repoPkgs[name]!!.deps) {
                val dn = dep.takeWhile { it != '=' && it != '>' && it != '<' && it != '~' }
                if (dn.isNotEmpty()) {
                    if (dn in repoPkgs) resolve(dn, visited)
                    else soToPkg[dn]?.let { resolve(it, visited) }
                }
            }
        }
        resolve(packageName)
        onProgress("${toInstall.size} packages to install")

        // 4. Download all .apk files
        val tmpDir = File(rootfsDir, "tmp"); tmpDir.listFiles()?.forEach { it.delete() }; tmpDir.mkdirs()
        val paths = mutableListOf<String>()
        for (name in toInstall) {
            val ver = repoPkgs[name]?.version ?: continue
            val fn = "$name-$ver.apk"; val f = File(context.filesDir, fn)
            if (!f.exists() || f.length() == 0L) {
                onProgress("Downloading $fn...")
                try {
                    val conn = URL("$alpineMirror/aarch64/$fn").openConnection() as HttpURLConnection
                    if (conn.responseCode != 200) { onProgress("HTTP ${conn.responseCode}"); lastError = "HTTP ${conn.responseCode}: $fn"; tmpDir.listFiles()?.forEach { it.delete() }; return@withContext false }
                    conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                } catch (ex: Throwable) { onProgress("FAIL: ${ex.message}"); lastError = "Download: ${ex.message}"; tmpDir.listFiles()?.forEach { it.delete() }; return@withContext false }
            }
            val dst = File(tmpDir, fn); f.copyTo(dst, true); f.delete(); paths.add("/tmp/$fn")
        }

        // 5. Pre-install: if we're replacing /bin/sh provider, install that first
        // to avoid post-install scripts failing when busybox-binsh is purged.
        val shPkgs = toInstall.filter { "binsh" in it || it == "yash" }.toList()
        if (shPkgs.isNotEmpty()) {
            val shPaths = paths.filter { p -> shPkgs.any { p.contains(it) } }
            if (shPaths.isNotEmpty()) {
                onProgress("Installing shell provider first...")
                val r = executeRaw("apk add --allow-untrusted --no-network ${shPaths.joinToString(" ")}", timeoutMs = 60000)
                onProgress(r.stdout)
                paths.removeAll(shPaths)
            }
        }

        // 6. Main install
        onProgress("Installing ${paths.size} packages...")
        val result = executeRaw("apk add --allow-untrusted --no-network ${paths.joinToString(" ")}", timeoutMs = 120000)
        onProgress(result.stdout); tmpDir.listFiles()?.forEach { it.delete() }
        // Verify install — apk may return non-zero on minor post-install script errors
        val installedOk = File(rootfsDir, "lib/apk/db/installed").readText(Charsets.UTF_8).contains("P:$packageName\n")
        if (!installedOk) { lastError = result.stderr.ifBlank { result.stdout }; return@withContext false }
        true
    }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        if (!isAvailable()) { _terminalOutput.value += "[apkList: isAvailable=false]\n"; return@withContext emptyList() }
        try {
            val db = File(rootfsDir, "lib/apk/db/installed")
            if (!db.exists()) { _terminalOutput.value += "[apkList: DB not found at ${db.absolutePath}]\n"; return@withContext emptyList() }
            val content = db.readText(Charsets.UTF_8)
            val pkgs = mutableListOf<SandboxManager.PackageInfo>()
            var n = ""; var v = ""; var d = ""
            content.lines().forEach { line ->
                if (line.startsWith("P:")) n = line.substring(2).trim()
                else if (line.startsWith("V:")) v = line.substring(2).trim()
                else if (line.startsWith("T:")) d = line.substring(2).trim()
                else if (line.isBlank()) { if (n.isNotBlank()) { pkgs.add(SandboxManager.PackageInfo(name = n, version = v, description = d)); n = ""; v = ""; d = "" } }
            }
            if (n.isNotBlank()) pkgs.add(SandboxManager.PackageInfo(name = n, version = v, description = d))
            if (pkgs.isEmpty()) _terminalOutput.value += "[apkList: parsed 0 from ${content.length}B]\n"
            pkgs
        } catch (e: Throwable) { _terminalOutput.value += "[apkList: ${e.message}]\n"; emptyList() }
    }

    override suspend fun apkDelete(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) { _terminalOutput.value += "Sandbox not available\n"; return@withContext false }
        val db = File(rootfsDir, "lib/apk/db/installed")
        if (db.exists()) { _terminalOutput.value += "DB has package: ${db.readText(Charsets.UTF_8).contains(packageName)}\n" }
        _terminalOutput.value += "Running: apk del $packageName\n"
        val result = executeRaw("apk del $packageName", timeoutMs = 60000)
        _terminalOutput.value += result.stdout
        _terminalOutput.value += if (result.exitCode == 0) "Exit: 0\n" else "Exit: ${result.exitCode}\n"
        result.exitCode == 0
    }

    override suspend fun getDiskUsageMB(): Long = withContext(Dispatchers.IO) {
        try { rootfsDir.walkTopDown().sumOf { it.length() } / (1024 * 1024) } catch (_: Throwable) { 0L }
    }

    // ── Tar Extraction ──────────────────────────────────

    private fun extractTarEntries(tar: org.apache.commons.compress.archivers.tar.TarArchiveInputStream, destDir: File) {
        val symlinks = mutableListOf<Pair<String, String>>()
        var entry = tar.nextEntry
        while (entry != null) {
            val outFile = File(destDir, entry.name)
            when {
                entry.isDirectory -> outFile.mkdirs()
                entry.isSymbolicLink -> { outFile.parentFile?.mkdirs(); symlinks.add(entry.name to entry.linkName) }
                entry.isFile -> { outFile.parentFile?.mkdirs(); outFile.outputStream().use { tar.copyTo(it) }; if (entry.mode and 0x40 != 0) outFile.setExecutable(true, false) }
            }
            entry = tar.nextEntry
        }
        for ((name, target) in symlinks) {
            val outFile = File(destDir, name); if (outFile.exists()) continue
            val src = if (target.startsWith("/")) File(destDir, target)
                      else File(outFile.parentFile ?: destDir, target)
            if (!src.exists()) continue
            try {
                if (src.isDirectory) src.walkTopDown().forEach { f -> val rel = f.relativeTo(src).path; val dst = File(outFile, rel); if (f.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); f.copyTo(dst, true) } }
                else { outFile.parentFile?.mkdirs(); src.copyTo(outFile, true) }
            } catch (_: Throwable) {}
        }
    }

    // ── APKINDEX Parsing ────────────────────────────────

    private data class FullPkgEntry(val name: String, val version: String, val deps: List<String>)

    private fun parseFullApkIndex(indexFile: File): Pair<Map<String, FullPkgEntry>, Map<String, String>> {
        val result = mutableMapOf<String, FullPkgEntry>()
        val soToPkg = mutableMapOf<String, String>()
        java.util.zip.GZIPInputStream(indexFile.inputStream()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name == "APKINDEX") {
                        val lines = tar.readBytes().toString(Charsets.UTF_8).lines()
                        for (i in lines.indices) {
                            val line = lines[i].trim()
                            if (!line.startsWith("P:")) continue
                            val name = line.substring(2).trim()
                            var version = ""; var provider = ""
                            val deps = mutableListOf<String>()
                            val isSoEntry = name.startsWith("so:")
                            for (j in i + 1 until minOf(i + 30, lines.size)) {
                                val n = lines[j].trim()
                                if (n.startsWith("C:")) break
                                if (n.startsWith("V:")) version = n.substring(2).trim()
                                if (n.startsWith("p:")) {
                                    provider = n.substring(2).trim()
                                    if (!isSoEntry) {
                                        // p: lists all provides (so:libfoo.so.1=1.0 so:libbar.so.1=1.0)
                                        for (prov in provider.split(Regex("\\s+"))) {
                                            val pn = prov.takeWhile { it != '=' }
                                            if (pn.isNotEmpty()) soToPkg[pn] = name
                                        }
                                    }
                                }
                                if (n.startsWith("D:")) deps.addAll(n.substring(2).trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                            }
                            if (isSoEntry && provider.isNotEmpty()) soToPkg[name] = provider
                            else if (!isSoEntry && name.isNotEmpty() && version.isNotEmpty()) result[name] = FullPkgEntry(name, version, deps)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return Pair(result, soToPkg)
    }

    // ── Helpers ────────────────────────────────────────

    private fun resolvePath(path: String): File {
        val resolved = File(rootfsDir, path.trimStart('/')).canonicalFile
        require(resolved.absolutePath.startsWith(rootfsDir.canonicalFile.absolutePath)) { "Path traversal: $path" }
        return resolved
    }

    private fun walkFiles(dir: File, result: MutableList<String>, rootFsAbsPath: String) {
        try { dir.listFiles()?.forEach { if (it.isDirectory) walkFiles(it, result, rootFsAbsPath) else result.add(it.absolutePath.removePrefix(rootFsAbsPath).removePrefix("/")) } } catch (_: Throwable) {}
    }

    private fun globMatch(files: List<String>, basePath: String, pattern: String): List<String> {
        val adj = if (pattern.contains('/')) pattern else "**/$pattern"
        val matcher = try { FileSystems.getDefault().getPathMatcher("glob:$basePath/$adj") } catch (_: Throwable) { return emptyList() }
        return files.filter { f -> val full = if (f.startsWith("/")) f else "$basePath/$f"; try { matcher.matches(java.nio.file.Paths.get(full)) } catch (_: Throwable) { false } }
    }
}
