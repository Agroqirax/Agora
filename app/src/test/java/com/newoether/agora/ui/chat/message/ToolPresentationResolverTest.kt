package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresentationResolverTest {
    @Test
    fun emptyGlobJsonIsZeroFilesNotOneLine() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_glob",
                toolArgs = """{"pattern":"*.kt"}""",
                toolResult = """{"type":"file_glob","files":[]}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun emptyGrepJsonIsZeroMatchesNotOneLine() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_grep",
                toolArgs = """{"pattern":"missing"}""",
                toolResult = """{"type":"file_grep","matches":[]}""",
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun nullResultAndLiveOutputAreRunning() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = null,
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "line one\n",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(ToolPresentationState.RUNNING, presentation.state)
        assertEquals("line one\n", presentation.liveOutput)
        assertEquals("tinybox", presentation.device)
    }

    @Test
    fun backgroundJobRemainsActiveAfterToolCallReturns() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"background":true,"job_id":"abc","state":"running"}""",
            ),
        )

        assertEquals(ToolPresentationState.BACKGROUND_RUNNING, presentation.state)
        assertEquals("abc", presentation.jobId)
        assertTrue(presentation.isActive)
    }

    @Test
    fun structuredErrorUsesServerMessage() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"error":"error","message":"Cannot connect to Conch: refused"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("Cannot connect to Conch: refused", presentation.errorMessage)
    }

    @Test
    fun providerNoResultsCodeIsAnEmptySuccess() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolArgs = """{"query":"nothing"}""",
                toolResult = """{"type":"web_search","query":"nothing","error":"no_results"}""",
                toolState = ToolExecutionStates.FAILED,
            ),
        )

        assertEquals(ToolPresentationState.EMPTY, presentation.state)
        assertEquals(null, presentation.errorMessage)
    }

    @Test
    fun structuredErrorWithoutMessageStillFailsWithSpecificCode() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolResult = """{"type":"web_search","error":"no_api_key"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("no api key", presentation.errorMessage)
    }

    @Test
    fun shellOutputLengthExcludesJsonEnvelope() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":0,"output":"abc"}""",
            ),
        )

        assertEquals(3, presentation.outputLength)
    }

    @Test
    fun successfulShellWithoutOutputIsStillSucceeded() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":0,"output":""}""",
            ),
        )

        assertEquals(ToolPresentationState.SUCCEEDED, presentation.state)
        assertEquals(0, presentation.exitCode)
    }

    @Test
    fun nonZeroShellExitIsFailed() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":127,"output":"not found"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals(127, presentation.exitCode)
    }

    @Test
    fun nonZeroShellExitOverridesStaleSucceededWireState() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.SUCCEEDED,
                toolResult = """{"type":"execute_shell_command","exit_code":2,"output":"bad arguments"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals(2, presentation.exitCode)
    }

    @Test
    fun completedShellResultUsesAuthoritativeServerAndOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"server":"requested"}""",
                toolResult = """
                    {"type":"execute_shell_command","server":"actual","exit_code":0,"output":"done"}
                """.trimIndent(),
                toolTarget = "resolved",
            ),
        )

        assertEquals("actual", presentation.device)
        assertEquals("done", shellOutputText(presentation))
    }

    @Test
    fun legacyConnectingProgressIsNotRenderedAsCommandOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "Connecting to tinybox",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(null, shellOutputText(presentation))
    }
}
