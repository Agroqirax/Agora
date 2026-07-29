package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationGenerationStateTest {

    @Test
    fun replacementClaim_isIdleOnlyAndAtomic() {
        val state = ConversationGenerationState("conversation")

        val token = state.tryAcquireForReplacement()

        assertTrue(token != null)
        assertTrue(state.generating.value)
        assertNull(state.tryAcquireForReplacement())
        assertTrue(state.endGeneration(token!!))
        assertFalse(state.generating.value)
    }

    @Test
    fun normalCompletion_doesNotSuppressQueueDrain() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        assertTrue(state.endGeneration(token))

        assertTrue(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun stop_keepsGeneratingUntilCoroutineThenFinalizerFinish() {
        val state = activeStateWithStreamingMessage()
        val token = state.captureUiToken()

        state.stop()

        assertTrue(state.generating.value)
        assertTrue(state.isLoading.value)
        assertTrue(state.stopping.value)
        assertFalse(state.endGeneration(token))
        assertTrue(state.generating.value)
        assertTrue(state.finishStopFinalization())
        assertFalse(state.generating.value)
        assertFalse(state.isLoading.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stop_keepsGeneratingUntilFinalizerThenCoroutineFinish() {
        val state = activeStateWithStreamingMessage()
        val token = state.captureUiToken()

        state.stop()

        assertFalse(state.finishStopFinalization())
        assertTrue(state.generating.value)
        assertTrue(state.endGeneration(token))
        assertFalse(state.generating.value)
        assertFalse(state.isLoading.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stop_synchronouslyCancelsEveryRegisteredGenerationHandle() {
        val state = activeStateWithStreamingMessage()
        var firstCancelCount = 0
        var secondCancelCount = 0
        state.streamScope.register(GenerationCancelHandle { firstCancelCount += 1 })
        state.streamScope.register(GenerationCancelHandle { secondCancelCount += 1 })

        state.stop()
        state.streamScope.cancelAll()

        assertEquals(1, firstCancelCount)
        assertEquals(1, secondCancelCount)
    }

    @Test
    fun stop_suppressesOnlyTheNextQueueDrainDecision() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        val stopped = state.stop()

        assertEquals("run", stopped.runId)
        assertFalse(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun streamClear_commitsFinalMessageBeforeRemovingOverlay() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val finalMessage = ChatMessage(
            id = "model",
            text = "complete",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
        val events = mutableListOf<String>()
        state.onStreamCommit = { _, message ->
            assertEquals(finalMessage, state.streamingMessage.value)
            assertEquals(finalMessage, message)
            events += "commit"
        }
        state.streamUpdate(token, finalMessage)

        state.streamClear(token)

        events += "cleared"
        assertEquals(listOf("commit", "cleared"), events)
        assertNull(state.streamingMessage.value)
    }

    @Test
    fun terminalStreamUpdate_replacesStaleAnsweringSnapshotBeforeClearCommit() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val answering = ChatMessage(
            id = "model",
            text = "complete",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        val terminal = answering.copy(status = MessageStatus.SUCCESS)
        var committed: ChatMessage? = null
        state.onStreamCommit = { _, message -> committed = message }
        state.streamUpdate(token, answering)

        state.streamUpdate(token, terminal)
        state.streamClear(token)

        assertEquals(MessageStatus.SUCCESS, committed?.status)
        assertEquals(terminal, committed)
        assertNull(state.streamingMessage.value)
    }

    @Test
    fun streamClear_keepsStoppedOverlay() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val stopped = ChatMessage(
            id = "model",
            text = "partial",
            participant = Participant.MODEL,
            status = MessageStatus.STOPPED,
        )
        state.streamUpdate(token, stopped)

        state.streamClear(token)

        assertEquals(stopped, state.streamingMessage.value)
    }

    private fun activeStateWithStreamingMessage(): ConversationGenerationState {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        state.generationJob = Job()
        state.streamUpdate(
            token,
            ChatMessage(
                id = "model",
                text = "partial",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            )
        )
        return state
    }
}
