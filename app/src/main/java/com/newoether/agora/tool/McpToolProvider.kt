package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
class McpToolProvider : ToolProvider {
    /** User-confirmation gate for destructive MCP tool calls. Set by GenerationManager,
     *  which wires it to the ViewModel's confirmation controller. Returns true to
     *  proceed, false to deny. Never called for non-destructive tools. [serverId] is
     *  the stable [McpServerConfig.id] — use this, not [serverName], as the trust-list
     *  key: display names can collide or be renamed after being trusted. */
    var confirm: (suspend (serverId: String, serverName: String, toolName: String, summary: String) -> Boolean)? = null

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val TOOL_CACHE_TTL_MS = 30_000L
        const val PREFIX = "mcp__"
    }

    private data class RemoteTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject?,
        val destructive: Boolean
    )

    private data class ServerState(
        val server: McpServerConfig,
        val tools: List<RemoteTool>,
        val sessionId: String?,
        val fetchedAt: Long,
        val error: String?,
        val serverInfo: McpServerInfo? = null
    )

    private data class RpcResult(val obj: JsonObject?, val sessionId: String?, val error: String?)

    private val cache = ConcurrentHashMap<String, ServerState>()
    private val json = Json { ignoreUnknownKeys = true }
    private val reqId = java.util.concurrent.atomic.AtomicInteger(1)

    // Last-known serverInfo (name/version) per server id, from any successful
    // `initialize` handshake — populated by both refresh() and testConnection().
    // Purely informational (settings-page badge); never consulted for routing.
    private val _serverInfo = MutableStateFlow<Map<String, McpServerInfo>>(emptyMap())
    val serverInfoFlow: StateFlow<Map<String, McpServerInfo>> = _serverInfo.asStateFlow()

    private fun recordServerInfo(id: String, info: McpServerInfo?) {
        if (info == null) return
        _serverInfo.value = _serverInfo.value + (id to info)
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
            if (!server.enabled || server.url.isBlank()) {
                cache.remove(server.id)
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
            }
        val validIds = ctx.mcpServers.map { it.id }.toSet()
        cache.keys.retainAll(validIds)
        _serverInfo.value = _serverInfo.value.filterKeys { it in validIds }
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
        val initResult = sendRpc(server, rpcRequest("initialize", initParams), null)
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
        sendRpc(server, rpcNotification("notifications/initialized"), sessionId)

        val listResult = sendRpc(server, rpcRequest("tools/list", buildJsonObject {}), sessionId)
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
        ServerState(server, tools, initResult.sessionId ?: sessionId, System.currentTimeMillis(), null, serverInfo)
    }

    /** Ad-hoc connection test for the settings UI: connects, lists tools, and returns
     *  either the tool names found or a human-readable error — without touching the
     *  shared cache used by live generations. Still records serverInfo into
     *  [serverInfoFlow] so the settings-page badge picks it up immediately. */
    suspend fun testConnection(server: McpServerConfig): Result<List<String>> {
        val state = fetchServerState(server)
        recordServerInfo(server.id, state.serverInfo)
        return if (state.error != null) Result.failure(Exception(state.error))
        else Result.success(state.tools.map { it.name })
    }

    // ── JSON-RPC transport ──────────────────────────────────────

    private fun buildHeaders(server: McpServerConfig, sessionId: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream")
        put("MCP-Protocol-Version", PROTOCOL_VERSION)
        if (server.bearerToken.isNotBlank()) put("Authorization", "Bearer ${server.bearerToken}")
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
    private fun sendRpc(server: McpServerConfig, body: String, sessionId: String?): RpcResult {
        val handle = try {
            HttpClient.streamPost(
                server.url, body, buildHeaders(server, sessionId),
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
                return RpcResult(null, newSessionId, "HTTP ${handle.code}$suffix")
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

        var state = cache[server.id]
        var result = sendRpc(server, rpcRequest("tools/call", callParams), state?.sessionId)

        // Session likely expired — reconnect once and retry.
        if (result.obj == null && result.error?.contains("404") == true) {
            val fresh = fetchServerState(server)
            cache[server.id] = fresh
            recordServerInfo(server.id, fresh.serverInfo)
            state = fresh
            result = sendRpc(server, rpcRequest("tools/call", callParams), fresh.sessionId)
        }

        if (result.sessionId != null && result.sessionId != state?.sessionId) {
            state?.let { cache[server.id] = it.copy(sessionId = result.sessionId) }
        }

        if (result.obj == null) return "Error: MCP call failed — ${result.error ?: "no response"}"
        val resultObj = try { result.obj["result"]?.jsonObject } catch (_: Exception) { null }
            ?: return "Error: MCP call failed — ${result.error ?: "empty response"}"

        val isError = (resultObj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val contentArr = try { resultObj["content"]?.jsonArray ?: JsonArray(emptyList()) } catch (_: Exception) { JsonArray(emptyList()) }
        val text = contentArr.joinToString("\n") { el ->
            try {
                val o = el.jsonObject
                when ((o["type"] as? JsonPrimitive)?.content) {
                    "text" -> (o["text"] as? JsonPrimitive)?.content ?: ""
                    "resource" -> "[resource: ${(o["resource"]?.jsonObject?.get("uri") as? JsonPrimitive)?.content ?: "unknown"}]"
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
}
