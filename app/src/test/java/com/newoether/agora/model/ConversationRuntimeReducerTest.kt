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
    fun `durable Run arriving after pre-bind Stop receives one identified finalization effect`() {
        val send = sendCommand(ownerToken = 3, runId = "run", effectId = "send-1")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            send,
        ).newState
        val stopping = ConversationRuntimeReducer.reduce(
            preparing,
            ConversationCommand.StopRequested(
                identity = identity(ownerToken = 3),
                coroutineAlreadySettled = false,
                requiresPersistence = false,
                effectId = null,
            ),
        ).newState

        val persisted = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.InputPersisted(send.identity),
        )
        val expectedIdentity = effectIdentity(
            identity(ownerToken = 3, runId = "run"),
            "stop-3",
        )

        assertEquals(
            RunState.Stopping(
                identity = identity(ownerToken = 3, runId = "run"),
                finalizationEffectId = "stop-3",
                coroutineSettled = false,
                persistenceSettled = false,
            ),
            persisted.newState,
        )
        assertEquals(listOf(RunEffect.FinalizeStop(expectedIdentity)), persisted.effects)

        val duplicate = ConversationRuntimeReducer.reduce(
            persisted.newState,
            ConversationCommand.InputPersisted(send.identity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)

        val staleBind = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.BindRun(identity(ownerToken = 4, runId = "other")),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleBind.rejection)
    }

    @Test
    fun `replacement Run bind after Stop uses the same late finalization transition`() {
        val unbound = RunState.Active(identity(ownerToken = 9))
        val stopping = ConversationRuntimeReducer.reduce(
            unbound,
            ConversationCommand.StopRequested(
                identity = unbound.identity,
                coroutineAlreadySettled = false,
                requiresPersistence = false,
                effectId = null,
            ),
        ).newState
        val durableIdentity = identity(ownerToken = 9, runId = "replacement", pass = 0)

        val bound = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.BindRun(durableIdentity),
        )

        assertEquals(
            RunState.Stopping(
                identity = durableIdentity,
                finalizationEffectId = "stop-9",
                coroutineSettled = false,
                persistenceSettled = false,
            ),
            bound.newState,
        )
        assertEquals(
            listOf(
                RunEffect.FinalizeStop(effectIdentity(durableIdentity, "stop-9")),
            ),
            bound.effects,
        )
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
    fun `Send accepts memory guidance during preparation while stopping waits and direct-only is busy`() {
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

        val preparingGuidance = ConversationRuntimeReducer.reduce(
            preparing,
            request.copy(
                identity = request.identity.copy(
                    ownerToken = 99,
                    runId = "later",
                    effectId = "guidance",
                ),
            ),
        )
        assertSame(preparing, preparingGuidance.newState)
        assertEquals(
            RunEffect.AcceptGuidance(
                request.identity.copy(runId = "run", effectId = "guidance"),
            ),
            preparingGuidance.effects.single(),
        )

        val stoppingWait = ConversationRuntimeReducer.reduce(
            stopping,
            request.copy(directOnly = false),
        )
        assertSame(stopping, stoppingWait.newState)
        assertTrue(stoppingWait.effects.single() is RunEffect.AwaitRunRelease)

        for (state in listOf(preparing, stopping)) {
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
    fun `Stop before input persistence adopts the late durable Run and waits for both barriers`() {
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
        val stopIdentity = effectIdentity(request.identity.runIdentity(), "stop-5")
        assertTrue(lateInput.accepted)
        assertEquals(listOf(RunEffect.FinalizeStop(stopIdentity)), lateInput.effects)

        val coroutineSettled = ConversationRuntimeReducer.reduce(
            lateInput.newState,
            ConversationCommand.CoroutineSettled(request.identity.runIdentity()),
        )
        assertTrue(coroutineSettled.newState is RunState.Stopping)
        assertTrue(coroutineSettled.effects.isEmpty())

        val settled = ConversationRuntimeReducer.reduce(
            coroutineSettled.newState,
            ConversationCommand.PersistenceSettled(stopIdentity, success = true),
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
    fun `bound coroutine completion remains occupied until a terminal result`() {
        val active = active(ownerToken = 1, runId = "run")
        val completion = ConversationCommand.CoroutineSettled(active.identity)

        val first = ConversationRuntimeReducer.reduce(active, completion)
        val duplicate = ConversationRuntimeReducer.reduce(first.newState, completion)

        assertEquals(active.copy(coroutineSettled = true), first.newState)
        assertTrue(first.effects.isEmpty())
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
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
                CommandRejection.STALE_IDENTITY,
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
    fun `Provider pass must be authorized and its exact result accepted once`() {
        val active = active(ownerToken = 13, runId = "run", pass = 2)
        val identity = effectIdentity(active.identity, "provider-2-0")

        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ProviderPassRequested(identity),
        )
        assertEquals(listOf(RunEffect.StartProviderPass(identity)), requested.effects)
        assertEquals(
            RunProviderPhase.Running(identity),
            (requested.newState as RunState.Active).providerPhase,
        )
        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassRequested(identity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)
        val wrongPassRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassRequested(identity.copy(pass = 1)),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, wrongPassRequest.rejection)

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassCompleted(
                identity,
                ProviderPassResult.COMPLETED_TOOL_CALLS,
            ),
        )
        assertEquals(RunState.Active(active.identity), completed.newState)
        assertEquals(
            listOf(
                RunEffect.ProviderPassAccepted(
                    identity,
                    ProviderPassResult.COMPLETED_TOOL_CALLS,
                ),
            ),
            completed.effects,
        )

        val duplicate = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.ProviderPassCompleted(
                identity,
                ProviderPassResult.COMPLETED_TOOL_CALLS,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, duplicate.rejection)
    }

    @Test
    fun `stale Provider outcome and Provider result after Stop cannot mutate the Run`() {
        val active = active(ownerToken = 14, runId = "run", pass = 4)
        val currentIdentity = effectIdentity(active.identity, "provider-4-1")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ProviderPassRequested(currentIdentity),
        )
        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassCompleted(
                currentIdentity.copy(effectId = "provider-4-0"),
                ProviderPassResult.COMPLETED_TEXT,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(requested.newState, stale.newState)

        val stopping = ConversationRuntimeReducer.reduce(
            requested.newState,
            stopCommand(requested.newState as RunState.Active, effectId = "stop"),
        )
        val late = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.ProviderPassCompleted(
                currentIdentity,
                ProviderPassResult.COMPLETED_TEXT,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, late.rejection)
        assertSame(stopping.newState, late.newState)
    }

    @Test
    fun `normal finalization releases only after coroutine and persistence settle in either order`() {
        val active = active(ownerToken = 15, runId = "run", pass = 3)
        val identity = effectIdentity(active.identity, "finalize-run-3")
        val request = ConversationCommand.FinalizationRequested(
            identity,
            RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED,
            markConversationUnread = true,
        )
        val finalizing = ConversationRuntimeReducer.reduce(active, request)
        assertEquals(
            listOf(
                RunEffect.FinalizeRun(
                    identity,
                    RunStatus.COMPLETED,
                    RunEndReason.MODEL_COMPLETED,
                    markConversationUnread = true,
                ),
            ),
            finalizing.effects,
        )

        val persistenceFirst = ConversationRuntimeReducer.reduce(
            finalizing.newState,
            ConversationCommand.FinalizationCompleted(identity, success = true),
        )
        assertTrue((persistenceFirst.newState as RunState.Finalizing).persistenceSettled)
        val persistenceThenCoroutine = ConversationRuntimeReducer.reduce(
            persistenceFirst.newState,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), persistenceThenCoroutine.newState)
        assertEquals(
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            releaseEffect(persistenceThenCoroutine).reason,
        )

        val finalizingAgain = ConversationRuntimeReducer.reduce(active, request)
        val coroutineFirst = ConversationRuntimeReducer.reduce(
            finalizingAgain.newState,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertTrue((coroutineFirst.newState as RunState.Finalizing).coroutineSettled)
        val coroutineThenPersistence = ConversationRuntimeReducer.reduce(
            coroutineFirst.newState,
            ConversationCommand.FinalizationCompleted(identity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), coroutineThenPersistence.newState)
        assertEquals(
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            releaseEffect(coroutineThenPersistence).reason,
        )
    }

    @Test
    fun `failed normal finalization stays occupied and permits Stop recovery`() {
        val active = active(ownerToken = 16, runId = "run")
        val identity = effectIdentity(active.identity, "finalize-run-0")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.FinalizationRequested(
                identity,
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                markConversationUnread = true,
            ),
        )
        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.FinalizationCompleted(identity, success = false),
        )
        assertEquals(listOf(RunEffect.RunFinalizationFailed(identity)), failed.effects)
        assertTrue((failed.newState as RunState.Finalizing).persistenceFailureReported)

        val settled = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertTrue((settled.newState as RunState.Finalizing).coroutineSettled)
        assertTrue(settled.effects.isEmpty())

        val stop = ConversationRuntimeReducer.reduce(
            settled.newState,
            ConversationCommand.StopRequested(
                identity = active.identity,
                coroutineAlreadySettled = true,
                requiresPersistence = true,
                effectId = "stop-recovery",
            ),
        )
        assertTrue(stop.accepted)
        assertTrue(stop.newState is RunState.Stopping)
        assertTrue(stop.effects.any { it is RunEffect.FinalizeStop })
    }

    @Test
    fun `coroutine completion before finalization cannot release a bound durable Run`() {
        val active = active(ownerToken = 18, runId = "run", pass = 1)
        val settled = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertEquals(active.copy(coroutineSettled = true), settled.newState)
        assertTrue(settled.effects.isEmpty())

        val finalizationIdentity = effectIdentity(active.identity, "finalize-run-1")
        val requested = ConversationRuntimeReducer.reduce(
            settled.newState,
            ConversationCommand.FinalizationRequested(
                finalizationIdentity,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                markConversationUnread = true,
            ),
        )
        val staleResult = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.FinalizationCompleted(
                finalizationIdentity.copy(effectId = "old-finalization"),
                success = true,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleResult.rejection)

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.FinalizationCompleted(finalizationIdentity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
        assertEquals(
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            releaseEffect(completed).reason,
        )
        val duplicate = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.FinalizationCompleted(finalizationIdentity, success = true),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, duplicate.rejection)
    }

    @Test
    fun `Stop and normal finalization use first accepted command as the terminal owner`() {
        val active = active(ownerToken = 17, runId = "run")
        val finalizationIdentity = effectIdentity(active.identity, "finalize-run-0")
        val finalizing = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.FinalizationRequested(
                finalizationIdentity,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                markConversationUnread = true,
            ),
        )
        val lateStop = ConversationRuntimeReducer.reduce(
            finalizing.newState,
            stopCommand(active, effectId = "stop"),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, lateStop.rejection)

        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "stop"),
        )
        val lateFinalization = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.FinalizationRequested(
                finalizationIdentity,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                markConversationUnread = true,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, lateFinalization.rejection)
    }

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
    fun `manual Compact owns Idle without becoming a generation and serializes Send`() {
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
            mode = CompactMode.MANUAL,
        )

        val started = ConversationRuntimeReducer.reduce(RunState.Idle(CONVERSATION_ID), request)

        assertEquals(
            RunState.Compacting(identity, "compact-run", CompactMode.MANUAL, null),
            started.newState,
        )
        assertEquals(
            listOf(RunEffect.RunCompact(identity, "compact-run", CompactMode.MANUAL)),
            started.effects,
        )
        val waitingSend = ConversationRuntimeReducer.reduce(
            started.newState,
            sendCommand(ownerToken = 5, runId = "send-run", effectId = "send"),
        )
        assertEquals(
            RunEffect.AwaitCompactSettlement(sendCommand(5, "send-run", "send").identity),
            waitingSend.effects.single(),
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

        val stopped = ConversationRuntimeReducer.reduce(
            started.newState,
            ConversationCommand.StopRequested(
                identity = RuntimeRunIdentity(CONVERSATION_ID, ownerToken = 4),
                coroutineAlreadySettled = true,
                requiresPersistence = false,
                effectId = null,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, stopped.rejection)
        assertSame(started.newState, stopped.newState)

        val completed = ConversationRuntimeReducer.reduce(
            started.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.CREATED),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
        assertTrue(completed.effects.isEmpty())
    }

    @Test
    fun `automatic Compact resumes only after its exact successful result`() {
        val active = active(ownerToken = 7, runId = "run", pass = 3)
        val identity = effectIdentity(active.identity, "compact-effect")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity = identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        )

        assertEquals(
            RunState.Compacting(
                effectIdentity = identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
                resumeIdentity = active.identity,
            ),
            requested.newState,
        )
        assertEquals(
            RunEffect.RunCompact(identity, "compact-run", CompactMode.AUTOMATIC),
            requested.effects.single(),
        )

        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.CompactCompleted(
                identity.copy(effectId = "old-effect"),
                CompactOutcome.CREATED,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(requested.newState, stale.newState)

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.NOT_NEEDED),
        )
        assertEquals(active, completed.newState)
        assertEquals(
            listOf(RunEffect.ResumeAfterCompact(identity, CompactOutcome.NOT_NEEDED)),
            completed.effects,
        )
        val duplicate = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.NOT_NEEDED),
        )
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `failed automatic Compact returns to Active without continuation`() {
        val active = active(ownerToken = 9, runId = "run", pass = 1)
        val identity = effectIdentity(active.identity, "compact-effect")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        )

        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.FAILED),
        )

        assertEquals(active, failed.newState)
        assertEquals(
            listOf(RunEffect.CompactFailed(identity, CompactMode.AUTOMATIC)),
            failed.effects,
        )
        assertFalse(failed.effects.any { it is RunEffect.ResumeAfterCompact })
    }

    @Test
    fun `automatic Compact cannot overlap an executing tool batch`() {
        val active = active(ownerToken = 10, runId = "run", pass = 1)
        val toolRequested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(
                effectIdentity(active.identity, "provider-effect"),
            ),
        )
        val compactIdentity = effectIdentity(active.identity, "compact-effect")

        val compact = ConversationRuntimeReducer.reduce(
            toolRequested.newState,
            ConversationCommand.CompactRequested(
                compactIdentity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        )

        assertEquals(CommandRejection.ILLEGAL_STATE, compact.rejection)
        assertSame(toolRequested.newState, compact.newState)
        assertTrue(compact.effects.isEmpty())
    }

    @Test
    fun `Stop wins over automatic Compact and rejects its late result`() {
        val active = active(ownerToken = 11, runId = "run", pass = 2)
        val identity = effectIdentity(active.identity, "compact-effect")
        val compacting = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CompactRequested(
                identity,
                compactRunId = "compact-run",
                mode = CompactMode.AUTOMATIC,
            ),
        ).newState

        val stopping = ConversationRuntimeReducer.reduce(
            compacting,
            ConversationCommand.StopRequested(
                identity = active.identity,
                coroutineAlreadySettled = false,
                requiresPersistence = true,
                effectId = "stop",
            ),
        )
        assertTrue(stopping.newState is RunState.Stopping)
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(active.identity),
                RunEffect.FinalizeStop(effectIdentity(active.identity, "stop")),
            ),
            stopping.effects,
        )

        val late = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.CompactCompleted(identity, CompactOutcome.CREATED),
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
