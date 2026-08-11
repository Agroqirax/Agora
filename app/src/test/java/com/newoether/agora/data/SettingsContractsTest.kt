package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContractsTest {
    @Test
    fun automaticContextCompactIsEnabledByDefault() {
        assertTrue(DEFAULT_CONTEXT_COMPACT_ENABLED)
    }

    @Test
    fun contextCompactRetainsNoRecentMessagesByDefault() {
        assertEquals(0, DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT)
    }

    @Test
    fun defaultContextCompactPromptPreservesConversationLanguages() {
        val prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM.lowercase()
        assertTrue(prompt.contains("same language"))
        assertTrue(prompt.contains("do not translate"))
    }

    @Test
    fun legacyPromptContentResolvesToOneCustomSystemItem() {
        val prompt = SystemPromptEntry(
            title = "Legacy",
            content = "Preserve this prompt",
        )

        val resolved = prompt.resolvedSystemItems.single()
        assertEquals(PromptItemType.CUSTOM, resolved.type)
        assertEquals("Preserve this prompt", resolved.value)
    }

    @Test
    fun explicitSystemItemsTakePrecedenceOverLegacyContent() {
        val explicit = listOf(
            PromptTemplateItem(type = PromptItemType.CUSTOM, value = "Explicit"),
        )

        assertEquals(
            explicit,
            SystemPromptEntry(
                title = "Current",
                content = "Legacy",
                systemItems = explicit,
            ).resolvedSystemItems,
        )
    }

    @Test
    fun conversationSettingsReportsWhetherAnyOverrideExists() {
        assertTrue(ConversationSettings().isAllNull())
        assertFalse(ConversationSettings(shellEnabled = false).isAllNull())
    }
}
