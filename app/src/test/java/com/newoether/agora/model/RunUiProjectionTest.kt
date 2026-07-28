package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunUiProjectionTest {
    @Test
    fun intermediatePassMessages_haveNoActions_andBoundaryCopyKeepsOriginalRowSemantics() {
        val messages = listOf(
            message("u0", "start", Participant.USER, "run-a", 0),
            message("m0", "first", Participant.MODEL, "run-a", 1),
            message("u1", "steer", Participant.USER, "run-a", 2),
            message("m1", "final", Participant.MODEL, "run-a", 3),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("u0").showActions)
        assertEquals("start", projected.getValue("u0").copyText)
        assertFalse(projected.getValue("m0").showActions)
        assertFalse(projected.getValue("u1").showActions)
        assertTrue(projected.getValue("m1").showActions)
        assertEquals("final", projected.getValue("m1").copyText)
    }

    @Test
    fun emptyStoppedOutput_keepsActionsWithoutCopy() {
        val messages = listOf(
            message("u0", "start", Participant.USER, "run-a", 0),
            message("m0", "", Participant.MODEL, "run-a", 1, MessageStatus.STOPPED),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("m0").showActions)
        assertNull(projected.getValue("m0").copyText)
    }

    @Test
    fun branchSelector_isOnBothRunBoundaries_andTargetsSiblingBoundaryInput() {
        val left = listOf(
            message("u-left", "left", Participant.USER, "run-left", 0, parentId = "parent"),
            message("m-left", "answer", Participant.MODEL, "run-left", 1, parentId = "u-left"),
        )
        val right = listOf(
            message("u-right", "right", Participant.USER, "run-right", 0, parentId = "parent", timestamp = 2),
            message("m-right", "answer", Participant.MODEL, "run-right", 1, parentId = "u-right", timestamp = 3),
        )

        val projected = RunUiProjection.project(right, left + right)

        val input = projected.getValue("u-right")
        assertTrue(input.showBranchSelector)
        assertEquals(1, input.branchIndex)
        assertEquals(2, input.totalBranches)
        assertEquals("parent", input.branchAnchorParentId)
        assertEquals("u-right", input.branchAnchorMessageId)
        val output = projected.getValue("m-right")
        assertTrue(output.showBranchSelector)
        assertEquals(1, output.branchIndex)
        assertEquals(2, output.totalBranches)
        assertEquals("parent", output.branchAnchorParentId)
        assertEquals("u-right", output.branchAnchorMessageId)
    }

    @Test
    fun syntheticToolRows_neverBecomeBoundariesOrCopyText() {
        val messages = listOf(
            message("u0", "start", Participant.USER, "run-a", 0),
            message("m0", "answer", Participant.MODEL, "run-a", 1),
            message("tool_x", "hidden", Participant.MODEL, "run-a", 2),
            message("result_x", "hidden result", Participant.USER, "run-a", 3),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("m0").showActions)
        assertEquals("answer", projected.getValue("m0").copyText)
        assertFalse(projected.getValue("tool_x").showActions)
        assertFalse(projected.getValue("result_x").showActions)
    }

    private fun message(
        id: String,
        text: String,
        participant: Participant,
        runId: String,
        sequence: Long,
        status: MessageStatus = MessageStatus.SUCCESS,
        parentId: String? = null,
        timestamp: Long = sequence,
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        status = status,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
    )
}
