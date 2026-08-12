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
