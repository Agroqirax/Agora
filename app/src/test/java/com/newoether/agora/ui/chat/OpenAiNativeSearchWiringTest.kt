package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiNativeSearchWiringTest {
    @Test
    fun `chat and generation paths wire native OpenAI search end to end`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText()
        val requestBuilder = File(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        ).readText()
        val contracts = File(
            root,
            "com/newoether/agora/viewmodel/GenerationContracts.kt",
        ).readText()

        listOf(
            "openAiWebSearchAvailable = openAiWebSearchAvailable",
            "openAiWebSearchEnabled = openAiWebSearchEnabled",
            "onOpenAiWebSearchToggle =",
        ).forEach { wiring ->
            assertTrue("ChatApp must wire $wiring", wiring in chatApp)
        }
        listOf(
            "responsesApiEnabled = isResponsesApiEnabledForProvider(",
            "openAiWebSearchEnabled = effectiveSettings.openAiWebSearchEnabled == true",
        ).forEach { wiring ->
            assertTrue("generation request must wire $wiring", wiring in requestBuilder)
        }
        assertTrue("GenerationConfig must carry Responses API", "val responsesApiEnabled" in contracts)
        assertTrue("GenerationConfig must carry native search", "val openAiWebSearchEnabled" in contracts)
    }

    @Test
    fun `chat and generation paths wire compact threshold and provider transport`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText()
        val requestBuilder = File(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        ).readText()
        val contracts = File(
            root,
            "com/newoether/agora/viewmodel/GenerationContracts.kt",
        ).readText()
        val compactor = File(
            root,
            "com/newoether/agora/viewmodel/ContextCompactor.kt",
        ).readText()

        assertTrue(
            "ChatApp must collect the compact threshold",
            "settings.contextCompactThresholdPercent.collectAsState()" in chatApp,
        )
        assertTrue(
            "ChatApp must pass the compact threshold",
            "contextCompactThresholdPercent = compactThresholdPercent" in chatApp,
        )
        assertTrue(
            "automatic Compact must freeze the threshold",
            "thresholdPercent = settings.contextCompactThresholdPercent.value" in requestBuilder,
        )
        assertTrue(
            "automatic Compact must freeze the selected provider transport",
            "responsesApiEnabled = isResponsesApiEnabledForProvider(" in requestBuilder,
        )
        assertTrue("AutomaticCompactConfig must carry the threshold", "val thresholdPercent" in contracts)
        assertTrue("AutomaticCompactConfig must carry Responses API", "val responsesApiEnabled" in contracts)
        assertTrue(
            "ContextCompactor must apply the configured threshold",
            "automaticCompactTokenThreshold(contextLimit, config.thresholdPercent)" in compactor,
        )
        assertTrue(
            "ContextCompactor must project the selected provider transport",
            "responsesApiEnabled = responsesApiEnabled" in compactor,
        )
    }

    @Test
    fun `conversation service tier and legacy generic OpenAI search stay wired`() {
        val root = locateMainSourceRoot()
        val chatApp = File(root, "com/newoether/agora/ui/chat/ChatApp.kt").readText()
        val serviceTier = File(
            root,
            "com/newoether/agora/ui/chat/OpenAiConversationServiceTier.kt",
        ).readText()
        val settingsContracts = File(
            root,
            "com/newoether/agora/data/SettingsContracts.kt",
        ).readText()
        val webSearchProvider = File(
            root,
            "com/newoether/agora/tool/WebSearchToolProvider.kt",
        ).readText()
        val settingsPage = File(
            root,
            "com/newoether/agora/ui/settings/SettingsWebSearchPage.kt",
        ).readText()

        listOf(
            "openAiServiceTierAvailable =",
            "openAiServiceTierEnabled =",
            "openAiServiceTier =",
            "onOpenAiServiceTierToggle =",
            "onOpenAiServiceTierChange =",
        ).forEach { wiring -> assertTrue("ChatApp must wire $wiring", wiring in chatApp) }
        assertTrue(
            "service-tier toggle must persist a conversation override",
            "it.copy(openAiServiceTierEnabled = enabled)" in serviceTier,
        )
        assertTrue(
            "service-tier selection must persist a normalized conversation override",
            "it.copy(openAiServiceTier = OpenAiServiceTiers.normalize(tier))" in serviceTier,
        )
        assertTrue("generic provider set must retain OpenAI", "\"openai\"" in settingsContracts)
        assertTrue(
            "generic OpenAI provider must execute its Responses web-search request",
            "\"openai\" -> HttpClient.post(" in webSearchProvider,
        )
        assertTrue(
            "generic OpenAI provider must remain selectable in settings",
            "\"openai\" to R.string.web_search_openai" in settingsPage,
        )
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
