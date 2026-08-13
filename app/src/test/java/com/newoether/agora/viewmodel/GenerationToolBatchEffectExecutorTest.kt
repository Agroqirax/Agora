package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolBatchEffectExecutorTest {
    @Test
    fun `overlay uniquely owns stream indices metadata and terminal presentation`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String) =
                    ToolPresentationMetadata(displayName = "Display $name", target = "initial")
            },
            providerName = "provider",
        )

        assertTrue(overlay.upsert("stream", null, "tool", "{", "signature"))
        assertEquals(false, overlay.upsert("stream", "call", "", "{}", null))
        overlay.start(call())
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("resolved"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputDelta("partial"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\n"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\nsecond\n"))
        assertEquals(
            "first\nsecond\n",
            overlay.snapshot().single { it.type == "tool" }.toolProgress,
        )
        val completed = overlay.complete(
            call(),
            ToolExecutionResult(text = "done", displayText = "shown"),
        )

        assertEquals("done", completed.data.result)
        assertEquals("Display tool", completed.data.displayName)
        assertEquals("resolved", completed.segment.toolTarget)
        assertEquals("first\nsecond\n", completed.segment.toolProgress)
        assertEquals(ToolExecutionStates.SUCCEEDED, completed.segment.toolState)
        assertEquals("provider", completed.segment.signatureProvider)

        overlay.replaceAll(emptyList())
        assertNull(overlay.snapshot().singleOrNull())
    }

    @Test
    fun `snapshot clipping never starts with half of a surrogate pair`() {
        assertEquals("😀b", takeLastWholeCodePoints("a😀b", 3))
        assertEquals("b", takeLastWholeCodePoints("a😀b", 2))
        assertEquals("", takeLastWholeCodePoints("😀", 1))
        assertEquals("😀", takeLastWholeCodePoints("😀", 2))
    }

    @Test
    fun `accepted batch executes in order and returns the same identity without continuation`() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream", "call", "tool", "{}", null)
        val forces = mutableListOf<Boolean>()
        val publishedAt = mutableListOf<Long>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call()),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = { forces += it },
                onPublishedAt = publishedAt::add,
            ),
        )

        assertEquals(IDENTITY, outcome.identity)
        assertEquals(listOf("tool"), provider.executedNames)
        assertEquals(listOf(true, false, false, false), forces)
        assertEquals(4, publishedAt.size)
        assertEquals("done", outcome.calls.single().result)
        assertEquals(ToolExecutionStates.SUCCEEDED, outcome.segments.single().toolState)
        assertTrue(outcome.generatedImages.isEmpty())
    }

    private fun call() = StreamEvent.ToolCallRequest(
        id = "call",
        name = "tool",
        arguments = "{}",
        streamKey = "stream",
        signature = "signature",
    )

    private class StreamingToolProvider : ToolProvider {
        val executedNames = mutableListOf<String>()

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override fun presentationMetadata(name: String) = ToolPresentationMetadata("Display")

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            executedNames += name
            return flowOf(
                ToolExecutionEvent.TargetResolved("target"),
                ToolExecutionEvent.OutputDelta("partial"),
                ToolExecutionEvent.Completed(ToolExecutionResult(text = "done")),
            )
        }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "tool-batch-provider-2-0",
        )
    }
}
