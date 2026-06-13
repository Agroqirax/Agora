package com.newoether.agora.api

import okhttp3.MediaType.Companion.toMediaType
import com.newoether.agora.util.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

object HttpClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Header names that carry secret credentials across the providers.
    private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "x-goog-api-key", "api-key")

    /** True for loopback / RFC-1918 / link-local hosts and bare LAN hostnames
     *  (e.g. "ollama", "nas.local"). Public FQDNs like api.openai.com return false. */
    private fun isLocalHost(host: String): Boolean {
        if (host.isBlank()) return false
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".home") || h.endsWith(".internal")) return true
        // Bare hostname with no dot → LAN name, not a public domain.
        if (!h.contains('.')) return true
        val o = h.split('.')
        if (o.size == 4 && o.all { it.toIntOrNull() in 0..255 }) {
            val a = o[0].toInt(); val b = o[1].toInt()
            return a == 127 || a == 10 || (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return false
    }

    /** Fail-closed guard: never transmit API credentials over cleartext HTTP to a
     *  non-local host. LAN/loopback endpoints (Ollama, self-hosted) stay allowed. */
    private fun guardCleartextCredentials(url: String, headers: Map<String, String>) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        if (isLocalHost(host)) return
        if (headers.keys.any { it.lowercase() in CREDENTIAL_HEADERS }) {
            throw IOException("Refusing to send API credentials over cleartext HTTP to \"$host\". Use an https:// endpoint.")
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The currently active streaming handle, if any. Used to cancel
     *  generation immediately by closing the underlying socket. */
    @Volatile var activeStreamHandle: StreamHandle? = null

    class StreamHandle(private val call: okhttp3.Call, private val response: okhttp3.Response) {
        val code: Int get() = response.code
        val source: BufferedSource? get() = response.body?.source()
        val errorBody: String? by lazy {
            try { response.body?.string() } catch (_: Exception) { null }
        }
        fun close() {
            if (HttpClient.activeStreamHandle === this) {
                HttpClient.activeStreamHandle = null
            }
            response.close()
        }
        fun readLine(): String? = source?.readUtf8Line()
        /** Cancel the underlying HTTP call immediately — unblocks [readLine]. */
        fun cancel() = call.cancel()
    }

    fun streamPost(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): StreamHandle {
        guardCleartextCredentials(url, headers)
        val body = jsonBody.toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val call = client.newCall(requestBuilder.build())
        val handle = StreamHandle(call, call.execute())
        activeStreamHandle = handle
        return handle
    }

    fun post(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String? {
        guardCleartextCredentials(url, headers)
        val body = jsonBody.toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newCall(requestBuilder.build()).execute()
        return response.use {
            if (it.isSuccessful) it.body?.string()
            else {
                DebugLog.e("HttpClient", "POST $url failed: ${it.code} ${it.body?.string()}")
                null
            }
        }
    }

    fun fetchModels(url: String, headers: Map<String, String> = emptyMap()): String? {
        guardCleartextCredentials(url, headers)
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newCall(requestBuilder.build()).execute()
        return response.use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }
}
