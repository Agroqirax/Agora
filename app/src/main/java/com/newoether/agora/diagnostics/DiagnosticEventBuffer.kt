package com.newoether.agora.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-memory diagnostic history.
 *
 * Producer writes are best effort: they use [ReentrantLock.tryLock] and are dropped instead of
 * waiting behind a viewer or another producer. Event factories are not invoked while capture is
 * disabled, so the release-build default path does not construct diagnostic payloads.
 */
internal class DiagnosticEventBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<DiagnosticEvent>(capacity)
    private val droppedEvents = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(DiagnosticSnapshot())
    private var session: DiagnosticSession? = null
    private var nextSequence = 1L

    @Volatile
    private var captureActive = false

    @Volatile
    private var captureMode: DiagnosticCaptureMode? = null

    val snapshots: StateFlow<DiagnosticSnapshot> = mutableSnapshot.asStateFlow()
    val isCaptureActive: Boolean get() = captureActive
    val activeMode: DiagnosticCaptureMode? get() = captureMode.takeIf { captureActive }

    init {
        require(capacity in 1..MAX_CAPACITY)
    }

    fun start(mode: DiagnosticCaptureMode = DiagnosticCaptureMode.METADATA): DiagnosticSession {
        captureActive = false
        captureMode = null
        return lock.withLock {
            entries.clear()
            droppedEvents.set(0L)
            nextSequence = 1L
            val nextSession = DiagnosticSession(
                id = sessionIdFactory(),
                mode = mode,
                startedAtMillis = clock(),
            )
            session = nextSession
            captureMode = mode
            captureActive = true
            publishLocked()
            nextSession
        }
    }

    fun stop() {
        captureActive = false
        captureMode = null
        lock.withLock {
            session = session
                ?.takeIf(DiagnosticSession::isActive)
                ?.copy(stoppedAtMillis = clock())
                ?: session
            publishLocked()
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
            droppedEvents.set(0L)
            nextSequence = 1L
            if (!captureActive) session = null
            publishLocked()
        }
    }

    fun stopAndClear() {
        captureActive = false
        captureMode = null
        lock.withLock {
            entries.clear()
            droppedEvents.set(0L)
            nextSequence = 1L
            session = null
            publishLocked()
        }
    }

    /**
     * The factory runs only after capture is confirmed active and the producer acquires the lock.
     * Returning immediately on contention keeps diagnostics outside generation backpressure.
     */
    fun record(eventFactory: (sequence: Long, timestampMillis: Long) -> DiagnosticEvent) {
        if (!captureActive) return
        if (!lock.tryLock()) {
            droppedEvents.incrementAndGet()
            return
        }
        try {
            if (!captureActive || session?.isActive != true) return
            val event = eventFactory(nextSequence++, clock())
            if (entries.size == capacity) {
                entries.removeFirst()
                droppedEvents.incrementAndGet()
            }
            entries.addLast(event)
            publishLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun publishLocked() {
        mutableSnapshot.value = DiagnosticSnapshot(
            session = session,
            events = entries.toList(),
            droppedEventCount = droppedEvents.get(),
        )
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 512
        const val MAX_CAPACITY = 4_096
    }
}
