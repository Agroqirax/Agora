package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.SshClient
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ShellToolProvider(
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val imageStore: ToolImageStore? = null,
) : ToolProvider {

    private val sandbox = sandboxFactory?.create()

    /**
     * Optional user-confirmation gate for state-changing operations. The isolated local sandbox
     * normally proceeds directly; once shared storage is mounted, local commands/writes are gated
     * too because they can mutate files outside the app sandbox.
     */
    var confirm: (suspend (server: String, summary: String) -> Boolean)? = null

    private suspend fun confirmTarget(
        device: ShellDeviceConfig?,
        summary: String,
        localSharedStorageExposed: Boolean = false,
    ): Boolean {
        if (device == null && !localSharedStorageExposed) return true
        val target = device?.name?.ifBlank { "${device.type} server" }
            ?: "Local Sandbox · /mnt/shared"
        return confirm?.invoke(target, summary) ?: true
    }

    private fun targetsSharedStorage(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
        return normalized == "/mnt/shared" || normalized.startsWith("/mnt/shared/")
    }

    // ── Helpers ────────────────────────────────────────────

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> {
        return try {
            val argsStr = arguments.ifBlank { "{}" }
            Json.decodeFromString<Map<String, JsonElement>>(argsStr)
        } catch (_: Exception) { emptyMap() }
    }

    private fun jsonError(type: String, message: String, server: String? = null, command: String? = null): String {
        return buildJsonObject {
            if (type.isNotBlank()) put("type", type)
            put("error", "error"); put("message", message)
            if (server != null) put("server", server)
            if (command != null) put("command", command)
        }.toString()
    }

    private fun arg(args: Map<String, JsonElement>, key: String): String {
        return (args[key] as? JsonPrimitive)?.content ?: ""
    }

    private fun boolArg(args: Map<String, JsonElement>, key: String): Boolean =
        (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        if (serverName.equals("Local Sandbox", ignoreCase = true)) return null
        return if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else null
    }

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        val hasSandbox = ctx.sandboxEnabled && sandboxFactory?.isAvailable() == true
        val allNames = buildList {
            if (hasSandbox) add("\"Local Sandbox\"")
            addAll(ctx.shellDevices.map { "\"${it.name}\"" })
        }
        return if (allNames.size == 1) {
            "Unknown server: $serverName. Use ${allNames[0]} or omit the server parameter."
        } else {
            val names = allNames.joinToString(", ")
            if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names."
            else "Unknown server: $serverName. Available: $names."
        }
    }

    // ── Backend sealed interface ───────────────────────────

    private sealed interface Backend {
        /** The remote device this backend targets, or null for the local sandbox (never gated). */
        val device: ShellDeviceConfig?
        suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String
        fun executeCommandEvents(
            cmd: String,
            workdir: String,
            timeoutMs: Int,
        ): Flow<ToolExecutionEvent> = flow {
            emit(ToolExecutionEvent.Completed(executeCommand(cmd, workdir, timeoutMs)))
        }
        suspend fun fileRead(path: String, offset: Long, limit: Long): String
        suspend fun fileWrite(path: String, content: String): String?
        suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>>
        suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>>
        fun close()
    }

    private inner class ConchBackend(override val device: ShellDeviceConfig) : Backend {
        private val url = device.serverUrl.trimEnd('/')
        private val apiKey = device.apiKey
        private val pubKey = device.conchPublicKey
        private val deviceName = device.name

        private val client: ShellClient by lazy { ShellClient(url, apiKey, pubKey) }

        override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String =
            executeCommandInternal(cmd, workdir, timeoutMs) { }

        override fun executeCommandEvents(
            cmd: String,
            workdir: String,
            timeoutMs: Int,
        ): Flow<ToolExecutionEvent> = flow {
            val result = executeCommandInternal(cmd, workdir, timeoutMs) { delta ->
                emit(ToolExecutionEvent.OutputDelta(delta))
            }
            emit(ToolExecutionEvent.Completed(result))
        }

        private suspend fun executeCommandInternal(
            cmd: String,
            workdir: String,
            timeoutMs: Int,
            onOutput: suspend (String) -> Unit,
        ): String {
            if (url.isBlank()) return jsonError("execute_shell_command", "Server \"$deviceName\" has no URL configured.")
            if (!client.fetchPublicKey() && apiKey.isNotBlank()) {
                return jsonError(
                    "execute_shell_command",
                    client.lastError ?: "Conch public-key exchange failed for $url",
                    server = deviceName,
                )
            }
            val prepared = client.prepareRequest(cmd, timeoutMs, workdir)
            val handle = try {
                HttpClient.streamPost(
                    "${prepared.serverUrl}/execute",
                    prepared.body,
                    prepared.headers,
                )
            } catch (e: Exception) {
                return jsonError(
                    "execute_shell_command",
                    com.newoether.agora.util.describeConchRequestFailure(
                        prepared.serverUrl,
                        "/execute request",
                        e,
                    ),
                    server = deviceName,
                    command = cmd,
                )
            }
            if (handle.code !in 200..299) {
                val detail = handle.errorBody
                    ?.take(240)
                    ?.ifBlank { "empty response" }
                    ?: "empty response"
                handle.close()
                return jsonError(
                    "execute_shell_command",
                    "Conch at ${prepared.serverUrl} returned HTTP ${handle.code}: $detail",
                    server = deviceName,
                    command = cmd,
                )
            }
            return try {
                val output = StringBuilder()
                var exitCode: Int? = null
                var errorMessage: String? = null
                // Conch's structured discriminator for "the deadline killed the process".
                // Never infer this from the message text: a command's OWN timeout (curl's
                // "Operation timed out", a Go "i/o timeout") reads identically and would cause a
                // non-idempotent command to be silently re-run as a background job.
                var timedOut = false
                // Non-fatal degradation (currently output truncation). Kept apart from
                // errorMessage so a truncated line still reports the command's real exit code
                // instead of relabelling a successful command as execution_error.
                var warningMessage: String? = null
                var currentEvent: String? = null
                val aesKey = client.getSessionKey()
                stream@ while (currentCoroutineContext().isActive) {
                    val line = handle.readLine() ?: break
                    when {
                        line.startsWith("event: ") -> currentEvent = line.substring(7).trim()
                        line.startsWith("data: ") -> {
                            var dataStr = line.substring(6).trim()
                            if (aesKey != null) {
                                try {
                                    dataStr = client.decryptSseData(dataStr)
                                } catch (e: Exception) {
                                    errorMessage =
                                        "Conch stream decryption failed at $url: " +
                                            (e.message ?: e.javaClass.simpleName)
                                    break@stream
                                }
                            }
                            val dataJson = try { Json.parseToJsonElement(dataStr).jsonObject } catch (_: Exception) { null } ?: continue
                            when (currentEvent) {
                                "line" -> {
                                    val text = (dataJson["line"] as? JsonPrimitive)?.content
                                    if (text != null) {
                                        val delta = "$text\n"
                                        output.append(delta)
                                        onOutput(delta)
                                    }
                                }
                                "result" -> exitCode = (dataJson["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull()
                                "warning" -> {
                                    if (warningMessage == null) {
                                        warningMessage =
                                            (dataJson["message"] as? JsonPrimitive)?.content
                                    }
                                }
                                "error" -> {
                                    errorMessage = (dataJson["message"] as? JsonPrimitive)?.content
                                    timedOut = (dataJson["timed_out"] as? JsonPrimitive)
                                        ?.content?.toBooleanStrictOrNull() == true
                                }
                            }
                        }
                    }
                }
                buildJsonObject {
                    put("type", "execute_shell_command"); put("server", deviceName); put("command", cmd)
                    if (errorMessage != null) { put("error", "execution_error"); put("message", errorMessage); if (timedOut) put("timed_out", true) }
                    else { put("exit_code", exitCode ?: -1) }
                    // Emitted alongside a normal exit code on purpose: the output is incomplete but
                    // the command itself succeeded or failed on its own terms.
                    warningMessage?.let { put("warning", it) }
                    put("output", output.toString().trimEnd())
                }.toString()
            } catch (e: Exception) {
                jsonError("execute_shell_command", e.message ?: "Unknown error", server = deviceName, command = cmd)
            } finally { handle.close() }
        }

        override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
            val result = client.fileRead(path, offset, limit)
            if (result.error != null) return jsonError("file_read", result.error, server = deviceName)
            return buildJsonObject {
                put("type", "file_read"); put("server", deviceName); put("path", path)
                put("content", result.content); put("lines", result.lines)
            }.toString()
        }

        override suspend fun fileWrite(path: String, content: String): String? =
            client.fileWrite(path, content)?.let { jsonError("file_write", it, server = deviceName) }

        override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
            client.fileGlob(pattern, basePath, depth)

        override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>> =
            client.fileGrep(pattern, basePath, fileGlob)

        override fun close() {}

        suspend fun startJob(cmd: String, workdir: String, timeoutMs: Int): String =
            client.startJob(cmd, timeoutMs, workdir)

        suspend fun listJobs(): String = client.listJobs()

        suspend fun getJob(jobId: String): String = client.getJob(jobId)

        suspend fun stopJob(jobId: String): String = client.stopJob(jobId)

        suspend fun viewImage(path: String): ShellClient.FileImageResult =
            client.fileImage(path)
    }

    private inner class SshBackend(override val device: ShellDeviceConfig) : Backend {
        private val host = device.sshHost
        private val port = device.sshPort
        private val user = device.sshUser
        private val password = device.sshPassword
        private val deviceName = device.name
        private val hostKey = device.sshHostKey

        private val client: SshClient by lazy {
            SshClient(
                host, port, user, password,
                pinnedHostKey = hostKey,
                // Un-pinned devices stay usable (capture-only); once a key is pinned it is enforced.
                allowUnknownHostKey = hostKey.isBlank()
            )
        }

        override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
            if (host.isBlank()) return jsonError("execute_shell_command", "SSH device \"$deviceName\" has no host configured.")
            return try {
                val result = client.executeCommand(cmd, workdir, timeoutMs)
                buildJsonObject {
                    put("type", "execute_shell_command"); put("server", deviceName); put("command", cmd)
                    put("exit_code", result.exitCode)
                    put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
                }.toString()
            } catch (e: Exception) {
                jsonError("execute_shell_command", e.message ?: "Unknown error", server = deviceName, command = cmd)
            }
        }

        override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
            return try {
                val content = client.fileRead(path, offset, limit)
                buildJsonObject {
                    put("type", "file_read"); put("server", deviceName); put("path", path)
                    put("content", content); put("lines", content.lines().size)
                }.toString()
            } catch (e: Exception) {
                jsonError("file_read", "SFTP read failed: ${e.message}", server = deviceName)
            }
        }

        override suspend fun fileWrite(path: String, content: String): String? =
            client.fileWrite(path, content)?.let { jsonError("file_write", it, server = deviceName) }

        override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
            Result.success(client.fileGlob(pattern, basePath, depth))

        override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>> =
            client.fileGrep(pattern, basePath, fileGlob).map { matches ->
                matches.map { ShellClient.GrepMatch(it.path, it.line, it.content) }
            }

        override fun close() { client.close() }
    }

    private inner class SandboxBackend : Backend {
        override val device: ShellDeviceConfig? get() = null
        private val mgr = sandbox ?: throw IllegalStateException("Sandbox not available")

        override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
            if (!mgr.isAvailable()) return jsonError("execute_shell_command", "Local Sandbox is not installed.")
            return try {
                val result = mgr.executeCommand(cmd, workdir, timeoutMs)
                buildJsonObject {
                    put("type", "execute_shell_command"); put("server", "Local Sandbox"); put("command", cmd)
                    put("exit_code", result.exitCode)
                    put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
                }.toString()
            } catch (e: Exception) {
                jsonError("execute_shell_command", e.message ?: "Unknown error", server = "Local Sandbox", command = cmd)
            }
        }

        override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
            return try {
                val content = mgr.fileRead(path, offset, limit)
                buildJsonObject {
                    put("type", "file_read"); put("server", "Local Sandbox"); put("path", path)
                    put("content", content); put("lines", content.lines().size)
                }.toString()
            } catch (e: Exception) {
                jsonError("file_read", e.message ?: "Read failed", server = "Local Sandbox")
            }
        }

        override suspend fun fileWrite(path: String, content: String): String? =
            mgr.fileWrite(path, content)?.let { jsonError("file_write", it, server = "Local Sandbox") }

        override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
            Result.success(mgr.fileGlob(pattern, basePath, depth))

        override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>> =
            mgr.fileGrep(pattern, basePath, fileGlob).map { matches ->
                matches.map { ShellClient.GrepMatch(it.path, it.line, it.content) }
            }

        override fun close() {}
    }

    private suspend fun getBackend(serverName: String, ctx: GenerationContext): Backend? {
        // Local Sandbox
        if (serverName.equals("Local Sandbox", ignoreCase = true) && ctx.sandboxEnabled) {
            if (sandbox?.isAvailable() == true) return SandboxBackend()
            if (sandbox != null) return null
        }
        if (serverName.isBlank()) {
            if (ctx.sandboxEnabled && sandbox?.isAvailable() == true) {
                return SandboxBackend()
            }
        }
        val device = resolveShellDevice(serverName, ctx) ?: return null
        return when (device.type) {
            "ssh" -> SshBackend(device)
            else -> ConchBackend(device)
        }
    }

    // ── ToolProvider interface ─────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled) return emptyList()
        if (ctx.shellDevices.isEmpty() && !ctx.sandboxEnabled) return emptyList()

        val hasLocal = ctx.sandboxEnabled
        val allDeviceNames = buildList {
            if (hasLocal) add("Local Sandbox")
            addAll(ctx.shellDevices.map { d -> "\"${d.name}\"" })
        }
        val deviceNamesStr = allDeviceNames.joinToString(", ")

        val serverPropDesc = if (allDeviceNames.size == 1) {
            "The shell server name (optional, defaults to the only available server: ${allDeviceNames[0]})."
        } else {
            "The shell server name. Use list_shells to see available servers: $deviceNamesStr."
        }
        // timeout_ms is REQUIRED: the model must decide how long the tool call should wait. Conch
        // foreground execution is durable from the start, so expiry returns its job id without
        // killing or restarting the command.
        val shellRequiredParams =
            if (allDeviceNames.size == 1) listOf("command", "timeout_ms")
            else listOf("command", "server", "timeout_ms")

        val conchDeviceNames = ctx.shellDevices
            .filter { it.type != "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        val shellTools = buildList {
            add(ToolDefinition(function = ToolFunction(
                name = "list_shells",
                description = "List configured shell servers including the local sandbox (if enabled).",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "execute_shell_command",
                description = "Execute a shell command. Set background=true for a durable Conch job that survives client disconnects.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "command" to ToolProperty("string", "The shell command to execute."),
                        "server" to ToolProperty("string", serverPropDesc),
                        "timeout_ms" to ToolProperty("integer", "Required. Foreground wait budget in milliseconds (hard ceiling 295000ms inside the tool call). On Conch, a command still running at that point continues as the same durable job and returns its job_id; it is never killed or restarted. With background=true this instead bounds the durable job's runtime (up to Conch policy)."),
                        "workdir" to ToolProperty("string", "Working directory (optional)."),
                        "background" to ToolProperty("boolean", "Start a durable background job on Conch and return its job_id immediately (optional, default false)."),
                    ),
                    required = shellRequiredParams
                )
            )))
            if (conchDeviceNames.isNotEmpty()) {
                val jobServerDescription = if (conchDeviceNames.size == 1) {
                    "Conch server name (optional; defaults to ${conchDeviceNames.single()})."
                } else {
                    "Conch server name. Available: ${conchDeviceNames.joinToString(", ")}."
                }
                val jobRequired = if (conchDeviceNames.size == 1) {
                    emptyList()
                } else {
                    listOf("server")
                }
                add(ToolDefinition(function = ToolFunction(
                    name = "list_shell_jobs",
                    description = "List durable background shell jobs on a Conch server.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "get_shell_job",
                    description = "Get status and bounded output for a durable Conch shell job. Prefer wait_for_job for blocking use.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id") + jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "wait_for_job",
                    description = "Block until a durable Conch shell job finishes or timeout_ms elapses, then return its final output. Preferred over polling get_shell_job. If it returns timed_out=true the job is still running — call wait_for_job again to keep waiting.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "timeout_ms" to ToolProperty("integer", "Required. Maximum time to block in milliseconds before returning (whether or not the job finished). The hard ceiling for a single call is ${maxWaitMs(ctx)}ms; larger values are clamped to it and the result says so. To wait longer, call wait_for_job again."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id", "timeout_ms") + jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "stop_shell_job",
                    description = "Stop a running durable Conch shell job and its process tree.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id") + jobRequired,
                    ),
                )))
            }
        }

        val fileServerProperty = if (allDeviceNames.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only available server).")
        } else {
            ToolProperty("string", "The shell server name. Available: $deviceNamesStr.")
        }
        val fileRequired = if (allDeviceNames.size == 1) emptyList<String>() else listOf("server")

        val fileTools = listOf(
            ToolDefinition(function = ToolFunction(
                name = "file_read",
                description = "Read a file from a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "server" to fileServerProperty,
                        "offset" to ToolProperty("integer", "Byte offset (optional)."),
                        "limit" to ToolProperty("integer", "Max bytes to read (optional, default 1MB).")
                    ),
                    required = listOf("path") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_write",
                description = "Write content to a file on a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "content" to ToolProperty("string", "Content to write."),
                        "server" to fileServerProperty
                    ),
                    required = listOf("path", "content") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_edit",
                description = "Edit a file on a shell server or local sandbox by replacing old_string with new_string.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "old_string" to ToolProperty("string", "The exact text to find and replace."),
                        "new_string" to ToolProperty("string", "The replacement text."),
                        "server" to fileServerProperty,
                        "replace_all" to ToolProperty("boolean", "Replace all occurrences (optional, default false).")
                    ),
                    required = listOf("path", "old_string", "new_string") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_glob",
                description = "List files on a shell server or local sandbox matching a glob pattern.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Glob pattern matched against file names (e.g. '*.go', '*.md')."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "Base directory for the search (optional)."),
                        "depth" to ToolProperty("integer", "Max directory levels to search below 'path': 1 = base directory only, higher values recurse deeper, 0 = unlimited recursion. Omit for the server default.")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_grep",
                description = "Search for a regex pattern in files on a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Regular expression pattern to search for."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "File or directory to search in (optional)."),
                        "glob" to ToolProperty("string", "Filter files by glob pattern (optional).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            ))
        )

        val imageTools = if (conchDeviceNames.isEmpty()) {
            emptyList()
        } else {
            val imageServerDescription = if (conchDeviceNames.size == 1) {
                "Conch server name (optional; defaults to ${conchDeviceNames.single()})."
            } else {
                "Conch server name. Available: ${conchDeviceNames.joinToString(", ")}."
            }
            val imageRequired = if (conchDeviceNames.size == 1) {
                listOf("path")
            } else {
                listOf("path", "server")
            }
            listOf(
                ToolDefinition(
                    function = ToolFunction(
                        name = "view_image",
                        description =
                            "Load an image from a Conch device and return it as visual context.",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "path" to ToolProperty(
                                    "string",
                                    "Absolute path to the image file.",
                                ),
                                "server" to ToolProperty("string", imageServerDescription),
                            ),
                            required = imageRequired,
                        ),
                    ),
                ),
            )
        }

        return shellTools + fileTools + imageTools
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        return when (name) {
            "list_shells" -> listShells(ctx)
            "execute_shell_command" -> executeShellCommand(arguments, ctx)
            "list_shell_jobs" -> listShellJobs(arguments, ctx)
            "get_shell_job" -> getShellJob(arguments, ctx)
            "wait_for_job" -> waitForShellJob(arguments, ctx)
            "stop_shell_job" -> stopShellJob(arguments, ctx)
            "file_read" -> executeFileRead(arguments, ctx)
            "file_write" -> executeFileWrite(arguments, ctx)
            "file_edit" -> executeFileEdit(arguments, ctx)
            "file_glob" -> executeFileGlob(arguments, ctx)
            "file_grep" -> executeFileGrep(arguments, ctx)
            "view_image" -> executeViewImage(arguments, ctx).text
            else -> "Unknown tool: $name"
        }
    }

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> {
        return when (name) {
            "execute_shell_command" -> executeShellCommandEvents(arguments, ctx)
            "view_image" -> flow {
                emit(ToolExecutionEvent.Completed(executeViewImage(arguments, ctx)))
            }
            else -> super<ToolProvider>.executeEvents(name, arguments, ctx)
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_shells", "execute_shell_command",
        "list_shell_jobs", "get_shell_job", "wait_for_job", "stop_shell_job",
        "file_read", "file_write", "file_edit", "file_glob", "file_grep", "view_image"
    )

    // ── list_shells ────────────────────────────────────────

    private suspend fun listShells(ctx: GenerationContext): String {
        val items = buildList {
            val sandboxOk = ctx.sandboxEnabled && sandbox?.isAvailable() == true
            if (sandboxOk) {
                add(buildJsonObject {
                    put("name", "Local Sandbox")
                    put("description", "Alpine Linux on-device")
                    put("type", "local")
                })
            }
            ctx.shellDevices.forEach { d ->
                add(buildJsonObject {
                    put("name", d.name.ifBlank { "Untitled" })
                    put("description", d.description)
                    put("type", d.type)
                    when (d.type) {
                        "ssh" -> { put("host", d.sshHost); put("port", d.sshPort) }
                        else -> put("url", d.serverUrl)
                    }
                })
            }
        }
        return buildJsonObject {
            put("type", "list_shells")
            putJsonArray("devices") { items.forEach { add(it) } }
        }.toString()
    }

    // ── Shell execution ────────────────────────────────────

    private suspend fun executeShellCommand(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("execute_shell_command", "no_command")
        val serverName = arg(args, "server")
        val background = boolArg(args, "background")
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val timeoutMax = if (background) BACKGROUND_JOB_MAX_MS else foregroundMaxMs
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "execute_shell_command", "timeout_ms is required", server = serverName, command = command,
        )
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: return jsonError(
                "execute_shell_command",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = serverName,
                command = command,
            )).coerceIn(1000, timeoutMax)
        val workdir = arg(args, "workdir")

        if (background) {
            val backend = getConchBackend(serverName, ctx)
                ?: return jsonError(
                    "execute_shell_command",
                    conchServerNotFoundMessage(serverName, ctx),
                    server = serverName,
                    command = command,
                )
            if (!confirmTarget(backend.device, "start background job: $ $command")) {
                return jsonError(
                    "execute_shell_command",
                    "denied_by_user: the user declined to run this background command",
                    server = backend.device.name,
                    command = command,
                )
            }
            return try {
                backend.startJob(command, workdir, timeoutMs)
            } catch (e: Exception) {
                jsonError(
                    "execute_shell_command",
                    e.message ?: "Failed to start background job",
                    server = backend.device.name,
                    command = command,
                )
            }
        }

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("execute_shell_command", serverNotFoundMessage(serverName, ctx))
        return try {
            // Gate on the backend's ACTUAL target: with a blank server name the sandbox wins
            // resolution, while resolveShellDevice() would name an unrelated remote device.
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed =
                        backend.device == null && ctx.sandboxSharedStorageEnabled,
                )
            ) {
                return jsonError("execute_shell_command", "denied_by_user: the user declined to run this command", server = serverName, command = command)
            }
            if (backend is ConchBackend) {
                executeDurableForeground(
                    backend = backend,
                    command = command,
                    workdir = workdir,
                    waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                )
            } else {
                backend.executeCommand(command, workdir, timeoutMs)
            }
        } finally {
            backend.close()
        }
    }

    /**
     * Starts a Conch command as a durable job, then treats foreground execution as a bounded wait
     * on that same process. A wait expiry returns ownership to the model through job_id; it never
     * kills or replays the command.
     */
    private suspend fun executeDurableForeground(
        backend: ConchBackend,
        command: String,
        workdir: String,
        waitMs: Int,
    ): String {
        val startResult = try {
            backend.startJob(command, workdir, BACKGROUND_JOB_MAX_MS)
        } catch (e: Exception) {
            return jsonError(
                "execute_shell_command",
                e.message ?: "Failed to start durable foreground job",
                server = backend.device.name,
                command = command,
            )
        }
        val startObj = try {
            Json.parseToJsonElement(startResult).jsonObject
        } catch (_: Exception) {
            return startResult
        }
        if (startObj["error"] != null) return startResult
        val jobId = (startObj["job_id"] as? JsonPrimitive)?.content
            ?.takeIf(String::isNotBlank)
            ?: return jsonError(
                "execute_shell_command",
                "Conch started a job without returning job_id",
                server = backend.device.name,
                command = command,
            )

        val start = System.currentTimeMillis()
        var pollIntervalMs = INITIAL_WAIT_POLL_MS
        var consecutiveFailures = 0
        var lastFailure: String? = null
        try {
        while (currentCoroutineContext().isActive) {
            val raw = try {
                backend.getJob(jobId).also { consecutiveFailures = 0 }
            } catch (e: Exception) {
                consecutiveFailures++
                lastFailure = e.message ?: e.javaClass.simpleName
                if (consecutiveFailures >= MAX_WAIT_POLL_FAILURES) {
                    return buildJsonObject {
                        put("type", "execute_shell_command")
                        put("error", "poll_failed")
                        put(
                            "message",
                            "Durable job could not be polled $consecutiveFailures times: " +
                                lastFailure,
                        )
                        put("server", backend.device.name)
                        put("command", command)
                        put("job_id", jobId)
                        put("durable", true)
                        put("state", "unknown")
                        put(
                            "note",
                            "The command may still be running. Keep this job_id and retry with " +
                                "wait_for_job or get_shell_job; it was not killed or restarted.",
                        )
                    }.toString()
                }
                null
            }
            if (raw != null && isTerminalJobPayload(raw)) {
                val result = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                return buildJsonObject {
                    put("type", "execute_shell_command")
                    put("server", backend.device.name)
                    put("command", command)
                    put("job_id", jobId)
                    put("durable", true)
                    if (result != null) put("result", result) else put("result_raw", raw)
                }.toString()
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= waitMs) {
                return buildJsonObject {
                    put("type", "execute_shell_command")
                    put("server", backend.device.name)
                    put("command", command)
                    put("job_id", jobId)
                    put("background", true)
                    put("state", "running")
                    put("waited_ms", elapsed)
                    put(
                        "note",
                        "Foreground wait expired; the same durable job is still running. Use " +
                            "wait_for_job to await it. The command was not killed or restarted.",
                    )
                }.toString()
            }
            val remaining = (waitMs - elapsed).toInt()
            kotlinx.coroutines.delay(pollIntervalMs.coerceAtMost(remaining).toLong())
            pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_WAIT_POLL_MS)
        }
        } catch (cancelled: CancellationException) {
            // A wait expiry intentionally leaves the durable job running. An explicit generation
            // Stop is different: it revokes this tool execution and stops the remote process tree.
            withContext(NonCancellable) { runCatching { backend.stopJob(jobId) } }
            throw cancelled
        }
        return jsonError(
            "execute_shell_command",
            "cancelled while durable job $jobId continues on ${backend.device.name}",
            server = backend.device.name,
            command = command,
        )
    }

    private fun executeShellCommandEvents(
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) {
            emit(ToolExecutionEvent.Completed(jsonError("execute_shell_command", "no_command")))
            return@flow
        }
        val serverName = arg(args, "server")
        if (boolArg(args, "background")) {
            val device = resolveShellDevice(serverName, ctx)?.takeIf { it.type != "ssh" }
            if (device == null) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            conchServerNotFoundMessage(serverName, ctx),
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            emit(ToolExecutionEvent.TargetResolved(device.name))
            emit(ToolExecutionEvent.Progress("Starting durable background job"))
            // executeShellCommand owns the one confirmation and backend lifecycle.
            emit(ToolExecutionEvent.Completed(executeShellCommand(arguments, ctx)))
            return@flow
        }
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError(
                        "execute_shell_command", "timeout_ms is required",
                        server = serverName, command = command,
                    ),
                ),
            )
            return@flow
        }
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: run {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            "timeout_ms must be an integer, got \"$rawTimeout\"",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }).coerceIn(1000, foregroundMaxMs)
        val workdir = arg(args, "workdir")
        val backend = getBackend(serverName, ctx)
        if (backend == null) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError("execute_shell_command", serverNotFoundMessage(serverName, ctx)),
                ),
            )
            return@flow
        }
        emit(
            ToolExecutionEvent.TargetResolved(
                backend.device?.name?.ifBlank { "Untitled" } ?: "Local Sandbox",
            ),
        )
        try {
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed =
                        backend.device == null && ctx.sandboxSharedStorageEnabled,
                )
            ) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            "denied_by_user: the user declined to run this command",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            if (backend is ConchBackend) {
                emit(ToolExecutionEvent.Progress("Running as a durable foreground job"))
                emit(
                    ToolExecutionEvent.Completed(
                        executeDurableForeground(
                            backend = backend,
                            command = command,
                            workdir = workdir,
                            waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                        )
                    )
                )
            } else {
                emit(ToolExecutionEvent.Progress("Running command"))
                backend.executeCommandEvents(command, workdir, timeoutMs).collect { emit(it) }
            }
        } finally {
            backend.close()
        }
    }

    private fun getConchBackend(
        serverName: String,
        ctx: GenerationContext,
    ): ConchBackend? {
        val conchDevices = ctx.shellDevices.filter { it.type != "ssh" }
        val device = if (serverName.isNotBlank()) {
            conchDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else {
            conchDevices.singleOrNull()
        } ?: return null
        return ConchBackend(device)
    }

    private fun conchServerNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        val names = ctx.shellDevices
            .filter { it.type != "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        return when {
            names.isEmpty() -> "No Conch server is configured. Background jobs require Conch."
            serverName.isNotBlank() ->
                "Unknown Conch server \"$serverName\". Available: ${names.joinToString(", ")}."
            names.size > 1 ->
                "Multiple Conch servers are available. Specify one: ${names.joinToString(", ")}."
            else -> "Conch server is unavailable."
        }
    }

    private suspend fun listShellJobs(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "list_shell_jobs",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            backend.listJobs()
        } catch (e: Exception) {
            jsonError(
                "list_shell_jobs",
                e.message ?: "Failed to list shell jobs",
                server = backend.device.name,
            )
        }
    }

    private suspend fun getShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("get_shell_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "get_shell_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            backend.getJob(jobId)
        } catch (e: Exception) {
            jsonError(
                "get_shell_job",
                e.message ?: "Failed to get shell job",
                server = backend.device.name,
            )
        }
    }

    private suspend fun waitForShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("wait_for_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "wait_for_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "wait_for_job", "timeout_ms is required", server = backend.device.name,
        )
        val requestedMs = rawTimeout.toIntOrNull()
            ?: return jsonError(
                "wait_for_job",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = backend.device.name,
            )
        // The whole tool call runs under GenerationManager's withTimeout(ctx.toolTimeoutMs). A wait
        // that reaches that outer ceiling is killed as a generic tool timeout, so its graceful
        // "still running, call again" note never fires. Cap the effective wait strictly below the
        // outer budget (leaving a margin to emit the note) so the structured result always wins.
        val ceilingMs = maxWaitMs(ctx)
        val timeoutMs = requestedMs.coerceIn(MIN_WAIT_JOB_MS, ceilingMs)
        // Report silent clamping. Otherwise a model that asked for 10 minutes reads timed_out=true
        // after ~5 and concludes the job hung for the full budget it never actually waited.
        val clampedFrom = requestedMs.takeIf { it > ceilingMs }
        val start = System.currentTimeMillis()
        // A transient poll failure must not abort the wait: the job keeps running on the device.
        // Only a sustained run of failures is fatal.
        var consecutiveFailures = 0
        var lastFailure: String? = null
        var pollIntervalMs = INITIAL_WAIT_POLL_MS
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val raw = try {
                backend.getJob(jobId).also { consecutiveFailures = 0 }
            } catch (e: Exception) {
                consecutiveFailures++
                lastFailure = e.message ?: e.javaClass.simpleName
                if (consecutiveFailures >= MAX_WAIT_POLL_FAILURES) {
                    return buildJsonObject {
                        put("type", "wait_for_job")
                        put("error", "poll_failed")
                        put(
                            "message",
                            "Failed to poll job $consecutiveFailures times in a row: $lastFailure",
                        )
                        put("server", backend.device.name)
                        put("job_id", jobId)
                        put("durable", true)
                        put("state", "unknown")
                        put(
                            "note",
                            "The job may still be running. Retry with the same job_id; it was not " +
                                "stopped by this wait failure.",
                        )
                    }.toString()
                }
                null
            }
            if (raw != null && isTerminalJobPayload(raw)) {
                val result = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                return buildJsonObject {
                    put("type", "wait_for_job")
                    put("job_id", jobId)
                    put("waited_ms", System.currentTimeMillis() - start)
                    if (result != null) put("result", result) else put("result_raw", raw)
                }.toString()
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                val clampNote = clampedFrom?.let {
                    " The requested timeout_ms=$it exceeded this tool call's ceiling of ${ceilingMs}ms and was clamped, so the job has only been waited on for that long."
                } ?: ""
                return buildJsonObject {
                    put("type", "wait_for_job")
                    put("job_id", jobId)
                    put("waited_ms", elapsed)
                    put("timed_out", true)
                    put(
                        "note",
                        "Job still running. Call wait_for_job again to keep waiting, or " +
                            "get_shell_job for a one-shot look.$clampNote",
                    )
                }.toString()
            }
            // Back off so a long wait does not hammer the device, but never overshoot the deadline.
            val remaining = (timeoutMs - elapsed).toInt()
            kotlinx.coroutines.delay(pollIntervalMs.coerceAtMost(remaining).toLong())
            pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_WAIT_POLL_MS)
        }
        return jsonError("wait_for_job", "cancelled", server = backend.device.name)
    }

    /**
     * Decides whether a raw `/jobs/get` payload represents a finished job.
     *
     * Conch reports lifecycle in the **`state`** field (see conch shell/jobs.go): `running` and
     * `stopping` are live; `succeeded`, `failed`, `stopped` and `interrupted` are terminal. An
     * explicit server-side `error` (e.g. "job not found") is also terminal, because polling again
     * cannot change it. An unparseable or field-less payload is deliberately NOT terminal: a
     * transport hiccup must never be reported to the model as "the job finished".
     */
    private fun isTerminalJobPayload(raw: String): Boolean {
        if (raw.isBlank()) return false
        val obj = try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return false
        }
        if (obj["error"] != null) return true
        val state = (obj["state"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.lowercase()
            ?: return false
        return state in TERMINAL_JOB_STATES
    }

    private suspend fun stopShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("stop_shell_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "stop_shell_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        if (!confirmTarget(backend.device, "stop background shell job: $jobId")) {
            return jsonError(
                "stop_shell_job",
                "denied_by_user: the user declined to stop this job",
                server = backend.device.name,
            )
        }
        return try {
            backend.stopJob(jobId)
        } catch (e: Exception) {
            jsonError(
                "stop_shell_job",
                e.message ?: "Failed to stop shell job",
                server = backend.device.name,
            )
        }
    }

    // ── File tools ─────────────────────────────────────────

    private suspend fun executeViewImage(
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) {
            return ToolExecutionResult(
                text = jsonError("view_image", "path is required"),
                isError = true,
            )
        }
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    conchServerNotFoundMessage(serverName, ctx),
                    server = serverName,
                ),
                isError = true,
            )
        val store = imageStore
            ?: return ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    "Tool image storage is unavailable",
                    server = backend.device.name,
                ),
                isError = true,
            )
        return try {
            val remote = backend.viewImage(path)
            if (remote.error != null) {
                return ToolExecutionResult(
                    text = jsonError(
                        "view_image",
                        remote.error,
                        server = backend.device.name,
                    ),
                    isError = true,
                )
            }
            val attachment = withContext(Dispatchers.IO) {
                store.persistBase64(
                    data = remote.data,
                    mimeType = remote.mimeType,
                    filePrefix = "conch",
                )
            }
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "view_image")
                    put("server", backend.device.name)
                    put("path", path)
                    put("mime_type", attachment.mimeType)
                    put("size", attachment.sizeBytes)
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    put("ok", true)
                }.toString(),
                images = listOf(attachment),
            )
        } catch (error: Exception) {
            ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    error.message ?: "Failed to load image",
                    server = backend.device.name,
                ),
                isError = true,
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileRead(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_read", "path is required")
        val serverName = arg(args, "server")
        val offset = arg(args, "offset").toLongOrNull() ?: 0L
        val limit = arg(args, "limit").toLongOrNull() ?: 0L

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_read", serverNotFoundMessage(serverName, ctx))
        try {
            return backend.fileRead(path, offset, limit)
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileWrite(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_write", "path is required")
        val content = arg(args, "content")
        if (content.isBlank()) return jsonError("file_write", "content is required")
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_write", serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    backend.device,
                    "write file: $path",
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
                )
            ) {
                return jsonError("file_write", "denied_by_user: the user declined to write this file", server = serverName)
            }
            val error = backend.fileWrite(path, content)
            if (error != null) return error
            return buildJsonObject {
                put("type", "file_write"); put("path", path); put("ok", true)
            }.toString()
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileEdit(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_edit", "path is required")
        val oldStr = arg(args, "old_string")
        if (oldStr.isBlank()) return jsonError("file_edit", "old_string is required")
        val newStr = arg(args, "new_string")
        val replaceAll = arg(args, "replace_all").equals("true", ignoreCase = true)
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_edit", serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    backend.device,
                    "edit file: $path",
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
                )
            ) {
                return jsonError("file_edit", "denied_by_user: the user declined to edit this file", server = serverName)
            }
            // Read the file
            val rawContent = try {
                backend.fileRead(path, 0, 0)
            } catch (e: Exception) {
                return jsonError("file_edit", "read error: ${e.message}")
            }
            // Extract actual content (Conch wraps it in JSON, others return raw text)
            val actualContent = try {
                val obj = Json.parseToJsonElement(rawContent).jsonObject
                (obj["content"] as? JsonPrimitive)?.content ?: rawContent
            } catch (_: Exception) { rawContent }

            val count = actualContent.split(oldStr).size - 1
            if (count == 0) {
                return jsonError("file_edit", "old_string not found in file")
            }
            if (count > 1 && !replaceAll) {
                return jsonError("file_edit", "Found $count matches. Use replace_all=true or provide more context.")
            }
            val replaced = actualContent.replace(oldStr, newStr)
            val writeError = backend.fileWrite(path, replaced)
            if (writeError != null) {
                return jsonError("file_edit", "write error: $writeError")
            }
            return buildJsonObject {
                put("type", "file_edit"); put("path", path)
                put("replaced", if (replaceAll) count else 1)
            }.toString()
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileGlob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("file_glob", "pattern is required")
        val serverName = arg(args, "server")
        val basePath = arg(args, "path")
        // Absent/blank → null → backward-compatible default behavior per backend.
        val depth = arg(args, "depth").toIntOrNull()

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_glob", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGlob(pattern, basePath, depth)
            return result.fold(
                onSuccess = { files ->
                    buildJsonObject {
                        put("type", "file_glob"); put("pattern", pattern)
                        putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
                    }.toString()
                },
                onFailure = { e -> jsonError("file_glob", e.message ?: "Unknown error") }
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileGrep(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("file_grep", "pattern is required")
        val serverName = arg(args, "server")
        val basePath = arg(args, "path")
        val fileGlob = arg(args, "glob")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_grep", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGrep(pattern, basePath, fileGlob)
            return result.fold(
                onSuccess = { matches ->
                    buildJsonObject {
                        put("type", "file_grep"); put("pattern", pattern)
                        putJsonArray("matches") {
                            matches.forEach { m ->
                                add(buildJsonObject {
                                    put("path", m.path); put("line", m.line); put("content", m.content)
                                })
                            }
                        }
                    }.toString()
                },
                onFailure = { e -> jsonError("file_grep", e.message ?: "Unknown error") }
            )
        } finally {
            backend.close()
        }
    }

    companion object {
        /** Conch's durable-job runtime ceiling (24h). The single source for both promotion paths. */
        private const val BACKGROUND_JOB_MAX_MS = 86_400_000

        private const val MIN_WAIT_JOB_MS = 1_000

        /** Headroom below the outer per-tool budget so the "still running" note can be emitted
         *  before GenerationManager's withTimeout would otherwise kill the wait. */
        private const val WAIT_JOB_OUTER_MARGIN_MS = 5_000L

        /**
         * The real ceiling for one `wait_for_job` call, derived from the enclosing per-tool budget.
         *
         * There is no independent constant on purpose. Any larger literal would be dead code: the
         * outer `withTimeout(ctx.toolTimeoutMs)` kills the call first, and the tool description
         * would then advertise a wait the tool cannot perform.
         */
        internal fun maxWaitMs(ctx: GenerationContext): Int =
            (ctx.toolTimeoutMs - WAIT_JOB_OUTER_MARGIN_MS)
                .coerceAtLeast(MIN_WAIT_JOB_MS.toLong())
                .toInt()

        /** Poll cadence for wait_for_job: starts tight for short jobs, backs off for long ones. */
        private const val INITIAL_WAIT_POLL_MS = 500
        private const val MAX_WAIT_POLL_MS = 5_000
        private const val MAX_WAIT_POLL_FAILURES = 5

        /** Terminal `state` values reported by conch's job manager (shell/jobs.go). */
        private val TERMINAL_JOB_STATES = setOf(
            "succeeded",
            "failed",
            "stopped",
            "interrupted",
        )
    }
}
