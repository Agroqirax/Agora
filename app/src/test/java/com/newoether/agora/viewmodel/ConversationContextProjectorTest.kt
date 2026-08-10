package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextProjectorTest {
    @Test
    fun newChatProjectionIncludesTheCurrentlySelectedSystemPromptAndToolCost() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val admission = testGenerationAdmissionSnapshot(
            conversationId = "context-preview-conversation",
        )
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot(
                "context-preview-conversation",
                "provider:model",
                "prompt-selected-for-new-chat",
            )
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 221
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            toBranchMessage = { error("New Chat has no durable messages") },
            newChatSystemPromptId = { "prompt-selected-for-new-chat" },
        )

        val projection = projector.project(null, "provider:model", 4_096)

        assertEquals(221, projection.usage.estimatedTokenCount)
        assertTrue(projection.retainedMessageIds.isEmpty())
        coVerify(exactly = 1) {
            requestBuilder.captureContextProjectionSnapshot(
                "context-preview-conversation",
                "provider:model",
                "prompt-selected-for-new-chat",
            )
        }
        coVerify(exactly = 0) { conversations.getMessagesForConversationSnapshot(any()) }
        coVerify(exactly = 0) { conversations.restoreBranchSelections(any()) }
    }

    @Test
    fun projectionCountsFixedInputAndDurableToolPayloadInsteadOfUiStrippedRows() = runTest {
        val conversations = mockk<ConversationRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val generationManager = mockk<GenerationManager>()
        val user = entity("user", null, Participant.USER, "question", 0)
        val model = entity("model", user.id, Participant.MODEL, "", 1).copy(
            status = MessageStatus.SENDING,
        )
        val tool = entity("${Constants.TOOL_MSG_PREFIX}call", model.id, Participant.MODEL, "", 2)
            .copy(
                toolCallJson = Json.encodeToString(
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = "shell",
                            toolArgs = "{\"command\":\"echo durable payload\"}",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
            )
        val result = entity(
            "${Constants.RESULT_MSG_PREFIX}call",
            tool.id,
            Participant.USER,
            "durable result payload",
            3,
        ).copy(
            toolCallJson = Json.encodeToString(
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "shell",
                        toolArgs = "{}",
                        toolResult = "durable result payload",
                        toolCallId = "call-1",
                    ),
                ),
            ),
        )
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns
            listOf(user, model, tool, result)
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        val admission = testGenerationAdmissionSnapshot(conversationId = "conversation")
        val snapshot = GenerationContextProjectionSnapshot(admission.config, admission.context)
        coEvery {
            requestBuilder.captureContextProjectionSnapshot("conversation", "provider:model", null)
        } returns snapshot
        every { generationManager.fixedContextTokenCost(snapshot.config, snapshot.context) } returns 137
        val projector = ConversationContextProjector(
            conversations = conversations,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            toBranchMessage = { entity ->
                ChatMessage(
                    id = entity.id,
                    parentId = entity.parentId,
                    text = entity.text,
                    participant = entity.participant,
                    status = entity.status,
                    runId = entity.runId,
                    runSequence = entity.runSequence,
                )
            },
        )

        val projection = projector.project("conversation", "provider:model", 4_096)

        assertTrue(projection.usage.estimatedTokenCount > 137)
        assertEquals(4_096, projection.usage.tokenBudget)
        assertTrue(tool.id in projection.retainedMessageIds)
        assertTrue(result.id in projection.retainedMessageIds)
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        text: String,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = text,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        modelName = "provider:model",
        runId = "run",
        runSequence = sequence,
    )
}
