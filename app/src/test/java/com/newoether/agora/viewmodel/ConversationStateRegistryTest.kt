package com.newoether.agora.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateRegistryTest {
    @Test
    fun stoppingSlot_survivesUiOwnerReplacement() {
        val registry = ConversationStateRegistry()
        val firstOwner = Any()
        registry.attachUiCallbacks(firstOwner) { }
        val state = registry.getOrCreate("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        state.stop()
        registry.detachUiCallbacks(firstOwner)
        val secondOwner = Any()
        registry.attachUiCallbacks(secondOwner) { }

        assertSame(state, registry.getOrCreate("conversation"))
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertTrue("conversation" in registry.activeConversationIds.value)

        assertFalse(state.endGeneration(token))
        assertTrue(state.finishStopFinalization(success = true))
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
        assertFalse("conversation" in registry.activeConversationIds.value)
    }

    @Test
    fun staleOwnerCannotDetachNewerUiCallbacks() {
        val registry = ConversationStateRegistry()
        val firstOwner = Any()
        val secondOwner = Any()
        var secondOwnerActive = false
        registry.attachUiCallbacks(firstOwner) { state ->
            state.onActive = { }
        }
        registry.attachUiCallbacks(secondOwner) { state ->
            state.onActive = { secondOwnerActive = true }
        }
        registry.detachUiCallbacks(firstOwner)

        registry.getOrCreate("conversation").acquireForSend()

        assertTrue(secondOwnerActive)
    }
}
