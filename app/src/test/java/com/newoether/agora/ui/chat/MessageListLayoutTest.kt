package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MessageListLayoutTest {
    @Test
    fun appendingUserKeepsPreviousAssistantInTheSameTurn() {
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)

        val beforeSend = buildMessageListTurns(listOf(user1, assistant1))
        val afterSend = buildMessageListTurns(listOf(user1, assistant1, user2))

        assertEquals(beforeSend.single(), afterSend.first())
        assertEquals("user-1", afterSend.first().key)
        assertEquals(listOf("user-1", "assistant-1"), afterSend.first().messages.map { it.id })
        assertEquals(listOf("user-2"), afterSend.last().messages.map { it.id })
    }

    @Test
    fun turnCache_reusesHistoryAndReplacesOnlyTheStreamingTurn() {
        val cache = MessageListTurnCache()
        val user1 = message("user-1", Participant.USER)
        val assistant1 = message("assistant-1", Participant.MODEL)
        val user2 = message("user-2", Participant.USER)
        val firstStream = message("assistant-2", Participant.MODEL).copy(text = "a")
        val before = cache.update(listOf(user1, assistant1, user2, firstStream))

        val nextStream = firstStream.copy(text = "ab")
        val after = cache.update(listOf(user1, assistant1, user2, nextStream))

        assertSame(before.first(), after.first())
        assertNotSame(before.last(), after.last())
        assertEquals("ab", after.last().messages.last().text)
    }

    @Test
    fun everyMessageInATurnMapsToTheSameLazyItemIndex() {
        val turns = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
                message("user-2", Participant.USER),
                message("assistant-2", Participant.MODEL),
            ),
        )

        assertEquals(0, messageListTurnIndex(turns, "user-1"))
        assertEquals(0, messageListTurnIndex(turns, "assistant-1"))
        assertEquals(0, messageListTurnIndex(turns, "error-1"))
        assertEquals(1, messageListTurnIndex(turns, "user-2"))
        assertEquals(1, messageListTurnIndex(turns, "assistant-2"))
        assertEquals(-1, messageListTurnIndex(turns, "missing"))
    }

    @Test
    fun turnHeightEstimateSumsChildrenForForcedAnimatedScroll() {
        val turn = buildMessageListTurns(
            listOf(
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
                message("error-1", Participant.ERROR),
            ),
        ).single()

        assertEquals(
            372f,
            estimateMessageListTurnHeightPx(
                turn = turn,
                messageHeights = mapOf("user-1" to 120, "assistant-1" to 180),
                fallbackHeightPx = 72f,
            ),
            0f,
        )
    }

    @Test
    fun leadingNonUserMessagesRemainStableSingletonItems() {
        val turns = buildMessageListTurns(
            listOf(
                message("error-1", Participant.ERROR),
                message("assistant-0", Participant.MODEL),
                message("user-1", Participant.USER),
                message("assistant-1", Participant.MODEL),
            ),
        )

        assertEquals(
            listOf(
                listOf("error-1"),
                listOf("assistant-0"),
                listOf("user-1", "assistant-1"),
            ),
            turns.map { turn -> turn.messages.map { it.id } },
        )
    }

    @Test
    fun shortTailUsesTheAvailableViewportAsItsMinimumHeight() {
        val viewport = 1_000
        val top = 140
        val bottom = 180
        val content = 260

        val minimum = calculateTailMinHeightPx(viewport, top, bottom)
        val layoutHeight = calculateTailLayoutHeightPx(minimum, content)

        assertEquals(680, minimum)
        assertEquals(680, layoutHeight)
        assertEquals(viewport - top, layoutHeight + bottom)
    }

    @Test
    fun componentGrowthAndShrinkBelowMinimumNeverChangeTailGeometry() {
        val beforeContent = 500
        val afterContent = 220
        val minimum = calculateTailMinHeightPx(1_000, 140, 180)
        val beforeHeight = calculateTailLayoutHeightPx(minimum, beforeContent)
        val afterHeight = calculateTailLayoutHeightPx(minimum, afterContent)

        assertEquals(minimum, beforeHeight)
        assertEquals(minimum, afterHeight)
    }

    @Test
    fun bottomBarGrowthReducesTheTailMinimumDirectly() {
        val beforeBottom = 120
        val afterBottom = 260
        val beforeMinimum = calculateTailMinHeightPx(1_000, 140, beforeBottom)
        val afterMinimum = calculateTailMinHeightPx(1_000, 140, afterBottom)

        assertEquals(140, beforeMinimum - afterMinimum)
    }

    @Test
    fun longTailGrowsNaturallyPastTheMinimum() {
        val minimum = calculateTailMinHeightPx(1_000, 140, 180)

        assertEquals(
            2_000,
            calculateTailLayoutHeightPx(minimum, contentHeightPx = 2_000),
        )
    }

    @Test
    fun scrollStateMachineOnlyCorrectsStableVisibleLayouts() {
        assertEquals(
            MessageListLayoutMode.STABLE,
            messageListLayoutMode(isSwitching = false, isScrollInProgress = false),
        )
        assertEquals(
            MessageListLayoutMode.ACTIVE_SCROLL,
            messageListLayoutMode(isSwitching = false, isScrollInProgress = true),
        )
        assertEquals(
            MessageListLayoutMode.COVERED_TRANSITION,
            messageListLayoutMode(isSwitching = true, isScrollInProgress = false),
        )
    }

    @Test
    fun reversingMutationKeepsTheOriginalPreChangeAnchor() {
        val lock = MessageListMutationAnchorLock()
        val original = MessageListViewportAnchor("message-a", 37)

        assertEquals(original, lock.begin("thinking-card", original))
        assertEquals(original, lock.begin(
            "thinking-card",
            MessageListViewportAnchor("already-shifted", 91),
        ))

        assertEquals(1, lock.activeMutationCount)
        assertEquals(original, lock.anchor)
        assertEquals(original, lock.finish("thinking-card"))
        assertNull(lock.anchor)
    }

    @Test
    fun overlappingMutationsReleaseOnlyAfterTheLastAnimationSettles() {
        val lock = MessageListMutationAnchorLock()
        val original = MessageListViewportAnchor("message-a", 12)

        lock.begin("card-a", original)
        lock.begin("card-b", MessageListViewportAnchor("message-b", 99))

        assertNull(lock.finish("card-a"))
        assertEquals(original, lock.anchor)
        assertEquals(original, lock.finish("card-b"))
    }

    @Test
    fun userScrollCancelsPendingMutationCorrection() {
        val lock = MessageListMutationAnchorLock()
        lock.begin(
            "thinking-card",
            MessageListViewportAnchor("message-a", 12),
        )

        lock.cancel()

        assertEquals(0, lock.activeMutationCount)
        assertNull(lock.anchor)
        assertNull(lock.finish("thinking-card"))
    }

    @Test
    fun appendOnlyTextCanBeCoalescedDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "a",
            segments = listOf(MessageSegment(type = "answer", content = "a")),
        )
        val after = before.copy(
            text = "append-only",
            segments = listOf(MessageSegment(type = "answer", content = "append-only")),
        )

        assertEquals(
            true,
            sameStreamingRenderStructure(listOf(before), listOf(after)),
        )
    }

    @Test
    fun newToolSegmentCannotBeDeferredDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.THINKING,
            segments = listOf(MessageSegment(type = "thought", content = "reasoning")),
        )
        val after = before.copy(
            status = MessageStatus.TOOL_CALLING,
            segments = checkNotNull(before.segments) + MessageSegment(
                type = "tool",
                toolName = "arbitrary_tool",
                toolCallId = "call",
            ),
        )

        assertEquals(
            false,
            sameStreamingRenderStructure(listOf(before), listOf(after)),
        )
    }

    @Test
    fun terminalStateCannotBeDeferredDuringActiveScroll() {
        val before = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "complete",
        )

        assertEquals(
            false,
            sameStreamingRenderStructure(
                listOf(before),
                listOf(before.copy(status = MessageStatus.SUCCESS)),
            ),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
