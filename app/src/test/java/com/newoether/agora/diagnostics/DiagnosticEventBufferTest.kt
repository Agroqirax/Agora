package com.newoether.agora.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticEventBufferTest {
    @Test
    fun `disabled capture does not invoke event factory`() {
        val buffer = DiagnosticEventBuffer(capacity = 4)
        var invoked = false

        buffer.record { sequence, timestamp ->
            invoked = true
            event(sequence, timestamp)
        }

        assertFalse(invoked)
        assertEquals(DiagnosticSnapshot(), buffer.snapshots.value)
    }

    @Test
    fun `session lifecycle is explicit and stopped events remain inspectable until clear`() {
        var now = 10L
        val buffer = DiagnosticEventBuffer(
            capacity = 4,
            clock = { now++ },
            sessionIdFactory = { "session-1" },
        )

        val session = buffer.start()
        buffer.record(::event)
        buffer.stop()
        var invokedAfterStop = false
        buffer.record { sequence, timestamp ->
            invokedAfterStop = true
            event(sequence, timestamp)
        }

        val stopped = buffer.snapshots.value
        assertEquals("session-1", session.id)
        assertFalse(stopped.isCaptureActive)
        assertNotNull(stopped.session?.stoppedAtMillis)
        assertEquals(1, stopped.events.size)
        assertFalse(invokedAfterStop)

        buffer.clear()

        assertNull(buffer.snapshots.value.session)
        assertTrue(buffer.snapshots.value.events.isEmpty())
    }

    @Test
    fun `capacity eviction is ordered and counted`() {
        val buffer = DiagnosticEventBuffer(
            capacity = 2,
            clock = { 100L },
            sessionIdFactory = { "bounded" },
        )
        buffer.start()

        repeat(3) { buffer.record(::event) }

        val snapshot = buffer.snapshots.value
        assertEquals(listOf(2L, 3L), snapshot.events.map(DiagnosticEvent::sequence))
        assertEquals(1L, snapshot.droppedEventCount)
    }

    @Test
    fun `concurrent producers keep a bounded monotonically ordered snapshot`() {
        val buffer = DiagnosticEventBuffer(
            capacity = 64,
            sessionIdFactory = { "concurrent" },
        )
        buffer.start()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)

        repeat(8) {
            executor.execute {
                start.await()
                repeat(100) { buffer.record(::event) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        // Publish the final atomic dropped count after all contending producers have settled.
        buffer.record(::event)

        val snapshot = buffer.snapshots.value
        val sequences = snapshot.events.map(DiagnosticEvent::sequence)
        assertTrue(snapshot.events.size <= 64)
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.distinct(), sequences)
        assertTrue(snapshot.droppedEventCount > 0L)
    }

    @Test
    fun `http detail parser keeps only allowlisted metadata`() {
        val attributes = DeveloperDiagnostics.safeHttpAttributes(
            "code=200 messages=3 authorization=secret endpoint=/private proxy=DIRECT",
        )

        assertEquals(
            mapOf("code" to "200", "messages" to "3", "proxy" to "DIRECT"),
            attributes,
        )
        assertFalse(attributes.containsKey("authorization"))
        assertFalse(attributes.containsKey("endpoint"))
    }

    private fun event(sequence: Long, timestamp: Long) = DiagnosticEvent(
        sequence = sequence,
        timestampMillis = timestamp,
        context = DiagnosticRequestContext(),
        payload = DiagnosticEventPayload.HttpStage(
            stage = "test",
            elapsedMillis = 0L,
            attributes = emptyMap(),
        ),
    )
}
