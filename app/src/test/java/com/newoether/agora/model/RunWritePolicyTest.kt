package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunWritePolicyTest {

    @Test
    fun idleConversation_startsChildOfSelectedLeaf() {
        val decision = RunWritePolicy.placeInput(
            newRunId = "new",
            selectedLeafRunId = "leaf",
            liveRun = null,
        ) as RunInputDecision.StartRun

        assertEquals("new", decision.runId)
        assertEquals("leaf", decision.parentRunId)
        assertNull(decision.terminalizeRunId)
        assertEquals(0L, decision.runSequence)
        assertEquals(0, decision.consumedAtPass)
    }

    @Test
    fun activeConversation_appendsUnconsumedIntervention() {
        val decision = RunWritePolicy.placeInput(
            newRunId = "unused",
            selectedLeafRunId = "leaf",
            liveRun = LiveRunHead(
                id = "active",
                status = RunStatus.ACTIVE,
                currentPass = 2,
                lastSequence = 7,
            ),
        ) as RunInputDecision.AppendIntervention

        assertEquals("active", decision.runId)
        assertEquals(8L, decision.runSequence)
        assertNull(decision.consumedAtPass)
    }

    @Test
    fun stoppingConversation_terminalizesOldRunBeforeStartingItsChild() {
        val decision = RunWritePolicy.placeInput(
            newRunId = "new",
            selectedLeafRunId = "stale-leaf",
            liveRun = LiveRunHead(
                id = "stopping",
                status = RunStatus.STOPPING,
                currentPass = 1,
                lastSequence = 4,
            ),
        ) as RunInputDecision.StartRun

        assertEquals("new", decision.runId)
        assertEquals("stopping", decision.parentRunId)
        assertEquals("stopping", decision.terminalizeRunId)
    }

}
