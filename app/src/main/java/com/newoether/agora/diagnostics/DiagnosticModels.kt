package com.newoether.agora.diagnostics

data class DiagnosticRequestContext(
    val requestId: String? = null,
    val conversationIdHash: String? = null,
    val runId: String? = null,
    val pass: Int? = null,
    val provider: String? = null,
    val model: String? = null,
    val requestKind: String? = null,
)

enum class DiagnosticCaptureMode {
    METADATA,
    REDACTED_CONTENT,
    SENSITIVE_CONTENT,
}

data class DiagnosticSession(
    val id: String,
    val mode: DiagnosticCaptureMode,
    val startedAtMillis: Long,
    val stoppedAtMillis: Long? = null,
) {
    val isActive: Boolean get() = stoppedAtMillis == null
}

data class CapturedDiagnosticText(
    val value: String,
    val originalLength: Int,
    val truncated: Boolean,
    /** True when a redaction policy was applied, even if no matching secret was present. */
    val redacted: Boolean,
)

sealed interface DiagnosticEventPayload {
    data class RuntimeTransition(
        val oldState: String,
        val commandType: String,
        val newState: String,
        val effectId: String?,
        val effectTypes: List<String>,
    ) : DiagnosticEventPayload

    data class HttpStage(
        val stage: String,
        val elapsedMillis: Long,
        val attributes: Map<String, String>,
    ) : DiagnosticEventPayload

    data class HttpRequest(
        val method: String,
        val url: CapturedDiagnosticText,
        val headers: Map<String, String>,
        val body: CapturedDiagnosticText,
    ) : DiagnosticEventPayload

    data class HttpResponseBody(
        val code: Int,
        val body: CapturedDiagnosticText,
    ) : DiagnosticEventPayload

    data class WireLine(
        val lineNumber: Long,
        val line: CapturedDiagnosticText,
    ) : DiagnosticEventPayload

    data class ParsedStreamEvent(
        val eventType: String,
        val attributes: Map<String, String>,
        val content: CapturedDiagnosticText?,
    ) : DiagnosticEventPayload
}

data class DiagnosticEvent(
    val sequence: Long,
    val timestampMillis: Long,
    val context: DiagnosticRequestContext,
    val payload: DiagnosticEventPayload,
)

data class DiagnosticSnapshot(
    val session: DiagnosticSession? = null,
    val events: List<DiagnosticEvent> = emptyList(),
    val droppedEventCount: Long = 0,
) {
    val isCaptureActive: Boolean get() = session?.isActive == true
}
