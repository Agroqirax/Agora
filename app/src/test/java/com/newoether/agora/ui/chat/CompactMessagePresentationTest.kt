package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.ui.chat.message.usesExplicitDetailBackHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactMessagePresentationTest {
    @Test
    fun compactMessagesRemainInTurnsEvenWhenTheirSummaryIsBlank() {
        val compact = message("compact_boundary", Participant.MODEL).copy(text = "")

        val leading = buildMessageListTurns(listOf(compact))
        val insideTurn = buildMessageListTurns(
            listOf(message("user", Participant.USER), compact)
        )

        assertEquals(listOf("compact_boundary"), leading.single().messages.map { it.id })
        assertEquals(
            listOf("user", "compact_boundary"),
            insideTurn.single().messages.map { it.id },
        )
    }

    @Test
    fun explicitDetailBackHandlingIsEnabledOnlyForBottomSheetMode() {
        assertTrue(usesExplicitDetailBackHandler(ThinkingSegmentDisplayModes.BOTTOM_SHEET))
        assertFalse(usesExplicitDetailBackHandler(ThinkingSegmentDisplayModes.CARD))
        assertFalse(usesExplicitDetailBackHandler("unknown"))
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
