package com.newoether.agora.model

/**
 * Durable lifecycle of one user-visible agentic execution.
 *
 * Provider calls are passes inside ACTIVE; they are deliberately not represented as Run states.
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

sealed interface RunState {
    val conversationId: String

    data class Idle(override val conversationId: String) : RunState {
        init {
            require(conversationId.isNotBlank())
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

    data class Active(val identity: RuntimeRunIdentity) : RunState {
        override val conversationId: String = identity.conversationId
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
}

sealed interface ConversationCommand {
    val conversationId: String

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
    STOP_BARRIERS_SETTLED,
    EMPTY_STOP,
    SEND_LAUNCH_ABANDONED,
}

sealed interface RunEffect {
    data class SlotActivated(val identity: RuntimeRunIdentity) : RunEffect
    data class PersistAcceptedInput(val identity: RunEffectIdentity) : RunEffect
    data class AcceptGuidance(val identity: RunEffectIdentity) : RunEffect
    data class DrainGuidanceFirst(val identity: RunEffectIdentity) : RunEffect
    data class AwaitRunRelease(val identity: RunEffectIdentity) : RunEffect
    data class RejectSendBusy(val identity: RunEffectIdentity) : RunEffect
    data class CancelProviderPass(val identity: RuntimeRunIdentity) : RunEffect
    data class FinalizeStop(val identity: RunEffectIdentity) : RunEffect
    data class StopPersistenceFailed(val identity: RunEffectIdentity) : RunEffect
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

/**
 * Authoritative migrated slice of the conversation runtime reducer.
 *
 * It owns ordinary Send placement/input acceptance, the process slot, and Stop's coroutine/
 * persistence barriers. Provider phases, tools, guidance execution, Compact, and recovery remain
 * behind the legacy adapter until their later migration phases. The reducer has no Android,
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
            is ConversationCommand.AcquireSlot -> acquire(state, command)
            is ConversationCommand.SendRequested -> requestSend(state, command)
            is ConversationCommand.InputPersisted -> inputPersisted(state, command)
            is ConversationCommand.InputPersistenceFailed -> inputPersistenceFailed(state, command)
            is ConversationCommand.SendLaunchAbandoned -> abandonSendLaunch(state, command)
            is ConversationCommand.BindRun -> bindRun(state, command)
            is ConversationCommand.StopRequested -> requestStop(state, command)
            is ConversationCommand.CoroutineSettled -> settleCoroutine(state, command)
            is ConversationCommand.PersistenceSettled -> settlePersistence(state, command)
        }
    }

    private fun acquire(
        state: RunState,
        command: ConversationCommand.AcquireSlot,
    ): Transition = when (state) {
        is RunState.Idle -> Transition(
            newState = RunState.Active(command.identity),
            effects = listOf(RunEffect.SlotActivated(command.identity)),
        )
        is RunState.Preparing,
        is RunState.Active,
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
        is RunState.Preparing -> deferredOrBusy(state, command)
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
    }

    private fun inputPersisted(
        state: RunState,
        command: ConversationCommand.InputPersisted,
    ): Transition {
        val preparing = state as? RunState.Preparing
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
        if (preparing.inputEffectIdentity != command.identity) {
            return reject(state, CommandRejection.STALE_IDENTITY)
        }
        return Transition(RunState.Active(command.identity.runIdentity()))
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
        val active = state as? RunState.Active
            ?: return reject(state, CommandRejection.ILLEGAL_STATE)
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

    private fun requestStop(
        state: RunState,
        command: ConversationCommand.StopRequested,
    ): Transition {
        val activeIdentity = when (state) {
            is RunState.Preparing -> state.ownerIdentity
            is RunState.Active -> state.identity
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
            } else {
                Transition(
                    newState = RunState.Idle(state.conversationId),
                    effects = listOf(
                        RunEffect.ReleaseSlot(state.identity, SlotReleaseReason.NORMAL_COMPLETION),
                    ),
                )
            }
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

    private fun RuntimeRunIdentity.effectIdentity(effectId: String) = RunEffectIdentity(
        conversationId = conversationId,
        ownerToken = ownerToken,
        runId = requireNotNull(runId) {
            "An asynchronous Run effect requires a bound Run id"
        },
        pass = pass,
        effectId = effectId,
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
