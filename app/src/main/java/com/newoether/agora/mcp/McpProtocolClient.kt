package com.newoether.agora.mcp

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal production Streamable HTTP client for MCP 2025-11-25.
 *
 * It deliberately uses Agora's shared OkHttp stack so proxy selection, connection pooling and
 * credential transport guards stay identical to model requests. A client owns one MCP session;
 * requests are serialized because sessionful servers commonly require ordered initialization.
 */
internal class McpProtocolClient(
    private val endpoint: String,
    private val customHeaders: Map<String, String>,
) {
    companion object {
        private const val PROTOCOL_VERSION = "2025-11-25"
        private const val MAX_TOOL_PAGES = 100
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val ids = AtomicLong(0)
    private val mutex = Mutex()
    private val client = HttpClient.client.newBuilder()
        .callTimeout(Constants.TOOL_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.TOOL_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionId: String? = null

    suspend fun listTools(): List<McpRemoteTool> = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureInitializedLocked()
            retryAfterSessionExpiry {
                val tools = mutableListOf<McpRemoteTool>()
                var cursor: String? = null
                repeat(MAX_TOOL_PAGES) {
                    val params = if (cursor == null) {
                        buildJsonObject {}
                    } else {
                        buildJsonObject { put("cursor", cursor) }
                    }
                    val result = requestLocked("tools/list", params)
                    val page = result["tools"] as? JsonArray
                        ?: throw IOException("MCP tools/list returned no tools array")
                    page.forEach { element ->
                        val obj = element.asObjectOrNull() ?: return@forEach
                        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?: return@forEach
                        tools += McpRemoteTool(
                            name = name,
                            description = (obj["description"] as? JsonPrimitive)
                                ?.contentOrNull
                                .orEmpty(),
                            inputSchema = obj["inputSchema"]?.asObjectOrNull()
                                ?: buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {})
                                },
                        )
                    }
                    cursor = (result["nextCursor"] as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                    if (cursor == null) return@retryAfterSessionExpiry tools
                }
                throw IOException("MCP tools/list exceeded $MAX_TOOL_PAGES pages")
            }
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallPayload = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureInitializedLocked()
            retryAfterSessionExpiry {
                val result = requestLocked(
                    method = "tools/call",
                    params = buildJsonObject {
                        put("name", name)
                        put("arguments", arguments)
                    },
                )
                parseCallPayload(result)
            }
        }
    }

    private fun ensureInitializedLocked() {
        if (initialized) return
        initializeLocked()
    }

    private fun initializeLocked() {
        sessionId = null
        val result = requestLocked(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "Agora")
                    put("version", "1.3.7")
                })
            },
        )
        val negotiated = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("MCP initialize returned no protocolVersion")
        if (negotiated !in setOf(PROTOCOL_VERSION, "2025-06-18", "2024-11-05")) {
            throw IOException("Unsupported MCP protocol version: $negotiated")
        }
        notificationLocked("notifications/initialized", buildJsonObject {})
        initialized = true
    }

    private inline fun <T> retryAfterSessionExpiry(block: () -> T): T {
        return try {
            block()
        } catch (expired: McpSessionExpiredException) {
            initialized = false
            sessionId = null
            initializeLocked()
            block()
        }
    }

    private fun requestLocked(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = postLocked(
            envelope,
            expectResponse = true,
            callTimeoutMillis = if (method == "tools/call") {
                Constants.TOOL_EXECUTION_TIMEOUT_MS
            } else {
                Constants.NETWORK_TOOL_TIMEOUT_MS
            },
        )
            ?: throw IOException("MCP $method returned an empty response")
        val responseId = (response["id"] as? JsonPrimitive)?.contentOrNull
        if (responseId != id.toString()) {
            throw IOException("MCP $method returned mismatched response id")
        }
        response["error"]?.takeUnless { it is JsonNull }?.let { error ->
            val errorObj = error.asObjectOrNull()
            val message = (errorObj?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: error.toString()
            throw IOException("MCP $method failed: $message")
        }
        return response["result"]?.asObjectOrNull()
            ?: throw IOException("MCP $method returned no result")
    }

    private fun notificationLocked(method: String, params: JsonObject) {
        postLocked(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
            expectResponse = false,
            callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
        )
    }

    private fun postLocked(
        envelope: JsonObject,
        expectResponse: Boolean,
        callTimeoutMillis: Long,
    ): JsonObject? {
        HttpClient.guardCleartextCredentials(
            endpoint,
            if (customHeaders.isEmpty()) {
                emptyMap()
            } else {
                // Header names are user-defined, so conservatively treat every non-empty header
                // set as credentials when applying the cleartext transport guard.
                customHeaders + ("Authorization" to "<configured>")
            },
        )
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
        customHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && name.lowercase() !in RESERVED_HEADERS) {
                requestBuilder.header(name.trim(), value)
            }
        }
        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }

        try {
            val call = client.newCall(requestBuilder.build()).also {
                it.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            return call.execute().use { response ->
                if (response.code == 404 && sessionId != null) {
                    throw McpSessionExpiredException()
                }
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(2_048)
                    throw IOException(
                        "MCP HTTP ${response.code}${body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
                    )
                }
                response.header("Mcp-Session-Id")
                    ?.takeIf(String::isNotBlank)
                    ?.let { sessionId = it }
                if (!expectResponse || response.code == 202) return@use null
                val body = response.body ?: throw IOException("MCP response body is empty")
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    parseSseResponse(body.source())
                } else {
                    parseEnvelope(body.string())
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun parseEnvelope(payload: String): JsonObject =
        json.parseToJsonElement(payload).jsonObject

    private fun parseSseResponse(source: BufferedSource): JsonObject {
        val data = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val payload = data.toString().trimEnd('\n')
                    data.clear()
                    val parsed = runCatching { parseEnvelope(payload) }.getOrNull()
                    if (parsed != null && ("result" in parsed || "error" in parsed)) return parsed
                }
            } else if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trimStart()).append('\n')
            }
        }
        if (data.isNotEmpty()) {
            val parsed = runCatching { parseEnvelope(data.toString().trimEnd()) }.getOrNull()
            if (parsed != null) return parsed
        }
        throw IOException("MCP SSE stream ended before a JSON-RPC response")
    }

    private fun parseCallPayload(result: JsonObject): McpCallPayload {
        val texts = mutableListOf<String>()
        val images = mutableListOf<McpImagePayload>()
        (result["content"] as? JsonArray).orEmpty().forEach { element ->
            val item = element.asObjectOrNull() ?: return@forEach
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (item["text"] as? JsonPrimitive)?.contentOrNull?.let(texts::add)
                "image" -> {
                    val data = (item["data"] as? JsonPrimitive)?.contentOrNull
                    val mimeType = (item["mimeType"] as? JsonPrimitive)?.contentOrNull
                    if (!data.isNullOrBlank() && !mimeType.isNullOrBlank()) {
                        images += McpImagePayload(data, mimeType)
                    }
                }
                "resource" -> {
                    val resource = item["resource"]?.asObjectOrNull()
                    val mimeType = (resource?.get("mimeType") as? JsonPrimitive)?.contentOrNull
                    val blob = (resource?.get("blob") as? JsonPrimitive)?.contentOrNull
                    val text = (resource?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (!blob.isNullOrBlank() && mimeType?.startsWith("image/") == true) {
                        images += McpImagePayload(blob, mimeType)
                    } else if (!text.isNullOrBlank()) {
                        texts += text
                    }
                }
            }
        }
        return McpCallPayload(
            textParts = texts,
            images = images,
            structuredContent = result["structuredContent"]?.takeUnless { it is JsonNull },
            isError = (result["isError"] as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull() == true,
        )
    }

    private class McpSessionExpiredException : IOException()

    private val RESERVED_HEADERS = setOf(
        "accept",
        "content-type",
        "content-length",
        "host",
        "connection",
        "mcp-session-id",
        "mcp-protocol-version",
    )
}
