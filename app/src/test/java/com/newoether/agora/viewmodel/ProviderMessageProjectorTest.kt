package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.toMessageSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderMessageProjectorTest {
    @Test
    fun `provider history excludes citation metadata without changing the answer`() {
        val retained = MessageSegment(type = "answer", content = "answer")
        val citation = CitationRecord(
            sourceId = "citation_source",
            provider = "openai",
            kind = "url",
            title = "Private source",
            url = "https://example.com/source",
            providerSourceId = "turn0search0",
        ).toMessageSegment()
        val entity = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 1L,
            toolCallJson = Json.encodeToString(listOf(retained, citation)),
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()

        assertEquals("answer", projected.text)
        assertEquals(listOf(retained), projected.segments)
        assertNull(projected.toolCall)
    }
}
