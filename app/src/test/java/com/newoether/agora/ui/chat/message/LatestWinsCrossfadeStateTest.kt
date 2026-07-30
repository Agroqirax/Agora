package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWinsCrossfadeStateTest {
    private val same: (String, String) -> Boolean = { first, second -> first == second }

    @Test
    fun firstUpdateAfterVisibleLiveSnapshotIsStagedInIncomingBuffer() {
        val state = LatestWinsCrossfadeState(front = "first")
            .offer("second", animateChanges = true, sameContent = same)

        assertEquals("first", state.front)
        assertEquals("second", state.incoming)
        assertTrue(state.isFading)
    }

    @Test
    fun chunksCannotInterruptAnActiveFadeAndOnlyLatestPendingSurvives() {
        val fading = LatestWinsCrossfadeState(front = "old")
            .offer("incoming", animateChanges = true, sameContent = same)
        val firstPending = fading.offer("pending-1", animateChanges = true, sameContent = same)
        val latestPending = firstPending.offer("pending-2", animateChanges = true, sameContent = same)

        assertEquals(fading.transitionId, latestPending.transitionId)
        assertEquals("incoming", latestPending.incoming)
        assertEquals("pending-2", latestPending.pending)
    }

    @Test
    fun terminalContentArrivingMidFadeIsArmedNextWithoutSnapping() {
        val state = LatestWinsCrossfadeState(front = "old")
            .offer("incoming", animateChanges = true, sameContent = same)
            .offer("final", animateChanges = true, sameContent = same)
            .finish(same)

        assertEquals("incoming", state.front)
        assertEquals("final", state.incoming)
        assertTrue(state.isFading)
    }

    @Test
    fun revertingToIncomingClearsAStalePendingValue() {
        val state = LatestWinsCrossfadeState(front = "old")
            .offer("incoming", animateChanges = true, sameContent = same)
            .offer("stale", animateChanges = true, sameContent = same)
            .offer("incoming", animateChanges = true, sameContent = same)

        assertNull(state.pending)
        assertEquals("incoming", state.finish(same).front)
    }

    @Test
    fun historicalContentRendersDirectlyWithoutAStreamAnimation() {
        val state = LatestWinsCrossfadeState<String>()
            .offer("history", animateChanges = false, sameContent = same)

        assertEquals("history", state.front)
        assertNull(state.incoming)
        assertFalse(state.isFading)
    }
}
