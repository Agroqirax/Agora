package com.newoether.agora.api.anthropic

import com.newoether.agora.api.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicStreamEventRouterTest {
    @Test
    fun toolBlock_createsSegmentAtStartAndOwnsEveryFollowingDelta() {
        val router = AnthropicStreamEventRouter()

        val started = router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 3,
                contentBlock = AnthropicContentBlock(
                    type = "tool_use",
                    id = "call-3",
                    name = "any_tool",
                ),
            )
        )
        val initial = started.single() as StreamEvent.ToolCallUpdate
        assertEquals("call-3", initial.streamKey)
        assertEquals("any_tool", initial.name)
        assertEquals("", initial.arguments)

        val mirroredText = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 3,
                delta = AnthropicDelta(
                    type = "text_delta",
                    text = "transport-owned display data",
                ),
            )
        )
        assertTrue(mirroredText.isEmpty())

        val argumentDelta = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 3,
                delta = AnthropicDelta(
                    type = "input_json_delta",
                    partialJson = """{"value":"partial"}""",
                ),
            )
        ).single() as StreamEvent.ToolCallUpdate
        assertEquals("""{"value":"partial"}""", argumentDelta.arguments)

        val completed = router.route(
            AnthropicStreamEvent(
                type = "content_block_stop",
                index = 3,
            )
        ).single() as StreamEvent.ToolCallRequest
        assertEquals(initial.streamKey, completed.streamKey)
        assertEquals(argumentDelta.arguments, completed.arguments)
    }

    @Test
    fun multipleToolBlocks_areTrackedAndCompletedByIndex() {
        val router = AnthropicStreamEventRouter()
        router.route(toolStart(index = 1, id = "call-1", name = "first"))
        router.route(toolStart(index = 2, id = "call-2", name = "second"))
        router.route(toolArguments(index = 1, delta = """{"a":1}"""))
        router.route(toolArguments(index = 2, delta = """{"b":2}"""))

        val second = router.route(toolStop(index = 2)).single() as StreamEvent.ToolCallRequest
        val first = router.route(toolStop(index = 1)).single() as StreamEvent.ToolCallRequest

        assertEquals("call-2", second.id)
        assertEquals("""{"b":2}""", second.arguments)
        assertEquals("call-1", first.id)
        assertEquals("""{"a":1}""", first.arguments)
        assertTrue(router.finish().isEmpty())
    }

    @Test
    fun textAndThinkingBlocksRemainIndependent() {
        val router = AnthropicStreamEventRouter()
        router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 0,
                contentBlock = AnthropicContentBlock(type = "text"),
            )
        )
        val text = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 0,
                delta = AnthropicDelta(type = "text_delta", text = "answer"),
            )
        ).single()
        assertEquals(StreamEvent.TextChunk("answer"), text)

        router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 1,
                contentBlock = AnthropicContentBlock(type = "thinking"),
            )
        )
        val thought = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 1,
                delta = AnthropicDelta(type = "thinking_delta", thinking = "reasoning"),
            )
        ).single()
        assertEquals(StreamEvent.ThoughtChunk("reasoning"), thought)
    }

    private fun toolStart(index: Int, id: String, name: String) = AnthropicStreamEvent(
        type = "content_block_start",
        index = index,
        contentBlock = AnthropicContentBlock(type = "tool_use", id = id, name = name),
    )

    private fun toolArguments(index: Int, delta: String) = AnthropicStreamEvent(
        type = "content_block_delta",
        index = index,
        delta = AnthropicDelta(type = "input_json_delta", partialJson = delta),
    )

    private fun toolStop(index: Int) = AnthropicStreamEvent(
        type = "content_block_stop",
        index = index,
    )
}
