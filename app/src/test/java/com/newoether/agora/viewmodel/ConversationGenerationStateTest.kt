package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    fun stop_waitsForCoroutineAndFinalizerBeforeReleasing() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state

        state.stop()

        assertTrue(state.generating.value)
        assertTrue(state.isLoading.value)
        assertTrue(state.stopping.value)
        active.unwind.complete(Unit)
        active.job.join()
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertTrue(state.finishStopFinalization(success = true))
        assertFalse(state.generating.value)
        assertFalse(state.isLoading.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stopFinalizer_neverReleasesAnOccupiedCoroutineSlot() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state

        state.stop()

        assertFalse(state.finishStopFinalization(success = true))
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        active.unwind.complete(Unit)
        active.job.join()
        assertFalse(state.generating.value)
        assertFalse(state.isLoading.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun failedStopFinalization_keepsSlotOccupied() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state

        state.stop()
        active.unwind.complete(Unit)
        active.job.join()

        assertFalse(state.finishStopFinalization(success = false))
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertNull(state.acquireForSend())
    }

    @Test
    fun stop_synchronouslyCancelsEveryRegisteredGenerationHandle() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
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
    fun stopCancelsAnExternallyOwnedBackgroundGenerationJob() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val started = CompletableDeferred<Unit>()
        val externalJob = launch {
            started.complete(Unit)
            awaitCancellation()
        }
        assertTrue(state.attachGenerationJob(token, externalJob))
        state.bindRun(token, "background-run")
        started.await()

        val stopped = state.stop()
        externalJob.join()

        assertTrue(externalJob.isCancelled)
        assertEquals("background-run", stopped.runId)
        // The coroutine half settled, but the durable Run half still owns STOPPING.
        assertTrue(state.generating.value)
        assertTrue(state.finishStopFinalization(success = true))
        assertFalse(state.generating.value)
    }

    @Test
    fun normalExternalCompletionRequestsQueueDrainExactlyOnce() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val externalJob = Job()
        var drainRequests = 0
        state.onQueueDrainRequested = { drainRequests += 1 }

        assertTrue(state.attachGenerationJob(token, externalJob))
        externalJob.complete()
        externalJob.join()

        assertEquals(1, drainRequests)
        assertFalse(state.generating.value)
    }

    @Test
    fun stopPreservesQueueDrainPermission() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        val stopped = state.stop()

        assertEquals("run", stopped.runId)
        // Stop no longer suppresses queue drain. The existing queue consumption logic
        // naturally takes over after the stop state settles.
        assertTrue(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    /**
     * The Stop barrier has two halves (coroutine unwind, durable terminal write) that can complete
     * in either order, and whichever finishes LAST performs the release.
     *
     * These two tests pin both orders against the same requirement: the releaser must announce the
     * settle through onStopSettled, because that is the only path which migrates the still-pending
     * queued inputs onto a fresh Run. A release that instead just reported "you may drain" would
     * look correct in isolation while handing the drain a terminalized Run, which fails deep inside
     * and strands durably-accepted user messages with no answer.
     */
    @Test
    fun stopSettledByDurableWriteLast_announcesSettle() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        var settledCount = 0
        state.onStopSettled = { settledCount += 1 }

        state.stop()
        active.unwind.complete(Unit)
        active.job.join()
        // Coroutine unwound first, so it must not have settled anything on its own.
        assertEquals(0, settledCount)

        assertTrue(state.finishStopFinalization(success = true))

        assertEquals(1, settledCount)
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stopSettledByCoroutineUnwindLast_announcesSettle() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        val settled = CompletableDeferred<Unit>()
        state.onStopSettled = { settled.complete(Unit) }

        state.stop()
        // Durable half lands first; it cannot release while the coroutine still owns the slot.
        assertFalse(state.finishStopFinalization(success = true))
        assertFalse(settled.isCompleted)

        active.unwind.complete(Unit)
        active.job.join()

        // The coroutine released, so it owes the announcement.
        settled.await()
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
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

    @Test
    fun queuedGuidanceRemainsMemoryOnlyAndPreservesOrder() {
        val state = ConversationGenerationState("conversation")
        val first = QueuedSend("one", "first", "model", emptyList(), "run")
        val second = QueuedSend("two", "second", "model", emptyList(), "run")

        state.enqueueSend(first)
        state.enqueueSend(second)

        assertEquals(listOf("one", "two"), state.queuedSends.value.map { it.id })
        assertEquals(listOf(first, second), state.takeQueuedSends())
        assertTrue(state.queuedSends.value.isEmpty())
    }

    @Test
    fun removingQueuedGuidanceTransfersOwnershipExactlyOnce() {
        val state = ConversationGenerationState("conversation")
        val queued = QueuedSend("one", "first", "model", emptyList(), "run")
        state.enqueueSend(queued)

        assertEquals(queued, state.removeQueuedSend("one"))
        assertNull(state.removeQueuedSend("one"))
        assertTrue(state.queuedSends.value.isEmpty())
    }

    @Test
    fun failedBoundaryDrainRestoresWholeBatchAtFront() {
        val state = ConversationGenerationState("conversation")
        val older = QueuedSend("older", "a", "model", emptyList(), "run")
        val newer = QueuedSend("newer", "b", "model", emptyList(), "run")
        state.enqueueSend(newer)

        state.requeueFront(listOf(older))

        assertEquals(listOf("older", "newer"), state.queuedSends.value.map { it.id })
    }

    @Test
    fun failedBoundaryDrainDefersOnlyImmediateAutomaticRetry() {
        val state = ConversationGenerationState("conversation")

        state.deferNextQueueDrain()

        assertFalse(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    private fun activeStateWithStreamingMessage(): ActiveGeneration {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val unwind = CompletableDeferred<Unit>()
        val job = checkNotNull(
            state.launchGenerationJob(token) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { unwind.await() }
                }
            }
        )
        state.streamUpdate(
            token,
            ChatMessage(
                id = "model",
                text = "partial",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            )
        )
        return ActiveGeneration(state, token, job, unwind)
    }

    private data class ActiveGeneration(
        val state: ConversationGenerationState,
        val token: Long,
        val job: Job,
        val unwind: CompletableDeferred<Unit>,
    )
}
