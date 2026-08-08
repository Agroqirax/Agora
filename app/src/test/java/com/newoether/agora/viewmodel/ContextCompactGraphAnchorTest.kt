package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextCompactGraphAnchorTest {
    @Test
    fun providerSuffixStartingAtToolAnchorsBeforeVisibleAggregate() {
        val model = entity("model", "user", Participant.MODEL, 1)
        val tool = entity("tool_round", "model", Participant.MODEL, 2)
        val result = entity("result_round", "tool_round", Participant.USER, 3)
        val byId = listOf(model, tool, result).associateBy(MessageEntity::id)

        assertEquals(
            "model",
            resolveCompactGraphSuffixRoot("tool_round", byId)?.id,
        )
        assertEquals(
            "model",
            resolveCompactGraphSuffixRoot("result_round", byId)?.id,
        )
    }

    @Test
    fun automaticSplitExcludesCurrentEmptyPlaceholderButKeepsDurableUserBoundary() {
        val oldUser = entity("old-user", null, Participant.USER, 1).copy(text = "old")
        val oldModel = entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer")
        val currentUser = entity("current-user", "old-model", Participant.USER, 3).copy(text = "new")
        val placeholder = entity("placeholder", "current-user", Participant.MODEL, 4).copy(
            status = MessageStatus.SENDING,
        )

        val split = com.newoether.agora.api.util.splitLogicalContext(
            compactSplitMessages(
                listOf(oldUser, oldModel, currentUser, placeholder).map {
                    it.toUiChatMessage { text -> text }
                }
            ),
            retainLogicalMessages = 1,
        )

        assertEquals(listOf("old-user", "old-model"), split.prefix.map { it.id })
        assertEquals(listOf("current-user"), split.suffix.map { it.id })
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = "",
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
    )
}
