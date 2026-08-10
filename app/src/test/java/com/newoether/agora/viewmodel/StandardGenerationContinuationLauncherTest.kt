package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StandardGenerationContinuationLauncherTest {
    @Test
    fun createsFreshRunAndAssistantUnderDurableBoundary() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation")
        val parent = MessageEntity(
            id = "compact-boundary",
            conversationId = "conversation",
            parentId = "tool-result",
            text = "summary",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "compact-run",
            runSequence = 0,
        )
        val createdRun = slot<com.newoether.agora.data.local.RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        val launchedRequest = slot<BoundRunGenerationRequest>()
        val launched = CompletableDeferred<Unit>()

        coEvery {
            conversations.getMessagesForConversationSnapshot("conversation")
        } returns listOf(parent)
        coEvery {
            conversations.restoreBranchSelections("conversation")
        } returns emptyMap()
        coEvery {
            conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = any(),
                conversationModelId = any(),
                at = any(),
            )
        } answers {
            RunGraphCommit(
                messages = createdMessages.captured,
                messageSelections = mapOf(parent.id to createdMessages.captured.single().id),
                runSelections = mapOf(parent.runId to createdRun.captured.id),
            )
        }
        coEvery { boundLauncher.launch(capture(launchedRequest), state) } answers {
            launched.complete(Unit)
        }

        val ids = ArrayDeque(listOf("continuation-run", "continuation-message"))
        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { ids.removeFirst() },
            clock = { 200L },
        )

        assertNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = "origin-run",
                    ),
                ),
                state,
            )
        )
        launched.await()

        assertEquals("continuation-run", createdRun.captured.id)
        assertEquals("compact-run", createdRun.captured.parentRunId)
        assertEquals(parent.id, createdMessages.captured.single().parentId)
        assertEquals(MessageStatus.SENDING, createdMessages.captured.single().status)
        assertEquals("continuation-run", launchedRequest.captured.runId)
        assertEquals("continuation-message", launchedRequest.captured.modelMessageId)
        coVerify(exactly = 1) { boundLauncher.launch(any(), state) }
        state.dispose()
        Unit
    }

    @Test
    fun selectedChildSupersedesAutomaticContinuation() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation")
        val parent = MessageEntity(
            id = "tool-result",
            conversationId = "conversation",
            text = "result",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "origin-run",
            runSequence = 2,
        )
        coEvery {
            conversations.getMessagesForConversationSnapshot("conversation")
        } returns listOf(parent)
        coEvery {
            conversations.restoreBranchSelections("conversation")
        } returns mapOf(parent.id to "queued-user")

        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = mockk(),
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { "unused" },
        )

        assertNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = "origin-run",
                    ),
                ),
                state,
            )
        )
        state.awaitSendAvailable()

        coVerify(exactly = 0) {
            conversations.createRunWithMessages(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { boundLauncher.launch(any(), any()) }
        state.dispose()
        Unit
    }

    private companion object {
        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )
    }
}
