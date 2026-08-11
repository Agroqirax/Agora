package com.newoether.agora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelPresentationStateTest {
    @Test
    fun ownerIsRetainedUntilMatchingExitCompletes() {
        val state = TopLevelPresentationState()
        state.present(TopLevelPresentation.SETTINGS)

        assertEquals(TopLevelPresentation.SETTINGS, state.owner)
        assertTrue(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.CHAT, state.owner)
    }

    @Test
    fun restoredBlockingOwnerStartsFailClosed() {
        val state = TopLevelPresentationState(TopLevelPresentation.TASKS)
        assertEquals(TopLevelPresentation.TASKS, state.owner)
    }
    @Test
    fun staleExitCannotReleaseNewerPresentation() {
        val state = TopLevelPresentationState()
        state.present(TopLevelPresentation.SETTINGS)
        state.present(TopLevelPresentation.MEDIA_PREVIEW)

        assertFalse(state.release(TopLevelPresentation.SETTINGS))
        assertEquals(TopLevelPresentation.MEDIA_PREVIEW, state.owner)
    }
}
