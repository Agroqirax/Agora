package com.newoether.agora.model

import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageGenerationBoundaryResolverTest {
    @Test
    fun userMessagesAreTheOnlyGenerationSeparators() {
        val messages = listOf(
            message("u0", Participant.USER),
            message("m0", Participant.MODEL, "u0"),
            message("m1", Participant.MODEL, "m0"),
            message("u1", Participant.USER, "m1"),
            message("m2", Participant.MODEL, "u1"),
        )

        val boundaries = MessageGenerationBoundaryResolver.resolve(messages)

        assertEquals(2, boundaries.size)
        assertEquals("u0", boundaries[0].input?.id)
        assertEquals("m0", boundaries[0].firstAssistant?.id)
        assertEquals("m1", boundaries[0].lastAssistant?.id)
        assertEquals("u1", boundaries[1].input?.id)
        assertEquals("m2", boundaries[1].lastAssistant?.id)
    }

    @Test
    fun protocolAndCompactRowsDoNotCreateOrReplaceBoundaries() {
        val messages = listOf(
            message("u0", Participant.USER),
            message("m0", Participant.MODEL, "u0"),
            message("${Constants.TOOL_MSG_PREFIX}0", Participant.MODEL, "m0"),
            message("${Constants.RESULT_MSG_PREFIX}0", Participant.USER, "${Constants.TOOL_MSG_PREFIX}0"),
            message("${Constants.COMPACT_MSG_PREFIX}0", Participant.MODEL, "${Constants.RESULT_MSG_PREFIX}0"),
        )

        val boundary = MessageGenerationBoundaryResolver.resolve(messages).single()

        assertEquals("u0", boundary.input?.id)
        assertEquals("m0", boundary.firstAssistant?.id)
        assertEquals("m0", boundary.lastAssistant?.id)
    }

    @Test
    fun ancestorLookupFailsClosedForMissingParentsAndCycles() {
        val valid = listOf(
            message("u0", Participant.USER),
            message("m0", Participant.MODEL, "u0"),
            message("m1", Participant.MODEL, "m0"),
        )
        assertEquals(
            "u0",
            MessageGenerationBoundaryResolver.nearestInputAncestorId(valid, "m1"),
        )

        val missing = listOf(message("m0", Participant.MODEL, "missing"))
        assertNull(MessageGenerationBoundaryResolver.nearestInputAncestorId(missing, "m0"))

        val cycle = listOf(
            message("m0", Participant.MODEL, "m1"),
            message("m1", Participant.MODEL, "m0"),
        )
        assertNull(MessageGenerationBoundaryResolver.nearestInputAncestorId(cycle, "m0"))
    }

    private fun message(
        id: String,
        participant: Participant,
        parentId: String? = null,
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = participant,
    )
}
