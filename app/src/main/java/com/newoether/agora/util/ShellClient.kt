package com.newoether.agora.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ShellClient(
    private val serverUrl: String,
    private val apiKey: String,
    cachedPublicKey: String = ""
) {
    private var serverPublicKey: java.security.PublicKey? = null
    private var currentAesKey: ByteArray? = null
    private var currentKeyPair: java.security.KeyPair? = null
    var lastError: String? = null
        private set

    init {
        if (cachedPublicKey.isNotBlank()) {
            try {
                serverPublicKey = ShellCrypto.decodePublicKey(cachedPublicKey)
            } catch (_: Exception) {
                // Will fetch fresh
            }
        }
    }

    suspend fun fetchPublicKey(): Boolean {
        if (serverPublicKey != null) return true
        if (apiKey.isBlank()) {
            lastError = "No API key configured"
            return false
        }
        var rawResponse: String? = null
        return try {
            rawResponse = com.newoether.agora.api.HttpClient.fetchModels(
                "$serverUrl/public-key",
                mapOf("Authorization" to "Bearer $apiKey")
            )
            if (rawResponse == null) {
                lastError = "Server returned no response (check server URL and port)"
                DebugLog.e("ShellClient", lastError!!)
                return false
            }
            val json = Json.parseToJsonElement(rawResponse).jsonObject
            val pubKeyStr = json["public_key"]?.jsonPrimitive?.content?.replace(" ", "")
            val nonce = json["nonce"]?.jsonPrimitive?.content?.replace(" ", "")
            val sig = json["signature"]?.jsonPrimitive?.content?.replace(" ", "")
            if (pubKeyStr == null || nonce == null || sig == null) {
                lastError = "Server response missing public_key, nonce, or signature fields"
                DebugLog.e("ShellClient", "$lastError: $rawResponse")
                return false
            }
            if (!verifyPublicKeySignature(pubKeyStr, nonce, sig)) {
                lastError = "Public key signature verification failed — API keys may not match between Conch server and Agora"
                DebugLog.e("ShellClient", lastError!!)
                return false
            }
            serverPublicKey = ShellCrypto.decodePublicKey(pubKeyStr)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = "Failed to fetch public key: ${e.message} (response: ${rawResponse ?: "null"})"
            DebugLog.w("ShellClient", lastError!!)
            false
        }
    }

    private fun verifyPublicKeySignature(pubKey: String, nonce: String, sig: String): Boolean {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "$nonce|$pubKey"
        val expected = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            sig.toByteArray(Charsets.UTF_8)
        )
    }

    fun getServerPublicKeyBase64(): String? {
        return serverPublicKey?.let { ShellCrypto.encodePublicKey(it) }
    }

    data class PreparedRequest(
        val body: String,
        val headers: Map<String, String>,
        val isEncrypted: Boolean,
        val serverUrl: String
    )

    fun prepareRequest(
        command: String,
        timeoutMs: Int,
        workdir: String
    ): PreparedRequest {
        val jsonBody = buildJsonBody(command, timeoutMs, workdir)

        if (apiKey.isBlank()) {
            return PreparedRequest(jsonBody, mapOf("Content-Type" to "application/json"), false, serverUrl)
        }

        val pubKey = serverPublicKey
            ?: throw IllegalStateException("Server public key not available. Call fetchPublicKey() first.")

        // Generate ephemeral key pair and derive AES key
        val ephemeralKP = ShellCrypto.generateEphemeralKeyPair()
        val aesKey = ShellCrypto.deriveAesKey(ephemeralKP.private, pubKey)
        currentAesKey = aesKey
        currentKeyPair = ephemeralKP

        // Encrypt body
        val encryptedBody = ShellCrypto.encrypt(aesKey, jsonBody.toByteArray(Charsets.UTF_8))
        val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
        val bodySha256 = ShellCrypto.sha256Hex(bodyBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ShellCrypto.generateNonce()
        val signature = ShellCrypto.sign(apiKey, timestamp, "POST", "/execute", bodySha256, nonce)
        val clientPubKey = ShellCrypto.encodePublicKey(ephemeralKP.public)

        val headers = mapOf(
            "Content-Type" to "application/octet-stream",
            "Authorization" to "Bearer $apiKey",
            "X-Timestamp" to timestamp.toString(),
            "X-Signature" to signature,
            "X-Nonce" to nonce,
            "X-Encryption" to "v1",
            "X-Client-Public-Key" to clientPubKey
        )

        return PreparedRequest(encryptedBody, headers, true, serverUrl)
    }

    fun decryptSseData(encryptedData: String): String {
        val key = currentAesKey ?: throw IllegalStateException("No session key")
        return String(ShellCrypto.decrypt(key, encryptedData.replace(" ", "")), Charsets.UTF_8)
    }

    fun getSessionKey(): ByteArray? = currentAesKey

    private fun buildJsonBody(command: String, timeoutMs: Int, workdir: String): String {
        val sb = StringBuilder()
        sb.append("{\"command\":\"")
        sb.append(escapeJson(command))
        sb.append("\",\"timeout_ms\":")
        sb.append(timeoutMs)
        if (workdir.isNotBlank()) {
            sb.append(",\"workdir\":\"")
            sb.append(escapeJson(workdir))
            sb.append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
