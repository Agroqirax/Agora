package com.newoether.agora.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `headers query parameters and user info are permanently redacted`() {
        val headers = DiagnosticRedactor.captureHeaders(
            mapOf(
                "Authorization" to "Bearer top-secret-token",
                "X-Trace" to "api_key=another-secret",
            ),
        )
        val url = DiagnosticRedactor.captureUrl(
            "https://user:password@example.com/v1/chat?key=query-secret&model=test",
        )

        assertEquals("[REDACTED_SECRET]", headers["Authorization"])
        assertFalse(headers.getValue("X-Trace").contains("another-secret"))
        assertFalse(url.value.contains("user"))
        assertFalse(url.value.contains("password"))
        assertFalse(url.value.contains("query-secret"))
        assertTrue(url.value.contains("model=test"))
    }

    @Test
    fun `redacted content mode keeps json structure but removes secrets and content`() {
        val raw = """
            {
              "api_key": "nested-secret",
              "messages": [{"role": "user", "content": "private prompt"}],
              "max_tokens": 10,
              "nested": {"access_token": "token-secret", "text": "private text"}
            }
        """.trimIndent()

        val captured = DiagnosticRedactor.captureJson(
            raw,
            DiagnosticCaptureMode.REDACTED_CONTENT,
        )

        assertFalse(captured.value.contains("nested-secret"))
        assertFalse(captured.value.contains("token-secret"))
        assertFalse(captured.value.contains("private prompt"))
        assertFalse(captured.value.contains("private text"))
        assertTrue(captured.value.contains("[REDACTED_SECRET]"))
        assertTrue(captured.value.contains("[REDACTED_CONTENT]"))
        assertTrue(captured.value.contains("max_tokens"))
        assertTrue(captured.value.contains("10"))
    }

    @Test
    fun `sensitive mode preserves content but still removes nested and inline credentials`() {
        val raw = """
            {
              "content": "keep this text but remove sk-abcdefghijklmnop",
              "authorization": "Bearer abcdefghijklmnop",
              "nested": {"password": "password-secret"}
            }
        """.trimIndent()

        val captured = DiagnosticRedactor.captureJson(
            raw,
            DiagnosticCaptureMode.SENSITIVE_CONTENT,
        )

        assertTrue(captured.value.contains("keep this text"))
        assertFalse(captured.value.contains("sk-abcdefghijklmnop"))
        assertFalse(captured.value.contains("abcdefghijklmnop"))
        assertFalse(captured.value.contains("password-secret"))
    }

    @Test
    fun `redacted mode sanitizes ndjson wire lines used by local providers`() {
        val line = DiagnosticRedactor.captureWireLine(
            rawLine = """{"message":{"content":"private local response"}}""",
            mode = DiagnosticCaptureMode.REDACTED_CONTENT,
        )

        assertFalse(line.value.contains("private local response"))
        assertTrue(line.value.contains("[REDACTED_CONTENT]"))
    }

    @Test
    fun `malformed urls and short inline credentials fail closed`() {
        val url = DiagnosticRedactor.captureUrl(
            "not a valid url user:password",
        )
        val content = DiagnosticRedactor.captureContent(
            "token=x password=y Bearer z",
            DiagnosticCaptureMode.SENSITIVE_CONTENT,
        )

        assertEquals("[UNAVAILABLE_INVALID_URL]", url.value)
        assertFalse(content.value.contains("token=x"))
        assertFalse(content.value.contains("password=y"))
        assertFalse(content.value.contains("Bearer z"))
    }

    @Test
    fun `invalid json and invalid sse data fail closed`() {
        val json = DiagnosticRedactor.captureJson(
            "not-json private-secret",
            DiagnosticCaptureMode.SENSITIVE_CONTENT,
        )
        val line = DiagnosticRedactor.captureWireLine(
            "data: not-json private-secret",
            DiagnosticCaptureMode.SENSITIVE_CONTENT,
        )

        assertEquals("[UNAVAILABLE_INVALID_JSON]", json.value)
        assertFalse(line.value.contains("private-secret"))
        assertTrue(line.value.contains("[UNAVAILABLE_INVALID_JSON]"))
    }

    @Test
    fun `large captured values expose truncation and original length`() {
        val privateContent = "x".repeat(40_000)
        val captured = DiagnosticRedactor.captureJson(
            """{"content":"$privateContent"}""",
            DiagnosticCaptureMode.SENSITIVE_CONTENT,
        )

        assertTrue(captured.truncated)
        assertTrue(captured.originalLength > captured.value.length)
        assertEquals(32_768, captured.value.length)
    }
}
