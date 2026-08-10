package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDaoRunAdmissionTest {
    @Test
    fun runGraphAdmissionWritesSelectedModelInTheSameTransactionBoundary() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = ChatEntity(
            id = "conversation",
            title = "title",
            modelId = "provider:old",
        )
        val run = RunEntity(
            id = "run",
            conversationId = conversation.id,
            parentRunId = null,
            status = RunStatus.ACTIVE,
            activeSlot = 1,
            startedAt = 1L,
            lastCheckpointAt = 2L,
        )
        val message = MessageEntity(
            id = "model",
            conversationId = conversation.id,
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            timestamp = 2L,
            modelName = "provider:new",
            runId = run.id,
            runSequence = 0,
        )
        coEvery {
            dao.createRunWithMessages(any(), any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getLiveRun(conversation.id) } returns null
        coEvery { dao.insertRun(run) } just Runs
        coEvery { dao.insertMessage(message) } just Runs
        coEvery {
            dao.updateConversationForRunAdmission(
                conversationId = conversation.id,
                selectedBranchesJson = any(),
                selectedRunBranchesJson = any(),
                modelId = "provider:new",
                at = 10L,
            )
        } returns 1

        val result = dao.createRunWithMessages(
            run = run,
            messages = listOf(message),
            messageSelectionUpdates = mapOf(null to message.id),
            conversationModelId = "provider:new",
            at = 10L,
        )

        assertEquals(listOf(message), result.messages)
        coVerifyOrder {
            dao.insertRun(run)
            dao.insertMessage(message)
            dao.updateConversationForRunAdmission(
                conversationId = conversation.id,
                selectedBranchesJson = any(),
                selectedRunBranchesJson = any(),
                modelId = "provider:new",
                at = 10L,
            )
        }
    }
}
