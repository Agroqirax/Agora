package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiPathAssemblerTest {
    @Test
    fun protocolRowsReachableFromAncestryAndSideChain_areEmittedExactlyOnce() {
        val user = message("u", null, Participant.USER, 0)
        val model = message("m", "u", Participant.MODEL, 1, toolJson = "aggregated")
        val tool = message("tool_round", "m", Participant.MODEL, 2)
        val result = message("result_round", "tool_round", Participant.USER, 3)
        val queued = message("queued", "result_round", Participant.USER, 4)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model, tool, result, queued),
            allMessages = listOf(user, model, tool, result, queued),
        )

        assertEquals(
            listOf("u", "tool_round", "result_round", "m", "queued"),
            assembled.map { it.id },
        )
        assertEquals(assembled.size, assembled.map { it.id }.distinct().size)
        assertNull(assembled.first { it.id == "m" }.toolCallJson)
    }

    @Test
    fun interventionPersistedBeforeToolRoundStillReceivesCompletedSideChain() {
        val user = message("u", null, Participant.USER, 0)
        val model = message("m", "u", Participant.MODEL, 1, toolJson = "aggregated")
        val queued = message("queued", "m", Participant.USER, 2)
        val tool = message("tool_round", "m", Participant.MODEL, 3)
        val result = message("result_round", "tool_round", Participant.USER, 4)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model, queued),
            allMessages = listOf(user, model, queued, tool, result),
        )

        assertEquals(
            listOf("u", "tool_round", "result_round", "m", "queued"),
            assembled.map { it.id },
        )
    }

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
        toolJson: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = if (participant == Participant.USER) id else "",
        participant = participant,
        timestamp = sequence,
        modelName = "claude-sonnet-5",
        toolCallJson = toolJson,
        runId = "run",
        runSequence = sequence,
    )
}
