package com.newoether.agora.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExecutionCoordinatorTest {
    @Test
    fun sameConversation_isStrictlySerialized() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            coordinator.withConversationLock("conversation") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.withConversationLock("conversation") { secondEntered = true }
        }

        runCurrent()
        assertFalse(secondEntered)
        assertTrue(coordinator.isExecuting("conversation"))

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
        assertFalse(coordinator.isExecuting("conversation"))
        assertEquals(0, coordinator.trackedConversationCount())
    }

    @Test
    fun differentConversations_canProceedIndependently() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            coordinator.withConversationLock("one") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.withConversationLock("two") { secondEntered = true }
        }
        second.join()

        assertTrue(secondEntered)
        assertTrue(coordinator.isExecuting("one"))
        releaseFirst.complete(Unit)
        first.join()
        assertEquals(0, coordinator.trackedConversationCount())
    }

    @Test
    fun cancelledWaiter_releasesItsReference() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.withConversationLock("conversation") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val waiter = launch {
            coordinator.withConversationLock("conversation") { error("must not enter") }
        }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals(1, coordinator.trackedConversationCount())

        releaseFirst.complete(Unit)
        first.join()
        assertEquals(0, coordinator.trackedConversationCount())
    }
}
