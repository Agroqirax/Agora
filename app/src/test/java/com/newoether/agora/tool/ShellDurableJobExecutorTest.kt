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
        assertFalse(executor.isTerminalJobPayload("""{"state":"settling","error":"process exited"}"""))
        assertFalse(
            executor.isTerminalJobPayload(
                """{"state":"settling","settlement_error":"sync pending"}""",
            ),
        )
    }

    @Test
    fun explicitErrorFinishesButMalformedPayloadDoesNot() {
        assertTrue(executor.isTerminalJobPayload("""{"error":"job not found"}"""))
        assertFalse(executor.isTerminalJobPayload(""))
        assertFalse(executor.isTerminalJobPayload("not-json"))
        assertFalse(executor.isTerminalJobPayload("{}"))
    }

    @Test
    fun durableOutputCursorEmitsEachUtf8ByteExactlyOnceAcrossGrowingSnapshots() {
        val cursor = ConchJobOutputCursor()
        assertEquals(
            ConchJobOutputUpdate("one\n", 0),
            cursor.consume(jobSnapshot("one\n")),
        )
        assertEquals(
            ConchJobOutputUpdate("中文\n", 0),
            cursor.consume(jobSnapshot("one\n中文\n")),
        )
        assertEquals(
            ConchJobOutputUpdate("", 0),
            cursor.consume(jobSnapshot("one\n中文\n")),
        )
        assertEquals(
            ConchJobOutputUpdate("last\n", 0),
            cursor.consume(jobSnapshot("one\n中文\nlast\n", state = "succeeded")),
        )
    }
    @Test
    fun durableOutputCursorReportsEvictedBytesAndContinuesFromRetainedUtf8Tail() {
        val cursor = ConchJobOutputCursor()
        val retained = "中文-tail\n"
        val raw = """{"state":"running","output":${jsonString(retained)},"output_bytes":30}"""
        val update = cursor.consume(raw)
        assertEquals(retained, update.delta)
        assertEquals(30L - retained.toByteArray(Charsets.UTF_8).size, update.lostBytes)
        assertEquals(ConchJobOutputUpdate("", 0), cursor.consume(raw))
    }
    @Test
    fun durableOutputCursorIgnoresMalformedAndRegressiveSnapshotsWithoutReplaying() {
        val cursor = ConchJobOutputCursor()
        assertEquals(ConchJobOutputUpdate("", 0), cursor.consume("not-json"))
        assertEquals(ConchJobOutputUpdate("abc", 0), cursor.consume(jobSnapshot("abc")))
        assertEquals(
            ConchJobOutputUpdate("", 0),
            cursor.consume("""{"output":"ab","output_bytes":2}"""),
        )
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
    private fun jobSnapshot(output: String, state: String = "running"): String =
        """{"state":"$state","output":${jsonString(output)},"output_bytes":${output.toByteArray(Charsets.UTF_8).size}}"""
    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
