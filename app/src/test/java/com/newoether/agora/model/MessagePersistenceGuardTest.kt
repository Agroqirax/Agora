package com.newoether.agora.model

import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePersistenceGuardTest {
    @Test
    fun independentlyPersistedToolResultFieldsAreIncludedInRowBudget() {
        val originalLength = 6_000
        val encoded = checkNotNull(
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolResult = "r".repeat(originalLength),
                        toolResultText = "t".repeat(originalLength),
                        toolStructuredResult = "s".repeat(originalLength),
                    ),
                ),
                maxBytes = 12_000,
            ),
        )

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 12_000)
        val segment = Json.decodeFromString<List<MessageSegment>>(encoded).single()
        assertTrue(
            listOf(
                segment.toolResult,
                segment.toolResultText,
                segment.toolStructuredResult,
            ).any { it.orEmpty().length < originalLength },
        )
    }

    @Test
    fun unshrinkableAggregateFailsClosedInsteadOfPersistingAnOversizedRow() {
        val encoded = MessagePersistenceGuard.encodeSegmentsBounded(
            segments = List(40) {
                MessageSegment(
                    type = "tool",
                    toolName = "tool_$it",
                    toolArgs = """{"payload":"${"x".repeat(2_000)}"}""",
                )
            },
            maxBytes = 8_000,
        )

        assertNull(encoded)
    }

    @Test
    fun responsesContinuationStateFailsExplicitlyInsteadOfBecomingSqlNull() {
        val error = runCatching {
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "lookup",
                        responseOutputItems = listOf(
                            buildJsonObject {
                                put("id", "rs_1")
                                put("type", "reasoning")
                                put("encrypted_content", "x".repeat(4_000))
                            },
                        ),
                        responseOutputItemProvider = "OpenAI",
                    ),
                ),
                maxBytes = 512,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("continuation state"))
    }

    @Test
    fun clippedTextDoesNotSplitASurrogatePair() {
        val prefix = "a".repeat(Constants.MAX_PERSISTED_TEXT_CHARS - 1)
        val clipped = MessagePersistenceGuard.clipText(prefix + "😀" + "tail")
        val retained = clipped.removeSuffix(MessagePersistenceGuard.TRUNCATION_MARKER)

        assertTrue(clipped.endsWith(MessagePersistenceGuard.TRUNCATION_MARKER))
        assertFalse(retained.last().isHighSurrogate())
    }
}
