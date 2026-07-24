package com.newoether.agora.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide, single-slot serialization gate for every LLM generation.
 *
 * Unlike [ConversationExecutionCoordinator] (which is per-conversation and therefore lets two
 * different conversations stream in parallel), this queue is global: at most one generation runs
 * at any instant across the whole process. Everything that drives a provider stream must acquire
 * it — foreground send / regenerate / edit, title generation, and headless Task / Loop execution.
 *
 * The backing [Mutex] is fair (FIFO), so callers run in the order they arrived and nothing can be
 * starved. [Mutex.lock] is cancellable, so a caller still waiting in line unwinds cleanly when its
 * job is cancelled (e.g. the user hits Stop before this generation ever started).
 *
 * Lock ordering: callers that also hold a conversation lock always acquire it BEFORE this queue
 * (conversation → queue). Title generation holds no conversation lock and takes the queue directly.
 * There is therefore no cross-lock cycle and no deadlock.
 */
class GenerationQueue {
    private val mutex = Mutex()

    private val _waiting = AtomicInteger(0)
    private val _active = MutableStateFlow(false)

    /** True while a generation currently owns the queue. UI may surface a "queued" hint from this. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Number of callers currently blocked waiting for the slot (does not include the active one). */
    val waitingCount: Int get() = _waiting.get()

    suspend fun <T> withLock(block: suspend () -> T): T {
        _waiting.incrementAndGet()
        var acquired = false
        try {
            mutex.lock()
            acquired = true
            _waiting.decrementAndGet()
            _active.value = true
            return block()
        } finally {
            if (acquired) {
                _active.value = false
                mutex.unlock()
            } else {
                // lock() threw (cancelled) before acquiring — balance the waiting counter.
                _waiting.decrementAndGet()
            }
        }
    }
}
