package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CompactMode
import com.newoether.agora.model.CompactOutcome
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.ConversationRuntimeTrace
import com.newoether.agora.model.ConversationRuntimeTraceEntry
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.SlotReleaseReason
import com.newoether.agora.model.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

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
 * ## Slot lifecycle (requestSend / replacement compatibility / endGeneration / stop)
 *
 * Ordinary foreground Send, queued-guidance placement, and headless Task/Loop Send enter
 * [requestSend]'s sequential mailbox. [acquireForSend] remains only as a legacy test adapter;
 * [tryAcquireForReplacement] remains the idle-only regenerate/edit adapter. [endGeneration] and
 * [stop] submit through the same mailbox. [endGeneration] releases token-gated ownership; Stop
 * establishes the terminal cutoff, then cancels only this conversation's [generationJob] and
 * in-flight HTTP streams (via [streamScope]).
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
    private val guidanceLock = Any()
    private val claimedGuidance = mutableMapOf<String, List<QueuedSend>>()
    private var guidanceDisposed = false
    /** Serializes durable intervention acceptance against slot release/queue drain. */
    val queueMutationMutex = Mutex()

    // ── Ownership tokens ──
    private val genLock = Any()
    /** Authoritative process-slot state. All mutations go through [ConversationRuntimeReducer]. */
    private var runState: RunState = RunState.Idle(conversationId)
    private val runtimeTrace = ConversationRuntimeTrace()
    private var generationJob: Job? = null
    private var uiGenToken = 0L
    /** Foreground/headless Send, Stop, tool-effect, and Compact lifecycle commands enter here. */
    private val commandMailbox = ConversationCommandMailbox(scope, ::reduceMailboxCommand)
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

    /**
     * Submit one ordinary foreground Send placement decision to this conversation's mailbox.
     * Cancellation before the caller receives a direct claim emits an identified abandonment
     * command, so a Preparing slot cannot be stranded without a generation Job.
     */
    suspend fun requestSend(
        proposedRunId: String,
        effectId: String,
        directOnly: Boolean,
        hasPendingGuidance: Boolean,
    ): Transition {
        require(proposedRunId.isNotBlank())
        require(effectId.isNotBlank())
        return commandMailbox.submit(
            commandFactory = ConversationCommandFactory {
                ConversationCommand.SendRequested(
                    identity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = uiGenToken + 1,
                        runId = proposedRunId,
                        pass = 0,
                        effectId = effectId,
                    ),
                    directOnly = directOnly,
                    hasPendingGuidance = hasPendingGuidance,
                )
            },
            cancellationCommand = { transition ->
                transition.effects
                    .filterIsInstance<RunEffect.PersistAcceptedInput>()
                    .singleOrNull()
                    ?.let { effect -> ConversationCommand.SendLaunchAbandoned(effect.identity) }
            },
        )
    }

    /** Echo the exact Room acceptance effect back through the mailbox. */
    suspend fun inputPersisted(identity: RunEffectIdentity): Boolean = commandMailbox.submit(
        ConversationCommandFactory { ConversationCommand.InputPersisted(identity) },
    ).accepted

    /** Report failure of the exact accepted-input persistence effect without releasing its Job. */
    suspend fun inputPersistenceFailed(identity: RunEffectIdentity): Boolean = commandMailbox.submit(
        ConversationCommandFactory { ConversationCommand.InputPersistenceFailed(identity) },
    ).accepted

    /** Release an exact direct-Send claim that could not install any owning coroutine. */
    suspend fun abandonSendLaunch(identity: RunEffectIdentity): Boolean = commandMailbox.submit(
        ConversationCommandFactory { ConversationCommand.SendLaunchAbandoned(identity) },
    ).accepted

    /** Authorize one exact validated Provider tool batch. */
    suspend fun requestToolBatch(
        providerOutcomeIdentity: RunEffectIdentity,
    ): RunEffect.ExecuteToolBatch? = withContext(NonCancellable) {
        commandMailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolBatchRequested(providerOutcomeIdentity)
            },
        ).effects.filterIsInstance<RunEffect.ExecuteToolBatch>().singleOrNull()
    }

    /** Close one exact tool batch and authorize its atomic protocol-round commit. */
    suspend fun completeToolBatch(
        batchIdentity: RunEffectIdentity,
    ): RunEffect.CommitToolRound? = withContext(NonCancellable) {
        commandMailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolBatchCompleted(batchIdentity)
            },
        ).effects.filterIsInstance<RunEffect.CommitToolRound>().singleOrNull()
    }

    /** Echo the exact Room tool-round result; only success authorizes another Provider pass. */
    suspend fun finishToolRoundCommit(
        commitIdentity: RunEffectIdentity,
        success: Boolean,
    ): RunEffect? = withContext(NonCancellable) {
        commandMailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolRoundCommitted(commitIdentity, success)
            },
        ).effects.singleOrNull()
    }

    /** Authorize exactly one Provider pass for the current Run/pass. */
    suspend fun requestProviderPass(
        identity: RunEffectIdentity,
    ): RunEffect.StartProviderPass? = commandMailbox.submit(
        commandFactory = ConversationCommandFactory {
            ConversationCommand.ProviderPassRequested(identity)
        },
        cancellationCommand = { transition ->
            transition.effects.filterIsInstance<RunEffect.StartProviderPass>()
                .singleOrNull()
                ?.let { effect ->
                    ConversationCommand.ProviderPassCompleted(
                        effect.identity,
                        ProviderPassResult.CANCELLED,
                    )
                }
        },
    ).effects.filterIsInstance<RunEffect.StartProviderPass>().singleOrNull()

    /** Accept the closed semantic outcome of the exact currently-running Provider pass. */
    suspend fun finishProviderPass(
        identity: RunEffectIdentity,
        result: ProviderPassResult,
    ): RunEffect.ProviderPassAccepted? = withContext(NonCancellable) {
        commandMailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ProviderPassCompleted(identity, result)
            },
        ).effects.filterIsInstance<RunEffect.ProviderPassAccepted>().singleOrNull()
    }

    /** Move the exact active Run into mailbox-owned normal finalization. */
    suspend fun requestRunFinalization(
        identity: RunEffectIdentity,
        status: RunStatus,
        reason: RunEndReason,
        markConversationUnread: Boolean,
    ): RunEffect.FinalizeRun? = withContext(NonCancellable) {
        commandMailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.FinalizationRequested(
                    identity = identity,
                    status = status,
                    reason = reason,
                    markConversationUnread = markConversationUnread,
                )
            },
        ).effects.filterIsInstance<RunEffect.FinalizeRun>().singleOrNull()
    }

    /** Echo the exact Room finalization result; release requires both durable and Job barriers. */
    suspend fun finishRunFinalization(
        identity: RunEffectIdentity,
        success: Boolean,
    ): RunFinalizationOutcome = withContext(NonCancellable) {
        val transition = commandMailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.FinalizationCompleted(identity, success)
            },
        )
        if (!transition.accepted) return@withContext RunFinalizationOutcome.REJECTED
        if (!success) return@withContext RunFinalizationOutcome.FAILED
        val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()
            ?: return@withContext RunFinalizationOutcome.RECORDED
        check(release.reason == SlotReleaseReason.NORMAL_FINALIZATION_SETTLED)
        // Fire after mailbox handling and outside [genLock].
        onQueueDrainRequested?.invoke(this@ConversationGenerationState)
        RunFinalizationOutcome.SETTLED
    }

    /** Claim an idle-only manual Compact without presenting it as a generation Run. */
    suspend fun requestManualCompact(
        compactRunId: String,
        effectId: String,
    ): RunEffect.RunCompact? {
        require(compactRunId.isNotBlank())
        require(effectId.isNotBlank())
        return commandMailbox.submit(
            commandFactory = ConversationCommandFactory {
                ConversationCommand.CompactRequested(
                    identity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = (uiGenToken + 1).coerceAtLeast(1),
                        runId = compactRunId,
                        pass = 0,
                        effectId = effectId,
                    ),
                    compactRunId = compactRunId,
                    mode = CompactMode.MANUAL,
                )
            },
            cancellationCommand = { transition ->
                transition.effects.filterIsInstance<RunEffect.RunCompact>()
                    .singleOrNull()
                    ?.let { effect ->
                        ConversationCommand.CompactCompleted(
                            effect.identity,
                            CompactOutcome.FAILED,
                        )
                    }
            },
        ).effects.filterIsInstance<RunEffect.RunCompact>().singleOrNull()
    }

    /** Claim an automatic Compact for the exact currently-active Run/pass. */
    suspend fun requestAutomaticCompact(
        compactRunId: String,
        effectId: String,
    ): RunEffect.RunCompact? {
        require(compactRunId.isNotBlank())
        require(effectId.isNotBlank())
        return commandMailbox.submit(
            commandFactory = ConversationCommandFactory {
                val currentIdentity = runState.identityOrNull()
                val effectIdentity = if (currentIdentity?.runId != null) {
                    currentIdentity.effectIdentity(effectId)
                } else {
                    RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = (uiGenToken + 1).coerceAtLeast(1),
                        runId = "unbound_$compactRunId",
                        pass = 0,
                        effectId = effectId,
                    )
                }
                ConversationCommand.CompactRequested(
                    identity = effectIdentity,
                    compactRunId = compactRunId,
                    mode = CompactMode.AUTOMATIC,
                )
            },
            cancellationCommand = { transition ->
                transition.effects.filterIsInstance<RunEffect.RunCompact>()
                    .singleOrNull()
                    ?.let { effect ->
                        ConversationCommand.CompactCompleted(
                            effect.identity,
                            CompactOutcome.FAILED,
                        )
                    }
            },
        ).effects.filterIsInstance<RunEffect.RunCompact>().singleOrNull()
    }

    /** Settle one exact Compact result; stale, duplicate, and post-Stop results are rejected. */
    suspend fun finishCompact(
        identity: RunEffectIdentity,
        outcome: CompactOutcome,
    ): Transition = commandMailbox.submit(
        ConversationCommandFactory {
            ConversationCommand.CompactCompleted(identity, outcome)
        },
    )

    /** Wait until neither a generation nor an idle manual Compact owns this conversation. */
    suspend fun awaitSendAvailable() {
        combine(generating, compacting) { isGenerating, isCompacting ->
            !isGenerating && !isCompacting
        }.first { available -> available }
    }

    /** Wait only for Compact settlement, then let the mailbox re-evaluate Idle versus Active. */
    suspend fun awaitCompactSettled() {
        compacting.first { isCompacting -> !isCompacting }
    }

    // ── Generation slot (single source of truth: [runState] under [genLock]) ─────────────
    // The reducer-backed slot is the atomic decision point for "launch now vs enqueue": exactly
    // one generation owns a conversation's tree at a time.

    /**
     * Legacy test/setup claim. Production Send/Task/Loop paths use [requestSend]. If the slot is
     * free, marks this conversation
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
        applyActivatedSlotLocked(transition.newState.identityOrNull()!!, loading = false)
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
        applyActivatedSlotLocked(transition.newState.identityOrNull()!!, loading = true)
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
        val installed = AtomicBoolean(false)
        job.invokeOnCompletion {
            if (installed.get()) settleCoroutineAsync(uiToken)
        }

        val accepted = synchronized(genLock) {
            if (
                !runState.isLaunchableOwner(uiToken) ||
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
            if (abandonedStoppingLaunch) settleCoroutineAsync(uiToken)
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
        val installed = AtomicBoolean(false)
        job.invokeOnCompletion {
            if (installed.get()) settleCoroutineAsync(uiToken)
        }
        val accepted = synchronized(genLock) {
            if (
                !runState.isLaunchableOwner(uiToken) ||
                generationJob != null
            ) {
                false
            } else {
                generationJob = job
                installed.set(true)
                true
            }
        }
        // An external Job can complete between hook registration and installation. The hook sees
        // installed=false in that race, so this post-install check supplies the result. A duplicate
        // delivery from the opposite race is harmless because reducer identity rejects it.
        if (accepted && job.isCompleted) settleCoroutineAsync(uiToken)
        return accepted
    }

    /**
     * Owner-token-gated release of the slot when a generation coroutine finishes (normally OR
     * after a Stop — [stop] deliberately does not free the slot, see there). Only the installed
     * Job's completion hook reports settlement, so a coroutine superseded in an earlier era is a
     * no-op.
     * A bound durable Run cannot release from this signal alone: normal finalization or Stop must
     * also settle. Returns true only if this command actually emitted the release effect (i.e. the
     * caller may now drain the queue).
     */
    suspend fun endGeneration(uiToken: Long): Boolean = withContext(NonCancellable) {
        require(uiToken > 0)
        val transition = commandMailbox.submit(
            ConversationCommandFactory {
                check(generationJob?.isCompleted != false) {
                    "CoroutineSettled requires the installed generation Job to be completed"
                }
                val currentIdentity = runState.identityOrNull()
                val commandIdentity = currentIdentity
                    ?.takeIf { it.ownerToken == uiToken }
                    ?: RuntimeRunIdentity(conversationId = conversationId, ownerToken = uiToken)
                ConversationCommand.CoroutineSettled(commandIdentity)
            },
        )
        if (!transition.accepted) return@withContext false
        when (
            transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()?.reason
        ) {
            SlotReleaseReason.NORMAL_COMPLETION -> true
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED -> {
                // The durable callback owns queue drain when it wins the barrier race. If the Job
                // completion wins, this hook owns it instead.
                true
            }
            SlotReleaseReason.STOP_BARRIERS_SETTLED -> {
                // Pending inputs still belong to the STOPPED Run and must migrate to a fresh one.
                // The callback runs after mailbox handling and therefore outside [genLock].
                onStopSettled?.invoke(this@ConversationGenerationState)
                false
            }
            SlotReleaseReason.EMPTY_STOP -> error("Coroutine settlement cannot emit EMPTY_STOP")
            SlotReleaseReason.SEND_LAUNCH_ABANDONED ->
                error("Coroutine settlement cannot abandon an unlaunched Send")
            null -> false
        }
    }

    /** Completion hooks cannot suspend; enqueue their identified result on the owned scope. */
    private fun settleCoroutineAsync(uiToken: Long) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (endGeneration(uiToken)) onQueueDrainRequested?.invoke(this@ConversationGenerationState)
        }
    }

    /** Applies UI/resource release after the reducer has already transitioned to [RunState.Idle]. */
    private fun applyReleasedSlotLocked() {
        check(runState is RunState.Idle)
        generationJob = null
        isLoading.value = false
        generating.value = false
        stopping.value = false
        compacting.value = false
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
        onProviderPassRequested = ::requestProviderPass,
        onProviderPassCompleted = ::finishProviderPass,
        onRunFinalizationRequested = ::requestRunFinalization,
        onRunFinalizationCompleted = { identity, success ->
            finishRunFinalization(identity, success).accepted
        },
        // Steering: lets the tool loop see a mid-generation queued send and end at the next
        // round boundary, so the queue flushes without waiting out the whole tool loop.
        hasQueuedSends = { queuedSends.value.isNotEmpty() },
        onToolBatchRequested = ::requestToolBatch,
        onToolBatchCompleted = ::completeToolBatch,
        onToolRoundCommitted = ::finishToolRoundCommit,
    )

    // ── Stop / finalization ───────────────────────────────────────────────
    /**
     * Terminal user Stop request. Cancels ONLY this conversation's job + in-flight HTTP streams,
     * advances the UI token, and commits STOPPED to the streaming snapshot. The cancelled
     * coroutine retains the slot until its installed Job completion hook reports settlement.
     * Regenerate/edit never call Stop; they can claim only an idle slot through
     * [tryAcquireForReplacement].
     */
    internal suspend fun stop(): StopResult = withContext(NonCancellable) {
        val previousJob = AtomicReference<Job?>()
        val requestedIdentity = AtomicReference<RuntimeRunIdentity>()
        val stoppedMessage = AtomicReference<ChatMessage?>()
        val duplicateStoppingRequest = AtomicBoolean(false)
        val transition = commandMailbox.submit(
            ConversationCommandFactory {
                val currentState = runState
                val identity = currentState.identityOrNull()
                    ?: RuntimeRunIdentity(
                        conversationId = conversationId,
                        ownerToken = (uiGenToken + 1).coerceAtLeast(1),
                    )
                previousJob.set(generationJob)
                requestedIdentity.set(identity)
                duplicateStoppingRequest.set(currentState is RunState.Stopping)
                stoppedMessage.set(
                    streamingMessage.value
                        ?.takeUnless {
                            currentState is RunState.Idle ||
                                currentState is RunState.Compacting &&
                                currentState.resumeIdentity == null ||
                                currentState is RunState.Finalizing &&
                                !currentState.persistenceFailureReported
                        }
                        ?.copy(status = MessageStatus.STOPPED),
                )
                val requiresPersistence = identity.runId != null
                val effectId = when {
                    !requiresPersistence -> null
                    currentState is RunState.Stopping -> currentState.finalizationEffectId
                    else -> "stop-${identity.ownerToken}"
                }
                ConversationCommand.StopRequested(
                    identity = identity,
                    coroutineAlreadySettled = previousJob.get()?.isCompleted != false,
                    requiresPersistence = requiresPersistence,
                    effectId = effectId,
                )
            },
        )
        val identity = checkNotNull(requestedIdentity.get())
        if (transition.accepted || duplicateStoppingRequest.get()) {
            // Hard kill after the mailbox-owned cutoff: synchronous handle cancellation wakes
            // blocking HTTP/native reads, then Job cancellation unwinds every remaining child.
            streamScope.cancelAll()
            previousJob.get()?.cancel()
        }
        StopResult(
            stoppedMessage = stoppedMessage.get(),
            conversationId = conversationId,
            runId = identity.runId,
            finalizationEffect = transition.effects
                .filterIsInstance<RunEffect.FinalizeStop>()
                .singleOrNull(),
        )
    }

    /** Enqueue Stop immediately, but keep accepted-effect handling on the conversation scope. */
    internal fun requestStop(onResult: (StopResult) -> Unit): Job = scope.launch(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        withContext(NonCancellable) {
            onResult(stop())
        }
    }

    /**
     * Completes the durable half of the Stop barrier. A failed terminal write deliberately keeps
     * STOPPING occupied: the unique live-Run slot is still unavailable, so reporting IDLE would
     * make the next Send fail or attach to the doomed Run.
     */
    internal suspend fun finishStopFinalization(
        command: ConversationCommand.PersistenceSettled,
    ): StopFinalizationOutcome = withContext(NonCancellable) {
        val transition = commandMailbox.submit(
            ConversationCommandFactory { command },
        )
        if (!transition.accepted) return@withContext StopFinalizationOutcome.REJECTED
        if (!command.success) return@withContext StopFinalizationOutcome.FAILED
        val release = transition.effects.filterIsInstance<RunEffect.ReleaseSlot>().singleOrNull()
            ?: return@withContext StopFinalizationOutcome.RECORDED
        check(release.reason == SlotReleaseReason.STOP_BARRIERS_SETTLED)
        // Fire after mailbox handling and outside [genLock].
        onStopSettled?.invoke(this@ConversationGenerationState)
        StopFinalizationOutcome.SETTLED
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
    private fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
    }

    /** Runtime disposal is not a user Stop and therefore does not create a durable Stop effect. */
    internal fun dispose(): List<QueuedSend> {
        val pendingGuidance = synchronized(guidanceLock) {
            guidanceDisposed = true
            queuedSends.value.also { queuedSends.value = emptyList() }
        }
        val job = synchronized(genLock) { generationJob }
        streamScope.cancelAll()
        job?.cancel()
        cancelScope()
        return pendingGuidance
    }

    /** Append a queued send (generation in progress → enqueue instead of launching). */
    fun enqueueSend(send: QueuedSend) {
        synchronized(guidanceLock) {
            check(!guidanceDisposed) { "Conversation guidance store was disposed" }
            queuedSends.value = queuedSends.value + send
        }
    }

    /**
     * Remove a queued send by id (X button). Returns the removed item (or null) so the caller can
     * delete its now-orphaned attachment files — the composer already cleared its own reference on
     * enqueue, so the QueuedSend holds the only handle to those copied files.
     */
    fun removeQueuedSend(id: String): QueuedSend? {
        synchronized(guidanceLock) {
            val removed = queuedSends.value.firstOrNull { it.id == id } ?: return null
            queuedSends.value = queuedSends.value.filterNot { it.id == id }
            return removed
        }
    }

    /** Transfer the pending batch to one explicit in-flight owner before leaving memory-only state. */
    fun claimQueuedSends(): GuidanceBatchLease? = synchronized(guidanceLock) {
        if (guidanceDisposed || queuedSends.value.isEmpty()) return null
        val lease = GuidanceBatchLease(UUID.randomUUID().toString(), queuedSends.value)
        queuedSends.value = emptyList()
        check(claimedGuidance.put(lease.id, lease.batch) == null)
        lease
    }

    /**
     * End one in-flight ownership lease. A durable commit transfers file ownership to Room;
     * otherwise the exact batch returns to the front, unless disposal now owns cleanup.
     */
    fun settleGuidanceClaim(leaseId: String, durable: Boolean): Boolean {
        var orphaned = emptyList<QueuedSend>()
        synchronized(guidanceLock) {
            val batch = claimedGuidance.remove(leaseId) ?: return false
            when {
                durable -> Unit
                guidanceDisposed -> orphaned = batch
                else -> queuedSends.value = batch + queuedSends.value
            }
        }
        orphaned.forEach(QueuedSend::deleteOwnedFiles)
        return true
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

    enum class RunFinalizationOutcome {
        /** Old, duplicate, wrong-Run, wrong-pass, or otherwise illegal callback. */
        REJECTED,
        /** Current finalization effect failed; the live Run remains occupied. */
        FAILED,
        /** Durable barrier recorded; coroutine barrier is still pending. */
        RECORDED,
        /** Both barriers settled and the slot was released. */
        SETTLED;

        val accepted: Boolean get() = this != REJECTED
    }

    private fun RunState.identityOrNull(): RuntimeRunIdentity? = when (this) {
        is RunState.Idle -> null
        is RunState.Preparing -> ownerIdentity
        is RunState.Active -> identity
        is RunState.Compacting -> resumeIdentity
        is RunState.Finalizing -> identity
        is RunState.Stopping -> identity
    }

    private fun RunState.isLaunchableOwner(ownerToken: Long): Boolean = when (this) {
        is RunState.Preparing -> ownerIdentity.ownerToken == ownerToken
        is RunState.Active -> !coroutineSettled && identity.ownerToken == ownerToken
        is RunState.Idle,
        is RunState.Compacting,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> false
    }

    private fun RunState.isStoppingOwner(ownerToken: Long): Boolean =
        this is RunState.Stopping && identity.ownerToken == ownerToken

    /** Privacy-safe bounded trace for diagnostics/tests; contains no prompt or message content. */
    internal fun runtimeTraceSnapshot(): List<ConversationRuntimeTraceEntry> = runtimeTrace.snapshot()

    private fun reduceMailboxCommand(factory: ConversationCommandFactory): Transition =
        synchronized(genLock) {
            val transition = reduceLocked(factory.create())
            if (transition.accepted) applyMailboxEffectsLocked(transition)
            transition
        }

    /** Apply only effects whose authority has moved to the mailbox in the current phase. */
    private fun applyMailboxEffectsLocked(transition: Transition) {
        transition.effects.filterIsInstance<RunEffect.RunCompact>()
            .singleOrNull()
            ?.let { effect ->
                val compactState = runState as? RunState.Compacting
                    ?: error("RunCompact must enter Compacting")
                check(compactState.effectIdentity == effect.identity)
                check(compactState.compactRunId == effect.compactRunId)
                check(compactState.mode == effect.mode)
                compacting.value = true
            }
        if (runState !is RunState.Compacting && compacting.value) {
            compacting.value = false
        }
        transition.effects.filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
            ?.let { effect ->
                val preparing = runState as? RunState.Preparing
                    ?: error("Accepted input persistence must enter Preparing")
                check(preparing.inputEffectIdentity == effect.identity)
                applyActivatedSlotLocked(preparing.ownerIdentity, loading = false)
            }
        transition.effects.filterIsInstance<RunEffect.CancelProviderPass>()
            .singleOrNull()
            ?.let { effect ->
                check(uiGenToken == effect.identity.ownerToken)
                val stopped = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
                check(stopped == null || effect.identity.runId != null) {
                    "A streaming Stop effect requires a bound Run"
                }
                // Revoke DB/UI ownership before cancellation can enter GenerationManager.finally.
                persistId.incrementAndGet()
                uiGenToken += 1
                streamingMessage.value = stopped
                if (runState is RunState.Stopping) {
                    isLoading.value = true
                    generating.value = true
                    stopping.value = true
                }
            }
        transition.effects.filterIsInstance<RunEffect.ReleaseSlot>()
            .singleOrNull()
            ?.let { release ->
                when (release.reason) {
                    SlotReleaseReason.STOP_BARRIERS_SETTLED -> {
                        // Stop settlement owns the next drain; a stale failed-boundary suppression
                        // must not prevent accepted guidance from moving to its fresh Run.
                        suppressNextQueueDrain = false
                        applyReleasedSlotLocked()
                    }
                    SlotReleaseReason.NORMAL_COMPLETION,
                    SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
                    SlotReleaseReason.EMPTY_STOP,
                    SlotReleaseReason.SEND_LAUNCH_ABANDONED,
                    -> applyReleasedSlotLocked()
                }
            }
    }

    private fun applyActivatedSlotLocked(identity: RuntimeRunIdentity, loading: Boolean) {
        check(runState !is RunState.Idle)
        uiGenToken = identity.ownerToken
        isLoading.value = loading
        generating.value = true
        stopping.value = false
        onRegistryActive(conversationId)
        onActive?.invoke(conversationId)
    }

    /** Must be called while [genLock] is held. This is the only process-slot state write path. */
    private fun reduceLocked(command: ConversationCommand): com.newoether.agora.model.Transition {
        val oldState = runState
        val transition = ConversationRuntimeReducer.reduce(oldState, command)
        runtimeTrace.record(oldState, command, transition)
        if (transition.accepted) runState = transition.newState
        return transition
    }

    private fun RuntimeRunIdentity.effectIdentity(effectId: String): RunEffectIdentity =
        RunEffectIdentity(
            conversationId = conversationId,
            ownerToken = ownerToken,
            runId = requireNotNull(runId),
            pass = pass,
            effectId = effectId,
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
    /** Provenance only; drain always creates a fresh Run and never reuses this id. */
    val runId: String,
    /** Legacy bare-image paths retained for queue display and cleanup. */
    val images: List<String> = emptyList(),
    /** Prepared payload owned by this in-memory guidance until its boundary commit. */
    val preparedImages: List<String> = emptyList(),
    val preparedAttachmentMetaJson: String? = null,
    val preparedOwnedPaths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class GuidanceBatchLease(
    val id: String,
    val batch: List<QueuedSend>,
) {
    init {
        require(id.isNotBlank())
        require(batch.isNotEmpty())
    }
}

internal fun QueuedSend.deleteOwnedFiles() {
    com.newoether.agora.util.AttachmentFiles.deleteBacking(attachments)
    preparedOwnedPaths.forEach { path -> runCatching { java.io.File(path).delete() } }
}

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
