package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLifecycleTest {

    @Test
    fun passWithPendingIntervention_staysActiveAndStartsAnotherPass() {
        val transition = RunLifecycle.reduce(
            RunLifecycleState(),
            RunLifecycleEvent.PassCompleted(hasPendingInterventions = true),
        )

        assertEquals(RunStatus.ACTIVE, transition.state.status)
        assertEquals(null, transition.state.endReason)
        assertEquals(RunNextAction.START_NEXT_PASS, transition.nextAction)
    }

    @Test
    fun finalPassWithoutPendingIntervention_completesRun() {
        val transition = RunLifecycle.reduce(
            RunLifecycleState(),
            RunLifecycleEvent.PassCompleted(hasPendingInterventions = false),
        )

        assertEquals(RunStatus.COMPLETED, transition.state.status)
        assertEquals(RunEndReason.MODEL_COMPLETED, transition.state.endReason)
        assertTrue(transition.state.status.isTerminal)
    }

    @Test
    fun stopEstablishesCutoffAndPendingInputCannotRestartPass() {
        val stopping = RunLifecycle.reduce(
            RunLifecycleState(),
            RunLifecycleEvent.StopRequested,
        ).state

        val passFinished = RunLifecycle.reduce(
            stopping,
            RunLifecycleEvent.PassCompleted(hasPendingInterventions = true),
        )
        val stopped = RunLifecycle.reduce(
            passFinished.state,
            RunLifecycleEvent.StopFinalized,
        )

        assertEquals(RunStatus.STOPPING, passFinished.state.status)
        assertEquals(RunNextAction.NONE, passFinished.nextAction)
        assertEquals(RunStatus.STOPPED, stopped.state.status)
        assertEquals(RunEndReason.USER_STOPPED, stopped.state.endReason)
    }

    @Test
    fun providerFailureAfterStop_isStillUserStopped() {
        val stopping = RunLifecycle.reduce(
            RunLifecycleState(),
            RunLifecycleEvent.StopRequested,
        ).state

        val transition = RunLifecycle.reduce(stopping, RunLifecycleEvent.ProviderFailed)

        assertEquals(RunStatus.STOPPED, transition.state.status)
        assertEquals(RunEndReason.USER_STOPPED, transition.state.endReason)
    }

    @Test
    fun activeProviderFailure_failsRun() {
        val transition = RunLifecycle.reduce(
            RunLifecycleState(),
            RunLifecycleEvent.ProviderFailed,
        )

        assertEquals(RunStatus.FAILED, transition.state.status)
        assertEquals(RunEndReason.PROVIDER_ERROR, transition.state.endReason)
    }

    @Test
    fun processRecovery_closesActiveAndStoppingRuns() {
        val activeRecovery = RunLifecycle.reduce(
            RunLifecycleState(),
            RunLifecycleEvent.ProcessRecovered,
        )
        val stoppingRecovery = RunLifecycle.reduce(
            RunLifecycleState(status = RunStatus.STOPPING),
            RunLifecycleEvent.ProcessRecovered,
        )

        assertEquals(RunStatus.STOPPED, activeRecovery.state.status)
        assertEquals(RunEndReason.PROCESS_RECOVERED, activeRecovery.state.endReason)
        assertEquals(RunStatus.STOPPED, stoppingRecovery.state.status)
        assertEquals(RunEndReason.PROCESS_RECOVERED, stoppingRecovery.state.endReason)
    }

    @Test
    fun terminalStatesIgnoreLateEvents() {
        val terminal = RunLifecycleState(
            status = RunStatus.COMPLETED,
            endReason = RunEndReason.MODEL_COMPLETED,
        )

        for (event in listOf(
            RunLifecycleEvent.StopRequested,
            RunLifecycleEvent.StopFinalized,
            RunLifecycleEvent.ProviderFailed,
            RunLifecycleEvent.ProcessRecovered,
            RunLifecycleEvent.PassCompleted(hasPendingInterventions = true),
        )) {
            val transition = RunLifecycle.reduce(terminal, event)
            assertSame(terminal, transition.state)
            assertEquals(RunNextAction.NONE, transition.nextAction)
        }
    }

    @Test
    fun stateRequiresEndReasonExactlyForTerminalStatus() {
        assertFalse(RunLifecycleState().status.isTerminal)

        assertFails {
            RunLifecycleState(status = RunStatus.COMPLETED)
        }
        assertFails {
            RunLifecycleState(
                status = RunStatus.ACTIVE,
                endReason = RunEndReason.MODEL_COMPLETED,
            )
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
