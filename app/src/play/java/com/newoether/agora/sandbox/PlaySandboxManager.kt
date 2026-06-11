package com.newoether.agora.sandbox

/**
 * No-op stub for the Play Store flavor — Linux sandbox is not included.
 */
class PlaySandboxManager : SandboxManager {

    override val lastError: String? = null

    override suspend fun isAvailable(): Boolean = false

    override suspend fun install(): Boolean = false

    override suspend fun executeCommand(
        command: String,
        workdir: String,
        timeoutMs: Int
    ): SandboxManager.SandboxResult = SandboxManager.SandboxResult(
        stdout = "",
        stderr = "Linux Sandbox is not available in this build.",
        exitCode = -1
    )

    override suspend fun fileRead(
        path: String,
        offset: Long,
        limit: Long
    ): String = throw UnsupportedOperationException("Sandbox not available")

    override suspend fun fileWrite(path: String, content: String): String? =
        "Sandbox not available in this build"

    override suspend fun fileGlob(pattern: String, basePath: String): List<String> =
        emptyList()

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String
    ): Result<List<SandboxManager.GrepMatch>> =
        Result.success(emptyList())

    override suspend fun fileEdit(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): SandboxManager.FileEditResult = SandboxManager.FileEditResult(
        replaced = 0,
        error = "Sandbox not available in this build"
    )

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean { onProgress("Sandbox not available"); return false }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = emptyList()

    override suspend fun apkDelete(packageName: String): Boolean = false

    override suspend fun getDiskUsageMB(): Long = 0L

    override suspend fun reset(): Boolean = false
    override fun close() {}
}
