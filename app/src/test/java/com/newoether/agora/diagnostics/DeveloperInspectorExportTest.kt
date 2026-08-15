package com.newoether.agora.diagnostics

import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperInspectorExportTest {
    @Test
    fun `conversation inspector retains metadata but no raw ids or message content`() {
        val conversation = ChatConversation(
            id = CONVERSATION_ID,
            title = "private title",
            modelId = "provider:model",
            origin = "user",
        )
        val messages = listOf(
            ChatMessage(
                id = MESSAGE_ID,
                text = PRIVATE_TEXT,
                thoughts = "private thought",
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                runId = RUN_ID,
                tokenCount = 12,
            ),
        )

        val inspection = checkNotNull(
            DeveloperConversationInspector.inspect(
                conversation = conversation,
                messages = messages,
                totalTokens = 12,
                isLoading = false,
                runtimeTransitions = listOf(
                    ConversationRuntimeTraceEntry(
                        sequence = 1L,
                        conversationIdHash = "existing-hash",
                        runId = RUN_ID,
                        pass = 2,
                        effectId = EFFECT_ID,
                        oldState = "Idle",
                        commandType = "SendRequested",
                        newState = "Preparing",
                        effectTypes = listOf("PersistAcceptedInput"),
                        timestamp = 10L,
                    ),
                ),
            ),
        )
        val formatted = DeveloperConversationInspector.format(inspection)

        assertEquals(1, inspection.messageCount)
        assertEquals(PRIVATE_TEXT.length, inspection.messages.single().textChars)
        assertEquals(15, inspection.messages.single().thoughtChars)
        assertEquals(24, inspection.conversationIdHash.length)
        assertFalse(formatted.contains(CONVERSATION_ID))
        assertFalse(formatted.contains(MESSAGE_ID))
        assertFalse(formatted.contains(RUN_ID))
        assertFalse(formatted.contains(EFFECT_ID))
        assertFalse(formatted.contains(PRIVATE_TEXT))
        assertFalse(formatted.contains("private thought"))
        assertFalse(formatted.contains("private title"))
    }

    @Test
    fun `redacted export strips sensitive payload content and exposes omissions`() {
        val events = (1L..300L).map { sequence ->
            DiagnosticEvent(
                sequence = sequence,
                timestampMillis = sequence,
                context = DiagnosticRequestContext(
                    requestId = REQUEST_ID,
                    runId = RUN_ID,
                ),
                payload = when (sequence) {
                    300L -> {
                        val raw = """{"content":"private export","api_key":"export-secret"}"""
                        DiagnosticEventPayload.HttpRequest(
                            method = "POST",
                            url = CapturedDiagnosticText(
                                value = "https://example.invalid?key=query-secret",
                                originalLength = 40,
                                truncated = false,
                                redacted = true,
                            ),
                            headers = mapOf("Authorization" to "Bearer header-secret"),
                            body = CapturedDiagnosticText(
                                value = raw,
                                originalLength = raw.length,
                                truncated = false,
                                redacted = true,
                            ),
                        )
                    }
                    299L -> DiagnosticEventPayload.RuntimeTransition(
                        oldState = "Active",
                        commandType = "StopRequested",
                        newState = "Stopping",
                        effectId = EFFECT_ID,
                        effectTypes = listOf("FinalizeStop"),
                    )
                    298L -> DiagnosticEventPayload.ParsedStreamEvent(
                        eventType = "ToolCallRequest",
                        attributes = mapOf(
                            "id" to TOOL_CALL_ID,
                            "streamKey" to STREAM_KEY,
                            "name" to "fixture_tool",
                        ),
                        content = null,
                    )
                    else -> DiagnosticEventPayload.HttpStage(
                        stage = "stage",
                        elapsedMillis = sequence,
                        attributes = emptyMap(),
                    )
                },
            )
        }
        val snapshot = DiagnosticSnapshot(
            session = DiagnosticSession(
                id = SESSION_ID,
                mode = DiagnosticCaptureMode.SENSITIVE_CONTENT,
                startedAtMillis = 1L,
            ),
            events = events,
        )

        val exported = DiagnosticBundleExporter.exportRedacted(
            snapshot = snapshot,
            conversation = null,
            generatedAtMillis = 2L,
        )

        assertFalse(exported.contains("private export"))
        assertFalse(exported.contains("export-secret"))
        assertFalse(exported.contains("query-secret"))
        assertFalse(exported.contains("header-secret"))
        assertFalse(exported.contains(SESSION_ID))
        assertFalse(exported.contains(REQUEST_ID))
        assertFalse(exported.contains(RUN_ID))
        assertFalse(exported.contains(EFFECT_ID))
        assertFalse(exported.contains(TOOL_CALL_ID))
        assertFalse(exported.contains(STREAM_KEY))
        assertTrue(exported.contains("idHash"))
        assertTrue(exported.contains("requestIdHash"))
        assertTrue(exported.contains("runIdHash"))
        assertTrue(exported.contains("effectIdHash"))
        assertTrue(exported.contains("fixture_tool"))
        assertTrue(exported.contains("[REDACTED_CONTENT]"))
        assertTrue(exported.contains("[REDACTED_SECRET]"))
        assertTrue(exported.contains("omittedEventCount"))
        assertTrue(exported.contains("44"))
        assertTrue(exported.contains("redactedExport"))
    }

    @Test
    fun `diagnostic display projection replaces custom provider ids with current alias`() {
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providers = listOf(CustomProviderConfig(name = "Relay Alias", id = providerId))
        val rawBody = """{"model":"$providerId:model"}"""
        val rawSnapshot = DiagnosticSnapshot(
            events = listOf(
                DiagnosticEvent(
                    sequence = 1L,
                    timestampMillis = 1L,
                    context = DiagnosticRequestContext(
                        provider = providerId,
                        model = "$providerId:model",
                    ),
                    payload = DiagnosticEventPayload.HttpRequest(
                        method = "POST",
                        url = CapturedDiagnosticText(
                            value = "https://example.invalid",
                            originalLength = 23,
                            truncated = false,
                            redacted = true,
                        ),
                        headers = mapOf("X-Provider" to providerId),
                        body = CapturedDiagnosticText(
                            value = rawBody,
                            originalLength = rawBody.length,
                            truncated = false,
                            redacted = true,
                        ),
                    ),
                ),
            ),
        )
        val displaySnapshot = rawSnapshot.forDisplay(providers)
        val displayInspection = checkNotNull(
            DeveloperConversationInspector.inspect(
                conversation = ChatConversation(
                    id = CONVERSATION_ID,
                    title = "diagnostic fixture",
                    modelId = "$providerId:model",
                    origin = "user",
                ),
                messages = emptyList(),
                totalTokens = 0,
                isLoading = false,
                runtimeTransitions = emptyList(),
            ),
        ).forDisplay(providers)

        assertEquals("Relay Alias", displaySnapshot.events.single().context.provider)
        assertEquals("Relay Alias:model", displaySnapshot.events.single().context.model)
        assertEquals("Relay Alias:model", displayInspection.model)
        assertFalse(displaySnapshot.toString().contains(providerId))
        assertFalse(displayInspection.toString().contains(providerId))

        val exported = DiagnosticBundleExporter.exportRedacted(
            snapshot = displaySnapshot,
            conversation = displayInspection,
            generatedAtMillis = 2L,
        )
        assertFalse(exported.contains(providerId))
        assertTrue(exported.contains("Relay Alias"))
    }

    @Test
    fun `offline test lab fixtures all pass`() {
        val results = DeveloperTestLab.runAll()

        assertTrue(results.isNotEmpty())
        assertTrue(results.joinToString(), results.all(DeveloperTestResult::passed))
    }

    private companion object {
        const val CONVERSATION_ID = "raw-conversation-id"
        const val MESSAGE_ID = "raw-message-id"
        const val RUN_ID = "raw-run-id"
        const val EFFECT_ID = "raw-effect-id"
        const val SESSION_ID = "raw-session-id"
        const val REQUEST_ID = "raw-request-id"
        const val TOOL_CALL_ID = "raw-tool-call-id"
        const val STREAM_KEY = "raw-stream-key"
        const val PRIVATE_TEXT = "private message text"
    }
}
