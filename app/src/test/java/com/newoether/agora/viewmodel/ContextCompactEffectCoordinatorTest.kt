package com.newoether.agora.viewmodel

import com.newoether.agora.model.RunEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContextCompactEffectCoordinatorTest {
    @Test
    fun executionRunsOnlyTheClaimedStandardEffectAndSettlesIdle() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "standard" }
        var received: RunEffect.RunCompact? = null

        val execution = coordinator.execute(state) { effect ->
            received = effect
            assertTrue(state.compacting.value)
            assertTrue(state.generating.value)
            assertTrue(state.isLoading.value)
            assertEquals("", state.compactPreview.value)
            assertTrue(state.updateCompactPreview(effect.identity, "first"))
            assertTrue(state.updateCompactPreview(effect.identity, "first second"))
            assertEquals("first second", state.compactPreview.value)
            assertFalse(
                state.updateCompactPreview(
                    effect.identity.copy(effectId = "stale-compact"),
                    " stale",
                )
            )
            CompactResult.Created("compact-message")
        }

        assertEquals(
            RunEffect.RunCompact(
                identity = requireNotNull(received).identity,
                compactRunId = "compact_run_standard",
            ),
            received,
        )
        assertEquals(
            ContextCompactEffectCoordinator.Execution.Settled(
                CompactResult.Created("compact-message"),
            ),
            execution,
        )
        assertFalse(state.compacting.value)
        assertEquals("", state.compactPreview.value)
        assertFalse(state.generating.value)
    }

    @Test
    fun thrownEffectFailureSettlesRuntimeBeforePropagating() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "failure" }

        try {
            coordinator.execute(state) {
                throw IllegalStateException("effect failed")
            }
            fail("Expected effect failure")
        } catch (error: IllegalStateException) {
            assertEquals("effect failed", error.message)
        }

        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        assertEquals(
            listOf("CompactRequested", "CompactCompleted"),
            state.runtimeTraceSnapshot().map { it.commandType },
        )
        assertEquals(
            listOf("CompactFailed", "ReleaseSlot"),
            state.runtimeTraceSnapshot().last().effectTypes,
        )
    }

    @Test
    fun cancellationCannotStrandACompactClaim() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val coordinator = ContextCompactEffectCoordinator { "cancel" }
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.execute(state) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        entered.await()
        assertTrue(state.compacting.value)
        job.cancelAndJoin()

        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        val next = state.commands.requestCompact("compact-run-next", "compact-effect-next")
        assertTrue(next != null)
        state.commands.finishCompact(
            requireNotNull(next).identity,
            com.newoether.agora.model.CompactOutcome.NOT_NEEDED,
        )
        Unit
    }

    @Test
    fun compactKeepsPendingGuidanceQueuedForTheNextStandardRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val queued = QueuedSend(
            id = "guidance",
            text = "next",
            modelId = "model",
            attachments = emptyList(),
            runId = "origin-run",
        )
        state.enqueueSend(queued)
        val coordinator = ContextCompactEffectCoordinator { "queued" }
        var invoked = false

        val execution = coordinator.execute(state) {
            invoked = true
            CompactResult.NotNeeded
        }

        assertEquals(
            ContextCompactEffectCoordinator.Execution.Settled(CompactResult.NotNeeded),
            execution,
        )
        assertTrue(invoked)
        assertEquals(listOf(queued), state.queuedSends.value)
        assertFalse(state.compacting.value)
    }
}
