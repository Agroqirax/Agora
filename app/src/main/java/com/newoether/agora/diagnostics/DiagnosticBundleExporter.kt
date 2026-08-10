package com.newoether.agora.diagnostics

import com.newoether.agora.model.ConversationRuntimeTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Produces a bounded, redacted-by-default bundle. There is intentionally no upload path here. */
object DiagnosticBundleExporter {
    private val json = Json { prettyPrint = true }

    fun exportRedacted(
        snapshot: DiagnosticSnapshot,
        conversation: DeveloperConversationInspection?,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String {
        val retainedEvents = snapshot.events.takeLast(MAX_EXPORT_EVENTS)
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("generatedAtMillis", generatedAtMillis)
            put("redactedExport", true)
            put("captureMode", snapshot.session?.mode?.name.orEmpty())
            put("sessionActive", snapshot.isCaptureActive)
            put("droppedEventCount", snapshot.droppedEventCount)
            put("eventCount", snapshot.events.size)
            put("omittedEventCount", snapshot.events.size - retainedEvents.size)
            snapshot.session?.let { session ->
                putJsonObject("session") {
                    put("idHash", hashIdentifier(session.id))
                    put("startedAtMillis", session.startedAtMillis)
                    session.stoppedAtMillis?.let { put("stoppedAtMillis", it) }
                }
            }
            putJsonArray("events") {
                retainedEvents.forEach { event -> add(event.toRedactedJson()) }
            }
            conversation?.let { put("conversation", it.toJson()) }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun DiagnosticEvent.toRedactedJson(): JsonObject = buildJsonObject {
        put("sequence", sequence)
        put("timestampMillis", timestampMillis)
        put("context", context.toJson())
        put("payload", payload.toRedactedJson())
    }

    private fun DiagnosticRequestContext.toJson(): JsonObject = buildJsonObject {
        requestId?.let { put("requestIdHash", hashIdentifier(it)) }
        conversationIdHash?.let { put("conversationIdHash", it) }
        runId?.let { put("runIdHash", hashIdentifier(it)) }
        pass?.let { put("pass", it) }
        provider?.let { put("provider", DiagnosticRedactor.safeIdentifier(it)) }
        model?.let { put("model", DiagnosticRedactor.safeIdentifier(it)) }
        requestKind?.let { put("requestKind", DiagnosticRedactor.safeIdentifier(it)) }
    }

    private fun DiagnosticEventPayload.toRedactedJson(): JsonObject = when (this) {
        is DiagnosticEventPayload.RuntimeTransition -> buildJsonObject {
            put("type", "RuntimeTransition")
            put("oldState", oldState)
            put("commandType", commandType)
            put("newState", newState)
            effectId?.let { put("effectIdHash", hashIdentifier(it)) }
            putJsonArray("effectTypes") {
                effectTypes.forEach { add(DiagnosticRedactor.safeIdentifier(it)) }
            }
        }
        is DiagnosticEventPayload.HttpStage -> buildJsonObject {
            put("type", "HttpStage")
            put("stage", stage)
            put("elapsedMillis", elapsedMillis)
            put("attributes", attributes.toSafeJson())
        }
        is DiagnosticEventPayload.HttpRequest -> buildJsonObject {
            put("type", "HttpRequest")
            put("method", method)
            put("url", DiagnosticRedactor.captureUrl(url.value).bounded().toJson())
            put("headers", DiagnosticRedactor.captureHeaders(headers).toSafeJson())
            put(
                "body",
                DiagnosticRedactor.captureJson(
                    body.value,
                    DiagnosticCaptureMode.REDACTED_CONTENT,
                ).bounded(body).toJson(),
            )
        }
        is DiagnosticEventPayload.HttpResponseBody -> buildJsonObject {
            put("type", "HttpResponseBody")
            put("code", code)
            put(
                "body",
                DiagnosticRedactor.captureJson(
                    body.value,
                    DiagnosticCaptureMode.REDACTED_CONTENT,
                ).bounded(body).toJson(),
            )
        }
        is DiagnosticEventPayload.WireLine -> buildJsonObject {
            put("type", "WireLine")
            put("lineNumber", lineNumber)
            put(
                "line",
                DiagnosticRedactor.captureWireLine(
                    line.value,
                    DiagnosticCaptureMode.REDACTED_CONTENT,
                ).bounded(line).toJson(),
            )
        }
        is DiagnosticEventPayload.ParsedStreamEvent -> buildJsonObject {
            put("type", "ParsedStreamEvent")
            put("eventType", eventType)
            put("attributes", attributes.toSafeJson(REDACTED_EXPORT_IDENTIFIER_KEYS))
            content?.let {
                put(
                    "content",
                    DiagnosticRedactor.captureContent(
                        it.value,
                        DiagnosticCaptureMode.REDACTED_CONTENT,
                    ).bounded(it).toJson(),
                )
            }
        }
    }

    private fun DeveloperConversationInspection.toJson(): JsonObject = buildJsonObject {
        put("conversationIdHash", conversationIdHash)
        model?.let { put("model", DiagnosticRedactor.safeIdentifier(it)) }
        put("origin", DiagnosticRedactor.safeIdentifier(origin))
        put("taskLinked", taskLinked)
        put("messageCount", messageCount)
        put("omittedMessageCount", omittedMessageCount)
        put("totalTokens", totalTokens)
        put("isLoading", isLoading)
        put("participantCounts", participantCounts.toCountJson())
        put("statusCounts", statusCounts.toCountJson())
        putJsonArray("messages") {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("index", message.index)
                        put("messageIdHash", message.messageIdHash)
                        message.parentIdHash?.let { put("parentIdHash", it) }
                        put("participant", message.participant)
                        put("status", message.status)
                        put("textChars", message.textChars)
                        put("thoughtChars", message.thoughtChars)
                        put("imageCount", message.imageCount)
                        put("segmentCount", message.segmentCount)
                        put("tokenCount", message.tokenCount)
                        message.model?.let {
                            put("model", DiagnosticRedactor.safeIdentifier(it))
                        }
                        message.runIdHash?.let { put("runIdHash", it) }
                        put("hasToolCall", message.hasToolCall)
                        put("hasAttachment", message.hasAttachment)
                    },
                )
            }
        }
        putJsonArray("runtimeTransitions") {
            runtimeTransitions.forEach { transition ->
                add(
                    buildJsonObject {
                        put("sequence", transition.sequence)
                        transition.runIdHash?.let { put("runIdHash", it) }
                        put("pass", transition.pass)
                        transition.effectIdHash?.let { put("effectIdHash", it) }
                        put("oldState", transition.oldState)
                        put("commandType", transition.commandType)
                        put("newState", transition.newState)
                        putJsonArray("effectTypes") {
                            transition.effectTypes.forEach { add(it) }
                        }
                        put("timestamp", transition.timestamp)
                    },
                )
            }
        }
    }

    private fun Map<String, String>.toSafeJson(
        identifierKeys: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        entries.take(MAX_EXPORT_MAP_ENTRIES).forEach { (key, value) ->
            val safeKey = DiagnosticRedactor.safeIdentifier(key)
            val safeValue = if (key in identifierKeys) {
                hashIdentifier(value)
            } else {
                DiagnosticRedactor.safeIdentifier(value).take(MAX_EXPORT_TEXT_CHARS)
            }
            put(safeKey, safeValue)
        }
    }

    private fun Map<String, Int>.toCountJson(): JsonObject = buildJsonObject {
        entries.take(MAX_EXPORT_MAP_ENTRIES).forEach { (key, value) ->
            put(DiagnosticRedactor.safeIdentifier(key), value)
        }
    }

    private fun CapturedDiagnosticText.bounded(
        original: CapturedDiagnosticText = this,
    ): CapturedDiagnosticText = copy(
        value = value.take(MAX_EXPORT_TEXT_CHARS),
        originalLength = original.originalLength,
        truncated = truncated || original.truncated || value.length > MAX_EXPORT_TEXT_CHARS,
        redacted = true,
    )

    private fun CapturedDiagnosticText.toJson(): JsonObject = buildJsonObject {
        put("value", value)
        put("originalLength", originalLength)
        put("truncated", truncated)
        put("redacted", true)
    }

    private fun hashIdentifier(value: String): String =
        ConversationRuntimeTrace.hashConversationId(value)

    private val REDACTED_EXPORT_IDENTIFIER_KEYS = setOf("id", "streamKey")
    private const val MAX_EXPORT_EVENTS = 256
    private const val MAX_EXPORT_TEXT_CHARS = 8_192
    private const val MAX_EXPORT_MAP_ENTRIES = 64
}
