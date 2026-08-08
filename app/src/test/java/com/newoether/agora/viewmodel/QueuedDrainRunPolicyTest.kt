package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuedDrainRunPolicyTest {
    @Test
    fun activeOriginContinuesSameRunForNextPass() {
        assertEquals(
            "run",
            QueuedDrainRunPolicy.continuationRunId(
                conversationId = "conversation",
                batchOriginRunIds = listOf("run", "run"),
                originRun = run(status = RunStatus.ACTIVE, activeSlot = 1),
            ),
        )
    }

    @Test
    fun failedOrStoppedOriginStartsFreshChildRun() {
        assertNull(
            QueuedDrainRunPolicy.continuationRunId(
                "conversation",
                listOf("run"),
                run(RunStatus.FAILED, activeSlot = null, RunEndReason.PROVIDER_ERROR),
            )
        )
        assertNull(
            QueuedDrainRunPolicy.continuationRunId(
                "conversation",
                listOf("run"),
                run(RunStatus.STOPPED, activeSlot = null, RunEndReason.USER_STOPPED),
            )
        )
    }

    @Test
    fun mixedOriginBatchCannotAttachToEitherRun() {
        assertNull(
            QueuedDrainRunPolicy.continuationRunId(
                "conversation",
                listOf("run", "other"),
                run(status = RunStatus.ACTIVE, activeSlot = 1),
            )
        )
    }

    private fun run(
        status: RunStatus,
        activeSlot: Int?,
        endReason: RunEndReason? = null,
    ) = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = status,
        activeSlot = activeSlot,
        startedAt = 1,
        lastCheckpointAt = 1,
        endedAt = if (status.isTerminal) 2 else null,
        endReason = endReason,
    )
}
