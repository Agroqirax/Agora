package com.newoether.agora.model

import kotlinx.serialization.json.Json
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
}
