package com.newoether.agora.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Fail-closed redaction used before any payload enters the in-memory diagnostic buffer. */
internal object DiagnosticRedactor {
    private const val REDACTED_SECRET = "[REDACTED_SECRET]"
    private const val REDACTED_CONTENT = "[REDACTED_CONTENT]"
    private const val INVALID_URL = "[UNAVAILABLE_INVALID_URL]"
    private const val INVALID_JSON = "[UNAVAILABLE_INVALID_JSON]"
    private const val OVERSIZE_JSON = "[UNAVAILABLE_OVERSIZE_JSON]"
    private const val MAX_CAPTURE_CHARS = 32_768
    private const val MAX_JSON_INPUT_CHARS = 1_048_576
    private const val MAX_HEADERS = 64
    private const val MAX_HEADER_VALUE_CHARS = 1_024

    private val json = Json { ignoreUnknownKeys = true }

    fun captureUrl(rawUrl: String): CapturedDiagnosticText {
        val parsed = rawUrl.toHttpUrlOrNull()
        val sanitized = if (parsed == null) {
            INVALID_URL
        } else {
            val builder = parsed.newBuilder()
            if (parsed.username.isNotEmpty()) builder.username(REDACTED_SECRET)
            if (parsed.password.isNotEmpty()) builder.password(REDACTED_SECRET)
            parsed.queryParameterNames
                .filter(::isSecretKey)
                .forEach { name -> builder.setQueryParameter(name, REDACTED_SECRET) }
            redactSecrets(builder.build().toString())
        }
        return capture(rawUrl.length, sanitized, redacted = true)
    }

    fun captureHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
        headers.entries.take(MAX_HEADERS).forEach { (name, value) ->
            put(
                name.take(160),
                if (isSecretKey(name)) {
                    REDACTED_SECRET
                } else {
                    redactSecrets(value).take(MAX_HEADER_VALUE_CHARS)
                },
            )
        }
        if (headers.size > MAX_HEADERS) {
            put("[TRUNCATED_HEADERS]", (headers.size - MAX_HEADERS).toString())
        }
    }

    fun captureJson(
        rawJson: String,
        mode: DiagnosticCaptureMode,
    ): CapturedDiagnosticText {
        require(mode != DiagnosticCaptureMode.METADATA)
        if (rawJson.length > MAX_JSON_INPUT_CHARS) {
            return capture(
                originalLength = rawJson.length,
                sanitized = OVERSIZE_JSON,
                redacted = true,
                alreadyTruncated = true,
            )
        }
        val parsed = runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
            ?: return capture(rawJson.length, INVALID_JSON, redacted = true)
        val redacted = redactElement(
            element = parsed,
            key = null,
            redactContent = mode != DiagnosticCaptureMode.SENSITIVE_CONTENT,
            contentScope = false,
        ).toString()
        return capture(rawJson.length, redacted, redacted = true)
    }

    fun captureWireLine(
        rawLine: String,
        mode: DiagnosticCaptureMode,
    ): CapturedDiagnosticText {
        require(mode != DiagnosticCaptureMode.METADATA)
        val trimmed = rawLine.trimStart()
        val leading = rawLine.substring(0, rawLine.length - trimmed.length)
        if (trimmed.startsWith("data:")) {
            val data = trimmed.removePrefix("data:").trimStart()
            if (data == "[DONE]") {
                return capture(rawLine.length, leading + "data: [DONE]", redacted = true)
            }
            val body = captureJson(data, mode)
            return capture(
                originalLength = rawLine.length,
                sanitized = leading + "data: " + body.value,
                redacted = true,
                alreadyTruncated = body.truncated,
            )
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val body = captureJson(trimmed, mode)
            return capture(
                originalLength = rawLine.length,
                sanitized = leading + body.value,
                redacted = true,
                alreadyTruncated = body.truncated,
            )
        }
        val isSseControl = trimmed.isBlank() || trimmed.startsWith(":") ||
            trimmed.startsWith("event:") || trimmed.startsWith("id:") ||
            trimmed.startsWith("retry:")
        val sanitized = if (
            mode == DiagnosticCaptureMode.REDACTED_CONTENT && !isSseControl
        ) {
            REDACTED_CONTENT
        } else {
            redactSecrets(rawLine)
        }
        return capture(rawLine.length, sanitized, redacted = true)
    }

    fun captureContent(
        content: String,
        mode: DiagnosticCaptureMode,
    ): CapturedDiagnosticText {
        require(mode != DiagnosticCaptureMode.METADATA)
        val sanitized = if (mode == DiagnosticCaptureMode.SENSITIVE_CONTENT) {
            redactSecrets(content)
        } else {
            REDACTED_CONTENT
        }
        return capture(content.length, sanitized, redacted = true)
    }

    fun safeIdentifier(value: String): String =
        redactSecrets(value).take(MAX_HEADER_VALUE_CHARS)

    private fun redactElement(
        element: JsonElement,
        key: String?,
        redactContent: Boolean,
        contentScope: Boolean,
    ): JsonElement {
        val normalizedKey = key?.normalizeKey()
        if (normalizedKey != null && isSecretKey(normalizedKey)) {
            return JsonPrimitive(REDACTED_SECRET)
        }
        val nextContentScope = contentScope ||
            (redactContent && normalizedKey != null && normalizedKey in CONTENT_KEYS)
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (childKey, childValue) ->
                    redactElement(
                        element = childValue,
                        key = childKey,
                        redactContent = redactContent,
                        contentScope = nextContentScope,
                    )
                },
            )
            is JsonArray -> JsonArray(
                element.map { child ->
                    redactElement(
                        element = child,
                        key = null,
                        redactContent = redactContent,
                        contentScope = nextContentScope,
                    )
                },
            )
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(
                    if (nextContentScope) REDACTED_CONTENT else redactSecrets(element.content),
                )
            } else {
                element
            }
        }
    }

    private fun capture(
        originalLength: Int,
        sanitized: String,
        redacted: Boolean,
        alreadyTruncated: Boolean = false,
    ): CapturedDiagnosticText {
        val truncated = alreadyTruncated || sanitized.length > MAX_CAPTURE_CHARS
        return CapturedDiagnosticText(
            value = sanitized.take(MAX_CAPTURE_CHARS),
            originalLength = originalLength,
            truncated = truncated,
            redacted = redacted,
        )
    }

    private fun isSecretKey(key: String): Boolean = key.normalizeKey() in SECRET_KEYS

    private fun String.normalizeKey(): String =
        lowercase().filter(Char::isLetterOrDigit)

    private fun redactSecrets(value: String): String {
        var result = BEARER_SECRET.replace(value) { match ->
            match.groupValues[1] + REDACTED_SECRET
        }
        result = NAMED_SECRET.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + REDACTED_SECRET
        }
        SECRET_TOKEN_PATTERNS.forEach { pattern ->
            result = pattern.replace(result, REDACTED_SECRET)
        }
        return result
    }

    private val SECRET_KEYS = setOf(
        "authorization",
        "proxyauthorization",
        "apikey",
        "xapikey",
        "xgoogapikey",
        "cookie",
        "setcookie",
        "key",
        "password",
        "passwd",
        "secret",
        "clientsecret",
        "accesstoken",
        "refreshtoken",
        "token",
    )
    private val CONTENT_KEYS = setOf(
        "arguments",
        "content",
        "data",
        "input",
        "output",
        "prompt",
        "query",
        "reasoning",
        "reasoningcontent",
        "systeminstruction",
        "text",
        "thought",
    )
    private val BEARER_SECRET = Regex(
        """(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+""",
    )
    private val NAMED_SECRET = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|token)\s*([=:])\s*["']?[^\s"'&,}]+""",
    )
    private val SECRET_TOKEN_PATTERNS = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{12,}\b"""),
        Regex("""\bAIza[A-Za-z0-9_-]{20,}\b"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b"""),
        Regex("""\bxox[baprs]-[A-Za-z0-9-]{12,}\b"""),
    )
}
