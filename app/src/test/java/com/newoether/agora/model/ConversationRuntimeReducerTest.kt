package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeReducerTest {
    @Test
    fun `slot acquire and Run bind are reducer-owned transitions`() {
        val acquired = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            ConversationCommand.AcquireSlot(identity(ownerToken = 1)),
        )

        assertTrue(acquired.accepted)
        assertEquals(RunState.Active(identity(ownerToken = 1)), acquired.newState)
        assertEquals(
            listOf(RunEffect.SlotActivated(identity(ownerToken = 1))),
            acquired.effects,
        )

        val bound = ConversationRuntimeReducer.reduce(
            acquired.newState,
            ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run", pass = 2)),
        )

        assertTrue(bound.accepted)
        assertEquals(
            RunState.Active(identity(ownerToken = 1, runId = "run", pass = 2)),
            bound.newState,
        )
    }

    @Test
    fun `foreground Send prepares one identified persistence effect before binding the Run`() {
        val requested = sendCommand(ownerToken = 3, runId = "run", effectId = "send-1")

        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            requested,
        )

        assertEquals(
            RunState.Preparing(
                ownerIdentity = identity(ownerToken = 3),
                inputEffectIdentity = requested.identity,
            ),
            preparing.newState,
        )
        assertEquals(
            listOf(RunEffect.PersistAcceptedInput(requested.identity)),
            preparing.effects,
        )

        val persisted = ConversationRuntimeReducer.reduce(
            preparing.newState,
            ConversationCommand.InputPersisted(requested.identity),
        )

        assertEquals(
            RunState.Active(identity(ownerToken = 3, runId = "run")),
            persisted.newState,
        )
        assertTrue(persisted.effects.isEmpty())
    }

    @Test
    fun `active Send accepts guidance only for the currently bound Run`() {
        val active = active(ownerToken = 4, runId = "active-run", pass = 2)
        val request = sendCommand(ownerToken = 99, runId = "unused", effectId = "guidance")

        val transition = ConversationRuntimeReducer.reduce(active, request)

        assertSame(active, transition.newState)
        assertEquals(
            listOf(
                RunEffect.AcceptGuidance(
                    RunEffectIdentity(
                        conversationId = CONVERSATION_ID,
                        ownerToken = 4,
                        runId = "active-run",
                        pass = 2,
                        effectId = "guidance",
                    ),
                ),
            ),
            transition.effects,
        )
    }

    @Test
    fun `Send waits during preparation or stopping and direct-only reports busy`() {
        val request = sendCommand(ownerToken = 1, runId = "run", effectId = "send")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            request,
        ).newState
        val stoppingActive = active(ownerToken = 8, runId = "stopping")
        val stopping = ConversationRuntimeReducer.reduce(
            stoppingActive,
            stopCommand(stoppingActive, effectId = "stop"),
        ).newState

        for (state in listOf(preparing, stopping)) {
            val wait = ConversationRuntimeReducer.reduce(state, request.copy(directOnly = false))
            assertSame(state, wait.newState)
            assertTrue(wait.effects.single() is RunEffect.AwaitRunRelease)

            val busy = ConversationRuntimeReducer.reduce(state, request.copy(directOnly = true))
            assertSame(state, busy.newState)
            assertTrue(busy.effects.single() is RunEffect.RejectSendBusy)
        }
    }

    @Test
    fun `pending guidance is drained before a newer idle Send can claim the slot`() {
        val state = RunState.Idle(CONVERSATION_ID)
        val request = sendCommand(
            ownerToken = 1,
            runId = "new-run",
            effectId = "send",
            hasPendingGuidance = true,
        )

        val drain = ConversationRuntimeReducer.reduce(state, request)
        assertSame(state, drain.newState)
        assertEquals(
            listOf(RunEffect.DrainGuidanceFirst(request.identity)),
            drain.effects,
        )

        val directOnly = ConversationRuntimeReducer.reduce(
            state,
            request.copy(directOnly = true),
        )
        assertSame(state, directOnly.newState)
        assertEquals(
            listOf(RunEffect.RejectSendBusy(request.identity)),
            directOnly.effects,
        )
    }

    @Test
    fun `stale persistence and abandonment results cannot mutate another Send`() {
        val first = sendCommand(ownerToken = 1, runId = "first", effectId = "first-effect")
        val second = sendCommand(ownerToken = 2, runId = "second", effectId = "second-effect")
        val firstPreparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            first,
        ).newState

        val staleInput = ConversationRuntimeReducer.reduce(
            firstPreparing,
            ConversationCommand.InputPersisted(second.identity),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleInput.rejection)
        assertSame(firstPreparing, staleInput.newState)

        val staleAbandonment = ConversationRuntimeReducer.reduce(
            firstPreparing,
            ConversationCommand.SendLaunchAbandoned(second.identity),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleAbandonment.rejection)
        assertSame(firstPreparing, staleAbandonment.newState)

        val abandoned = ConversationRuntimeReducer.reduce(
            firstPreparing,
            ConversationCommand.SendLaunchAbandoned(first.identity),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), abandoned.newState)
        assertEquals(
            SlotReleaseReason.SEND_LAUNCH_ABANDONED,
            releaseEffect(abandoned).reason,
        )
    }

    @Test
    fun `input persistence failure is identified idempotent and releases on coroutine settlement`() {
        val request = sendCommand(ownerToken = 6, runId = "run", effectId = "input")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            request,
        ).newState
        val failureCommand = ConversationCommand.InputPersistenceFailed(request.identity)

        val failed = ConversationRuntimeReducer.reduce(preparing, failureCommand)
        assertTrue((failed.newState as RunState.Preparing).inputFailureReported)
        assertTrue(failed.effects.isEmpty())

        val duplicate = ConversationRuntimeReducer.reduce(failed.newState, failureCommand)
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertSame(failed.newState, duplicate.newState)

        val settled = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.CoroutineSettled(identity(ownerToken = 6)),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), settled.newState)
        assertEquals(SlotReleaseReason.NORMAL_COMPLETION, releaseEffect(settled).reason)
    }

    @Test
    fun `Stop before input persistence rejects the late Room result and waits only for coroutine`() {
        val request = sendCommand(ownerToken = 5, runId = "run", effectId = "input")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            request,
        ).newState as RunState.Preparing
        val stop = ConversationCommand.StopRequested(
            identity = preparing.ownerIdentity,
            coroutineAlreadySettled = false,
            requiresPersistence = false,
            effectId = null,
        )

        val stopping = ConversationRuntimeReducer.reduce(preparing, stop)
        assertEquals(
            RunState.Stopping(
                identity = preparing.ownerIdentity,
                finalizationEffectId = null,
                coroutineSettled = false,
                persistenceSettled = true,
            ),
            stopping.newState,
        )
        assertTrue(stopping.effects.none { it is RunEffect.FinalizeStop })

        val lateInput = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.InputPersisted(request.identity),
        )
        assertFalse(lateInput.accepted)
        assertSame(stopping.newState, lateInput.newState)

        val settled = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.CoroutineSettled(preparing.ownerIdentity),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), settled.newState)
        assertEquals(SlotReleaseReason.STOP_BARRIERS_SETTLED, releaseEffect(settled).reason)
    }

    @Test
    fun `Stop emits identified cancellation and persistence effects`() {
        val active = active(ownerToken = 4, runId = "run", pass = 3)

        val transition = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect-1"),
        )

        assertTrue(transition.accepted)
        assertEquals(
            RunState.Stopping(
                identity = active.identity,
                finalizationEffectId = "effect-1",
                coroutineSettled = false,
                persistenceSettled = false,
            ),
            transition.newState,
        )
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(active.identity),
                RunEffect.FinalizeStop(effectIdentity(active.identity, "effect-1")),
            ),
            transition.effects,
        )
    }

    @Test
    fun `Stop releases only after both barriers in either order`() {
        val active = active(ownerToken = 1, runId = "run", pass = 2)
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect"),
        ).newState
        val persistence = ConversationCommand.PersistenceSettled(
            effectIdentity(active.identity, "effect"),
            success = true,
        )
        val coroutine = ConversationCommand.CoroutineSettled(active.identity)

        val persistenceFirst = ConversationRuntimeReducer.reduce(stopping, persistence)
        assertTrue(persistenceFirst.newState is RunState.Stopping)
        assertTrue((persistenceFirst.newState as RunState.Stopping).persistenceSettled)
        val persistenceThenCoroutine = ConversationRuntimeReducer.reduce(
            persistenceFirst.newState,
            coroutine,
        )

        val coroutineFirst = ConversationRuntimeReducer.reduce(stopping, coroutine)
        assertTrue(coroutineFirst.newState is RunState.Stopping)
        assertTrue((coroutineFirst.newState as RunState.Stopping).coroutineSettled)
        val coroutineThenPersistence = ConversationRuntimeReducer.reduce(
            coroutineFirst.newState,
            persistence,
        )

        val expectedEffect = RunEffect.ReleaseSlot(
            active.identity,
            SlotReleaseReason.STOP_BARRIERS_SETTLED,
        )
        for (completed in listOf(persistenceThenCoroutine, coroutineThenPersistence)) {
            assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
            assertEquals(listOf(expectedEffect), completed.effects)
        }
    }

    @Test
    fun `stale and duplicate persistence results are rejected without effects`() {
        val active = active(ownerToken = 1, runId = "run", pass = 1)
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "expected"),
        ).newState
        val stale = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "old-effect"),
                success = true,
            ),
        )

        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(stopping, stale.newState)
        assertTrue(stale.effects.isEmpty())

        val accepted = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "expected"),
                success = true,
            ),
        )
        val duplicate = ConversationRuntimeReducer.reduce(
            accepted.newState,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "expected"),
                success = true,
            ),
        )

        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertSame(accepted.newState, duplicate.newState)
        assertTrue(duplicate.effects.isEmpty())

        val contradictoryFailure = ConversationRuntimeReducer.reduce(
            accepted.newState,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "expected"),
                success = false,
            ),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, contradictoryFailure.rejection)
        assertSame(accepted.newState, contradictoryFailure.newState)
        assertTrue(contradictoryFailure.effects.isEmpty())
    }

    @Test
    fun `failed persistence result remains pending and a later success can settle`() {
        val active = active(ownerToken = 1, runId = "run")
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect", coroutineSettled = true),
        ).newState
        val failedCommand = ConversationCommand.PersistenceSettled(
            effectIdentity(active.identity, "effect"),
            success = false,
        )

        val failed = ConversationRuntimeReducer.reduce(stopping, failedCommand)
        assertTrue(failed.accepted)
        assertEquals(
            listOf(RunEffect.StopPersistenceFailed(effectIdentity(active.identity, "effect"))),
            failed.effects,
        )
        assertTrue((failed.newState as RunState.Stopping).persistenceFailureReported)

        val duplicateFailure = ConversationRuntimeReducer.reduce(failed.newState, failedCommand)
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateFailure.rejection)

        val recovered = ConversationRuntimeReducer.reduce(
            failed.newState,
            failedCommand.copy(success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), recovered.newState)
        assertEquals(SlotReleaseReason.STOP_BARRIERS_SETTLED, releaseEffect(recovered).reason)
    }

    @Test
    fun `old effect result cannot release a later stopping Run`() {
        val first = active(ownerToken = 1, runId = "first")
        val firstStopping = ConversationRuntimeReducer.reduce(
            first,
            stopCommand(first, effectId = "first-effect", coroutineSettled = true),
        ).newState
        val firstCompletion = ConversationCommand.PersistenceSettled(
            effectIdentity(first.identity, "first-effect"),
            success = true,
        )
        val idle = ConversationRuntimeReducer.reduce(firstStopping, firstCompletion).newState

        val secondAcquired = ConversationRuntimeReducer.reduce(
            idle,
            ConversationCommand.AcquireSlot(identity(ownerToken = 2)),
        ).newState
        val second = ConversationRuntimeReducer.reduce(
            secondAcquired,
            ConversationCommand.BindRun(identity(ownerToken = 2, runId = "second")),
        ).newState as RunState.Active
        val secondStopping = ConversationRuntimeReducer.reduce(
            second,
            stopCommand(second, effectId = "second-effect", coroutineSettled = true),
        ).newState

        val stale = ConversationRuntimeReducer.reduce(secondStopping, firstCompletion)

        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(secondStopping, stale.newState)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `normal coroutine completion releases active slot exactly once`() {
        val active = active(ownerToken = 1, runId = "run")
        val completion = ConversationCommand.CoroutineSettled(active.identity)

        val first = ConversationRuntimeReducer.reduce(active, completion)
        val duplicate = ConversationRuntimeReducer.reduce(first.newState, completion)

        assertEquals(RunState.Idle(CONVERSATION_ID), first.newState)
        assertEquals(SlotReleaseReason.NORMAL_COMPLETION, releaseEffect(first).reason)
        assertEquals(CommandRejection.ILLEGAL_STATE, duplicate.rejection)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `illegal state command matrix is rejected without state or effects`() {
        val active = active(ownerToken = 1, runId = "run")
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect"),
        ).newState
        val idle = RunState.Idle(CONVERSATION_ID)
        val cases = listOf(
            Triple(
                active,
                ConversationCommand.AcquireSlot(identity(ownerToken = 2)),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                stopping,
                ConversationCommand.AcquireSlot(identity(ownerToken = 2)),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                idle,
                ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run")),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                stopping,
                ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run", pass = 1)),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                RunState.Preparing(
                    ownerIdentity = identity(ownerToken = 1),
                    inputEffectIdentity = effectIdentity(identity(1, "run"), "send"),
                ),
                ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run")),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                idle,
                stopCommand(active, effectId = "effect"),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                stopping,
                stopCommand(active, effectId = "effect"),
                CommandRejection.DUPLICATE_RESULT,
            ),
            Triple(
                idle,
                ConversationCommand.CoroutineSettled(active.identity),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                active,
                ConversationCommand.PersistenceSettled(
                    effectIdentity(active.identity, "effect"),
                    success = true,
                ),
                CommandRejection.ILLEGAL_STATE,
            ),
        )

        cases.forEach { (state, command, expectedRejection) ->
            val transition = ConversationRuntimeReducer.reduce(state, command)
            assertEquals(expectedRejection, transition.rejection)
            assertSame(state, transition.newState)
            assertTrue(transition.effects.isEmpty())
        }
    }

    @Test
    fun `Bind Run rejects stale owner Run and pass identities`() {
        val active = active(ownerToken = 3, runId = "run", pass = 4)
        val staleCommands = listOf(
            ConversationCommand.BindRun(identity(ownerToken = 2, runId = "run", pass = 4)),
            ConversationCommand.BindRun(identity(ownerToken = 3, runId = "other", pass = 4)),
            ConversationCommand.BindRun(identity(ownerToken = 3, runId = "run", pass = 3)),
            ConversationCommand.BindRun(identity(ownerToken = 3, runId = "run", pass = 6)),
        )

        staleCommands.forEach { command ->
            val transition = ConversationRuntimeReducer.reduce(active, command)
            assertEquals(CommandRejection.STALE_IDENTITY, transition.rejection)
            assertSame(active, transition.newState)
            assertTrue(transition.effects.isEmpty())
        }

        val duplicate = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.BindRun(active.identity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertSame(active, duplicate.newState)
    }

    @Test
    fun `unbound Stop releases immediately without a persistence effect`() {
        val active = RunState.Active(identity(ownerToken = 8))

        val transition = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.StopRequested(
                identity = active.identity,
                coroutineAlreadySettled = true,
                requiresPersistence = false,
                effectId = null,
            ),
        )

        assertEquals(RunState.Idle(CONVERSATION_ID), transition.newState)
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(active.identity),
                RunEffect.ReleaseSlot(active.identity, SlotReleaseReason.EMPTY_STOP),
            ),
            transition.effects,
        )
    }

    @Test
    fun `validated tool batch must commit before continuation is authorized`() {
        val active = active(ownerToken = 9, runId = "run", pass = 2)
        val providerIdentity = effectIdentity(active.identity, "provider-2-1")

        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(providerIdentity),
        )
        val batchEffect = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()
        assertEquals("tool-batch-provider-2-1", batchEffect.identity.effectId)
        assertEquals(
            RunToolPhase.Executing(batchEffect.identity),
            (requested.newState as RunState.Active).toolPhase,
        )

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchCompleted(batchEffect.identity),
        )
        val commitEffect = completed.effects.filterIsInstance<RunEffect.CommitToolRound>().single()
        assertEquals("tool-round-tool-batch-provider-2-1", commitEffect.identity.effectId)
        assertTrue((completed.newState as RunState.Active).toolPhase is RunToolPhase.Committing)

        val committed = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.ToolRoundCommitted(commitEffect.identity, success = true),
        )
        assertEquals(RunState.Active(active.identity), committed.newState)
        assertEquals(
            listOf(RunEffect.ContinueProviderPass(commitEffect.identity)),
            committed.effects,
        )
    }

    @Test
    fun `duplicate and stale tool results cannot advance the active Run`() {
        val active = active(ownerToken = 10, runId = "run", pass = 3)
        val providerIdentity = effectIdentity(active.identity, "provider-3-0")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(providerIdentity),
        )
        val batch = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()

        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchRequested(providerIdentity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)

        val passAdvance = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.BindRun(identity(ownerToken = 10, runId = "run", pass = 4)),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, passAdvance.rejection)

        val staleBatch = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchCompleted(
                batch.identity.copy(effectId = "tool-batch-old-provider"),
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleBatch.rejection)
        assertSame(requested.newState, staleBatch.newState)
    }

    @Test
    fun `tool commit failure is recorded once and never authorizes continuation`() {
        val active = active(ownerToken = 11, runId = "run")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(
                effectIdentity(active.identity, "provider-0-0"),
            ),
        )
        val batch = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()
        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchCompleted(batch.identity),
        )
        val commit = completed.effects.filterIsInstance<RunEffect.CommitToolRound>().single()

        val failed = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.ToolRoundCommitted(commit.identity, success = false),
        )
        assertEquals(listOf(RunEffect.ToolRoundCommitFailed(commit.identity)), failed.effects)
        assertTrue(
            ((failed.newState as RunState.Active).toolPhase as RunToolPhase.Committing)
                .failureReported,
        )

        val duplicate = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.ToolRoundCommitted(commit.identity, success = false),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `Stop invalidates a late tool result`() {
        val active = active(ownerToken = 12, runId = "run")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(
                effectIdentity(active.identity, "provider-0-0"),
            ),
        )
        val batch = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()
        val stopping = ConversationRuntimeReducer.reduce(
            requested.newState,
            stopCommand(requested.newState as RunState.Active, effectId = "stop"),
        )

        val late = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.ToolBatchCompleted(batch.identity),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, late.rejection)
        assertSame(stopping.newState, late.newState)
    }

    @Test
    fun `same command sequence produces equal states and effects`() {
        val send = sendCommand(ownerToken = 1, runId = "run", effectId = "input")
        val commands = listOf(
            send,
            ConversationCommand.InputPersisted(send.identity),
            ConversationCommand.StopRequested(
                identity(ownerToken = 1, runId = "run", pass = 0),
                coroutineAlreadySettled = false,
                requiresPersistence = true,
                effectId = "effect",
            ),
            ConversationCommand.PersistenceSettled(
                effectIdentity(identity(1, "run", 0), "effect"),
                success = true,
            ),
            ConversationCommand.CoroutineSettled(identity(1, "run", 0)),
        )

        fun replay(): List<Transition> {
            var state: RunState = RunState.Idle(CONVERSATION_ID)
            return commands.map { command ->
                ConversationRuntimeReducer.reduce(state, command).also { state = it.newState }
            }
        }

        assertEquals(replay(), replay())
        assertFalse(replay().any { it.rejection != null })
    }

    private fun active(
        ownerToken: Long,
        runId: String,
        pass: Int = 0,
    ): RunState.Active = RunState.Active(identity(ownerToken, runId, pass))

    private fun stopCommand(
        active: RunState.Active,
        effectId: String,
        coroutineSettled: Boolean = false,
    ) = ConversationCommand.StopRequested(
        identity = active.identity,
        coroutineAlreadySettled = coroutineSettled,
        requiresPersistence = true,
        effectId = effectId,
    )

    private fun sendCommand(
        ownerToken: Long,
        runId: String,
        effectId: String,
        directOnly: Boolean = false,
        hasPendingGuidance: Boolean = false,
    ) = ConversationCommand.SendRequested(
        identity = RunEffectIdentity(
            conversationId = CONVERSATION_ID,
            ownerToken = ownerToken,
            runId = runId,
            pass = 0,
            effectId = effectId,
        ),
        directOnly = directOnly,
        hasPendingGuidance = hasPendingGuidance,
    )

    private fun identity(
        ownerToken: Long,
        runId: String? = null,
        pass: Int = 0,
    ) = RuntimeRunIdentity(CONVERSATION_ID, ownerToken, runId, pass)

    private fun effectIdentity(identity: RuntimeRunIdentity, effectId: String) =
        RunEffectIdentity(
            conversationId = identity.conversationId,
            ownerToken = identity.ownerToken,
            runId = requireNotNull(identity.runId),
            pass = identity.pass,
            effectId = effectId,
        )

    private fun releaseEffect(transition: Transition): RunEffect.ReleaseSlot =
        transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().single()

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
