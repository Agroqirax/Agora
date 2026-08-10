package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty

class ContextTokenEstimatorTest {

    @Test
    fun fixedCostIncludesSystemPromptAndCompleteToolSchemaDeterministically() {
        val tool = ToolDefinition(
            function = ToolFunction(
                name = "shell",
                description = "Execute a command",
                parameters = ToolParameters(
                    properties = linkedMapOf(
                        "timeout" to ToolProperty("integer", "Timeout seconds"),
                        "command" to ToolProperty("string", "Command text"),
                    ),
                    required = listOf("command"),
                ),
            ),
        )

        val first = ContextTokenEstimator.estimateFixed("System prompt", listOf(tool))
        val reordered = tool.copy(
            function = tool.function.copy(
                parameters = tool.function.parameters.copy(
                    properties = tool.function.parameters.properties.entries
                        .reversed()
                        .associate { it.toPair() },
                ),
            ),
        )

        assertEquals(first, ContextTokenEstimator.estimateFixed("System prompt", listOf(reordered)))
        assertTrue(first > ContextTokenEstimator.estimateFixed(null, emptyList()))
    }
    @Test
    fun multilingualTextIsDeterministicAndNonZero() {
        val text = "hello world 你好，世界 👋"
        val first = ContextTokenEstimator.estimateText(text)

        assertTrue(first >= 10)
        assertEquals(first, ContextTokenEstimator.estimateText(text))
    }

    @Test
    fun toolArgumentsAndResultsContributeToCost() {
        val plain = message("u", "start", Participant.USER)
        val tool = message(Constants.TOOL_MSG_PREFIX + "1", "", Participant.MODEL).copy(
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "file_read",
                    toolArgs = """{"path":"/a/very/long/path"}""",
                )
            )
        )
        val result = message(Constants.RESULT_MSG_PREFIX + "1", "", Participant.USER).copy(
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "file_read",
                    toolArgs = "{}",
                    toolResult = "result ".repeat(100),
                )
            )
        )

        assertTrue(
            ContextTokenEstimator.estimate(listOf(plain, tool, result)) >
                ContextTokenEstimator.estimate(listOf(plain))
        )
    }

    @Test
    fun mirroredToolResultTextIsCountedOnlyThroughItsWirePayload() {
        val segment = MessageSegment(
            type = "tool",
            toolName = "file_read",
            toolArgs = "{}",
            toolResult = "provider-visible result",
            toolCallId = "call-1",
        )
        val withMirroredRoomText = message(
            Constants.RESULT_MSG_PREFIX + "1",
            "provider-visible result",
            Participant.USER,
        ).copy(segments = listOf(segment))
        val withoutMirroredRoomText = withMirroredRoomText.copy(text = "")

        assertEquals(
            ContextTokenEstimator.estimate(listOf(withoutMirroredRoomText)),
            ContextTokenEstimator.estimate(listOf(withMirroredRoomText)),
        )
    }

    @Test
    fun contextLimitNeverSplitsLatestToolRoundAndRetainsUserAnchor() {
        val user = message("u", "start", Participant.USER)
        val tool = message(Constants.TOOL_MSG_PREFIX + "1", "", Participant.MODEL)
        val result = message(Constants.RESULT_MSG_PREFIX + "1", "large ".repeat(100), Participant.USER)

        assertEquals(
            listOf("u", tool.id, result.id),
            limitContext(listOf(user, tool, result), contextTokenBudget = 1).map { it.id },
        )
    }

    private fun message(id: String, text: String, participant: Participant) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
    )
}
