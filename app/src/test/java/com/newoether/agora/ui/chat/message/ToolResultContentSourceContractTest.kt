package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContentSourceContractTest {
    @Test
    fun `Web Search results use distinct semantic tiers and clean separated rows`() {
        val source = source(locateMainSourceRoot(), "ToolResultContent.kt")
        val webSearch = source
            .substringAfter("private fun WebSearchResult(")
            .substringBefore("private fun IndexedCodeLine(")

        assertTrue(webSearch.contains("style = ChatType.body,"))
        assertTrue(webSearch.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(webSearch.contains("style = ChatType.thoughtBody,"))
        assertTrue(webSearch.contains("style = ChatType.micro,"))
        assertTrue(webSearch.contains("HorizontalDivider("))
        assertFalse(webSearch.contains(".background("))

        val titlePosition = webSearch.indexOf("text = title")
        val snippetPosition = webSearch.indexOf("text = snippet")
        val urlPosition = webSearch.indexOf("text = url")
        assertTrue(titlePosition >= 0)
        assertTrue(snippetPosition > titlePosition)
        assertTrue(urlPosition > snippetPosition)
    }

    private fun source(root: File, name: String): String =
        File(root, "com/newoether/agora/ui/chat/message/$name").readText()

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
