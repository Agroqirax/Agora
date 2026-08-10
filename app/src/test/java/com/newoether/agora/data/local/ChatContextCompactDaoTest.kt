package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RunEndReason
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ChatContextCompactDaoTest {
    @Test
    fun automaticCompactIsInsertedInsideActiveRunBeforePlaceholder() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation()
        val run = activeRun()
        val user = message("user", null, run.id, 0, Participant.USER)
        val placeholder = message("model", user.id, run.id, 1, Participant.MODEL)
            .copy(status = MessageStatus.SENDING)
        val compact = message("compact_boundary", user.id, run.id, 0, Participant.MODEL)
            .copy(status = MessageStatus.SENDING)
        val inserted = slot<MessageEntity>()

        coEvery {
            dao.beginAutomaticContextCompact(any(), any(), any(), any(), any(), any())
        } coAnswers {
            callOriginal()
        }
        coEvery { dao.getRun(run.id) } returns run
        coEvery { dao.getMessage(user.id) } returns user
        coEvery { dao.getMessage(placeholder.id) } returns placeholder
        coEvery { dao.shiftRunSequencesForInsertion(run.id, 1) } returns 1
        coEvery { dao.insertMessage(capture(inserted)) } just Runs
        coEvery { dao.updateMessageParent(placeholder.id, compact.id) } returns 1
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) } returns 1
        coEvery { dao.touchRun(run.id, 20) } returns 1

        val assigned = dao.beginAutomaticContextCompact(
            message = compact,
            childMessageId = placeholder.id,
            expectedPass = 0,
            expectedSelectedBranchesJson = "{}",
            selectedBranchesJson = "{}",
            at = 20,
        )

        assertEquals(run.id, assigned.runId)
        assertEquals(1, assigned.runSequence)
        assertEquals(assigned, inserted.captured)
        coVerifyOrder {
            dao.shiftRunSequencesForInsertion(run.id, 1)
            dao.insertMessage(assigned)
            dao.updateMessageParent(placeholder.id, compact.id)
        }
        coVerify(exactly = 0) { dao.insertRun(any()) }
    }

    @Test
    fun automaticCompactRejectsAStalePassBeforeWriting() = runTest {
        val dao = mockk<ChatDao>()
        val run = activeRun().copy(currentPass = 2)
        val compact = message("compact_boundary", "user", run.id, 0, Participant.MODEL)
            .copy(status = MessageStatus.SENDING)
        coEvery {
            dao.beginAutomaticContextCompact(any(), any(), any(), any(), any(), any())
        } coAnswers {
            callOriginal()
        }
        coEvery { dao.getRun(run.id) } returns run

        try {
            dao.beginAutomaticContextCompact(compact, null, 1, "{}", "{}", 20)
            fail("Expected stale pass rejection")
        } catch (_: IllegalStateException) {
            // expected
        }

        coVerify(exactly = 0) { dao.insertMessage(any()) }
    }

    @Test
    fun automaticCompactSettlesOnlyInsideItsCurrentRunPass() = runTest {
        val dao = mockk<ChatDao>()
        val run = activeRun().copy(currentPass = 3)
        val compact = message("compact_boundary", "user", run.id, 1, Participant.MODEL)
            .copy(status = MessageStatus.SENDING, text = "")
        coEvery {
            dao.settleAutomaticContextCompact(any(), any(), any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getRun(run.id) } returns run
        coEvery {
            dao.settleContextCompactMessage(
                compact.id,
                run.id,
                "summary",
                MessageStatus.SUCCESS,
            )
        } returns 1
        coEvery { dao.touchRun(run.id, 30) } returns 1

        assertEquals(
            true,
            dao.settleAutomaticContextCompact(
                compact.id,
                run.id,
                expectedPass = 3,
                text = "summary",
                status = MessageStatus.SUCCESS,
                at = 30,
            ),
        )
        coVerify(exactly = 0) { dao.terminalizeManualContextCompactRun(any(), any(), any(), any()) }
    }

    @Test
    fun manualCompactCreatesLiveMessageThenSettlesMessageAndRunAtomically() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation()
        val sourceRun = activeRun().copy(
            id = "source-run",
            status = RunStatus.COMPLETED,
            activeSlot = null,
            endedAt = 5,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val source = message("source", null, sourceRun.id, 0, Participant.MODEL)
        val compactRun = activeRun().copy(id = "compact-run", parentRunId = sourceRun.id)
        val compact = message("compact_boundary", source.id, compactRun.id, 0, Participant.MODEL)
            .copy(status = MessageStatus.SENDING, text = "")
        coEvery {
            dao.beginManualContextCompact(any(), any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getLiveRun(conversation.id) } returns null
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getMessage(source.id) } returns source
        coEvery { dao.insertRun(compactRun) } just Runs
        coEvery { dao.insertMessage(compact) } just Runs
        coEvery { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) } returns 1

        assertEquals(
            compact,
            dao.beginManualContextCompact(compactRun, compact, "{}", "{}", 20),
        )

        coEvery {
            dao.settleManualContextCompact(any(), any(), any(), any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getRun(compactRun.id) } returns compactRun
        coEvery {
            dao.settleContextCompactMessage(
                compact.id,
                compactRun.id,
                "summary",
                MessageStatus.SUCCESS,
            )
        } returns 1
        coEvery {
            dao.terminalizeManualContextCompactRun(
                compactRun.id,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                30,
            )
        } returns 1

        assertEquals(
            true,
            dao.settleManualContextCompact(
                compact.id,
                compactRun.id,
                "summary",
                MessageStatus.SUCCESS,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                30,
            ),
        )
    }

    @Test
    fun recompactRestartsTheSameTerminalRowWithoutChangingItsGraphOrRun() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation()
        val compact = message(
            id = "compact_boundary",
            parentId = "source",
            runId = "original-run",
            sequence = 7,
            participant = Participant.MODEL,
        ).copy(text = "old summary", modelName = "old/model")
        coEvery {
            dao.beginRecompactContextCompact(any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getLiveRun(conversation.id) } returns null
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.restartContextCompactMessage(compact.id, "new/model") } returns 1

        val restarted = dao.beginRecompactContextCompact(
            messageId = compact.id,
            modelName = "new/model",
            expectedSelectedBranchesJson = "{}",
        )

        assertEquals(compact.id, restarted.id)
        assertEquals(compact.parentId, restarted.parentId)
        assertEquals(compact.runId, restarted.runId)
        assertEquals(compact.runSequence, restarted.runSequence)
        assertEquals("", restarted.text)
        assertEquals(MessageStatus.SENDING, restarted.status)
        assertEquals("new/model", restarted.modelName)
        coVerify(exactly = 0) { dao.insertRun(any()) }
        coVerify(exactly = 0) { dao.insertMessage(any()) }
        coVerify(exactly = 0) { dao.updateMessageParent(any(), any()) }
    }

    @Test
    fun deletingAutomaticCompactNeverDeletesItsOwningRun() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation().copy(
            selectedBranchesJson = "{\"user\":\"compact_boundary\"}",
        )
        val run = activeRun().copy(
            status = RunStatus.COMPLETED,
            activeSlot = null,
            endedAt = 10,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val user = message("user", null, run.id, 0, Participant.USER)
        val compact = message("compact_boundary", user.id, run.id, 1, Participant.MODEL)
        val model = message("model", compact.id, run.id, 2, Participant.MODEL)
        coEvery { dao.removeContextCompact(compact.id) } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getRun(run.id) } returns run
        coEvery { dao.getMessagesForRuns(listOf(run.id)) } returns listOf(user, compact, model)
        coEvery { dao.reparentMessageChildren(compact.id, user.id) } returns 1
        coEvery { dao.deleteEmbeddingsByMessageIds(listOf(compact.id)) } just Runs
        coEvery { dao.deleteMessagesByIds(listOf(compact.id)) } just Runs
        coEvery { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) } returns 1

        assertEquals(true, dao.removeContextCompact(compact.id))

        coVerify(exactly = 0) { dao.deleteRun(run.id) }
        coVerify(exactly = 0) { dao.reparentRunChildren(any(), any()) }
    }

    private fun conversation() = ChatEntity(
        id = "conversation",
        title = "title",
        selectedBranchesJson = "{}",
        selectedRunBranchesJson = "{}",
    )

    private fun activeRun() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.ACTIVE,
        activeSlot = 1,
        startedAt = 1,
        lastCheckpointAt = 2,
        currentPass = 0,
    )

    private fun message(
        id: String,
        parentId: String?,
        runId: String,
        sequence: Long,
        participant: Participant,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = runId,
        runSequence = sequence,
    )
}
