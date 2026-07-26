package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One per conversation. Owns that conversation's private generation state — the IO scope,
 * current generation job, send gate, streaming/loading UI flows, ownership tokens, and the
 * queued-send list — so two conversations can generate in parallel without their state
 * clobbering each other.
 *
 * Replaces the process-global single-slot state that previously lived in [GenerationSession].
 * The global StateFlows ChatViewModel exposes to the UI are now a mirror of whichever
 * conversation is currently open (see [ConversationStateRegistry]); background conversations
 * mutate only their own private flows here and write the DB, so they stay invisible until the
 * user switches back.
 *
 * ## Ownership tokens (unchanged semantics, scoped per conversation)
 *
 *  • [uiGenToken] owns the shared UI mirror (isLoading/streamingMessage/generatingInConversationId
 *    as seen through the registry). Advanced on EVERY stop and captured by each new generation.
 *    Token-gated mutators below only touch state while their captured token is current.
 *
 *  • [persistId] owns the model message's DB row. Advanced ONLY when a new generation starts
 *    (never on stop), so a stopped generation still persists its own text while a superseded
 *    one is blocked from clobbering the newer message.
 *
 * ## Stop / stopForReplacement
 *
 * [stop] / [stopForReplacement] cancel ONLY this conversation's [generationJob] and this
 * conversation's in-flight HTTP streams (via [streamScope]) — never another conversation's.
 * This is the fix for the cross-conversation "Stop kills the wrong generation" race.
 */
class ConversationGenerationState(val conversationId: String) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var generationJob: Job? = null
    val sendGate = AtomicBoolean(false)

    @Volatile private var stopFinalizationJob: Job? = null

    /** This conversation's in-flight HTTP streaming handles. Cancelled together on stop. */
    val streamScope: StreamScope = StreamScope()

    // ── Private UI state (mirrored to the global UI flows only while this conversation is open) ──
    val streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val isLoading = MutableStateFlow(false)
    /** True while this conversation has an active generation. Drives the Stop-button visibility. */
    val generating = MutableStateFlow(false)

    /** Queued sends waiting for the current generation to finish. Per-conversation. */
    val queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())

    // ── Ownership tokens ──
    private val genLock = Any()
    private var uiGenToken = 0L
    private val persistId = AtomicLong(0L)

    /** Captures the current UI-ownership token right after a stop, under the lock. */
    fun captureUiToken(): Long = synchronized(genLock) { uiGenToken }
    /** Claims DB-row ownership for a freshly-started generation. */
    fun nextPersistId(): Long = persistId.incrementAndGet()
    /** True while [persistId] still belongs to the generation that captured [id]. */
    fun isLatestPersist(id: Long): Boolean = persistId.get() == id

    /** Marks this conversation as actively generating and claims a fresh persist id. */
    fun beginGeneration(): Long {
        generating.value = true
        return nextPersistId()
    }

    /** Clears the generating flag (token-gated so a stale generation can't resurrect it). */
    fun clearGenerating(uiToken: Long) {
        synchronized(genLock) { if (uiGenToken == uiToken) generating.value = false }
    }

    // ── Token-gated UI mutators ───────────────────────────────────────────
    fun streamUpdate(uiToken: Long, msg: ChatMessage) {
        synchronized(genLock) { if (uiGenToken == uiToken) streamingMessage.value = msg }
    }
    fun loadingChange(uiToken: Long, value: Boolean) {
        synchronized(genLock) { if (uiGenToken == uiToken) isLoading.value = value }
    }
    fun generatingIdChange(uiToken: Long, id: String?) {
        synchronized(genLock) {
            if (uiGenToken != uiToken) return
            if (id != null) {
                generating.value = true
                onActive?.invoke(conversationId)
            } else {
                generating.value = false
                onIdle?.invoke(conversationId)
            }
        }
    }
    fun streamClear(uiToken: Long) {
        synchronized(genLock) {
            if (uiGenToken != uiToken) return
            streamingMessage.value = null
        }
    }

    /** Wired by ChatViewModel to mark this conversation active/idle in the registry. */
    @Volatile var onActive: ((String) -> Unit)? = null
    @Volatile var onIdle: ((String) -> Unit)? = null

    /** Builds the token-gated callbacks for one generation, writing ONLY to this conversation's
     *  private state. The ChatViewModel mirror pipes private→global when this conversation is
     *  open, so the callbacks need no knowledge of the current conversation id. */
    fun callbacksFor(uiToken: Long, persistId: Long): GenerationCallbacks = GenerationCallbacks(
        onStreamUpdate = { streamUpdate(uiToken, it) },
        onLoadingChange = { loadingChange(uiToken, it) },
        onGeneratingIdChange = { generatingIdChange(uiToken, it) },
        onStreamClear = { streamClear(uiToken) },
        isLatestPersist = { isLatestPersist(persistId) },
    )

    // ── Stop / finalization ───────────────────────────────────────────────
    /**
     * Advance the UI-ownership token and commit terminal UI state as one atomic step. Returns
     * the [StopResult] (the stopped message snapshot + a finalization job) so the caller can
     * persist STOPPED to the DB. The caller wires DB/rag callbacks because they depend on repos
     * this class deliberately doesn't hold.
     */
    fun stop(): StopResult = stopInternal(releaseSendGate = true)

    fun stopForReplacement(): StopResult = stopInternal(releaseSendGate = false)

    private fun stopInternal(releaseSendGate: Boolean): StopResult {
        val previousJob = generationJob
        // Hard kill: cancel THIS conversation's in-flight HTTP streams only.
        streamScope.cancelAll()
        previousJob?.cancel()
        val stoppedMsg = synchronized(genLock) {
            uiGenToken += 1
            isLoading.value = false
            val s = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
            streamingMessage.value = s
            generating.value = false
            s
        }
        onIdle?.invoke(conversationId)
        if (releaseSendGate) sendGate.set(false)
        return StopResult(stoppedMsg, conversationId)
    }

    /** Records the stop-finalization job so a subsequent stop can chain onto it. */
    fun setStopFinalizationJob(job: Job?) {
        synchronized(genLock) { stopFinalizationJob = job }
    }

    fun currentStopFinalizationJob(): Job? =
        synchronized(genLock) { stopFinalizationJob?.takeUnless { it.isCompleted } }

    /** Cancel this conversation's scope (called when the conversation is deleted). */
    fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
    }

    /** Append a queued send (generation in progress → enqueue instead of launching). */
    fun enqueueSend(send: QueuedSend) {
        queuedSends.update { it + send }
    }

    /** Pop and return the next queued send, or null if empty. */
    fun dequeueSend(): QueuedSend? {
        var popped: QueuedSend? = null
        queuedSends.update { queue ->
            if (queue.isEmpty()) queue
            else { popped = queue.first(); queue.drop(1) }
        }
        return popped
    }

    /** Remove a queued send by id (X button). Returns true if removed. */
    fun removeQueuedSend(id: String): Boolean {
        var removed = false
        queuedSends.update { queue ->
            val target = queue.firstOrNull { it.id == id }
            if (target != null) { removed = true; queue.filterNot { it.id == id } } else queue
        }
        return removed
    }

    fun clearQueuedSends() {
        queuedSends.value = emptyList()
    }

    data class StopResult(val stoppedMessage: ChatMessage?, val conversationId: String)

    /** Launch a stop-finalization coroutine on this conversation's scope. */
    fun launchFinalization(block: suspend () -> Unit): Job {
        val job = scope.launch {
            try { block() } catch (_: Exception) { /* callers log inside block */ }
        }
        setStopFinalizationJob(job)
        return job
    }
}

/** A message queued behind an in-progress generation, waiting to be sent. */
data class QueuedSend(
    val id: String,
    val text: String,
    val attachmentPaths: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Per-conversation collection of in-flight HTTP streaming handles. [cancelAll] severs only the
 * streams opened under this scope — so a Stop on conversation A no longer kills conversation B's
 * in-flight provider stream (the fix for the global `cancelAllStreams` race).
 */
class StreamScope {
    private val handles = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<com.newoether.agora.api.HttpClient.StreamHandle, Boolean>()
    )

    fun register(handle: com.newoether.agora.api.HttpClient.StreamHandle) {
        handles.add(handle)
    }

    fun cancelAll() {
        handles.toList().forEach { runCatching { it.cancel() } }
        handles.clear()
    }
}
