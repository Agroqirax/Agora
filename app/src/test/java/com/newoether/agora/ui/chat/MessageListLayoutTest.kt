package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageListLayoutTest {
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
}
