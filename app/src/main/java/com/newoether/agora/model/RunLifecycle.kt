package com.newoether.agora.model

/**
 * Durable lifecycle of one user-visible agentic execution.
 *
 * Provider calls are identified substates inside ACTIVE; they are not separate durable Runs.
 */
enum class RunStatus {
    ACTIVE,
    STOPPING,
    COMPLETED,
    STOPPED,
    FAILED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == STOPPED || this == FAILED
}

enum class RunEndReason {
    MODEL_COMPLETED,
    USER_STOPPED,
    PROCESS_RECOVERED,
    PROVIDER_ERROR,
}

/**
 * Identity of the in-process owner for one conversation Run.
 *
 * [ownerToken] is the legacy slot token retained during the strangler migration. [runId] is null
 * only between the synchronous slot claim and the Room transaction that creates/binds the Run.
 */
data class RuntimeRunIdentity(
    val conversationId: String,
    val ownerToken: Long,
    val runId: String? = null,
    val pass: Int = 0,
) {
    init {
        require(conversationId.isNotBlank())
        require(ownerToken > 0L)
        require(runId == null || runId.isNotBlank())
        require(pass >= 0)
    }
}

/**
 * Full Run-target identity echoed by every asynchronous effect result migrated here.
 * [RunEffect.PersistAcceptedInput] carries the proposed Run id before Room binds it; every later
 * effect carries an already-durable Run id.
 */
data class RunEffectIdentity(
    val conversationId: String,
    val ownerToken: Long,
    val runId: String,
    val pass: Int,
    val effectId: String,
) {
    init {
        require(conversationId.isNotBlank())
        require(ownerToken > 0L)
        require(runId.isNotBlank())
        require(pass >= 0)
        require(effectId.isNotBlank())
    }

    fun runIdentity(): RuntimeRunIdentity = RuntimeRunIdentity(
        conversationId = conversationId,
        ownerToken = ownerToken,
        runId = runId,
        pass = pass,
    )
}

/** Durable process-start snapshot for one live Run; no coroutine is ever reconstructed from it. */
data class RunRecoverySnapshot(
    val conversationId: String,
    val runId: String,
    val pass: Int,
    val status: RunStatus,
) {
    init {
        require(conversationId.isNotBlank())
        require(runId.isNotBlank())
        require(pass >= 0)
        require(status == RunStatus.ACTIVE || status == RunStatus.STOPPING)
    }
}

sealed interface RunState {
    val conversationId: String

    data class Idle(override val conversationId: String) : RunState {
        init {
            require(conversationId.isNotBlank())
        }
    }

    /** Ephemeral startup-only ownership while an orphaned durable Run is terminalized. */
    data class Recovering(
        val snapshot: RunRecoverySnapshot,
        val effectIdentity: RunEffectIdentity,
        val failureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = snapshot.conversationId

        init {
            require(effectIdentity.conversationId == snapshot.conversationId)
            require(effectIdentity.runId == snapshot.runId)
            require(effectIdentity.pass == snapshot.pass)
        }
    }

    /** A foreground Send owns the slot while its conversation/Run/message transaction executes. */
    data class Preparing(
        val ownerIdentity: RuntimeRunIdentity,
        val inputEffectIdentity: RunEffectIdentity,
        val inputFailureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = ownerIdentity.conversationId

        init {
            require(ownerIdentity.runId == null)
            require(ownerIdentity.pass == 0)
            require(inputEffectIdentity.conversationId == ownerIdentity.conversationId)
            require(inputEffectIdentity.ownerToken == ownerIdentity.ownerToken)
            require(inputEffectIdentity.pass == 0)
        }
    }

    data class Active(
        val identity: RuntimeRunIdentity,
        val coroutineSettled: Boolean = false,
        val providerPhase: RunProviderPhase = RunProviderPhase.None,
        val toolPhase: RunToolPhase = RunToolPhase.None,
    ) : RunState {
        override val conversationId: String = identity.conversationId

        init {
            require(
                providerPhase == RunProviderPhase.None || toolPhase == RunToolPhase.None,
            ) { "Provider and tool phases cannot overlap" }
            when (providerPhase) {
                RunProviderPhase.None -> Unit
                is RunProviderPhase.Running -> require(
                    providerPhase.identity.runIdentity() == identity,
                )
            }
            when (toolPhase) {
                RunToolPhase.None -> Unit
                is RunToolPhase.Executing -> require(
                    toolPhase.batchIdentity.runIdentity() == identity,
                )
                is RunToolPhase.Committing -> {
                    require(toolPhase.batchIdentity.runIdentity() == identity)
                    require(toolPhase.commitIdentity.runIdentity() == identity)
                }
            }
        }
    }

    /**
     * One identified Context Compact effect. Automatic Compact temporarily owns an existing
     * active Run and must settle before that Run may continue; manual Compact starts from Idle
     * and deliberately owns no generation slot.
     */
    data class Compacting(
        val effectIdentity: RunEffectIdentity,
        val compactRunId: String,
        val mode: CompactMode,
        val resumeIdentity: RuntimeRunIdentity?,
    ) : RunState {
        override val conversationId: String = effectIdentity.conversationId

        init {
            require(compactRunId.isNotBlank())
            when (mode) {
                CompactMode.MANUAL -> {
                    require(resumeIdentity == null)
                    require(effectIdentity.runId == compactRunId)
                    require(effectIdentity.pass == 0)
                }
                CompactMode.AUTOMATIC -> {
                    requireNotNull(resumeIdentity)
                    require(effectIdentity.runIdentity() == resumeIdentity)
                    require(compactRunId != effectIdentity.runId)
                }
            }
        }
    }

    data class Stopping(
        val identity: RuntimeRunIdentity,
        val finalizationEffectId: String?,
        val coroutineSettled: Boolean,
        val persistenceSettled: Boolean,
        val persistenceFailureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = identity.conversationId

        init {
            require(finalizationEffectId == null || finalizationEffectId.isNotBlank())
            require(finalizationEffectId != null || persistenceSettled) {
                "A Stop without a persistence effect must already have a settled persistence barrier"
            }
            require(!persistenceFailureReported || finalizationEffectId != null)
            require(!(coroutineSettled && persistenceSettled)) {
                "A fully settled Stop must release to Idle in the same transition"
            }
        }
    }

    /** Natural SUCCESS/FAILED/external-cancellation terminalization with two settlement barriers. */
    data class Finalizing(
        val identity: RuntimeRunIdentity,
        val effectIdentity: RunEffectIdentity,
        val status: RunStatus,
        val reason: RunEndReason,
        val markConversationUnread: Boolean,
        val coroutineSettled: Boolean,
        val persistenceSettled: Boolean,
        val persistenceFailureReported: Boolean = false,
    ) : RunState {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.runId != null)
            require(effectIdentity.runIdentity() == identity)
            require(status.isTerminal)
            require(!persistenceFailureReported || !persistenceSettled)
            require(!(coroutineSettled && persistenceSettled)) {
                "A fully settled finalization must release to Idle in the same transition"
            }
        }
    }
}

sealed interface RunProviderPhase {
    data object None : RunProviderPhase
    data class Running(val identity: RunEffectIdentity) : RunProviderPhase
}

/** Authoritative in-process boundary for one validated provider tool batch. */
sealed interface RunToolPhase {
    data object None : RunToolPhase

    data class Executing(
        val batchIdentity: RunEffectIdentity,
    ) : RunToolPhase

    data class Committing(
        val batchIdentity: RunEffectIdentity,
        val commitIdentity: RunEffectIdentity,
        val failureReported: Boolean = false,
    ) : RunToolPhase
}

enum class CompactMode {
    MANUAL,
    AUTOMATIC,
}

enum class CompactOutcome {
    CREATED,
    NOT_NEEDED,
    FAILED,
}

enum class ProviderPassResult {
    COMPLETED_TEXT,
    COMPLETED_TOOL_CALLS,
    TRUNCATED,
    FAILED,
    CANCELLED,
}

sealed interface ConversationCommand {
    val conversationId: String

    /** Convert one Room live-Run snapshot into an identified terminal recovery effect. */
    data class Recover(val snapshot: RunRecoverySnapshot) : ConversationCommand {
        override val conversationId: String = snapshot.conversationId
    }

    /** Result of the exact [RunEffect.RecoverDurableRun] transaction. */
    data class RecoveryCompleted(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class AcquireSlot(val identity: RuntimeRunIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.runId == null)
            require(identity.pass == 0)
        }
    }

    data class SendRequested(
        val identity: RunEffectIdentity,
        val directOnly: Boolean,
        val hasPendingGuidance: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.pass == 0)
        }
    }

    /** Result of the exact [RunEffect.PersistAcceptedInput] emitted for this Send. */
    data class InputPersisted(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class InputPersistenceFailed(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Cancellation won after a direct claim but before its state-owned Job could be installed. */
    data class SendLaunchAbandoned(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class BindRun(val identity: RuntimeRunIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(identity.runId != null)
        }
    }

    /** Request execution of exactly one Provider pass for the current Run/pass. */
    data class ProviderPassRequested(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Closed semantic result of the exact emitted Provider pass. */
    data class ProviderPassCompleted(
        val identity: RunEffectIdentity,
        val result: ProviderPassResult,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** A termination-validated Provider outcome requests execution of exactly one tool batch. */
    data class ToolBatchRequested(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** All tools in the exact emitted batch completed with authoritative results. */
    data class ToolBatchCompleted(val identity: RunEffectIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Result of the exact atomic protocol-round Room commit. */
    data class ToolRoundCommitted(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Request one Compact effect with a separately identified durable Compact Run. */
    data class CompactRequested(
        val identity: RunEffectIdentity,
        val compactRunId: String,
        val mode: CompactMode,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(compactRunId.isNotBlank())
            require(mode != CompactMode.MANUAL || identity.runId == compactRunId)
            require(mode != CompactMode.AUTOMATIC || identity.runId != compactRunId)
        }
    }

    /** Result of the exact [RunEffect.RunCompact] emitted for this operation. */
    data class CompactCompleted(
        val identity: RunEffectIdentity,
        val outcome: CompactOutcome,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    /** Request the one normal terminal Room transaction for the active Run. */
    data class FinalizationRequested(
        val identity: RunEffectIdentity,
        val status: RunStatus,
        val reason: RunEndReason,
        val markConversationUnread: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(status.isTerminal)
        }
    }

    /** Result of the exact [RunEffect.FinalizeRun] Room transaction. */
    data class FinalizationCompleted(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class StopRequested(
        val identity: RuntimeRunIdentity,
        val coroutineAlreadySettled: Boolean,
        val requiresPersistence: Boolean,
        val effectId: String?,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId

        init {
            require(requiresPersistence == (effectId != null))
            require(requiresPersistence == (identity.runId != null)) {
                "A durable Run must have exactly one identified Stop finalization effect"
            }
            require(effectId == null || effectId.isNotBlank())
        }
    }

    data class CoroutineSettled(val identity: RuntimeRunIdentity) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }

    data class PersistenceSettled(
        val identity: RunEffectIdentity,
        val success: Boolean,
    ) : ConversationCommand {
        override val conversationId: String = identity.conversationId
    }
}

enum class SlotReleaseReason {
    NORMAL_COMPLETION,
    NORMAL_FINALIZATION_SETTLED,
    STOP_BARRIERS_SETTLED,
    EMPTY_STOP,
    SEND_LAUNCH_ABANDONED,
}

sealed interface RunEffect {
    data class RecoverDurableRun(
        val identity: RunEffectIdentity,
        val priorStatus: RunStatus,
    ) : RunEffect {
        init {
            require(priorStatus == RunStatus.ACTIVE || priorStatus == RunStatus.STOPPING)
        }
    }
    data class RunRecoveryFailed(val identity: RunEffectIdentity) : RunEffect
    data class SlotActivated(val identity: RuntimeRunIdentity) : RunEffect
    data class PersistAcceptedInput(val identity: RunEffectIdentity) : RunEffect
    data class AcceptGuidance(val identity: RunEffectIdentity) : RunEffect
    data class DrainGuidanceFirst(val identity: RunEffectIdentity) : RunEffect
    data class AwaitRunRelease(val identity: RunEffectIdentity) : RunEffect
    data class AwaitCompactSettlement(val identity: RunEffectIdentity) : RunEffect
    data class RejectSendBusy(val identity: RunEffectIdentity) : RunEffect
    data class StartProviderPass(val identity: RunEffectIdentity) : RunEffect
    data class ProviderPassAccepted(
        val identity: RunEffectIdentity,
        val result: ProviderPassResult,
    ) : RunEffect
    data class CancelProviderPass(val identity: RuntimeRunIdentity) : RunEffect
    data class FinalizeStop(val identity: RunEffectIdentity) : RunEffect
    data class StopPersistenceFailed(val identity: RunEffectIdentity) : RunEffect
    data class ExecuteToolBatch(val identity: RunEffectIdentity) : RunEffect
    data class CommitToolRound(val identity: RunEffectIdentity) : RunEffect
    data class ContinueProviderPass(val identity: RunEffectIdentity) : RunEffect
    data class ToolRoundCommitFailed(val identity: RunEffectIdentity) : RunEffect
    data class RunCompact(
        val identity: RunEffectIdentity,
        val compactRunId: String,
        val mode: CompactMode,
    ) : RunEffect {
        init {
            require(compactRunId.isNotBlank())
            require(mode != CompactMode.MANUAL || identity.runId == compactRunId)
            require(mode != CompactMode.AUTOMATIC || identity.runId != compactRunId)
        }
    }
    data class ResumeAfterCompact(
        val identity: RunEffectIdentity,
        val outcome: CompactOutcome,
    ) : RunEffect {
        init {
            require(outcome != CompactOutcome.FAILED)
        }
    }
    data class CompactFailed(
        val identity: RunEffectIdentity,
        val mode: CompactMode,
    ) : RunEffect
    data class FinalizeRun(
        val identity: RunEffectIdentity,
        val status: RunStatus,
        val reason: RunEndReason,
        val markConversationUnread: Boolean,
    ) : RunEffect {
        init {
            require(status.isTerminal)
        }
    }
    data class RunFinalizationFailed(val identity: RunEffectIdentity) : RunEffect
    data class ReleaseSlot(
        val identity: RuntimeRunIdentity,
        val reason: SlotReleaseReason,
    ) : RunEffect
}

enum class CommandRejection {
    ILLEGAL_STATE,
    STALE_IDENTITY,
    DUPLICATE_RESULT,
}

data class Transition(
    val newState: RunState,
    val effects: List<RunEffect> = emptyList(),
    val rejection: CommandRejection? = null,
) {
    val accepted: Boolean get() = rejection == null
}

private const val RECOVERY_OWNER_TOKEN = Long.MAX_VALUE

/**
 * Authoritative migrated slice of the conversation runtime reducer.
 *
 * It owns startup recovery planning, ordinary Send placement/input acceptance, the process slot,
 * Provider-pass acceptance, normal/Stop coroutine/persistence barriers, the
 * tool-batch/result/commit/continuation gate, and Context Compact admission/result settlement.
 * Guidance and external effect bodies remain bounded adapters. The reducer has no Android,
 * coroutine, Room, network, or Compose dependency.
 */
object ConversationRuntimeReducer {
    fun reduce(
        state: RunState,
        command: ConversationCommand,
    ): Transition {
        if (state.conversationId != command.conversationId) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }

        return when (command) {
            is ConversationCommand.Recover -> requestRecovery(state, command)
            is ConversationCommand.RecoveryCompleted -> completeRecovery(state, command)
            is ConversationCommand.AcquireSlot -> acquire(state, command)
            is ConversationCommand.SendRequested -> requestSend(state, command)
            is ConversationCommand.InputPersisted -> inputPersisted(state, command)
            is ConversationCommand.InputPersistenceFailed -> inputPersistenceFailed(state, command)
            is ConversationCommand.SendLaunchAbandoned -> abandonSendLaunch(state, command)
            is ConversationCommand.BindRun -> bindRun(state, command)
            is ConversationCommand.ProviderPassRequested -> requestProviderPass(state, command)
            is ConversationCommand.ProviderPassCompleted -> completeProviderPass(state, command)
            is ConversationCommand.ToolBatchRequested -> requestToolBatch(state, command)
            is ConversationCommand.ToolBatchCompleted -> completeToolBatch(state, command)
            is ConversationCommand.ToolRoundCommitted -> commitToolRound(state, command)
            is ConversationCommand.CompactRequested -> requestCompact(state, command)
            is ConversationCommand.CompactCompleted -> completeCompact(state, command)
            is ConversationCommand.FinalizationRequested -> requestFinalization(state, command)
            is ConversationCommand.FinalizationCompleted -> completeFinalization(state, command)
            is ConversationCommand.StopRequested -> requestStop(state, command)
            is ConversationCommand.CoroutineSettled -> settleCoroutine(state, command)
            is ConversationCommand.PersistenceSettled -> settlePersistence(state, command)
        }
    }

    private fun requestRecovery(
        state: RunState,
        command: ConversationCommand.Recover,
    ): Transition = when (state) {
        is RunState.Idle -> {
            val snapshot = command.snapshot
            val effectIdentity = RunEffectIdentity(
                conversationId = snapshot.conversationId,
                ownerToken = RECOVERY_OWNER_TOKEN,
                runId = snapshot.runId,
                pass = snapshot.pass,
                effectId = "recover-${snapshot.runId}-${snapshot.pass}",
            )
            Transition(
                newState = RunState.Recovering(snapshot, effectIdentity),
                effects = listOf(
                    RunEffect.RecoverDurableRun(effectIdentity, snapshot.status),
                ),
            )
        }
        is RunState.Recovering -> reject(
            state,
            if (state.snapshot == command.snapshot) {
                CommandRejection.DUPLICATE_RESULT
            } else {
                CommandRejection.STALE_IDENTITY
            },
        )
        is RunState.Preparing,
        is RunState.Active,
        is RunState.Compacting,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun completeRecovery(
        state: RunState,
        command: ConversationCommand.RecoveryCompleted,
    ): Transition {
        val recovering = state as? RunState.Recovering
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (recovering.effectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (!command.success) {
            if (recovering.failureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = recovering.copy(failureReported = true),
                effects = listOf(RunEffect.RunRecoveryFailed(command.identity)),
            )
        }
        return Transition(RunState.Idle(recovering.conversationId))
    }

    private fun acquire(
        state: RunState,
        command: ConversationCommand.AcquireSlot,
    ): Transition = when (state) {
        is RunState.Idle -> Transition(
            newState = RunState.Active(command.identity),
            effects = listOf(RunEffect.SlotActivated(command.identity)),
        )
        is RunState.Recovering,
        is RunState.Preparing,
        is RunState.Active,
        is RunState.Compacting,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun requestSend(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ): Transition = when (state) {
        is RunState.Idle -> when {
            command.directOnly && command.hasPendingGuidance -> Transition(
                newState = state,
                effects = listOf(
                    RunEffect.RejectSendBusy(command.identity),
                ),
            )
            command.hasPendingGuidance -> Transition(
                newState = state,
                effects = listOf(
                    RunEffect.DrainGuidanceFirst(command.identity),
                ),
            )
            else -> {
                val ownerIdentity = RuntimeRunIdentity(
                    conversationId = command.identity.conversationId,
                    ownerToken = command.identity.ownerToken,
                )
                Transition(
                    newState = RunState.Preparing(ownerIdentity, command.identity),
                    effects = listOf(RunEffect.PersistAcceptedInput(command.identity)),
                )
            }
        }
        is RunState.Preparing -> if (command.directOnly) {
            busy(state, command)
        } else {
            Transition(
                newState = state,
                effects = listOf(
                    RunEffect.AcceptGuidance(
                        command.identity.copy(
                            ownerToken = state.ownerIdentity.ownerToken,
                            runId = state.inputEffectIdentity.runId,
                            pass = 0,
                        ),
                    ),
                ),
            )
        }
        is RunState.Active -> when {
            command.directOnly -> busy(state, command)
            state.identity.runId == null -> deferred(state, command)
            else -> Transition(
                newState = state,
                effects = listOf(
                    RunEffect.AcceptGuidance(
                        RunEffectIdentity(
                            conversationId = state.identity.conversationId,
                            ownerToken = state.identity.ownerToken,
                            runId = state.identity.runId,
                            pass = state.identity.pass,
                            effectId = command.identity.effectId,
                        ),
                    ),
                ),
            )
        }
        is RunState.Stopping -> deferredOrBusy(state, command)
        is RunState.Compacting -> if (command.directOnly) {
            busy(state, command)
        } else {
            Transition(
                newState = state,
                effects = listOf(RunEffect.AwaitCompactSettlement(command.identity)),
            )
        }
        is RunState.Finalizing -> deferredOrBusy(state, command)
        is RunState.Recovering -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun inputPersisted(
        state: RunState,
        command: ConversationCommand.InputPersisted,
    ): Transition {
        return when (state) {
            is RunState.Preparing -> {
                if (state.inputEffectIdentity != command.identity) {
                    reject(state, CommandRejection.STALE_IDENTITY)
                } else {
                    Transition(RunState.Active(command.identity.runIdentity()))
                }
            }
            is RunState.Stopping -> bindDurableRunAfterStop(
                state,
                command.identity.runIdentity(),
            )
            else -> reject(state, CommandRejection.ILLEGAL_STATE)
        }
    }

    private fun inputPersistenceFailed(
        state: RunState,
        command: ConversationCommand.InputPersistenceFailed,
    ): Transition {
        val preparing = state as? RunState.Preparing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (preparing.inputEffectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (preparing.inputFailureReported) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        return Transition(
            newState = preparing.copy(inputFailureReported = true),
        )
    }

    private fun abandonSendLaunch(
        state: RunState,
        command: ConversationCommand.SendLaunchAbandoned,
    ): Transition {
        val preparing = state as? RunState.Preparing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (preparing.inputEffectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(
            newState = RunState.Idle(state.conversationId),
            effects = listOf(
                RunEffect.ReleaseSlot(
                    preparing.ownerIdentity,
                    SlotReleaseReason.SEND_LAUNCH_ABANDONED,
                ),
            ),
        )
    }

    private fun bindRun(
        state: RunState,
        command: ConversationCommand.BindRun,
    ): Transition {
        if (state is RunState.Stopping) {
            return bindDurableRunAfterStop(state, command.identity)
        }
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (
            active.coroutineSettled ||
            active.providerPhase != RunProviderPhase.None ||
            active.toolPhase != RunToolPhase.None
        ) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (!sameOwner(active.identity, command.identity)) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val currentRunId = active.identity.runId
        if (currentRunId != null && currentRunId != command.identity.runId) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (command.identity.pass < active.identity.pass) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (active.identity == command.identity) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        if (currentRunId != null && command.identity.pass != active.identity.pass + 1) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(RunState.Active(command.identity))
    }

    private fun bindDurableRunAfterStop(
        stopping: RunState.Stopping,
        boundIdentity: RuntimeRunIdentity,
    ): Transition {
        if (stopping.identity.runId != null) {
            return reject(
                stopping,
                if (stopping.identity == boundIdentity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
        if (stopping.finalizationEffectId != null || !stopping.persistenceSettled) {
            return reject(stopping, CommandRejection.ILLEGAL_STATE)
        }
        if (!sameOwner(stopping.identity, boundIdentity)) {
            return reject(stopping, CommandRejection.STALE_IDENTITY)
        }
        val effectId = "stop-${boundIdentity.ownerToken}"
        val effectIdentity = boundIdentity.effectIdentity(effectId)
        return Transition(
            newState = stopping.copy(
                identity = boundIdentity,
                finalizationEffectId = effectId,
                persistenceSettled = false,
            ),
            effects = listOf(RunEffect.FinalizeStop(effectIdentity)),
        )
    }

    private fun requestProviderPass(
        state: RunState,
        command: ConversationCommand.ProviderPassRequested,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (active.identity != command.identity.runIdentity()) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (active.toolPhase != RunToolPhase.None) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        return when (val phase = active.providerPhase) {
            RunProviderPhase.None -> Transition(
                newState = active.copy(
                    providerPhase = RunProviderPhase.Running(command.identity),
                ),
                effects = listOf(RunEffect.StartProviderPass(command.identity)),
            )
            is RunProviderPhase.Running -> reject(
                state,
                if (phase.identity == command.identity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
    }

    private fun completeProviderPass(
        state: RunState,
        command: ConversationCommand.ProviderPassCompleted,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        val running = active.providerPhase as? RunProviderPhase.Running
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (running.identity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(
            newState = active.copy(providerPhase = RunProviderPhase.None),
            effects = listOf(RunEffect.ProviderPassAccepted(command.identity, command.result)),
        )
    }

    private fun requestToolBatch(
        state: RunState,
        command: ConversationCommand.ToolBatchRequested,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled || active.providerPhase != RunProviderPhase.None) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (active.identity != command.identity.runIdentity()) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val batchIdentity = command.identity.derived("tool-batch")
        return when (val phase = active.toolPhase) {
            RunToolPhase.None -> Transition(
                newState = active.copy(toolPhase = RunToolPhase.Executing(batchIdentity)),
                effects = listOf(RunEffect.ExecuteToolBatch(batchIdentity)),
            )
            is RunToolPhase.Executing -> reject(
                state,
                if (phase.batchIdentity == batchIdentity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
            is RunToolPhase.Committing -> reject(
                state,
                if (phase.batchIdentity == batchIdentity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
    }

    private fun completeToolBatch(
        state: RunState,
        command: ConversationCommand.ToolBatchCompleted,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        return when (val phase = active.toolPhase) {
            RunToolPhase.None -> reject(state, CommandRejection.ILLEGAL_STATE)
            is RunToolPhase.Executing -> {
                if (phase.batchIdentity != command.identity) {
                    reject(state, CommandRejection.STALE_IDENTITY)
                } else {
                    val commitIdentity = command.identity.derived("tool-round")
                    Transition(
                        newState = active.copy(
                            toolPhase = RunToolPhase.Committing(
                                batchIdentity = command.identity,
                                commitIdentity = commitIdentity,
                            ),
                        ),
                        effects = listOf(RunEffect.CommitToolRound(commitIdentity)),
                    )
                }
            }
            is RunToolPhase.Committing -> reject(
                state,
                if (phase.batchIdentity == command.identity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
        }
    }

    private fun commitToolRound(
        state: RunState,
        command: ConversationCommand.ToolRoundCommitted,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.coroutineSettled) {
            return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        val committing = active.toolPhase as? RunToolPhase.Committing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (committing.commitIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (!command.success) {
            if (committing.failureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = active.copy(
                    toolPhase = committing.copy(failureReported = true),
                ),
                effects = listOf(RunEffect.ToolRoundCommitFailed(command.identity)),
            )
        }
        return Transition(
            newState = active.copy(toolPhase = RunToolPhase.None),
            effects = listOf(RunEffect.ContinueProviderPass(command.identity)),
        )
    }

    private fun requestCompact(
        state: RunState,
        command: ConversationCommand.CompactRequested,
    ): Transition = when (state) {
        is RunState.Idle -> {
            if (command.mode != CompactMode.MANUAL) {
                reject(state, CommandRejection.ILLEGAL_STATE)
            } else {
                val compacting = RunState.Compacting(
                    effectIdentity = command.identity,
                    compactRunId = command.compactRunId,
                    mode = command.mode,
                    resumeIdentity = null,
                )
                Transition(
                    newState = compacting,
                    effects = listOf(
                        RunEffect.RunCompact(
                            command.identity,
                            command.compactRunId,
                            command.mode,
                        ),
                    ),
                )
            }
        }
        is RunState.Active -> when {
            command.mode != CompactMode.AUTOMATIC ->
                reject(state, CommandRejection.ILLEGAL_STATE)
            state.providerPhase != RunProviderPhase.None ->
                reject(state, CommandRejection.ILLEGAL_STATE)
            state.coroutineSettled ->
                reject(state, CommandRejection.ILLEGAL_STATE)
            state.toolPhase != RunToolPhase.None ->
                reject(state, CommandRejection.ILLEGAL_STATE)
            state.identity != command.identity.runIdentity() ->
                reject(state, CommandRejection.STALE_IDENTITY)
            else -> Transition(
                newState = RunState.Compacting(
                    effectIdentity = command.identity,
                    compactRunId = command.compactRunId,
                    mode = command.mode,
                    resumeIdentity = state.identity,
                ),
                effects = listOf(
                    RunEffect.RunCompact(
                        command.identity,
                        command.compactRunId,
                        command.mode,
                    ),
                ),
            )
        }
        is RunState.Compacting -> reject(
            state,
            if (
                state.effectIdentity == command.identity &&
                state.compactRunId == command.compactRunId &&
                state.mode == command.mode
            ) {
                CommandRejection.DUPLICATE_RESULT
            } else {
                CommandRejection.STALE_IDENTITY
            },
        )
        is RunState.Recovering,
        is RunState.Preparing,
        is RunState.Finalizing,
        is RunState.Stopping,
        -> reject(state, CommandRejection.ILLEGAL_STATE)
    }

    private fun completeCompact(
        state: RunState,
        command: ConversationCommand.CompactCompleted,
    ): Transition {
        val compacting = state as? RunState.Compacting
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (compacting.effectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val effects = when {
            command.outcome == CompactOutcome.FAILED -> listOf(
                RunEffect.CompactFailed(command.identity, compacting.mode),
            )
            compacting.mode == CompactMode.AUTOMATIC -> listOf(
                RunEffect.ResumeAfterCompact(command.identity, command.outcome),
            )
            else -> emptyList()
        }
        val nextState = when (compacting.mode) {
            CompactMode.MANUAL -> RunState.Idle(compacting.conversationId)
            CompactMode.AUTOMATIC -> RunState.Active(
                identity = requireNotNull(compacting.resumeIdentity),
            )
        }
        return Transition(nextState, effects)
    }

    private fun requestFinalization(
        state: RunState,
        command: ConversationCommand.FinalizationRequested,
    ): Transition {
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (active.identity != command.identity.runIdentity()) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        val effect = RunEffect.FinalizeRun(
            identity = command.identity,
            status = command.status,
            reason = command.reason,
            markConversationUnread = command.markConversationUnread,
        )
        return Transition(
            newState = RunState.Finalizing(
                identity = active.identity,
                effectIdentity = command.identity,
                status = command.status,
                reason = command.reason,
                markConversationUnread = command.markConversationUnread,
                coroutineSettled = active.coroutineSettled,
                persistenceSettled = false,
            ),
            effects = listOf(effect),
        )
    }

    private fun completeFinalization(
        state: RunState,
        command: ConversationCommand.FinalizationCompleted,
    ): Transition {
        val finalizing = state as? RunState.Finalizing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (finalizing.effectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (finalizing.persistenceSettled) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        if (!command.success) {
            if (finalizing.persistenceFailureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = finalizing.copy(persistenceFailureReported = true),
                effects = listOf(RunEffect.RunFinalizationFailed(command.identity)),
            )
        }
        return if (finalizing.coroutineSettled) {
            releaseSettledFinalization(finalizing)
        } else {
            Transition(finalizing.copy(persistenceSettled = true))
        }
    }

    private fun requestStop(
        state: RunState,
        command: ConversationCommand.StopRequested,
    ): Transition {
        val activeIdentity = when (state) {
            is RunState.Recovering -> return reject(state, CommandRejection.ILLEGAL_STATE)
            is RunState.Preparing -> state.ownerIdentity
            is RunState.Active -> state.identity
            is RunState.Compacting -> state.resumeIdentity
                ?: return reject(state, CommandRejection.ILLEGAL_STATE)
            is RunState.Finalizing -> {
                if (!state.persistenceFailureReported) {
                    return reject(state, CommandRejection.ILLEGAL_STATE)
                }
                state.identity
            }
            is RunState.Stopping -> return reject(
                state = state,
                rejection = if (state.identity == command.identity) {
                    CommandRejection.DUPLICATE_RESULT
                } else {
                    CommandRejection.STALE_IDENTITY
                },
            )
            is RunState.Idle -> return reject(state, CommandRejection.ILLEGAL_STATE)
        }
        if (activeIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }

        val effects = mutableListOf<RunEffect>(RunEffect.CancelProviderPass(activeIdentity))
        val effectId = command.effectId
        if (effectId != null) {
            effects += RunEffect.FinalizeStop(activeIdentity.effectIdentity(effectId))
        }
        val persistenceSettled = !command.requiresPersistence
        if (command.coroutineAlreadySettled && persistenceSettled) {
            effects += RunEffect.ReleaseSlot(activeIdentity, SlotReleaseReason.EMPTY_STOP)
            return Transition(RunState.Idle(activeIdentity.conversationId), effects)
        }
        return Transition(
            newState = RunState.Stopping(
                identity = activeIdentity,
                finalizationEffectId = effectId,
                coroutineSettled = command.coroutineAlreadySettled,
                persistenceSettled = persistenceSettled,
            ),
            effects = effects,
        )
    }

    private fun settleCoroutine(
        state: RunState,
        command: ConversationCommand.CoroutineSettled,
    ): Transition = when (state) {
        is RunState.Idle -> reject(state, CommandRejection.ILLEGAL_STATE)
        is RunState.Recovering -> reject(state, CommandRejection.ILLEGAL_STATE)
        is RunState.Preparing -> {
            if (state.ownerIdentity != command.identity) {
                reject(state, CommandRejection.STALE_IDENTITY)
            } else {
                Transition(
                    newState = RunState.Idle(state.conversationId),
                    effects = listOf(
                        RunEffect.ReleaseSlot(
                            state.ownerIdentity,
                            SlotReleaseReason.NORMAL_COMPLETION,
                        ),
                    ),
                )
            }
        }
        is RunState.Active -> {
            if (state.identity != command.identity) {
                reject(state, CommandRejection.STALE_IDENTITY)
            } else if (state.coroutineSettled) {
                reject(state, CommandRejection.DUPLICATE_RESULT)
            } else if (state.identity.runId != null) {
                // A bound durable Run cannot become Idle merely because its coroutine ended. The
                // exact terminal Room result (or a subsequent Stop recovery) must settle it.
                Transition(state.copy(coroutineSettled = true))
            } else {
                Transition(
                    newState = RunState.Idle(state.conversationId),
                    effects = listOf(
                        RunEffect.ReleaseSlot(state.identity, SlotReleaseReason.NORMAL_COMPLETION),
                    ),
                )
            }
        }
        is RunState.Compacting -> {
            val resumeIdentity = state.resumeIdentity
                ?: return reject(state, CommandRejection.ILLEGAL_STATE)
            if (resumeIdentity != command.identity) {
                reject(state, CommandRejection.STALE_IDENTITY)
            } else {
                // The owning generation ended before its automatic Compact result. Invalidate the
                // effect and retain the durable Run as occupied until finalization or Stop.
                Transition(
                    newState = RunState.Active(
                        identity = resumeIdentity,
                        coroutineSettled = true,
                    ),
                    effects = listOf(
                        RunEffect.CompactFailed(state.effectIdentity, state.mode),
                    ),
                )
            }
        }
        is RunState.Finalizing -> when {
            state.identity != command.identity ->
                reject(state, CommandRejection.STALE_IDENTITY)
            state.coroutineSettled ->
                reject(state, CommandRejection.DUPLICATE_RESULT)
            state.persistenceSettled -> releaseSettledFinalization(state)
            else -> Transition(state.copy(coroutineSettled = true))
        }
        is RunState.Stopping -> {
            when {
                state.identity != command.identity ->
                    reject(state, CommandRejection.STALE_IDENTITY)
                state.coroutineSettled ->
                    reject(state, CommandRejection.DUPLICATE_RESULT)
                state.persistenceSettled -> releaseSettledStop(state)
                else -> Transition(state.copy(coroutineSettled = true))
            }
        }
    }

    private fun settlePersistence(
        state: RunState,
        command: ConversationCommand.PersistenceSettled,
    ): Transition {
        val stopping = state as? RunState.Stopping
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        val expectedEffectId = stopping.finalizationEffectId
            ?: return reject(state, CommandRejection.STALE_IDENTITY)
        val expectedIdentity = stopping.identity.effectIdentity(expectedEffectId)
        if (expectedIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        if (stopping.persistenceSettled) {
            return reject(state, CommandRejection.DUPLICATE_RESULT)
        }
        if (!command.success) {
            if (stopping.persistenceFailureReported) {
                return reject(state, CommandRejection.DUPLICATE_RESULT)
            }
            return Transition(
                newState = stopping.copy(persistenceFailureReported = true),
                effects = listOf(RunEffect.StopPersistenceFailed(command.identity)),
            )
        }
        return if (stopping.coroutineSettled) {
            releaseSettledStop(stopping)
        } else {
            Transition(stopping.copy(persistenceSettled = true))
        }
    }

    private fun releaseSettledStop(stopping: RunState.Stopping) = Transition(
        newState = RunState.Idle(stopping.conversationId),
        effects = listOf(
            RunEffect.ReleaseSlot(
                identity = stopping.identity,
                reason = SlotReleaseReason.STOP_BARRIERS_SETTLED,
            ),
        ),
    )

    private fun releaseSettledFinalization(finalizing: RunState.Finalizing) = Transition(
        newState = RunState.Idle(finalizing.conversationId),
        effects = listOf(
            RunEffect.ReleaseSlot(
                identity = finalizing.identity,
                reason = SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            ),
        ),
    )

    private fun RuntimeRunIdentity.effectIdentity(effectId: String) = RunEffectIdentity(
        conversationId = conversationId,
        ownerToken = ownerToken,
        runId = requireNotNull(runId) {
            "An asynchronous Run effect requires a bound Run id"
        },
        pass = pass,
        effectId = effectId,
    )

    private fun RunEffectIdentity.derived(prefix: String) = copy(
        effectId = "$prefix-$effectId",
    )

    private fun sameOwner(first: RuntimeRunIdentity, second: RuntimeRunIdentity): Boolean =
        first.conversationId == second.conversationId && first.ownerToken == second.ownerToken

    private fun deferredOrBusy(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ): Transition = if (command.directOnly) busy(state, command) else deferred(state, command)

    private fun busy(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ) = Transition(
        newState = state,
        effects = listOf(
            RunEffect.RejectSendBusy(command.identity),
        ),
    )

    private fun deferred(
        state: RunState,
        command: ConversationCommand.SendRequested,
    ) = Transition(
        newState = state,
        effects = listOf(
            RunEffect.AwaitRunRelease(command.identity),
        ),
    )

    private fun reject(state: RunState, rejection: CommandRejection) = Transition(
        newState = state,
        rejection = rejection,
    )
}
