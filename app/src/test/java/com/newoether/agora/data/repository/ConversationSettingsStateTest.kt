package com.newoether.agora.data.repository

import com.newoether.agora.data.ConversationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationSettingsStateTest {
    @Test
    fun `consecutive updates are immediately visible and preserve sibling toggles`() {
        val state = ConversationSettingsState()

        state.update("conversation") { it.copy(webSearchEnabled = false) }
        state.update("conversation") { it.copy(shellEnabled = false) }

        val settings = state.state.value.getValue("conversation")
        assertFalse(settings.webSearchEnabled ?: true)
        assertFalse(settings.shellEnabled ?: true)
    }

    @Test
    fun `stale persisted emission cannot overwrite pending optimistic settings`() {
        val state = ConversationSettingsState()
        state.acceptPersisted(
            mapOf("conversation" to ConversationSettings(webSearchEnabled = true)),
        )

        val write = state.update("conversation") {
            it.copy(webSearchEnabled = false, openAiWebSearchEnabled = false)
        }
        state.acceptPersisted(
            mapOf("conversation" to ConversationSettings(webSearchEnabled = true)),
        )

        assertEquals(
            ConversationSettings(
                webSearchEnabled = false,
                openAiWebSearchEnabled = false,
            ),
            state.state.value.getValue("conversation"),
        )

        state.complete(
            write,
            mapOf(
                "conversation" to ConversationSettings(
                    webSearchEnabled = false,
                    openAiWebSearchEnabled = false,
                ),
            ),
        )
        assertEquals(false, state.state.value.getValue("conversation").webSearchEnabled)
    }

    @Test
    fun `older write completion keeps a newer update pending`() {
        val state = ConversationSettingsState()
        val webWrite = state.update("conversation") { it.copy(webSearchEnabled = false) }
        val shellWrite = state.update("conversation") { it.copy(shellEnabled = false) }

        state.complete(
            webWrite,
            mapOf("conversation" to ConversationSettings(webSearchEnabled = false)),
        )

        assertEquals(
            ConversationSettings(webSearchEnabled = false, shellEnabled = false),
            state.state.value.getValue("conversation"),
        )

        state.complete(
            shellWrite,
            mapOf(
                "conversation" to ConversationSettings(
                    webSearchEnabled = false,
                    shellEnabled = false,
                ),
            ),
        )
        assertEquals(false, state.state.value.getValue("conversation").shellEnabled)
    }

    @Test
    fun `only the newest pending write remains eligible for persistence`() {
        val state = ConversationSettingsState()
        val first = state.update("conversation") { it.copy(webSearchEnabled = false) }
        val second = state.update("conversation") { it.copy(shellEnabled = false) }

        assertFalse(state.isLatest(first))
        assertEquals(true, state.isLatest(second))
    }
}
