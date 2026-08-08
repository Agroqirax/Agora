package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.ConversationRuntimeTrace
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.SlotReleaseReason
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
 * The reducer-backed generation slot under [genLock] is the single atomic decision point for
 * launch-vs-enqueue. [acquireForSend] claims it cooperatively (null → enqueue);
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
    /** True from a user Stop until both coroutine and durable finalization barriers settle.
     *  Drives the composer's gray "stopping…" spinner. */
    val stopping = MutableStateFlow(false)
    /** True while this conversation is evaluating or executing a Compact boundary. */
    val compacting = MutableStateFlow(false)

    /** Queued sends waiting for the current generation to finish. Per-conversation. */
    val queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())
    /** Serializes durable intervention acceptance against slot release/queue drain. */
    val queueMutationMutex = Mutex()

    // ── Ownership tokens ──
    private val genLock = Any()
    /** Authoritative process-slot state. All mutations go through [ConversationRuntimeReducer]. */
    private var runState: RunState = RunState.Idle(conversationId)
    private val runtimeTrace = ConversationRuntimeTrace()
    private var generationJob: Job? = null
    private var uiGenToken = 0L
    /** One-shot suppression used only by failed queue-boundary recovery. */
    private var suppressNextQueueDrain = false
    private val persistId = AtomicLong(0L)

    /** Captures the current UI-ownership token right after a stop, under the lock. */
    fun captureUiToken(): Long = synchronized(genLock) { uiGenToken }
    /** Claims DB-row ownership for a freshly-started generation. */
    fun nextPersistId(): Long = persistId.incrementAndGet()
    /** True while [persistId] still belongs to the generation that captured [id]. */
    fun isLatestPersist(id: Long): Boolean = persistId.get() == id
    /** True while [uiToken] is still the current UI-ownership token (nothing stopped/superseded us). */
    fun isCurrentToken(uiToken: Long): Boolean = synchronized(genLock) { uiGenToken == uiToken }

    fun tryBindRun(uiToken: Long, runId: String, pass: Int = 0): Boolean = synchronized(genLock) {
        require(runId.isNotBlank())
        require(pass >= 0)
        val transition = reduceLocked(
            ConversationCommand.BindRun(
                RuntimeRunIdentity(
                    conversationId = conversationId,
                    ownerToken = uiToken,
                    runId = runId,
                    pass = pass,
                ),
            ),
        )
        if (!transition.accepted) return false
        true
    }

    fun bindRun(uiToken: Long, runId: String, pass: Int = 0) {
        check(tryBindRun(uiToken, runId, pass)) {
            "Only the active slot owner can bind Run $runId"
        }
    }

    fun currentRunId(): String? = synchronized(genLock) { runState.identityOrNull()?.runId }

    // ── Generation slot (single source of truth: [runState] under [genLock]) ─────────────
    // The reducer-backed slot is the atomic decision point for "launch now vs enqueue": exactly
    // one generation owns a conversation's tree at a time.

    /**
     * Cooperative claim for a fresh send. If the slot is free, atomically marks this conversation
     * generating (advancing the UI token so any just-finished generation's late callbacks are gated
     * out), flips it active in the registry, and returns the captured token. If a generation is
     * already running, returns null → the caller must enqueue instead of launching (fixes the
     * silent-drop / same-conversation-parallel window: [generating] is now set synchronously here,
     * not deep inside the coroutine).
    */
    fun acquireForSend(): Long? = synchronized(genLock) {
        val nextToken = uiGenToken + 1
        val transition = reduceLocked(
            ConversationCommand.AcquireSlot(
                RuntimeRunIdentity(conversationId = conversationId, ownerToken = nextToken),
            ),
        )
        if (!transition.accepted) return null
        check(transition.effects.singleOrNull() is RunEffect.SlotActivated)
        uiGenToken = nextToken
        generating.value = true
        stopping.value = false
        onRegistryActive(conversationId)
        onActive?.invoke(conversationId)
        nextToken
    }

    /**
     * Atomic idle-only claim for regenerate/edit. The UI disables both actions while this
     * conversation is generating, but that visual gate can lag by a frame during a conversation
     * switch; enforcing the same rule here makes the state machine authoritative.
    */
    fun tryAcquireForReplacement(): Long? = synchronized(genLock) {
        val nextToken = uiGenToken + 1
        val transition = reduceLocked(
            ConversationCommand.AcquireSlot(
                RuntimeRunIdentity(conversationId = conversationId, ownerToken = nextToken),
            ),
        )
        if (!transition.accepted) return null
        check(transition.effects.singleOrNull() is RunEffect.SlotActivated)
        uiGenToken = nextToken
        isLoading.value = true
        generating.value = true
        stopping.value = false
        onRegistryActive(conversationId)
        onActive?.invoke(conversationId)
        nextToken
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
                !runState.isActiveOwner(uiToken) ||
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
                runState.isStoppingOwner(uiToken) &&
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
                !runState.isActiveOwner(uiToken) ||
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
            val currentIdentity = runState.identityOrNull()
            val commandIdentity = currentIdentity
                ?.takeIf { it.ownerToken == uiToken }
                ?: RuntimeRunIdentity(conversationId = conversationId, ownerToken = uiToken)
            val transition = reduceLocked(
                ConversationCommand.CoroutineSettled(commandIdentity),
            )
            if (!transition.accepted) return false
            val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()
            when (release?.reason) {
                SlotReleaseReason.NORMAL_COMPLETION -> {
                    applyReleasedSlotLocked()
                    true
                }
                SlotReleaseReason.STOP_BARRIERS_SETTLED -> {
                    // Pending inputs still belong to the STOPPED Run and must migrate to a fresh
                    // one, so only the post-Stop callback may drain them.
                    applyReleasedSlotLocked()
                    settledAfterStop = true
                    false
                }
                SlotReleaseReason.EMPTY_STOP -> error("Coroutine settlement cannot emit EMPTY_STOP")
                null -> false
            }
        }
        // Outside the lock: the callback drains on another coroutine and re-enters this monitor.
        if (settledAfterStop) onStopSettled?.invoke(this)
        return mayDrainQueue
    }

    /** Applies UI/resource release after the reducer has already transitioned to [RunState.Idle]. */
    private fun applyReleasedSlotLocked() {
        check(runState is RunState.Idle)
        generationJob = null
        isLoading.value = false
        generating.value = false
        stopping.value = false
        onRegistryIdle(conversationId)
        onIdle?.invoke(conversationId)
    }

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
            val currentState = runState
            if (currentState is RunState.Idle) {
                return@synchronized StopResult(
                    stoppedMessage = null,
                    conversationId = conversationId,
                    runId = null,
                    finalizationEffect = null,
                )
            }
            if (currentState is RunState.Stopping) {
                return@synchronized StopResult(
                    stoppedMessage = streamingMessage.value,
                    conversationId = conversationId,
                    runId = currentState.identity.runId,
                    finalizationEffect = null,
                )
            }
            check(currentState is RunState.Active)
            val identity = currentState.identity
            val boundRunId = identity.runId
            val s = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
            check(s == null || boundRunId != null) {
                "A streaming Stop effect requires a bound Run"
            }
            val requiresPersistence = boundRunId != null
            val effectId = if (requiresPersistence) "stop-${identity.ownerToken}" else null
            val transition = reduceLocked(
                ConversationCommand.StopRequested(
                    identity = identity,
                    coroutineAlreadySettled = previousJob == null || previousJob.isCompleted,
                    requiresPersistence = requiresPersistence,
                    effectId = effectId,
                ),
            )
            check(transition.accepted)
            check(transition.effects.any { it is RunEffect.CancelProviderPass })
            // Queue drain after stop is normal — the user explicitly asked for it.
            // Revoke DB ownership before cancellation can enter GenerationManager.finally. The
            // stopped coroutine therefore skips its normal NonCancellable terminal upsert; the
            // dedicated stop finalizer below is the only terminal writer.
            persistId.incrementAndGet()
            uiGenToken += 1
            streamingMessage.value = s
            // Accepted interventions survive Stop: the stop finalizer drains them into a fresh
            // Run after the STOPPED row is durably persisted. See drainQueuedAfterStop.
            if (runState is RunState.Stopping) {
                // STOPPING remains occupied for every tree-mutation/UI gate until the reducer has
                // accepted both the coroutine and durable terminal result for this identity.
                isLoading.value = true
                generating.value = true
                stopping.value = true
            } else {
                val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().single()
                check(release.reason == SlotReleaseReason.EMPTY_STOP)
                applyReleasedSlotLocked()
            }
            StopResult(
                stoppedMessage = s,
                conversationId = conversationId,
                runId = boundRunId,
                finalizationEffect = transition.effects
                    .filterIsInstance<RunEffect.FinalizeStop>()
                    .singleOrNull(),
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
    fun finishStopFinalization(
        command: ConversationCommand.PersistenceSettled,
    ): StopFinalizationOutcome {
        var settled = false
        val outcome = synchronized(genLock) {
            val transition = reduceLocked(command)
            if (!transition.accepted) return@synchronized StopFinalizationOutcome.REJECTED
            if (!command.success) return@synchronized StopFinalizationOutcome.FAILED

            val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()
            if (release == null) return@synchronized StopFinalizationOutcome.RECORDED
            check(release.reason == SlotReleaseReason.STOP_BARRIERS_SETTLED)
            // The controller's releaseAndDrain already returned while waiting for this finalizer, so
            // no matching queue-drain decision remains to consume the Stop suppression.
            suppressNextQueueDrain = false
            applyReleasedSlotLocked()
            settled = true
            StopFinalizationOutcome.SETTLED
        }
        // Fire OUTSIDE the lock so the callback can safely inspect state without deadlocking.
        if (settled) onStopSettled?.invoke(this)
        return outcome
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
        val finalizationEffect: RunEffect.FinalizeStop?,
    )

    enum class StopFinalizationOutcome {
        /** Old, duplicate, wrong-Run, wrong-pass, or otherwise illegal callback. */
        REJECTED,
        /** Current finalization effect failed; STOPPING remains occupied. */
        FAILED,
        /** Durable barrier recorded; coroutine barrier is still pending. */
        RECORDED,
        /** Both barriers settled and the slot was released. */
        SETTLED;

        val accepted: Boolean get() = this != REJECTED
    }

    private fun RunState.identityOrNull(): RuntimeRunIdentity? = when (this) {
        is RunState.Idle -> null
        is RunState.Active -> identity
        is RunState.Stopping -> identity
    }

    private fun RunState.isActiveOwner(ownerToken: Long): Boolean =
        this is RunState.Active && identity.ownerToken == ownerToken

    private fun RunState.isStoppingOwner(ownerToken: Long): Boolean =
        this is RunState.Stopping && identity.ownerToken == ownerToken

    /** Privacy-safe bounded trace for diagnostics/tests; contains no prompt or message content. */
    internal fun runtimeTraceSnapshot(): List<ConversationRuntimeTraceEntry> = runtimeTrace.snapshot()

    /** Must be called while [genLock] is held. This is the only process-slot state write path. */
    private fun reduceLocked(command: ConversationCommand): com.newoether.agora.model.Transition {
        val oldState = runState
        val transition = ConversationRuntimeReducer.reduce(oldState, command)
        runtimeTrace.record(oldState, command, transition)
        if (transition.accepted) runState = transition.newState
        return transition
    }

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
