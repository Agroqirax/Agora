package com.newoether.agora.sandbox

import kotlinx.coroutines.flow.StateFlow

/**
 * Manages an on-device Linux sandbox (proot + Alpine).
 * Available only in the fdroid flavor; play flavor gets a no-op stub.
 *
 * File operations (read/write/glob/grep/edit) operate directly on the
 * rootfs filesystem without proot — only shell commands go through proot.
 */
interface SandboxManager {

    /** Real-time terminal output stream for install/uninstall operations. */
    val terminalOutput: StateFlow<String>

    /** Live package list — updated automatically after install/remove. */
    val packageList: StateFlow<List<PackageInfo>>

    /** Global snackbar messages for install/remove/reset events. UI shows via SnackbarHost. */
    val snackbarMessage: StateFlow<String?>

    /** Load installed packages (call on UI init after confirming available). */
    suspend fun refreshPackageList()

    /** Whether an install/uninstall operation is in progress. */
    val isBusy: StateFlow<Boolean>

    /** Last typed package name — persisted across navigation. */
    var pendingPkgName: String

    /** Fire-and-forget package install. Runs on internal scope, survives navigation. */
    fun installPackage(name: String)

    /** Fire-and-forget package removal. */
    fun removePackage(name: String)
    /** Human-readable error from the last check, if any. */
    val lastError: String?

    /** Whether the sandbox rootfs is installed and ready. */
    suspend fun isAvailable(): Boolean

    /** Extract proot + rootfs from assets. Returns true on success. */
    suspend fun install(): Boolean

    /** Execute a shell command inside the sandbox via proot. */
    suspend fun executeCommand(
        command: String,
        workdir: String = "",
        timeoutMs: Int = 30000
    ): SandboxResult

    /** Read a file from the sandbox filesystem. */
    suspend fun fileRead(path: String, offset: Long = 0, limit: Long = 0): String

    /** Write a file into the sandbox filesystem. Returns null on success. */
    suspend fun fileWrite(path: String, content: String): String?

    /** List files matching a glob pattern within the sandbox. */
    suspend fun fileGlob(pattern: String, basePath: String = ""): List<String>

    /** Grep for a regex pattern within sandbox files. */
    suspend fun fileGrep(
        pattern: String,
        basePath: String = "",
        fileGlob: String = ""
    ): Result<List<GrepMatch>>

    /** Edit a file in the sandbox (read → replace → write). */
    suspend fun fileEdit(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean = false
    ): FileEditResult

    /** Install a package via Alpine apk. */
    suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit = {}): Boolean

    /** List installed Alpine packages. */
    suspend fun apkList(): List<PackageInfo>

    /** Remove a package from the sandbox. */
    suspend fun apkDelete(packageName: String): Boolean

    /** Get rootfs disk usage in MB. */
    suspend fun getDiskUsageMB(): Long

    /** Delete rootfs and proot binary, returning to uninstalled state. */
    suspend fun reset(): Boolean

    /** Release any held resources. */
    fun close()

    // ── Nested types ───────────────────────────────────────

    data class SandboxResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    data class GrepMatch(
        val path: String,
        val line: Int,
        val content: String
    )

    data class FileEditResult(
        val replaced: Int,
        val error: String? = null
    )

    data class PackageInfo(
        val name: String,
        val version: String = "",
        val sizeBytes: Long = 0,
        val description: String = ""
    )
}
