package com.newoether.agora.model

import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.CONVERSATION_ID
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.active
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.sendCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeRecoveryCompactReducerTest {
    @Test
    fun `Room live Run snapshot produces one deterministic recovery effect`() {
        listOf(RunStatus.ACTIVE, RunStatus.STOPPING).forEach { priorStatus ->
            val snapshot = RunRecoverySnapshot(
                conversationId = CONVERSATION_ID,
                runId = "orphaned-run",
                pass = 4,
                status = priorStatus,
            )
            val command = ConversationCommand.Recover(snapshot)

            val first = ConversationRuntimeReducer.reduce(
                RunState.Idle(CONVERSATION_ID),
                command,
            )
            val replay = ConversationRuntimeReducer.reduce(
                RunState.Idle(CONVERSATION_ID),
                command,
            )

            assertEquals(first, replay)
            val effect = first.effects.filterIsInstance<RunEffect.RecoverDurableRun>().single()
            assertEquals("orphaned-run", effect.identity.runId)
            assertEquals(4, effect.identity.pass)
            assertEquals("recover-orphaned-run-4", effect.identity.effectId)
            assertEquals(priorStatus, effect.priorStatus)
            assertTrue(first.newState is RunState.Recovering)
            assertFalse(first.effects.any { it is RunEffect.StartProviderPass })
        }
    }

    @Test
    fun `recovery rejects stale and duplicate results and becomes Idle only on durable success`() {
        val snapshot = RunRecoverySnapshot(
            conversationId = CONVERSATION_ID,
            runId = "orphaned-run",
            pass = 2,
            status = RunStatus.ACTIVE,
        )
        val requested = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            ConversationCommand.Recover(snapshot),
        )
        val effect = requested.effects.filterIsInstance<RunEffect.RecoverDurableRun>().single()
        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.Recover(snapshot),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)

        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(
                effect.identity.copy(pass = 1),
                success = true,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)

        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = false),
        )
        assertEquals(listOf(RunEffect.RunRecoveryFailed(effect.identity)), failed.effects)
        assertTrue((failed.newState as RunState.Recovering).failureReported)
        val duplicateFailure = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = false),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateFailure.rejection)

        val recovered = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), recovered.newState)
        assertTrue(recovered.effects.isEmpty())
    }

    @Test
    fun `Compact owns the standard generation slot queues Send and accepts Stop`() {
        val identity = RunEffectIdentity(
            conversationId = CONVERSATION_ID,
            ownerToken = 4,
            runId = "compact-run",
            pass = 0,
            effectId = "compact-effect",
        )
        val request = ConversationCommand.CompactRequested(
            identity = identity,
            compactRunId = "compact-run",
        )

        val started = ConversationRuntimeReducer.reduce(RunState.Idle(CONVERSATION_ID), request)

        assertEquals(RunState.Compacting(identity, "compact-run"), started.newState)
        assertEquals(
            listOf(RunEffect.RunCompact(identity, "compact-run")),
            started.effects,
        )
        val queuedSend = ConversationRuntimeReducer.reduce(
            started.newState,
            sendCommand(ownerToken = 5, runId = "send-run", effectId = "send"),
        )
        assertEquals(
            RunEffect.AcceptGuidance(
                RunEffectIdentity(
                    conversationId = CONVERSATION_ID,
                    ownerToken = 4,
                    runId = "compact-run",
                    pass = 0,
                    effectId = "send",
                ),
            ),
            queuedSend.effects.single(),
        )
        val directSend = ConversationRuntimeReducer.reduce(
            started.newState,
            sendCommand(
                ownerToken = 5,
                runId = "send-run",
                effectId = "direct",
                directOnly = true,
            ),
        )
        assertTrue(directSend.effects.single() is RunEffect.RejectSendBusy)

        val stopping = ConversationRuntimeReducer.reduce(
            started.newState,
            ConversationCommand.StopRequested(
                identity = identity.runIdentity(),
                coroutineAlreadySettled = false,
                requiresPersistence = true,
                effectId = "stop",
            ),
        )
        assertTrue(stopping.newState is RunState.Stopping)
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(identity.runIdentity()),
                RunEffect.FinalizeStop(identity.copy(effectId = "stop")),
            ),
            stopping.effects,
        )

        val completed = ConversationRuntimeReducer.reduce(
            started.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.CREATED),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
        assertEquals(
            listOf(
                RunEffect.ReleaseSlot(
                    identity.runIdentity(),
                    SlotReleaseReason.NORMAL_COMPLETION,
                ),
            ),
            completed.effects,
        )
    }

    @Test
    fun `Compact cannot interrupt or resume an active Run`() {
        val active = active(ownerToken = 7, runId = "run", pass = 3)
        val compactIdentity = RunEffectIdentity(
            conversationId = CONVERSATION_ID,
            ownerToken = 8,
            runId = "compact-run",
            pass = 0,
            effectId = "compact-effect",
        )

        val rejected = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity = compactIdentity,
                compactRunId = "compact-run",
            ),
        )

        assertEquals(CommandRejection.ILLEGAL_STATE, rejected.rejection)
        assertSame(active, rejected.newState)
        assertTrue(rejected.effects.isEmpty())
    }
}
