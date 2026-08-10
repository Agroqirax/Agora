package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactControllerTest {
    @Test
    fun disabledSettingShortCircuitsBeforeReadingDurableGraph() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val compactor = ContextCompactor(
            conversations = conversations,
            settings = settings,
            providers = mockk(),
            pauseLoop = {},
        )

        assertFalse(
            compactor.automaticNeeded(
                "conversation",
                4096,
                automaticConfig().copy(enabled = false),
            ),
        )

        coVerify(exactly = 0) {
            conversations.getMessagesForConversationSnapshot(any())
            conversations.restoreBranchSelections(any())
        }
    }

    @Test
    fun automaticBeforeSendDoesNotClaimRuntimeWhenCompactIsNotNeeded() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(automaticNeeded = false)
        val state = ConversationGenerationState("conversation")

        val started = controller(conversations, operation) { _, _, _ -> }
            .startAutomaticBeforeSend(
                "conversation",
                4096,
                automaticConfig(),
                state,
            )

        assertFalse(started)
        assertEquals(1, operation.automaticNeededCalls)
        assertEquals(0, operation.beforeSendCalls)
        assertTrue(state.runtimeTraceSnapshot().isEmpty())
        state.dispose()
        Unit
    }

    @Test
    fun automaticBeforeSendReturnsAfterCapsuleThenUsesOrdinaryGenerationSlot() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getLiveRun("conversation") } returns null
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns
            listOf(compactEntity())
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf(null to "compact_boundary")
        val release = CompletableDeferred<Unit>()
        val operation = FakeCompactOperation(
            beforeSendResult = CompactResult.Created("compact_boundary"),
            publishBeforeSendGraph = true,
            beforeSendRelease = release,
        )
        val state = ConversationGenerationState("conversation")
        val projections = mutableListOf<Map<String?, String>>()
        val startedRows = mutableListOf<String>()

        val started = controller(
            conversations = conversations,
            operation = operation,
            onCompactStarted = { _, messageId -> startedRows += messageId },
        ) { _, _, selected ->
            projections += selected
        }.startAutomaticBeforeSend(
            "conversation",
            4096,
            automaticConfig(),
            state,
        )

        assertTrue(started)
        assertTrue(state.generating.value)
        assertTrue(state.isLoading.value)
        assertTrue(state.compacting.value)
        assertEquals(listOf("compact_boundary"), startedRows)
        assertEquals(1, operation.automaticNeededCalls)
        assertEquals(1, operation.beforeSendCalls)
        assertEquals("compact_run_fixed", operation.beforeSendRunId)
        assertEquals(listOf(mapOf(null to "compact_boundary")), projections)

        release.complete(Unit)
        state.generating.first { generating -> !generating }
        assertFalse(state.compacting.value)
        state.dispose()
        Unit
    }

    @Test
    fun manualRejectsDurableLiveRunBeforeExecutingOperation() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getLiveRun("conversation") } returns liveRun()
        val operation = FakeCompactOperation()
        val state = ConversationGenerationState("conversation")

        val result = controller(conversations, operation) { _, _, _ -> }.manual(
            conversationId = "conversation",
            request = CompactRequest("model", "prompt", 4),
            state = state,
        )

        assertEquals(CompactResult.Failed("Conversation is busy"), result)
        assertEquals(0, operation.manualCalls)
        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        state.dispose()
        Unit
    }

    @Test
    fun manualCreatedReturnsTypedResultAndProjectsDurableGraph() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getLiveRun("conversation") } returns null
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns emptyList()
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf(null to "compact-message")
        val operation = FakeCompactOperation(
            manualResult = CompactResult.Created("compact-message"),
        )
        val state = ConversationGenerationState("conversation")
        val projections = mutableListOf<Map<String?, String>>()
        val request = CompactRequest("model", "prompt", 4)

        val result = controller(conversations, operation) { _, _, selected ->
            projections += selected
        }.manual("conversation", request, state)

        assertEquals(CompactResult.Created("compact-message"), result)
        assertEquals("conversation", operation.manualConversationId)
        assertEquals(request, operation.manualRequest)
        assertEquals("compact_run_fixed", operation.manualCompactRunId)
        assertEquals(listOf(mapOf(null to "compact-message")), projections)
        assertEquals("", state.compactPreview.value)
        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        state.dispose()
        Unit
    }

    private fun controller(
        conversations: ConversationRepository,
        operation: ContextCompactOperation,
        onCompactStarted: (String, String) -> Unit = { _, _ -> },
        projectGraph: (
            String,
            List<MessageEntity>,
            Map<String?, String>,
        ) -> Unit,
    ) = ConversationCompactController(
        conversations = conversations,
        operation = operation,
        effectCoordinator = ContextCompactEffectCoordinator { "fixed" },
        projectGraph = projectGraph,
        onCompactStarted = onCompactStarted,
    )

    private fun compactEntity() = MessageEntity(
        id = "compact_boundary",
        conversationId = "conversation",
        parentId = null,
        text = "",
        status = com.newoether.agora.model.MessageStatus.SENDING,
        participant = com.newoether.agora.model.Participant.MODEL,
        timestamp = 2L,
        modelName = "model",
        runId = "compact_run_fixed",
        runSequence = 0,
    )

    private fun liveRun() = RunEntity(
        id = "live-run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.ACTIVE,
        activeSlot = 1,
        startedAt = 1L,
        lastCheckpointAt = 1L,
    )

    private fun automaticConfig(): AutomaticCompactConfig =
        testGenerationAdmissionSnapshot().automaticCompact
}

private class FakeCompactOperation(
    private val automaticNeeded: Boolean = true,
    private val beforeSendResult: CompactResult = CompactResult.NotNeeded,
    private val manualResult: CompactResult = CompactResult.NotNeeded,
    private val publishBeforeSendGraph: Boolean = false,
    private val beforeSendRelease: CompletableDeferred<Unit>? = null,
) : ContextCompactOperation {
    var automaticNeededCalls = 0
        private set
    var beforeSendCalls = 0
        private set
    var manualCalls = 0
        private set
    var beforeSendRunId: String? = null
        private set
    var manualConversationId: String? = null
        private set
    var manualRequest: CompactRequest? = null
        private set
    var manualCompactRunId: String? = null
        private set

    override suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean {
        automaticNeededCalls += 1
        return automaticNeeded
    }

    override suspend fun compactBeforeSend(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryUpdate: (String) -> Unit,
        onGraphChanged: suspend () -> Unit,
    ): CompactResult {
        beforeSendCalls += 1
        beforeSendRunId = identity.runId
        onSummaryUpdate("partial summary")
        if (publishBeforeSendGraph) onGraphChanged()
        beforeSendRelease?.await()
        return beforeSendResult
    }

    override suspend fun compactManual(
        conversationId: String,
        request: CompactRequest,
        identity: RunEffectIdentity,
        compactRunId: String,
        onSummaryUpdate: (String) -> Unit,
        onGraphChanged: suspend () -> Unit,
    ): CompactResult {
        manualCalls += 1
        manualConversationId = conversationId
        manualRequest = request
        manualCompactRunId = compactRunId
        onSummaryUpdate("partial summary")
        return manualResult
    }
}
