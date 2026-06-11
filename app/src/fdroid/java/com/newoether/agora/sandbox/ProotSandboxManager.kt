package com.newoether.agora.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileSystems
class ProotSandboxManager(private val context: Context) : SandboxManager {

    companion object {
        private const val ALPINE_MIRROR = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64"
        private const val MAX_DISK_MB = 500L
    }

    private val rootfsDir: File = File(context.filesDir, "alpine-rootfs")

    private val prootExecPath: String by lazy {
        // Force System.loadLibrary to trigger extraction from APK.
        // Without this, the .so may not be in nativeLibraryDir at runtime.
        try { System.loadLibrary("agora_proot") } catch (_: Throwable) {}
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    override var lastError: String? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!rootfsDir.isDirectory) { lastError = "rootfs not found: ${rootfsDir.absolutePath}"; return@withContext false }
        val sh = listOf("bin/sh", "usr/bin/sh").map { File(rootfsDir, it) }.any { it.exists() }
        val linker = listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1").map { File(rootfsDir, it) }.any { it.exists() }
        if (!sh) { lastError = "/bin/sh missing (symlinks not created?)"; return@withContext false }
        if (!linker) { lastError = "musl linker missing"; return@withContext false }
        true
    }

    override suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
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
            val rc = File(rootfsDir, "etc/resolv.conf"); rc.parentFile?.mkdirs()
            rc.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            // Alpine repository config
            val repos = File(rootfsDir, "etc/apk/repositories"); repos.parentFile?.mkdirs()
            repos.writeText("$ALPINE_MIRROR\n")
            // Init installed DB if not present
            val db = File(rootfsDir, "lib/apk/db/installed"); db.parentFile?.mkdirs()
            if (!db.exists()) db.createNewFile()
            // Ensure all binaries are executable recursively
            listOf("bin", "usr/bin", "sbin", "usr/sbin", "usr/libexec").forEach { dir ->
                val d = File(rootfsDir, dir)
                if (d.isDirectory) d.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
            }
            isAvailable()
        } catch (e: Throwable) { e.printStackTrace(); lastError = e.message; false }
    }

    override fun close() {}
    override suspend fun reset(): Boolean = withContext(Dispatchers.IO) { try { rootfsDir.deleteRecursively(); prootBin.delete(); true } catch (e: Throwable) { false } }

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
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        // Kai-style CLI: --rootfs=, --bind=, -0
        val args = listOf(prootPath,
            "--rootfs=" + rootfsDir.absolutePath,
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "-w", workdir.ifBlank { "/root" },
            "-0",  // link2symlink extension
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

    private data class PkgEntry(val name: String, val version: String, val description: String = "", val deps: List<String> = emptyList())

    private fun getInstalledPackages(): MutableList<PkgEntry> {
        val db = File(rootfsDir, "lib/apk/db/installed")
        if (!db.exists()) return mutableListOf()
        val pkgs = mutableListOf<PkgEntry>()
        var n = ""; var v = ""; var d = ""
        db.readLines(Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("P:") -> n = line.substring(2).trim()
                line.startsWith("V:") -> v = line.substring(2).trim()
                line.startsWith("T:") -> d = line.substring(2).trim()
                line.isBlank() && n.isNotBlank() -> { pkgs.add(PkgEntry(name = n, version = v, description = d)); n = ""; v = ""; d = "" }
            }
        }
        if (n.isNotBlank()) pkgs.add(PkgEntry(name = n, version = v, description = d))
        return pkgs
    }

    private fun isPackageInstalled(name: String): Boolean =
        getInstalledPackages().any { it.name == name }

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // Check already installed
        if (isPackageInstalled(packageName)) {
            onProgress("Already installed: $packageName")
            return@withContext true
        }

        onProgress("Downloading APKINDEX...")
        val indexFile = File(context.filesDir, "APKINDEX.tar.gz")
        try { URL("$ALPINE_MIRROR/APKINDEX.tar.gz").openStream().use { i -> indexFile.outputStream().use { o -> i.copyTo(o) } } }
        catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = "APKINDEX: ${e.message}"; return@withContext false }

        onProgress("Parsing APKINDEX...")
        val allPkgs = parseApkIndex(indexFile)
        indexFile.delete()

        val entry = allPkgs[packageName]
        if (entry == null) {
            val similar = allPkgs.keys.filter { it.startsWith(packageName.take(2)) }.take(10).joinToString(", ")
            onProgress("NOT FOUND${if (similar.isNotEmpty()) ". Similar: $similar" else ""}")
            lastError = if (similar.isNotEmpty()) "'$packageName' not found. Available: $similar" else "'$packageName' not found"
            return@withContext false
        }

        // Install dependencies first
        for (dep in entry.deps) {
            val depName = dep.takeWhile { it != '=' && it != '>' && it != '<' && it != '~' }
            if (depName.isNotEmpty() && !isPackageInstalled(depName)) {
                onProgress("Installing dependency: $depName")
                val ok = apkInstallInternal(depName, allPkgs, onProgress)
                if (!ok) onProgress("WARNING: dependency $depName failed")
            }
        }

        apkInstallInternal(packageName, allPkgs, onProgress)
    }

    private suspend fun apkInstallInternal(packageName: String, allPkgs: Map<String, PkgEntry>, onProgress: (String) -> Unit): Boolean {
        if (isPackageInstalled(packageName)) { onProgress("Already installed: $packageName"); return true }

        val entry = allPkgs[packageName] ?: return false
        val fileName = "$packageName-${entry.version}.apk"
        val pkgUrl = "$ALPINE_MIRROR/$fileName"
        val pkgFile = File(context.filesDir, fileName)
        onProgress("Downloading $fileName...")
        try {
            val conn = URL(pkgUrl).openConnection() as HttpURLConnection
            val total = conn.contentLength
            conn.inputStream.use { i -> pkgFile.outputStream().use { o ->
                val buf = ByteArray(8192); var dl = 0L; var lr = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); dl += n
                    if (dl - lr > 50000 || dl == total.toLong()) { lr = dl; onProgress("  ${if (total > 0) "${dl * 100 / total}%" else "?"} (${dl / 1024}KB)") }
                }
            } }
        } catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = "Download: ${e.message}"; return false }
        onProgress("Downloaded ${pkgFile.length() / 1024}KB")

        onProgress("Extracting $fileName...")
        try { extractApk(pkgFile, rootfsDir) }
        catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = "Extract: ${e.message}"; pkgFile.delete(); return false }
        pkgFile.delete()
        onProgress("Extracted successfully")

        updateInstalledDb(rootfsDir, packageName, entry.version, entry.description)
        onProgress("Package database updated")
        return true
    }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext emptyList()
        getInstalledPackages().map { SandboxManager.PackageInfo(name = it.name, version = it.version, description = it.description) }
    }

    override suspend fun apkDelete(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        try {
            val pkgs = getInstalledPackages()
            val filtered = pkgs.filter { it.name != packageName }
            if (filtered.size == pkgs.size) return@withContext false // not found
            val db = File(rootfsDir, "lib/apk/db/installed")
            val sb = StringBuilder()
            filtered.forEach { sb.appendLine("P:${it.name}"); sb.appendLine("V:${it.version}"); if (it.description.isNotBlank()) sb.appendLine("T:${it.description}"); sb.appendLine() }
            db.writeText(sb.toString(), Charsets.UTF_8)
            true
        } catch (e: Throwable) { false }
    }

    override suspend fun getDiskUsageMB(): Long = withContext(Dispatchers.IO) {
        try { rootfsDir.walkTopDown().sumOf { it.length() } / (1024 * 1024) } catch (_: Throwable) { 0L }
    }

    private fun updateInstalledDb(rootfsDir: File, packageName: String, version: String, description: String) {
        val pkgs = getInstalledPackages()
        if (pkgs.any { it.name == packageName }) return // already recorded
        File(rootfsDir, "lib/apk/db/installed").appendText(
            "\nP:$packageName\nV:$version\nT:$description\n\n", Charsets.UTF_8
        )
    }

    // ── APKINDEX Parsing ────────────────────────────────

    private fun parseApkIndex(indexFile: File): Map<String, PkgEntry> {
        val result = mutableMapOf<String, PkgEntry>()
        java.util.zip.GZIPInputStream(indexFile.inputStream()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name == "APKINDEX") {
                        val content = tar.readBytes().toString(Charsets.UTF_8)
                        val lines = content.lines()
                        for (i in lines.indices) {
                            val line = lines[i].trim()
                            if (!line.startsWith("P:")) continue
                            val name = line.substring(2).trim()
                            var version = ""
                            var desc = ""
                            val deps = mutableListOf<String>()
                            for (j in i + 1 until minOf(i + 30, lines.size)) {
                                val next = lines[j].trim()
                                if (next.startsWith("C:")) break
                                if (next.startsWith("V:")) version = next.substring(2).trim()
                                if (next.startsWith("T:")) desc = next.substring(2).trim()
                                if (next.startsWith("D:")) deps.addAll(next.substring(2).trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                            }
                            if (name.isNotEmpty() && version.isNotEmpty()) result[name] = PkgEntry(name, version, desc, deps)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return result
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
            val src = File(destDir, target)
            if (!src.exists()) continue
            try {
                if (src.isDirectory) src.walkTopDown().forEach { f -> val rel = f.relativeTo(src).path; val dst = File(outFile, rel); if (f.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); f.copyTo(dst, true) } }
                else { outFile.parentFile?.mkdirs(); src.copyTo(outFile, true) }
            } catch (_: Throwable) {}
        }
    }

    // ── APK Extraction ──────────────────────────────────

    private fun extractApk(apkFile: File, destDir: File) {
        val bytes = apkFile.readBytes(); var gzStart = -1; var gzCount = 0
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x1F.toByte() && bytes[i + 1] == 0x8B.toByte()) { gzCount++; if (gzCount == 2) { gzStart = i; break } }
        }
        if (gzStart < 0) throw java.io.IOException("Cannot find data.tar.gz in .apk")
        java.util.zip.GZIPInputStream(bytes.copyOfRange(gzStart, bytes.size).inputStream()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar -> extractTarEntries(tar, destDir) }
        }
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
