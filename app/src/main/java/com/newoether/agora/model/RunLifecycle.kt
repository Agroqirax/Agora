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

data class RunLifecycleState(
    val status: RunStatus = RunStatus.ACTIVE,
    val endReason: RunEndReason? = null,
) {
    init {
        require(status.isTerminal == (endReason != null)) {
            "A Run must have an end reason exactly when it is terminal"
        }
    }
}

sealed interface RunLifecycleEvent {
    data class PassCompleted(val hasPendingInterventions: Boolean) : RunLifecycleEvent
    data object StopRequested : RunLifecycleEvent
    data object StopFinalized : RunLifecycleEvent
    data object ProviderFailed : RunLifecycleEvent
    data object ProcessRecovered : RunLifecycleEvent
}

enum class RunNextAction {
    NONE,
    START_NEXT_PASS,
}

data class RunTransition(
    val state: RunLifecycleState,
    val nextAction: RunNextAction = RunNextAction.NONE,
)

/**
 * Pure, idempotent Run reducer. Persistence and coroutine ownership live outside this class.
 */
object RunLifecycle {
    fun reduce(
        current: RunLifecycleState,
        event: RunLifecycleEvent,
    ): RunTransition {
        if (current.status.isTerminal) return RunTransition(current)

        return when (event) {
            is RunLifecycleEvent.PassCompleted -> when {
                current.status == RunStatus.STOPPING -> RunTransition(current)
                event.hasPendingInterventions -> RunTransition(
                    state = current,
                    nextAction = RunNextAction.START_NEXT_PASS,
                )
                else -> terminal(RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED)
            }

            RunLifecycleEvent.StopRequested -> RunTransition(
                RunLifecycleState(status = RunStatus.STOPPING)
            )

            RunLifecycleEvent.StopFinalized -> terminal(
                RunStatus.STOPPED,
                RunEndReason.USER_STOPPED,
            )

            RunLifecycleEvent.ProviderFailed -> {
                // Once Stop establishes the cutoff, a racing provider failure cannot change the
                // user-visible outcome from STOPPED to FAILED.
                if (current.status == RunStatus.STOPPING) {
                    terminal(RunStatus.STOPPED, RunEndReason.USER_STOPPED)
                } else {
                    terminal(RunStatus.FAILED, RunEndReason.PROVIDER_ERROR)
                }
            }

            RunLifecycleEvent.ProcessRecovered -> terminal(
                RunStatus.STOPPED,
                RunEndReason.PROCESS_RECOVERED,
            )
        }
    }

    private fun terminal(status: RunStatus, reason: RunEndReason) = RunTransition(
        RunLifecycleState(status = status, endReason = reason)
    )
}
