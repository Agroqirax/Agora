package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** The `serverInfo` block a server returns from `initialize` (name + version),
 *  surfaced in the settings UI as a badge once a connection has succeeded. */
data class McpServerInfo(val name: String, val version: String)

/**
 * [ToolProvider] for remote MCP (Model Context Protocol) servers, speaking the
 * Streamable HTTP transport (JSON-RPC 2.0 over POST — see modelcontextprotocol.io).
 * Each server's tools are discovered via `tools/list` and namespaced as
 * `mcp__<serverSlug>__<toolName>` so identically-named tools from different servers
 * never collide.
 *
 * Per the MCP spec, a tool's `annotations.destructiveHint` is a *hint*, not a security
 * boundary — so absent that hint we treat the tool as destructive (the safer default)
 * unless it's explicitly marked `readOnlyHint = true`. Only destructive tools ever go
 * through [confirm]; everything else runs immediately. If the "confirm MCP tools"
 * setting is off, the confirmation controller itself always returns true, so every
 * call is allowed — this class only decides *whether to ask*, not the answer.
 */
class McpToolProvider(private val sandboxFactory: SandboxManagerFactory? = null) : ToolProvider {
    /** User-confirmation gate for destructive MCP tool calls. Set by GenerationManager,
     *  which wires it to the ViewModel's confirmation controller. Returns true to
     *  proceed, false to deny. Never called for non-destructive tools. [serverId] is
     *  the stable [McpServerConfig.id] — use this, not [serverName], as the trust-list
     *  key: display names can collide or be renamed after being trusted. */
    var confirm: (suspend (serverId: String, serverName: String, toolName: String, summary: String) -> Boolean)? = null

    /** Set by GenerationManager, wired to a single shared [McpOAuthManager] instance.
     *  Used to refresh an OAuth server's access token on a 401, and to mark
     *  [ServerState.needsReauth]/[reauthNeededFlow] when a refresh itself fails. */
    var oauthManager: McpOAuthManager? = null

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val TOOL_CACHE_TTL_MS = 30_000L
        const val PREFIX = "mcp__"
        // Internal-only RpcResult.error marker: an OAuth server returned 401 and the
        // refresh attempt also failed. Never shown to the model/user directly — callers
        // translate it into a user-facing "sign in again" message.
        private const val REAUTH_REQUIRED_SENTINEL = "MCP_OAUTH_REAUTH_REQUIRED"
        // sendRpcStdio's error text when the process is still alive but simply hasn't
        // answered in time — distinct from the process having actually died, so callers
        // know whether reconnecting (killing and relaunching the process) is warranted.
        private const val STDIO_TIMEOUT_MESSAGE = "stdio server timed out"
    }

    // REMOTE is a real server tool dispatched via tools/call. LIST_RESOURCES/READ_RESOURCE
    // are synthetic tools synthesized locally (see fetchServerState) for servers that
    // advertise the "resources" capability — they let the model browse/read MCP Resources
    // through the same tool-call pipeline, since Agora has no separate resource-browsing UI.
    private enum class ToolKind { REMOTE, LIST_RESOURCES, READ_RESOURCE }

    private data class RemoteTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject?,
        val destructive: Boolean,
        val kind: ToolKind = ToolKind.REMOTE
    )

    private data class RemoteResource(val uri: String, val name: String, val description: String, val mimeType: String?)

    private data class RemoteResourceTemplate(val uriTemplate: String, val name: String, val description: String, val mimeType: String?)

    private data class ServerState(
        val server: McpServerConfig,
        val tools: List<RemoteTool>,
        val sessionId: String?,
        val fetchedAt: Long,
        val error: String?,
        val serverInfo: McpServerInfo? = null,
        // True when an OAuth refresh attempt failed (refresh token expired/revoked) —
        // distinct from a generic connection failure, surfaced via [reauthNeededFlow].
        val needsReauth: Boolean = false,
        val resources: List<RemoteResource> = emptyList(),
        val resourceTemplates: List<RemoteResourceTemplate> = emptyList()
    )

    private data class RpcResult(val obj: JsonObject?, val sessionId: String?, val error: String?, val statusCode: Int? = null)

    private val cache = ConcurrentHashMap<String, ServerState>()
    private val json = Json { ignoreUnknownKeys = true }
    private val reqId = java.util.concurrent.atomic.AtomicInteger(1)

    // ── Stdio transport (F-Droid sandbox only) ──────────────────

    /** One live subprocess + its in-flight request table, keyed by [McpServerConfig.id].
     *  The reader job runs on [ioScope] so it outlives any single RPC call — a stdio
     *  server's process is a long-lived connection, not a per-request one. */
    private class StdioConnection(
        val process: SandboxManager.SandboxProcess,
        // Snapshot of what launched this process — if the server's config no longer
        // matches, the connection is stale and must be restarted, not reused.
        val command: String,
        val env: Map<String, String>
    ) {
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        var readerJob: Job? = null
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stdioConnections = ConcurrentHashMap<String, StdioConnection>()

    private fun closeStdioConnection(id: String) {
        stdioConnections.remove(id)?.let { conn ->
            conn.readerJob?.cancel()
            conn.process.destroy()
        }
    }

    /** Kills every live stdio subprocess. Call this when the sandbox they're running in
     *  gets reset/uninstalled — a deleted rootfs doesn't stop an already-running process
     *  (Linux keeps deleted-but-open files mapped), so without this the orphaned process
     *  keeps limping along on stale state until something it needs (e.g. DNS) breaks. */
    fun closeAllStdioConnections() {
        stdioConnections.keys.toList().forEach { closeStdioConnection(it) }
    }

    // Last-known serverInfo (name/version) per server id, from any successful
    // `initialize` handshake — populated by both refresh() and testConnection().
    // Purely informational (settings-page badge); never consulted for routing.
    private val _serverInfo = MutableStateFlow<Map<String, McpServerInfo>>(emptyMap())
    val serverInfoFlow: StateFlow<Map<String, McpServerInfo>> = _serverInfo.asStateFlow()

    private fun recordServerInfo(id: String, info: McpServerInfo?) {
        if (info == null) return
        _serverInfo.value = _serverInfo.value + (id to info)
    }

    // Per-server "an OAuth refresh attempt failed, sign-in is needed again" flag —
    // consulted by the settings page to show a distinct "Sign in again" state instead of
    // a generic connection-failure message.
    private val _reauthNeeded = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val reauthNeededFlow: StateFlow<Map<String, Boolean>> = _reauthNeeded.asStateFlow()

    private fun recordReauth(id: String, needsReauth: Boolean) {
        _reauthNeeded.value = _reauthNeeded.value + (id to needsReauth)
    }

    private fun slug(name: String, id: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return (base.ifBlank { "server" }).take(24) + "_" + id.take(6)
    }

    // Most providers (OpenAI/Gemini-compatible tool schemas in particular) enforce a
    // ~64-char, [a-zA-Z0-9_-]-only function name. Server-supplied tool names are
    // untrusted input and can be arbitrarily long or contain other characters, so
    // sanitize + bound the qualified name instead of passing it through verbatim —
    // otherwise a single oddly-named remote tool can make every provider call fail.
    private fun sanitizeToolName(name: String): String {
        val cleaned = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return cleaned.ifBlank { "tool" }
    }

    private fun qualifiedName(server: McpServerConfig, toolName: String): String {
        val prefix = "$PREFIX${slug(server.name, server.id)}__"
        val budget = (64 - prefix.length).coerceAtLeast(8)
        return prefix + sanitizeToolName(toolName).take(budget)
    }

    /** Reverse-looks-up the (server, tool) pair for a qualified tool name. */
    private fun resolve(qualified: String): Pair<McpServerConfig, RemoteTool>? {
        if (!qualified.startsWith(PREFIX)) return null
        for (state in cache.values) {
            for (tool in state.tools) {
                if (qualifiedName(state.server, tool.name) == qualified) return state.server to tool
            }
        }
        return null
    }

    // ── Discovery (network) ────────────────────────────────────

    /** Refreshes the tool list for every enabled, configured server whose cache entry
     *  is stale or missing. Cheap no-op when everything is fresh. Call this once, in a
     *  suspend context, before reading [definitions] for a generation request. */
    suspend fun refresh(ctx: GenerationContext) = coroutineScope {
        if (!ctx.mcpEnabled) return@coroutineScope
        val now = System.currentTimeMillis()
        val stale = mutableListOf<McpServerConfig>()
        for (server in ctx.mcpServers) {
            val unconfigured = if (server.transport == "stdio") server.stdioCommand.isBlank() else server.url.isBlank()
            if (!server.enabled || unconfigured) {
                cache.remove(server.id)
                closeStdioConnection(server.id)
                continue
            }
            val existing = cache[server.id]
            // Cache entries are honored for the full TTL regardless of success/error,
            // so an unreachable server is retried at most once per TTL window instead
            // of on every single generation.
            if (existing != null && now - existing.fetchedAt < TOOL_CACHE_TTL_MS) continue
            stale.add(server)
        }
        // Fetch all stale servers concurrently rather than one round-trip at a time —
        // with several servers configured this used to add their timeouts up in serial.
        stale.map { server -> async { server to fetchServerState(server) } }
            .forEach { deferred ->
                val (server, state) = deferred.await()
                cache[server.id] = state
                recordServerInfo(server.id, state.serverInfo)
                recordReauth(server.id, state.needsReauth)
            }
        val validIds = ctx.mcpServers.map { it.id }.toSet()
        cache.keys.retainAll(validIds)
        _serverInfo.value = _serverInfo.value.filterKeys { it in validIds }
        _reauthNeeded.value = _reauthNeeded.value.filterKeys { it in validIds }
        stdioConnections.keys.filter { it !in validIds }.forEach { closeStdioConnection(it) }
    }

    /** Connects to [server] and lists its tools. Used both by [refresh] and by the
     *  settings page's "Test connection" action. Returns the resolved [ServerState],
     *  which carries an [ServerState.error] on failure instead of throwing.
     *
     *  Main-safe: does its blocking network I/O on [Dispatchers.IO] regardless of the
     *  calling thread, so callers (e.g. a Compose `rememberCoroutineScope`, which runs
     *  on the main dispatcher) never trip Android's network-on-main-thread guard. */
    private suspend fun fetchServerState(server: McpServerConfig): ServerState = withContext(Dispatchers.IO) {
        val initParams = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "Agora"); put("version", "1.0") })
        }
        val initResult = rpcCall(server, { rpcRequest("initialize", initParams) }, null)
        if (initResult.error == REAUTH_REQUIRED_SENTINEL) {
            return@withContext ServerState(server, emptyList(), null, System.currentTimeMillis(), "Sign in again to use this server", needsReauth = true)
        }
        if (initResult.obj == null) {
            return@withContext ServerState(server, emptyList(), null, System.currentTimeMillis(), initResult.error ?: "connection failed")
        }
        val sessionId = initResult.sessionId
        val serverInfo = try {
            initResult.obj["result"]?.jsonObject?.get("serverInfo")?.jsonObject?.let { o ->
                val n = (o["name"] as? JsonPrimitive)?.content
                if (n.isNullOrBlank()) null else McpServerInfo(n, (o["version"] as? JsonPrimitive)?.content ?: "")
            }
        } catch (_: Exception) { null }
        // Best-effort — servers may not require or acknowledge this notification.
        if (server.transport == "stdio") {
            sendRpcStdio(server, rpcNotification("notifications/initialized"))
        } else {
            val notifyToken = if (server.authType == "oauth") oauthManager?.accessToken(server) else null
            sendRpc(server, rpcNotification("notifications/initialized"), sessionId, notifyToken)
        }

        val listResult = rpcCall(server, { rpcRequest("tools/list", buildJsonObject {}) }, sessionId)
        if (listResult.error == REAUTH_REQUIRED_SENTINEL) {
            return@withContext ServerState(server, emptyList(), sessionId, System.currentTimeMillis(), "Sign in again to use this server", serverInfo, needsReauth = true)
        }
        if (listResult.error != null) {
            return@withContext ServerState(server, emptyList(), sessionId, System.currentTimeMillis(), listResult.error, serverInfo)
        }
        val toolsArr = try {
            listResult.obj?.get("result")?.jsonObject?.get("tools")?.jsonArray ?: JsonArray(emptyList())
        } catch (e: Exception) { JsonArray(emptyList()) }

        val tools = toolsArr.mapNotNull { el ->
            try {
                val o = el.jsonObject
                val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val description = (o["description"] as? JsonPrimitive)?.content ?: ""
                val schema = o["inputSchema"]?.jsonObject
                val annotations = o["annotations"]?.jsonObject
                val readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull ?: false
                val destructiveHint = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull
                // Spec default when destructiveHint is absent: treat as destructive (safer),
                // unless the server explicitly marked this tool read-only.
                val destructive = if (readOnly) false else (destructiveHint ?: true)
                RemoteTool(name, description, schema, destructive)
            } catch (e: Exception) {
                DebugLog.e("McpToolProvider", "Skipping malformed tool from ${server.name}: ${e.message}")
                null
            }
        }

        // Resources discovery — only attempted when the server actually advertises the
        // capability, since many servers 405/-32601 on resources/* methods otherwise.
        val resourcesCapability = try {
            initResult.obj["result"]?.jsonObject?.get("capabilities")?.jsonObject?.get("resources")
        } catch (_: Exception) { null }

        var resources = emptyList<RemoteResource>()
        var resourceTemplates = emptyList<RemoteResourceTemplate>()
        var allTools = tools
        if (resourcesCapability != null) {
            val resourcesResult = rpcCall(server, { rpcRequest("resources/list", buildJsonObject {}) }, initResult.sessionId ?: sessionId)
            if (resourcesResult.error == null) {
                val resourcesArr = try {
                    resourcesResult.obj?.get("result")?.jsonObject?.get("resources")?.jsonArray ?: JsonArray(emptyList())
                } catch (e: Exception) { JsonArray(emptyList()) }
                resources = resourcesArr.mapNotNull { el ->
                    try {
                        val o = el.jsonObject
                        val uri = (o["uri"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val name = (o["name"] as? JsonPrimitive)?.content ?: uri
                        val description = (o["description"] as? JsonPrimitive)?.content ?: ""
                        val mimeType = (o["mimeType"] as? JsonPrimitive)?.content
                        RemoteResource(uri, name, description, mimeType)
                    } catch (e: Exception) {
                        DebugLog.e("McpToolProvider", "Skipping malformed resource from ${server.name}: ${e.message}")
                        null
                    }
                }

                // Best-effort — a server that supports resources but not templates (or errors
                // on this call) just yields an empty template list, not a connection failure.
                val templatesResult = rpcCall(server, { rpcRequest("resources/templates/list", buildJsonObject {}) }, initResult.sessionId ?: sessionId)
                val templatesArr = try {
                    templatesResult.obj?.get("result")?.jsonObject?.get("resourceTemplates")?.jsonArray ?: JsonArray(emptyList())
                } catch (e: Exception) { JsonArray(emptyList()) }
                resourceTemplates = templatesArr.mapNotNull { el ->
                    try {
                        val o = el.jsonObject
                        val uriTemplate = (o["uriTemplate"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val name = (o["name"] as? JsonPrimitive)?.content ?: uriTemplate
                        val description = (o["description"] as? JsonPrimitive)?.content ?: ""
                        val mimeType = (o["mimeType"] as? JsonPrimitive)?.content
                        RemoteResourceTemplate(uriTemplate, name, description, mimeType)
                    } catch (e: Exception) { null }
                }

                // Synthetic tools exposing resources through the same tools/call pipeline the
                // model already knows how to use — Agora has no separate resource-browsing UI.
                // Skipped per-name if the server itself already exposes a real tool of that name.
                val existingNames = tools.map { it.name }.toSet()
                val synthetic = mutableListOf<RemoteTool>()
                if ("list_resources" !in existingNames) {
                    synthetic.add(
                        RemoteTool(
                            name = "list_resources",
                            description = "List resources available from this MCP server",
                            inputSchema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) },
                            destructive = false,
                            kind = ToolKind.LIST_RESOURCES
                        )
                    )
                }
                if ("read_resource" !in existingNames) {
                    synthetic.add(
                        RemoteTool(
                            name = "read_resource",
                            description = "Read the contents of a resource URI from this MCP server (see list_resources)",
                            inputSchema = buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("uri", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The resource URI to read, from list_resources")
                                    })
                                })
                                put("required", JsonArray(listOf(JsonPrimitive("uri"))))
                            },
                            destructive = false,
                            kind = ToolKind.READ_RESOURCE
                        )
                    )
                }
                allTools = tools + synthetic
            }
        }

        ServerState(
            server, allTools, initResult.sessionId ?: sessionId, System.currentTimeMillis(), null, serverInfo,
            resources = resources, resourceTemplates = resourceTemplates
        )
    }

    /** Ad-hoc connection test for the settings UI: connects, lists tools, and returns
     *  either the tool names found or a human-readable error — without touching the
     *  shared cache used by live generations. Still records serverInfo into
     *  [serverInfoFlow] so the settings-page badge picks it up immediately. */
    suspend fun testConnection(server: McpServerConfig): Result<List<String>> {
        val state = fetchServerState(server)
        recordServerInfo(server.id, state.serverInfo)
        recordReauth(server.id, state.needsReauth)
        return if (state.error != null) Result.failure(Exception(state.error))
        else Result.success(state.tools.map { it.name })
    }

    // ── JSON-RPC transport ──────────────────────────────────────

    private fun buildHeaders(server: McpServerConfig, sessionId: String?, oauthAccessToken: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream")
        put("MCP-Protocol-Version", PROTOCOL_VERSION)
        when (server.authType) {
            // Always emit the canonical "Bearer" scheme (capital B) regardless of the
            // token_type casing the authorization server returned (e.g. "bearer") — some
            // resource servers parse the Authorization header case-sensitively.
            "oauth" -> if (!oauthAccessToken.isNullOrBlank()) put("Authorization", "Bearer $oauthAccessToken")
            else -> if (server.bearerToken.isNotBlank()) put("Authorization", "Bearer ${server.bearerToken}")
        }
        server.headers.forEach { (k, v) -> if (k.isNotBlank()) put(k, v) }
        if (sessionId != null) put("Mcp-Session-Id", sessionId)
    }

    private fun rpcRequest(method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", reqId.getAndIncrement())
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    private fun rpcNotification(method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    /** Sends one JSON-RPC message over Streamable HTTP. Handles both a plain JSON
     *  response and a `text/event-stream` response (reads the whole body — MCP tool
     *  responses are small — and takes the last `data:` payload). */
    private fun sendRpc(server: McpServerConfig, body: String, sessionId: String?, oauthAccessToken: String?): RpcResult {
        val handle = try {
            HttpClient.streamPost(
                server.url, body, buildHeaders(server, sessionId, oauthAccessToken),
                timeoutMs = server.timeout.coerceIn(5, 120) * 1000L,
                trackAsActive = false
            )
        } catch (e: Exception) {
            return RpcResult(null, sessionId, e.message ?: "network error")
        }
        try {
            val newSessionId = handle.headers["Mcp-Session-Id"] ?: sessionId
            if (handle.code !in 200..299) {
                val errBody = try { handle.source?.readUtf8() } catch (_: Exception) { null }
                val suffix = if (!errBody.isNullOrBlank()) ": ${errBody.take(300)}" else ""
                return RpcResult(null, newSessionId, "HTTP ${handle.code}$suffix", handle.code)
            }
            val contentType = handle.headers["Content-Type"] ?: ""
            val raw = try { handle.source?.readUtf8() ?: "" } catch (e: Exception) {
                return RpcResult(null, newSessionId, e.message ?: "read error")
            }
            if (raw.isBlank()) return RpcResult(null, newSessionId, null) // e.g. 202 Accepted for a notification
            val payload = if (contentType.contains("text/event-stream")) {
                raw.lineSequence()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.isNotBlank() }
            } else raw.trim()
            if (payload.isNullOrBlank()) return RpcResult(null, newSessionId, null)
            val obj = try { json.parseToJsonElement(payload).jsonObject } catch (e: Exception) {
                return RpcResult(null, newSessionId, "invalid JSON-RPC response")
            }
            val rpcError = obj["error"]?.jsonObject
            if (rpcError != null) {
                val msg = (rpcError["message"] as? JsonPrimitive)?.content ?: "MCP server returned an error"
                return RpcResult(obj, newSessionId, msg)
            }
            return RpcResult(obj, newSessionId, null)
        } finally {
            handle.close()
        }
    }

    /** Sends [bodyFactory]'s JSON-RPC message. For an `"oauth"` server, proactively ensures
     *  a non-expired access token via [oauthManager] before the first attempt (AppAuth only
     *  hits the network if actually near/past expiry); if the resource server still returns
     *  a 401 (e.g. out-of-band revocation a proactive expiry check can't know about), forces
     *  one reactive refresh and retries exactly once with the fresh token. If the refresh
     *  itself fails (refresh token expired/revoked), returns [REAUTH_REQUIRED_SENTINEL] as
     *  the error instead of the raw 401, so callers can surface a distinct "sign in again"
     *  state. [bodyFactory] is re-invoked on retry so the JSON-RPC request id is regenerated,
     *  not reused. */
    private suspend fun sendRpcWithAuthRetry(server: McpServerConfig, bodyFactory: () -> String, sessionId: String?): RpcResult {
        val manager = oauthManager
        val isOAuth = server.authType == "oauth" && manager != null
        if (isOAuth) manager!!.ensureFreshAccessToken(server)
        var accessToken = if (isOAuth) manager!!.accessToken(server) else null

        var result = sendRpc(server, bodyFactory(), sessionId, accessToken)
        if (result.statusCode == 401 && isOAuth) {
            val refreshed = manager!!.refreshAccessToken(server)
            if (refreshed.isSuccess) {
                accessToken = manager.accessToken(server)
                result = sendRpc(server, bodyFactory(), sessionId, accessToken)
            } else {
                return RpcResult(null, result.sessionId, REAUTH_REQUIRED_SENTINEL)
            }
        }
        return result
    }

    /** Dispatches [bodyFactory]'s JSON-RPC message over whichever transport [server] is
     *  configured for. Stdio has no sessions/OAuth/401s, so it bypasses
     *  [sendRpcWithAuthRetry] entirely and goes straight to [sendRpcStdio]. */
    private suspend fun rpcCall(server: McpServerConfig, bodyFactory: () -> String, sessionId: String?): RpcResult =
        if (server.transport == "stdio") sendRpcStdio(server, bodyFactory())
        else sendRpcWithAuthRetry(server, bodyFactory, sessionId)

    /** Gets the live subprocess connection for [server], starting one if none exists
     *  (or the previous one died). Returns null if the sandbox isn't available or the
     *  server has no command configured — callers translate that into a connection
     *  error, same as an unreachable HTTP endpoint. */
    private suspend fun getOrCreateStdioConnection(server: McpServerConfig): StdioConnection? {
        stdioConnections[server.id]?.let { existing ->
            val stale = existing.command != server.stdioCommand || existing.env != server.stdioEnv
            if (existing.process.isAlive && !stale) return existing
            closeStdioConnection(server.id)
        }
        if (server.stdioCommand.isBlank()) return null
        val manager = sandboxFactory?.create() ?: return null
        if (!manager.isAvailable()) return null
        val process = try { manager.startProcess(server.stdioCommand, server.stdioEnv) } catch (e: Exception) {
            DebugLog.e("McpToolProvider", "Failed to start stdio server ${server.name}: ${e.message}")
            return null
        }
        val conn = StdioConnection(process, server.stdioCommand, server.stdioEnv)
        conn.readerJob = ioScope.launch {
            while (true) {
                val line = process.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val id = obj["id"]?.toString() ?: continue // notifications from the server aren't correlated
                    conn.pending.remove(id)?.complete(obj)
                } catch (_: Exception) {
                    // Some servers print plain-text startup banners to stdout before
                    // speaking JSON-RPC — ignore anything that doesn't parse.
                }
            }
            // stdout closed — the process exited (crash, missing config/env var, etc.).
            // Fail any in-flight request immediately instead of leaving it to time out.
            val reason = "MCP server process exited" + process.stderrTail.trim().let { if (it.isNotBlank()) ": ${it.take(500)}" else "" }
            conn.pending.keys.toList().forEach { key -> conn.pending.remove(key)?.completeExceptionally(IllegalStateException(reason)) }
        }
        stdioConnections[server.id] = conn
        return conn
    }

    /** Sends one JSON-RPC message to [server]'s stdio subprocess and, for a request
     *  (has an "id"), suspends for the matching response line on stdout, bounded by
     *  [McpServerConfig.timeout]. Notifications (no "id") are fire-and-forget. */
    private suspend fun sendRpcStdio(server: McpServerConfig, body: String): RpcResult {
        val conn = getOrCreateStdioConnection(server) ?: return RpcResult(null, null, "stdio sandbox unavailable")
        val requestObj = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            return RpcResult(null, null, "invalid request")
        }
        val id = requestObj["id"]?.toString()
        if (id == null) {
            try { conn.process.writeLine(body) } catch (e: Exception) { return RpcResult(null, null, e.message ?: "write failed") }
            return RpcResult(null, null, null)
        }
        val deferred = CompletableDeferred<JsonObject>()
        conn.pending[id] = deferred
        try {
            conn.process.writeLine(body)
        } catch (e: Exception) {
            conn.pending.remove(id)
            return RpcResult(null, null, e.message ?: "write failed")
        }
        val response = try {
            withTimeoutOrNull(server.timeout.coerceIn(5, 120) * 1000L) { deferred.await() }
        } catch (e: Exception) {
            return RpcResult(null, null, e.message ?: "stdio server exited")
        } finally {
            conn.pending.remove(id)
        }
        if (response == null) return RpcResult(null, null, STDIO_TIMEOUT_MESSAGE)
        val rpcError = response["error"]?.jsonObject
        if (rpcError != null) {
            val msg = (rpcError["message"] as? JsonPrimitive)?.content ?: "MCP server returned an error"
            return RpcResult(response, null, msg)
        }
        return RpcResult(response, null, null)
    }

    // ── JSON-Schema ⇄ ToolProperty (best-effort; drops keywords ToolProperty
    //    can't represent, like oneOf/const/format/additionalProperties) ──

    /** JSON Schema allows `"type"` to be a single string OR an array (commonly
     *  `["string", "null"]` for nullable fields — frequent from Python/Pydantic-based
     *  MCP servers). Picks the first non-"null" entry so nullable fields don't
     *  silently fall back to the "string" default. */
    private fun schemaTypeOf(schema: JsonObject, default: String): String {
        (schema["type"] as? JsonPrimitive)?.content?.let { return it }
        val arr = try { schema["type"]?.jsonArray } catch (_: Exception) { null } ?: return default
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }.firstOrNull { it != "null" } ?: default
    }

    private fun schemaToProperty(schema: JsonObject): ToolProperty {
        val type = schemaTypeOf(schema, "string")
        val description = (schema["description"] as? JsonPrimitive)?.content ?: ""
        val items = try { schema["items"]?.jsonObject?.let { schemaToProperty(it) } } catch (_: Exception) { null }
        val enumVals = try { schema["enum"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } } catch (_: Exception) { null }
        val nestedProps = try {
            schema["properties"]?.jsonObject?.mapValues { (_, v) -> schemaToProperty(v.jsonObject) }
        } catch (_: Exception) { null }
        val requiredList = try { schema["required"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } } catch (_: Exception) { null }
        return ToolProperty(type = type, description = description, items = items, enum = enumVals, properties = nestedProps, required = requiredList)
    }

    private fun schemaToParameters(schema: JsonObject?): ToolParameters {
        if (schema == null) return ToolParameters(type = "object", properties = emptyMap(), required = emptyList())
        val type = schemaTypeOf(schema, "object")
        val properties = try {
            schema["properties"]?.jsonObject?.mapValues { (_, v) -> schemaToProperty(v.jsonObject) } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        val required = try {
            schema["required"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return ToolParameters(type = type, properties = properties, required = required)
    }

    // ── ToolProvider ────────────────────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.mcpEnabled) return emptyList()
        val defs = mutableListOf<ToolDefinition>()
        for (server in ctx.mcpServers) {
            if (!server.enabled) continue
            val state = cache[server.id] ?: continue
            for (tool in state.tools) {
                try {
                    defs.add(
                        ToolDefinition(
                            function = ToolFunction(
                                name = qualifiedName(server, tool.name),
                                description = tool.description.ifBlank { "Tool \"${tool.name}\" from MCP server \"${server.name}\"" }.take(1024),
                                parameters = schemaToParameters(tool.inputSchema)
                            )
                        )
                    )
                } catch (e: Exception) {
                    DebugLog.e("McpToolProvider", "Skipping tool ${tool.name} from ${server.name}: ${e.message}")
                }
            }
        }
        return defs
    }

    override fun toString(): String = "McpToolProvider"

    override fun handles(name: String): Boolean = name.startsWith(PREFIX)

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val (server, tool) = resolve(name)
            ?: return "Error: unknown or unconfigured MCP tool \"$name\""

        when (tool.kind) {
            ToolKind.LIST_RESOURCES -> return formatResourcesListing(cache[server.id])
            ToolKind.READ_RESOURCE -> return executeReadResource(server, arguments, ctx)
            ToolKind.REMOTE -> Unit
        }

        if (tool.destructive) {
            val summary = buildSummary(tool.name, arguments)
            val allowed = confirm?.invoke(server.id, server.name, tool.name, summary) ?: true
            if (!allowed) return "Error: the user declined to run \"${tool.name}\" on MCP server \"${server.name}\""
        }

        val argsObj = try {
            json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        val callParams = buildJsonObject {
            put("name", tool.name)
            put("arguments", argsObj)
        }

        // Re-resolve the live config by id rather than trusting the (possibly stale, e.g.
        // OAuth-token-bearing) copy cached inside ServerState — a token refreshed since
        // the last fetch must be used here, not the one baked into the cache entry.
        val currentServer = ctx.mcpServers.find { it.id == server.id } ?: server

        var state = cache[server.id]
        var result = rpcCall(currentServer, { rpcRequest("tools/call", callParams) }, state?.sessionId)

        // Session likely expired — reconnect once and retry. (Stdio has no session
        // concept, but a dead subprocess surfaces as this same "connection failed" shape,
        // so reconnecting once here also covers a stdio server that crashed mid-session.
        // A plain timeout is deliberately excluded — the process is still alive, just
        // slow, and killing/relaunching it there would abort real in-flight work, e.g. a
        // cold `npx` start that's simply taking a while.)
        val stdioDied = currentServer.transport == "stdio" && result.error != STDIO_TIMEOUT_MESSAGE
        if (result.obj == null && (result.error?.contains("404") == true || stdioDied)) {
            closeStdioConnection(currentServer.id)
            val fresh = fetchServerState(currentServer)
            cache[server.id] = fresh
            recordServerInfo(server.id, fresh.serverInfo)
            recordReauth(server.id, fresh.needsReauth)
            state = fresh
            result = rpcCall(currentServer, { rpcRequest("tools/call", callParams) }, fresh.sessionId)
        }

        if (result.error == REAUTH_REQUIRED_SENTINEL) {
            recordReauth(server.id, true)
            return "Error: sign in again to MCP server \"${currentServer.name}\" to continue using its tools"
        }

        if (result.sessionId != null && result.sessionId != state?.sessionId) {
            state?.let { cache[server.id] = it.copy(sessionId = result.sessionId) }
        }

        if (result.obj == null) return "Error: MCP call failed — ${result.error ?: "no response"}"
        recordReauth(server.id, false)
        val resultObj = try { result.obj["result"]?.jsonObject } catch (_: Exception) { null }
            ?: return "Error: MCP call failed — ${result.error ?: "empty response"}"

        val isError = (resultObj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val contentArr = try { resultObj["content"]?.jsonArray ?: JsonArray(emptyList()) } catch (_: Exception) { JsonArray(emptyList()) }
        val text = contentArr.joinToString("\n") { el ->
            try {
                val o = el.jsonObject
                when ((o["type"] as? JsonPrimitive)?.content) {
                    "text" -> (o["text"] as? JsonPrimitive)?.content ?: ""
                    // An embedded resource content block carries its contents inline (no
                    // extra fetch needed) — surface the text directly when present, and
                    // only fall back to a placeholder for blob/absent contents.
                    "resource" -> o["resource"]?.jsonObject?.let { formatResourceContent(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "[resource: ${(o["resource"]?.jsonObject?.get("uri") as? JsonPrimitive)?.content ?: "unknown"}]"
                    "image" -> "[image content omitted]"
                    "audio" -> "[audio content omitted]"
                    else -> el.toString()
                }
            } catch (_: Exception) { "" }
        }.trim()

        return when {
            isError -> "Error: ${text.ifBlank { "MCP tool \"${tool.name}\" reported failure" }}"
            text.isBlank() -> "OK"
            else -> text
        }
    }

    private fun buildSummary(toolName: String, arguments: String): String {
        val compact = arguments.trim().let { if (it.length > 200) it.take(200) + "…" else it }
        return "$toolName($compact)"
    }

    // ── Resources (synthetic list_resources/read_resource tools) ────────────────

    /** Formats the cached resource/template listing for the synthetic `list_resources`
     *  tool — no RPC here, [state] is refreshed the same way (and on the same TTL) as
     *  the regular tool list, via [refresh]. */
    private fun formatResourcesListing(state: ServerState?): String {
        if (state == null) return "No resources available."
        val lines = mutableListOf<String>()
        if (state.resources.isNotEmpty()) {
            lines.add("Resources:")
            state.resources.forEach { r ->
                val desc = r.description.ifBlank { null }?.let { ": $it" } ?: ""
                val mime = r.mimeType?.let { " [$it]" } ?: ""
                lines.add("- ${r.uri} (${r.name})$desc$mime")
            }
        }
        if (state.resourceTemplates.isNotEmpty()) {
            lines.add("Resource templates:")
            state.resourceTemplates.forEach { t ->
                val desc = t.description.ifBlank { null }?.let { ": $it" } ?: ""
                val mime = t.mimeType?.let { " [$it]" } ?: ""
                lines.add("- ${t.uriTemplate} (${t.name})$desc$mime")
            }
        }
        return lines.joinToString("\n").ifBlank { "No resources available." }
    }

    /** Formats one `resources/read` (or embedded `resource` content block) contents
     *  entry: `{ uri, mimeType?, text? | blob? }`. Text is returned verbatim; a `blob`
     *  (base64) has no text representation Agora can pass to the model, so it becomes a
     *  placeholder describing what it is, same spirit as the image/audio placeholders
     *  above. */
    private fun formatResourceContent(o: JsonObject): String {
        return try {
            val text = (o["text"] as? JsonPrimitive)?.content
            if (text != null) return text
            val blob = (o["blob"] as? JsonPrimitive)?.content
            if (blob != null) {
                val uri = (o["uri"] as? JsonPrimitive)?.content ?: "unknown"
                val mimeType = (o["mimeType"] as? JsonPrimitive)?.content ?: "unknown"
                val approxBytes = (blob.length * 3) / 4
                return "[binary resource: $uri, mimeType: $mimeType, ~$approxBytes bytes — binary content isn't supported in tool results]"
            }
            ""
        } catch (_: Exception) { "" }
    }

    /** Implements the synthetic `read_resource` tool: calls `resources/read` for the
     *  `uri` argument and formats its `contents[]`. Mirrors the session-expiry-retry
     *  shape [execute] uses for `tools/call`, since this is just another RPC method to
     *  the same server/session. */
    private suspend fun executeReadResource(server: McpServerConfig, arguments: String, ctx: GenerationContext): String {
        val argsObj = try {
            json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) { JsonObject(emptyMap()) }
        val uri = (argsObj["uri"] as? JsonPrimitive)?.content
        if (uri.isNullOrBlank()) return "Error: read_resource requires a \"uri\" argument"

        val currentServer = ctx.mcpServers.find { it.id == server.id } ?: server
        val readParams = buildJsonObject { put("uri", uri) }

        var state = cache[server.id]
        var result = rpcCall(currentServer, { rpcRequest("resources/read", readParams) }, state?.sessionId)

        val stdioDied = currentServer.transport == "stdio" && result.error != STDIO_TIMEOUT_MESSAGE
        if (result.obj == null && (result.error?.contains("404") == true || stdioDied)) {
            closeStdioConnection(currentServer.id)
            val fresh = fetchServerState(currentServer)
            cache[server.id] = fresh
            recordServerInfo(server.id, fresh.serverInfo)
            recordReauth(server.id, fresh.needsReauth)
            state = fresh
            result = rpcCall(currentServer, { rpcRequest("resources/read", readParams) }, fresh.sessionId)
        }

        if (result.error == REAUTH_REQUIRED_SENTINEL) {
            recordReauth(server.id, true)
            return "Error: sign in again to MCP server \"${currentServer.name}\" to continue using its tools"
        }

        if (result.sessionId != null && result.sessionId != state?.sessionId) {
            state?.let { cache[server.id] = it.copy(sessionId = result.sessionId) }
        }

        if (result.obj == null) return "Error: MCP resource read failed — ${result.error ?: "no response"}"
        recordReauth(server.id, false)
        val resultObj = try { result.obj["result"]?.jsonObject } catch (_: Exception) { null }
            ?: return "Error: MCP resource read failed — ${result.error ?: "empty response"}"

        val contentsArr = try { resultObj["contents"]?.jsonArray ?: JsonArray(emptyList()) } catch (_: Exception) { JsonArray(emptyList()) }
        val text = contentsArr.joinToString("\n") { el ->
            try { formatResourceContent(el.jsonObject) } catch (_: Exception) { "" }
        }.trim()
        return text.ifBlank { "Error: resource \"$uri\" returned no content" }
    }
}
