package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBottomBarControlOrderTest {
    @Test
    fun `OpenAI Search appears directly below Service Tier`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        ).readText()
        val serviceTierCondition = "if (openAiServiceTierAvailable && isModelValid)"
        val nativeSearchCondition = "if (openAiWebSearchAvailable && isModelValid)"
        val genericSearchCondition = "if (showWebSearch)"

        assertEquals(1, source.countOccurrences(serviceTierCondition))
        assertEquals(1, source.countOccurrences(nativeSearchCondition))
        assertEquals(1, source.countOccurrences(genericSearchCondition))

        val serviceTierStart = source.indexOf(serviceTierCondition)
        val serviceTierBodyStart = source.indexOf('{', startIndex = serviceTierStart)
        val serviceTierEnd = source.matchingBraceIndex(serviceTierBodyStart)
        val nextControlStart = source.indexOfFirstNonWhitespace(serviceTierEnd + 1)
        val nativeSearchStart = source.indexOf(nativeSearchCondition)
        val genericSearchStart = source.indexOf(genericSearchCondition)

        assertTrue("Service Tier must be present", serviceTierStart >= 0)
        assertTrue("OpenAI Search must immediately follow Service Tier", source.startsWith(nativeSearchCondition, nextControlStart))
        assertTrue("OpenAI Search must remain before generic Web Search", nativeSearchStart < genericSearchStart)
    }

    private fun String.matchingBraceIndex(openBraceIndex: Int): Int {
        require(openBraceIndex >= 0) { "Opening brace was not found" }
        var depth = 0
        for (index in openBraceIndex until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("Closing brace was not found")
    }

    private fun String.indexOfFirstNonWhitespace(startIndex: Int): Int {
        for (index in startIndex until length) {
            if (!this[index].isWhitespace()) return index
        }
        return length
    }

    private fun String.countOccurrences(token: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val matchIndex = indexOf(token, startIndex = startIndex)
            if (matchIndex < 0) return count
            count++
            startIndex = matchIndex + token.length
        }
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
