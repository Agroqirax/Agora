package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class RunRegenerationPolicyTest {

    @Test
    fun selectBoundaryInput_returnsOnlyFirstUserAndIgnoresLaterInterventions() {
        val messages = listOf(
            message("${Constants.RESULT_MSG_PREFIX}tool", sequence = 1),
            message("later", sequence = 4, timestamp = 1),
            message("first", sequence = 0, timestamp = 3),
            message("middle", sequence = 2, timestamp = 2),
            message("other-run", sequence = 0, runId = "other"),
        )

        val boundary = RunRegenerationPolicy.selectBoundaryInput(messages, "run")

        assertEquals("first", boundary?.id)
    }

    @Test
    fun selectBoundaryInput_usesTimestampForLegacyUnassignedRows() {
        val messages = listOf(
            message("later", sequence = -1, timestamp = 20),
            message("first", sequence = -1, timestamp = 10),
        )

        val boundary = RunRegenerationPolicy.selectBoundaryInput(messages, "run")

        assertEquals("first", boundary?.id)
    }

    private fun message(
        id: String,
        sequence: Long,
        timestamp: Long = sequence,
        runId: String = "run",
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        text = id,
        participant = Participant.USER,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
    )
}
