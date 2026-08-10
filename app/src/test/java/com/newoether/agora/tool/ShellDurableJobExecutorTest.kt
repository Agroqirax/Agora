package com.newoether.agora.tool

import com.newoether.agora.model.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDurableJobExecutorTest {
    private val executor = ShellDurableJobExecutor()

    @Test
    fun onlyConchTerminalStatesFinishPolling() {
        listOf("succeeded", "failed", "stopped", "interrupted").forEach { state ->
            assertTrue(executor.isTerminalJobPayload("""{"state":"$state"}"""))
        }
        assertFalse(executor.isTerminalJobPayload("""{"state":"running"}"""))
        assertFalse(executor.isTerminalJobPayload("""{"state":"stopping"}"""))
    }

    @Test
    fun explicitErrorFinishesButMalformedPayloadDoesNot() {
        assertTrue(executor.isTerminalJobPayload("""{"error":"job not found"}"""))
        assertFalse(executor.isTerminalJobPayload(""))
        assertFalse(executor.isTerminalJobPayload("not-json"))
        assertFalse(executor.isTerminalJobPayload("{}"))
    }

    @Test
    fun committedTerminalShellResultsResolveAcknowledgementsAcrossEnvelopes() {
        val calls = listOf(
            ToolCallData(
                toolName = "execute_shell_command",
                arguments = """{"server":"primary"}""",
                result = """{"server":"primary","job_id":"one","result":{"state":"succeeded"}}""",
            ),
            ToolCallData(
                toolName = "get_shell_job",
                arguments = """{"server":"secondary","job_id":"two"}""",
                result = """{"type":"shell_job","job_id":"two","state":"failed"}""",
            ),
            ToolCallData(
                toolName = "wait_for_job",
                arguments = """{"server":"third","job_id":"three"}""",
                result = """{"job_id":"three","result":{"state":"interrupted"}}""",
            ),
        )

        assertEquals(
            listOf(
                TerminalShellJobAcknowledgement("primary", "one"),
                TerminalShellJobAcknowledgement("secondary", "two"),
                TerminalShellJobAcknowledgement("third", "three"),
            ),
            terminalShellJobAcknowledgements(calls),
        )
    }

    @Test
    fun runningMalformedAndUnrelatedResultsAreNeverAcknowledged() {
        val calls = listOf(
            ToolCallData(
                toolName = "execute_shell_command",
                arguments = "{}",
                result = """{"background":true,"job_id":"running","state":"running"}""",
            ),
            ToolCallData(
                toolName = "stop_shell_job",
                arguments = "{}",
                result = """{"job_id":"stopping","state":"stopping"}""",
            ),
            ToolCallData(
                toolName = "get_shell_job",
                arguments = "{}",
                result = "not-json",
            ),
        )

        assertTrue(terminalShellJobAcknowledgements(calls).isEmpty())
    }
}
