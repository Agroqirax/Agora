package com.newoether.agora.diagnostics

data class DeveloperTestResult(
    val id: String,
    val passed: Boolean,
    val detail: String,
)

/** Deterministic, offline fixtures. They never read credentials, network state, or user data. */
object DeveloperTestLab {
    fun runAll(): List<DeveloperTestResult> = listOf(
        run("credential_redaction") {
            val headers = DiagnosticRedactor.captureHeaders(
                mapOf("Authorization" to "Bearer fixture-secret-token"),
            )
            val url = DiagnosticRedactor.captureUrl(
                "https://example.invalid/chat?key=fixture-query-secret",
            )
            headers.values.none { it.contains("fixture-secret-token") } &&
                !url.value.contains("fixture-query-secret")
        },
        run("redacted_json_shape") {
            val captured = DiagnosticRedactor.captureJson(
                """{"model":"fixture","content":"private fixture","api_key":"fixture-secret"}""",
                DiagnosticCaptureMode.REDACTED_CONTENT,
            )
            captured.value.contains("model") &&
                captured.value.contains("fixture") &&
                !captured.value.contains("private fixture") &&
                !captured.value.contains("fixture-secret")
        },
        run("sse_fixture") {
            val captured = DiagnosticRedactor.captureWireLine(
                """data: {"text":"private fixture"}""",
                DiagnosticCaptureMode.REDACTED_CONTENT,
            )
            captured.value.startsWith("data:") &&
                captured.value.contains("[REDACTED_CONTENT]") &&
                !captured.value.contains("private fixture")
        },
        run("invalid_payload_fails_closed") {
            val captured = DiagnosticRedactor.captureJson(
                "not-json private fixture",
                DiagnosticCaptureMode.SENSITIVE_CONTENT,
            )
            captured.value == "[UNAVAILABLE_INVALID_JSON]"
        },
        run("capture_limit") {
            val captured = DiagnosticRedactor.captureContent(
                "x".repeat(40_000),
                DiagnosticCaptureMode.SENSITIVE_CONTENT,
            )
            captured.truncated && captured.value.length == 32_768
        },
        run("stable_private_identity") {
            val first = com.newoether.agora.model.ConversationRuntimeTrace
                .hashConversationId("fixture-id")
            val second = com.newoether.agora.model.ConversationRuntimeTrace
                .hashConversationId("fixture-id")
            first == second && first.length == 24 && !first.contains("fixture-id")
        },
    )

    private inline fun run(
        id: String,
        block: () -> Boolean,
    ): DeveloperTestResult = try {
        val passed = block()
        DeveloperTestResult(
            id = id,
            passed = passed,
            detail = if (passed) "PASS" else "FAILED_ASSERTION",
        )
    } catch (error: Throwable) {
        DeveloperTestResult(
            id = id,
            passed = false,
            detail = "FAILED_" + error.javaClass.simpleName,
        )
    }
}
