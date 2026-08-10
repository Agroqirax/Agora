package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.api.util.ContextTokenEstimator
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationApiPathBuilderTest {
    @Test
    fun `caller snapshot produces compact-bounded path and exact provider config`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(repository) { emptyList() }
        val compact = message("${Constants.COMPACT_MSG_PREFIX}boundary", parentId = "old", sequence = 1)
        val user = message("user", parentId = compact.id, sequence = 2, participant = Participant.USER)
        val model = message("model", parentId = user.id, sequence = 3)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = model.id,
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(message("old", null, 0), compact, user, model),
            ),
        )

        assertEquals(listOf(compact.id, user.id, model.id), path.messages.map { it.id })
        assertEquals("model-id", path.providerConfig.modelId)
        assertEquals("system", path.providerConfig.systemPrompt)
        assertTrue(path.providerConfig.tools.orEmpty().isEmpty())
        assertEquals(
            generationConfig().maxContextWindow -
                ContextTokenEstimator.estimateFixed("system", emptyList()),
            path.providerConfig.maxContextWindow,
        )
        coVerify(exactly = 0) { repository.getMessagesForConversationSnapshot(any()) }
    }

    @Test
    fun `failed compact is not a request boundary`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(repository) { emptyList() }
        val old = message("old", null, 0, participant = Participant.USER)
        val failed = message(
            "${Constants.COMPACT_MSG_PREFIX}failed",
            parentId = old.id,
            sequence = 1,
        ).copy(status = MessageStatus.ERROR)
        val user = message("user", failed.id, 2, Participant.USER)
        val model = message("model", user.id, 3)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = model.id,
                conversationId = "conversation",
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(old, failed, user, model),
            ),
        )

        assertTrue(path.messages.any { it.id == old.id })
        assertTrue(path.messages.any { it.id == failed.id && it.text == failed.text })
        assertEquals("user", path.messages.single { it.id == user.id }.text)
    }

    private fun generationConfig() = GenerationConfig(
        providerName = "provider",
        modelId = "model-id",
        apiKey = "key",
        effectiveSystemPrompt = "system",
        codeExecutionEnabled = false,
        googleSearchEnabled = false,
        thinkingEnabled = false,
        baseUrl = null,
    )

    private fun message(
        id: String,
        parentId: String?,
        sequence: Long,
        participant: Participant = Participant.MODEL,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        modelName = "model-id",
        runId = "run",
        runSequence = sequence,
    )
}
