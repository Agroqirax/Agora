package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/**
 * One per conversation. Owns that conversation's private generation state — the IO scope,
 * current generation job, send gate, streaming/loading UI flows, ownership tokens, and the
 * queued-send list — so two conversations can generate in parallel without their state
 * clobbering each other.
 *
 * Replaces the process-global single-slot generation state that predated per-conversation parallelism.
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
 *  • [persistId] owns the model message's DB row. Advanced when a new generation starts and when
 *    Stop transfers terminal-write ownership to [GenerationFinalizer], so the cancelled provider
 *    coroutine cannot race the dedicated STOPPED transaction.
 *
 * ## Slot lifecycle (acquireForSend / tryAcquireForReplacement / endGeneration / stop)
 *
 * The generation "slot" (the [generating] flag under [genLock]) is the single atomic decision
 * point for launch-vs-enqueue. [acquireForSend] claims it cooperatively (null → enqueue);
 * [tryAcquireForReplacement] claims it only while idle (regenerate/edit are disabled during an
 * active generation); [endGeneration] releases it token-gated when a generation ends; [stop] is
 * a terminal user Stop that fully releases it. Stop cancels ONLY this conversation's
 * [generationJob] and in-flight HTTP streams (via [streamScope]) — never another conversation's.
 */
class ConversationGenerationState(
    val conversationId: String,
    private val onRegistryActive: (String) -> Unit = {},
    private val onRegistryIdle: (String) -> Unit = {},
) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** This conversation's in-flight HTTP streaming handles. Cancelled together on stop. */
    val streamScope: StreamScope = StreamScope()

    // ── Private UI state (mirrored to the global UI flows only while this conversation is open) ──
    val streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val isLoading = MutableStateFlow(false)
    /** True while this conversation has an active generation. Drives the Stop-button visibility. */
    val generating = MutableStateFlow(false)
    /** True from a user Stop until the stopped generation coroutine has fully unwound (its
     *  finally released the slot). Drives the composer's gray "stopping…" spinner. */
    val stopping = MutableStateFlow(false)
    /** True while this conversation is evaluating or executing a Compact boundary. */
    val compacting = MutableStateFlow(false)

    /** Queued sends waiting for the current generation to finish. Per-conversation. */
    val queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())
    /** Serializes durable intervention acceptance against slot release/queue drain. */
    val queueMutationMutex = Mutex()

    // ── Ownership tokens ──
    private val genLock = Any()
    private enum class SlotPhase { IDLE, ACTIVE, STOPPING }
    private var slotPhase = SlotPhase.IDLE
    private var generationJob: Job? = null
    private var uiGenToken = 0L
    /** Token of the generation currently holding the slot; 0 = slot free. Unlike [uiGenToken]
     *  (advanced on every stop to gate late UI writes), this only changes on acquire/release,
     *  so a stopped-but-still-unwinding coroutine can still release the slot it owns. */
    private var slotOwnerToken = 0L
    /** Run owned by the current slot. Bound before provider work or queue acceptance. */
    private var slotRunId: String? = null
    private var suppressNextQueueDrain = false
    /** STOPPING releases only after both the provider coroutine and the durable terminal
     * transaction complete. Whichever finishes first records its half of the barrier. */
    private var stopFinalizationPending = false
    private var stoppedCoroutineUnwound = false
    private val persistId = AtomicLong(0L)

    /** Captures the current UI-ownership token right after a stop, under the lock. */
    fun captureUiToken(): Long = synchronized(genLock) { uiGenToken }
    /** Claims DB-row ownership for a freshly-started generation. */
    fun nextPersistId(): Long = persistId.incrementAndGet()
    /** True while [persistId] still belongs to the generation that captured [id]. */
    fun isLatestPersist(id: Long): Boolean = persistId.get() == id
    /** True while [uiToken] is still the current UI-ownership token (nothing stopped/superseded us). */
    fun isCurrentToken(uiToken: Long): Boolean = synchronized(genLock) { uiGenToken == uiToken }

    fun tryBindRun(uiToken: Long, runId: String): Boolean = synchronized(genLock) {
        require(runId.isNotBlank())
        if (slotOwnerToken != uiToken || slotPhase != SlotPhase.ACTIVE) return false
        val existing = slotRunId
        if (existing != null && existing != runId) return false
        slotRunId = runId
        true
    }

    fun bindRun(uiToken: Long, runId: String) {
        check(tryBindRun(uiToken, runId)) {
            "Only the active slot owner can bind Run $runId"
        }
    }

    fun currentRunId(): String? = synchronized(genLock) { slotRunId }

    // ── Generation slot (single source of truth: [generating] under [genLock]) ────────────
    // Replaces the old `sendGate` AtomicBoolean. The slot is the atomic decision point for
    // "launch now vs enqueue": exactly one generation owns a conversation's tree at a time.

    /**
     * Cooperative claim for a fresh send. If the slot is free, atomically marks this conversation
     * generating (advancing the UI token so any just-finished generation's late callbacks are gated
     * out), flips it active in the registry, and returns the captured token. If a generation is
     * already running, returns null → the caller must enqueue instead of launching (fixes the
     * silent-drop / same-conversation-parallel window: [generating] is now set synchronously here,
     * not deep inside the coroutine).
     */
    fun acquireForSend(): Long? = synchronized(genLock) {
        if (slotPhase != SlotPhase.IDLE) return null
        uiGenToken += 1
        slotOwnerToken = uiGenToken
        slotPhase = SlotPhase.ACTIVE
        stopFinalizationPending = false
        stoppedCoroutineUnwound = false
        generating.value = true
        stopping.value = false
        onRegistryActive(conversationId)
        onActive?.invoke(conversationId)
        uiGenToken
    }

    /**
     * Atomic idle-only claim for regenerate/edit. The UI disables both actions while this
     * conversation is generating, but that visual gate can lag by a frame during a conversation
     * switch; enforcing the same rule here makes the state machine authoritative.
     */
    fun tryAcquireForReplacement(): Long? = synchronized(genLock) {
            if (slotPhase != SlotPhase.IDLE) return null
            uiGenToken += 1
            slotOwnerToken = uiGenToken
            slotPhase = SlotPhase.ACTIVE
            stopFinalizationPending = false
            stoppedCoroutineUnwound = false
            isLoading.value = true
            generating.value = true
            stopping.value = false
            onRegistryActive(conversationId)
            onActive?.invoke(conversationId)
            uiGenToken
    }

    /**
     * Installs the generation Job before it can execute. This closes the launch-assignment race:
     * Stop either sees and cancels this exact Job, or marks the pre-launch slot STOPPING and this
     * method refuses to start it. A completion hook is a final safety net for cancellation that
     * lands after installation but before the LAZY body gets its first instruction.
     */
    fun launchGenerationJob(
        uiToken: Long,
        block: suspend CoroutineScope.() -> Unit,
    ): Job? {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        val installed = java.util.concurrent.atomic.AtomicBoolean(false)
        job.invokeOnCompletion {
            if (installed.get() && endGeneration(uiToken)) {
                // A cancelled-before-start coroutine cannot reach the controller's finally.
                onQueueDrainRequested?.invoke(this)
            }
        }

        val accepted = synchronized(genLock) {
            if (
                slotOwnerToken != uiToken ||
                slotPhase != SlotPhase.ACTIVE ||
                generationJob != null
            ) {
                false
            } else {
                generationJob = job
                installed.set(true)
                true
            }
        }
        if (!accepted) {
            job.cancel()
            val abandonedStoppingLaunch = synchronized(genLock) {
                slotOwnerToken == uiToken &&
                    slotPhase == SlotPhase.STOPPING &&
                    generationJob == null
            }
            if (abandonedStoppingLaunch && endGeneration(uiToken)) {
                onQueueDrainRequested?.invoke(this)
            }
            return null
        }
        job.start()
        return job
    }

    /**
     * Attaches a generation coroutine owned by an external process-scoped runner. Background
     * Task/Loop execution cannot be launched in [scope] because its caller must suspend until the
     * durable result is known, but Stop still has to cancel the exact worker coroutine and its
     * streams. The same install-before-work invariant as [launchGenerationJob] applies.
     */
    fun attachGenerationJob(uiToken: Long, job: Job): Boolean {
        val installed = java.util.concurrent.atomic.AtomicBoolean(false)
        job.invokeOnCompletion {
            if (installed.get() && endGeneration(uiToken)) {
                onQueueDrainRequested?.invoke(this)
            }
        }
        return synchronized(genLock) {
            if (
                slotOwnerToken != uiToken ||
                slotPhase != SlotPhase.ACTIVE ||
                generationJob != null
            ) {
                false
            } else {
                generationJob = job
                installed.set(true)
                true
            }
        }
    }

    /**
     * Owner-token-gated release of the slot when a generation coroutine finishes (normally OR
     * after a Stop — [stop] deliberately does not free the slot, see there). Only the owning
     * coroutine's finally releases, so a coroutine superseded in an earlier era is a no-op.
     * Returns true if this call actually released (i.e. the caller may now drain the queue —
     * the release point is by construction the moment the conversation lock is free again).
     */
    fun endGeneration(uiToken: Long): Boolean {
        var settledAfterStop = false
        val mayDrainQueue = synchronized(genLock) {
            if (slotOwnerToken != uiToken) return false
            if (slotPhase == SlotPhase.STOPPING) {
                stoppedCoroutineUnwound = true
                // The durable half has not landed yet; that writer releases the slot instead.
                if (stopFinalizationPending) return false
                // Both halves of the Stop barrier are done, so this call owns the release. It must
                // NOT report a normal drain: the pending inputs still belong to the STOPPED Run and
                // have to be migrated to a fresh one first. Only the onStopSettled path does that,
                // so route the post-stop drain there regardless of which half finished last.
                releaseSlotLocked()
                settledAfterStop = true
                false
            } else {
                releaseSlotLocked()
            }
        }
        // Outside the lock: the callback drains on another coroutine and re-enters this monitor.
        if (settledAfterStop) onStopSettled?.invoke(this)
        return mayDrainQueue
    }

    private fun releaseSlotLocked(): Boolean {
        slotPhase = SlotPhase.IDLE
        slotOwnerToken = 0L
        slotRunId = null
        generationJob = null
        stopFinalizationPending = false
        stoppedCoroutineUnwound = false
        isLoading.value = false
        generating.value = false
        stopping.value = false
        onRegistryIdle(conversationId)
        onIdle?.invoke(conversationId)
        return true
    }

    /** Stop is an atomic Run cutoff: accepted but unconsumed inputs stay in the stopped Run. */
    /**
     * Defers exactly the next automatic queue drain. A boundary send that failed before its batch
     * became durable keeps the guidance for a later boundary without immediately retrying itself
     * from the current generation's finally block.
     */
    fun deferNextQueueDrain() = synchronized(genLock) {
        suppressNextQueueDrain = true
    }

    fun consumeQueueDrainPermission(): Boolean = synchronized(genLock) {
        val allowed = !suppressNextQueueDrain
        suppressNextQueueDrain = false
        allowed
    }

    // ── Token-gated UI mutators ───────────────────────────────────────────
    fun streamUpdate(uiToken: Long, msg: ChatMessage) {
        synchronized(genLock) { if (uiGenToken == uiToken) streamingMessage.value = msg }
    }
    fun loadingChange(uiToken: Long, value: Boolean) {
        synchronized(genLock) { if (uiGenToken == uiToken) isLoading.value = value }
    }
    fun streamClear(uiToken: Long) {
        synchronized(genLock) {
            if (uiGenToken != uiToken) return
            val message = streamingMessage.value
            // A user Stop deliberately keeps the STOPPED overlay until Room has persisted it.
            // Normal completion must commit the final in-memory message before removing the
            // overlay, otherwise the UI briefly falls back to the empty SENDING placeholder.
            if (message?.status != MessageStatus.STOPPED) {
                if (message != null) onStreamCommit?.invoke(conversationId, message)
                streamingMessage.value = null
            }
        }
    }

    /** Wired by ChatViewModel to mark this conversation active/idle in the registry and to commit
     * the final streaming message into the currently open conversation before overlay removal. */
    @Volatile var onActive: ((String) -> Unit)? = null
    @Volatile var onIdle: ((String) -> Unit)? = null
    @Volatile var onStreamCommit: ((String, ChatMessage) -> Unit)? = null
    /** Fired when a process-owned generation (rather than the UI controller) releases normally. */
    @Volatile var onQueueDrainRequested: ((ConversationGenerationState) -> Unit)? = null
    /** Fired after a Stop cleanly settles (durable STOPPED row persisted + slot released).
     *  The controller wires this to drain queued sends into a fresh Run. */
    @Volatile var onStopSettled: ((ConversationGenerationState) -> Unit)? = null

    /** Builds the token-gated callbacks for one generation, writing ONLY to this conversation's
     *  private state. The ChatViewModel mirror pipes private→global when this conversation is
     *  open, so the callbacks need no knowledge of the current conversation id. */
    fun callbacksFor(uiToken: Long, persistId: Long): GenerationCallbacks = GenerationCallbacks(
        onStreamUpdate = { streamUpdate(uiToken, it) },
        onLoadingChange = { loadingChange(uiToken, it) },
        onStreamClear = { streamClear(uiToken) },
        isLatestPersist = { isLatestPersist(persistId) },
        // Steering: lets the tool loop see a mid-generation queued send and end at the next
        // round boundary, so the queue flushes without waiting out the whole tool loop.
        hasQueuedSends = { queuedSends.value.isNotEmpty() },
    )

    // ── Stop / finalization ───────────────────────────────────────────────
    /**
     * Terminal stop request (Stop button, or a delete that lands inside the generating
     * conversation). Cancels ONLY this conversation's job + in-flight HTTP streams, advances the
     * UI token, and commits STOPPED to the streaming snapshot. The cancelled coroutine retains
     * the slot until its finally block releases it. Regenerate/edit never call Stop; they can
     * claim only an idle slot through [tryAcquireForReplacement].
     */
    fun stop(): StopResult {
        val previousJob: Job?
        val result = synchronized(genLock) {
            previousJob = generationJob
            val boundRunId = slotRunId
            if (slotPhase == SlotPhase.IDLE) {
                return@synchronized StopResult(
                    stoppedMessage = null,
                    conversationId = conversationId,
                    runId = null,
                    shouldFinalize = false,
                )
            }
            if (slotPhase == SlotPhase.STOPPING) {
                return@synchronized StopResult(
                    stoppedMessage = streamingMessage.value,
                    conversationId = conversationId,
                    runId = boundRunId,
                    shouldFinalize = false,
                )
            }
            // Queue drain after stop is normal — the user explicitly asked for it.
            // Revoke DB ownership before cancellation can enter GenerationManager.finally. The
            // stopped coroutine therefore skips its normal NonCancellable terminal upsert; the
            // dedicated stop finalizer below is the only terminal writer.
            persistId.incrementAndGet()
            uiGenToken += 1
            val s = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
            streamingMessage.value = s
            // Accepted interventions survive Stop: the stop finalizer drains them into a fresh
            // Run after the STOPPED row is durably persisted. See drainQueuedAfterStop.
            if (slotPhase != SlotPhase.IDLE) {
                slotPhase = SlotPhase.STOPPING
                stopFinalizationPending = s != null || boundRunId != null
                stoppedCoroutineUnwound = previousJob == null || previousJob.isCompleted
                // STOPPING remains occupied for every tree-mutation/UI gate until the old
                // coroutine and the durable terminal transaction have both completed.
                isLoading.value = true
                generating.value = true
                stopping.value = true
                if (stoppedCoroutineUnwound && !stopFinalizationPending) releaseSlotLocked()
            }
            StopResult(
                stoppedMessage = s,
                conversationId = conversationId,
                runId = boundRunId,
                shouldFinalize = s != null || boundRunId != null,
            )
        }
        // Hard kill after the ownership cutoff: synchronous cancellation handles wake blocking
        // HTTP/native reads immediately, then Job cancellation tears down every remaining child.
        streamScope.cancelAll()
        previousJob?.cancel()
        return result
    }

    /**
     * Completes the durable half of the Stop barrier. A failed terminal write deliberately keeps
     * STOPPING occupied: the unique live-Run slot is still unavailable, so reporting IDLE would
     * make the next Send fail or attach to the doomed Run.
     */
    fun finishStopFinalization(success: Boolean): Boolean {
        val settled = synchronized(genLock) {
            if (!success) return false
            // Records the durable half. Clearing this before the unwound check is intentional and
            // safe: when the coroutine unwinds afterwards it releases the slot itself and routes
            // the drain through onStopSettled, so neither ordering can reuse the STOPPED Run.
            stopFinalizationPending = false
            if (slotPhase != SlotPhase.STOPPING || !stoppedCoroutineUnwound) return false
            // The controller's releaseAndDrain already returned while waiting for this finalizer, so
            // no matching queue-drain decision remains to consume the Stop suppression.
            suppressNextQueueDrain = false
            releaseSlotLocked()
            true
        }
        // Fire OUTSIDE the lock so the callback can safely inspect state without deadlocking.
        if (settled) onStopSettled?.invoke(this)
        return settled
    }

    /**
     * Clears a lingering STOPPED streaming snapshot. [stop] deliberately leaves the STOPPED
     * overlay in place until Room has persisted it (see [streamClear]); this is the matching
     * release, invoked once stop-finalization has written the row. Without it the stale overlay
     * survives indefinitely and [ConversationUiState.resolvePath] can re-append it as a ghost
     * after the persisted message is deleted.
     */
    fun clearStoppedOverlay() {
        synchronized(genLock) {
            if (streamingMessage.value?.status == MessageStatus.STOPPED) {
                streamingMessage.value = null
            }
        }
    }

    /** Cancel this conversation's scope (called when the conversation is deleted). */
    fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
    }

    /** Append a queued send (generation in progress → enqueue instead of launching). */
    fun enqueueSend(send: QueuedSend) {
        queuedSends.update { it + send }
    }

    /**
     * Remove a queued send by id (X button). Returns the removed item (or null) so the caller can
     * delete its now-orphaned attachment files — the composer already cleared its own reference on
     * enqueue, so the QueuedSend holds the only handle to those copied files.
     */
    fun removeQueuedSend(id: String): QueuedSend? {
        val before = queuedSends.getAndUpdate { queue -> queue.filterNot { it.id == id } }
        return before.firstOrNull { it.id == id }
    }

    /** Atomically take the whole queue for a batch drain (each item becomes its own bubble). */
    fun takeQueuedSends(): List<QueuedSend> = queuedSends.getAndUpdate { emptyList() }

    /** Push [items] back to the FRONT in order (a batch drain lost the slot race to a manual
     *  send — nothing is lost, the batch just waits for the next release). */
    fun requeueFront(items: List<QueuedSend>) {
        queuedSends.update { items + it }
    }

    data class StopResult(
        val stoppedMessage: ChatMessage?,
        val conversationId: String,
        val runId: String?,
        val shouldFinalize: Boolean,
    )

}

/**
 * A message queued behind an in-progress generation, waiting to be sent. Carries the full
 * [SelectedAttachment] list for the queue banner. It is deliberately not a MessageEntity yet:
 * Room and the selected tree first see it at the next durable tool/generation boundary.
 */
data class QueuedSend(
    val id: String,
    val text: String,
    /** Model selected in the originating conversation when Send was tapped. */
    val modelId: String,
    val attachments: List<SelectedAttachment>,
    /** Run that was ACTIVE when this guidance was accepted. It is only a boundary hint. */
    val runId: String,
    /** Legacy bare-image paths retained for queue display and cleanup. */
    val images: List<String> = emptyList(),
    /** Prepared payload owned by this in-memory guidance until its boundary commit. */
    val preparedImages: List<String> = emptyList(),
    val preparedAttachmentMetaJson: String? = null,
    val preparedOwnedPaths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Per-conversation collection of in-flight HTTP streaming handles. [cancelAll] severs only the
 * streams opened under this scope — so a Stop on conversation A no longer kills conversation B's
 * in-flight provider stream (the fix for the global `cancelAllStreams` race).
 */
fun interface GenerationCancelHandle {
    fun cancel()
}

class StreamScope {
    private val handles = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<GenerationCancelHandle, Boolean>()
    )

    fun register(handle: GenerationCancelHandle) {
        handles.add(handle)
    }

    fun unregister(handle: GenerationCancelHandle) {
        handles.remove(handle)
    }

    fun cancelAll() {
        handles.toList().forEach { runCatching { it.cancel() } }
        handles.clear()
    }
}
