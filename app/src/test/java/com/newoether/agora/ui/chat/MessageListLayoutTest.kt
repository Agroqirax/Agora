package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageListLayoutTest {
    @Test
    fun shortTailAlwaysFillsExactlyToTheComposerObstruction() {
        val viewport = 1_000
        val top = 140
        val bottom = 180
        val content = 260

        val spacer = calculateBottomSpacerPx(viewport, top, bottom, content)

        assertEquals(420, spacer)
        assertEquals(viewport - top, content + spacer + bottom)
    }

    @Test
    fun componentShrinkIsAbsorbedByEqualSpacerGrowth() {
        val beforeContent = 500
        val afterContent = 220
        val beforeSpacer = calculateBottomSpacerPx(1_000, 140, 180, beforeContent)
        val afterSpacer = calculateBottomSpacerPx(1_000, 140, 180, afterContent)

        assertEquals(beforeContent + beforeSpacer, afterContent + afterSpacer)
        assertEquals(beforeSpacer + (beforeContent - afterContent), afterSpacer)
    }

    @Test
    fun bottomBarGrowthIsAbsorbedByEqualSpacerReduction() {
        val content = 220
        val beforeBottom = 120
        val afterBottom = 260
        val beforeSpacer = calculateBottomSpacerPx(1_000, 140, beforeBottom, content)
        val afterSpacer = calculateBottomSpacerPx(1_000, 140, afterBottom, content)

        assertEquals(content + beforeSpacer + beforeBottom, content + afterSpacer + afterBottom)
    }

    @Test
    fun longTailNeverProducesNegativePadding() {
        assertEquals(
            0,
            calculateBottomSpacerPx(
                viewportHeightPx = 1_000,
                targetTopPx = 140,
                bottomObstructionPx = 180,
                tailContentHeightPx = 2_000,
            ),
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

        lock.begin("thinking-card", original)
        lock.begin(
            "thinking-card",
            MessageListViewportAnchor("already-shifted", 91),
        )

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
