package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.api.util.RequestFormatException
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestValidatorTest {
    @Test
    fun completeToolRound_isAccepted() {
        validRequest().requireValidWireFormat("OpenAI")
    }

    @Test
    fun normalUserCannotInterruptPendingToolResults() {
        val broken = validRequest().copy(
            messages = validRequest().messages.dropLast(1) + user("interrupt"),
        )

        val error = runCatching {
            broken.requireValidWireFormat("OpenAI")
        }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("pending tool results"))
    }

    @Test
    fun duplicateToolCallIds_areBlockedLocally() {
        val firstAssistant = assistantToolCall("same")
        val broken = OpenAiChatRequest(
            model = "gpt-test",
            messages = listOf(
                user("first"),
                firstAssistant,
                toolResult("same"),
                user("second"),
                assistantToolCall("same"),
                toolResult("same"),
            ),
        )

        val error = runCatching {
            broken.requireValidWireFormat("OpenAI")
        }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("reuses tool call id"))
    }

    private fun validRequest() = OpenAiChatRequest(
        model = "gpt-test",
        messages = listOf(
            user("start"),
            assistantToolCall("call_1"),
            toolResult("call_1"),
        ),
    )

    private fun user(text: String) = OpenAiMessage(
        role = "user",
        content = listOf(OpenAiContentPart(type = "text", text = text)),
    )

    private fun assistantToolCall(id: String) = OpenAiMessage(
        role = "assistant",
        content = null,
        toolCalls = listOf(
            OpenAiRequestToolCall(
                id = id,
                function = OpenAiRequestFunction(
                    name = "lookup",
                    arguments = "{}",
                ),
            )
        ),
    )

    private fun toolResult(id: String) = OpenAiMessage(
        role = "tool",
        content = listOf(OpenAiContentPart(type = "text", text = "ok")),
        toolCallId = id,
    )
}
