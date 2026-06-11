package com.newoether.agora.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
class ProotSandboxManager(private val context: Context) : SandboxManager {

    private val alpineMirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main"

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
            repos.writeText("$alpineMirror\n")
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

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) { onProgress("Sandbox not installed"); return@withContext false }
        onProgress("apk update...")
        val updateResult = executeRaw("apk update", timeoutMs = 60000)
        onProgress(updateResult.stdout)
        onProgress("apk add $packageName...")
        val result = executeRaw("apk add --no-cache $packageName", timeoutMs = 120000)
        onProgress(result.stdout)
        if (result.exitCode != 0) { lastError = result.stderr.ifBlank { result.stdout }; return@withContext false }
        true
    }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext emptyList()
        try {
            val result = executeRaw("apk info -v --installed", timeoutMs = 15000)
            if (result.exitCode != 0) return@withContext emptyList()
            result.stdout.lines().mapNotNull { line ->
                // Format: "python3-3.12.8-r0 description: text..."
                val parts = line.split(" ", limit = 2)
                val pv = parts.getOrNull(0) ?: return@mapNotNull null
                val desc = parts.getOrNull(1)?.removePrefix("description:")?.trim() ?: ""
                val lastDash = pv.lastIndexOf('-')
                if (lastDash < 0) return@mapNotNull null
                val name = pv.substring(0, lastDash)
                val version = pv.substring(lastDash + 1)
                SandboxManager.PackageInfo(name = name, version = version, description = desc)
            }
        } catch (e: Throwable) { emptyList() }
    }

    override suspend fun apkDelete(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        val result = executeRaw("apk del $packageName", timeoutMs = 60000)
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
            val src = File(destDir, target)
            if (!src.exists()) continue
            try {
                if (src.isDirectory) src.walkTopDown().forEach { f -> val rel = f.relativeTo(src).path; val dst = File(outFile, rel); if (f.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); f.copyTo(dst, true) } }
                else { outFile.parentFile?.mkdirs(); src.copyTo(outFile, true) }
            } catch (_: Throwable) {}
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
