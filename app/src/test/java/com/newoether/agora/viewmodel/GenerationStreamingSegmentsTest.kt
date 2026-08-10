package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationStreamingSegmentsTest {
    @Test
    fun `live segments merge answers and unsigned thoughts but preserve signed boundaries`() {
        val unsigned = buildLiveSegments(
            flushed = listOf(MessageSegment(type = "answer", content = "a")),
            answer = "b",
            thought = "c",
            thoughtDurationMs = 4L,
        )
        assertEquals(listOf("ab", "c"), unsigned?.map { it.content })
        assertEquals(4L, unsigned?.last()?.durationMs)

        val signed = mutableListOf(
            MessageSegment(type = "thought", content = "old", signature = "sig-old"),
        )
        appendMergedSegment(
            signed,
            MessageSegment(type = "thought", content = "new", signature = "sig-new"),
        )
        assertEquals(2, signed.size)
        assertNull(buildLiveSegments(emptyList(), "", ""))
    }

    @Test
    fun `thought timing remains call scoped and deterministic`() {
        var now = 100L
        val timing = GenerationThoughtTiming { now }

        timing.ensureStarted()
        now = 125L
        assertEquals(25L, timing.liveDurationMs())
        timing.finishCurrent()
        assertEquals(25L, timing.currentDurationMs)
        assertEquals(25L, timing.totalDurationMs)
        timing.resetCurrentDuration()
        now = 200L
        timing.ensureStarted()
        now = 215L
        timing.finishCurrent()

        assertEquals(15L, timing.currentDurationMs)
        assertEquals(40L, timing.totalDurationMs)
    }

    @Test
    fun `final snapshot preserves the terminal message projection`() {
        val oversized = "x".repeat(2_000_000)
        val snapshot = GenerationFinalSnapshot(
            messageId = "model",
            parentId = "user",
            text = oversized,
            images = listOf("image"),
            thoughts = "thought",
            thoughtTitle = "title",
            tokenCount = 9,
            tokenUsage = null,
            status = MessageStatus.SUCCESS,
            timestamp = 10L,
            thoughtTimeMs = 20L,
            modelName = "model-name",
            flushedSegments = listOf(MessageSegment(type = "answer", content = "first")),
            answerBuffer = "second",
            thoughtBuffer = "",
            thoughtSignature = null,
            thoughtSignatureProvider = null,
            thoughtDurationMs = null,
            errorMessage = null,
            runId = "run",
            runSequence = 3L,
        )

        val message: ChatMessage = snapshot.toMessage()

        assertEquals(MessagePersistenceGuard.clipText(oversized), message.text)
        assertEquals("firstsecond", message.segments?.single()?.content)
        assertEquals("run", message.runId)
        assertEquals(3L, message.runSequence)
    }

    @Test
    fun `failed snapshot keeps generated answer separate from terminal error`() {
        val snapshot = GenerationFinalSnapshot(
            messageId = "model",
            parentId = "user",
            text = "Useful partial answer",
            images = emptyList(),
            thoughts = "",
            thoughtTitle = null,
            tokenCount = 0,
            tokenUsage = null,
            status = MessageStatus.ERROR,
            timestamp = 10L,
            thoughtTimeMs = null,
            modelName = "model-name",
            flushedSegments = listOf(
                MessageSegment(type = "answer", content = "Useful partial answer"),
            ),
            answerBuffer = "",
            thoughtBuffer = "",
            thoughtSignature = null,
            thoughtSignatureProvider = null,
            thoughtDurationMs = null,
            errorMessage = "Connection closed before a valid terminator",
            runId = "run",
            runSequence = 1L,
        )

        val message = snapshot.toMessage()

        assertEquals("Useful partial answer", message.text)
        assertEquals(listOf("answer", "error"), message.segments?.map { it.type })
        assertEquals(
            "Connection closed before a valid terminator",
            message.segments?.last()?.content,
        )
    }
}
