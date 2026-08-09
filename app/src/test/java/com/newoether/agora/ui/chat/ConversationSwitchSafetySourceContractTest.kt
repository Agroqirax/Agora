package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwitchSafetySourceContractTest {
    @Test
    fun `projection timeout releases switching cover without entering new chat`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/ChatScrollCoordinator.kt",
        ).readText()
        val timeoutStart = source.indexOf("if (resolved == null)")
        val nextBranch = source.indexOf("} else if", startIndex = timeoutStart)

        assertTrue("conversation resolution timeout branch is missing", timeoutStart >= 0)
        assertTrue("conversation resolution timeout branch is malformed", nextBranch > timeoutStart)
        val timeoutBranch = source.substring(timeoutStart, nextBranch)
        assertTrue(timeoutBranch.contains("failSwitchingScroll"))
        assertFalse(
            "projection latency must never be interpreted as a request to enter New Chat",
            timeoutBranch.contains("createNewChat"),
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
