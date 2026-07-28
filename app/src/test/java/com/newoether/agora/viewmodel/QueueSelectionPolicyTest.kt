package com.newoether.agora.viewmodel

import com.newoether.agora.model.repairSelectionsAfterQueuedRemoval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QueueSelectionPolicyTest {
    @Test
    fun removingMiddleQueuedInput_reparentsSelectionToNextInput() {
        val repaired = repairSelectionsAfterQueuedRemoval(
            selections = mapOf(
                "model" to "q1",
                "q1" to "q2",
                "q2" to "q3",
            ),
            removedMessageId = "q2",
            removedParentId = "q1",
            reparentedChildIds = listOf("q3"),
        )

        assertEquals("q1", repaired["model"])
        assertEquals("q3", repaired["q1"])
        assertFalse(repaired.containsKey("q2"))
    }

    @Test
    fun removingLastQueuedInput_removesDeadSelectionEdge() {
        val repaired = repairSelectionsAfterQueuedRemoval(
            selections = mapOf(
                "model" to "q1",
                "q1" to "q2",
            ),
            removedMessageId = "q2",
            removedParentId = "q1",
            reparentedChildIds = emptyList(),
        )

        assertEquals(mapOf("model" to "q1"), repaired)
    }
}
