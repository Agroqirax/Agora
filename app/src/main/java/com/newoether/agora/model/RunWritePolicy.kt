package com.newoether.agora.model

/**
 * Minimal persisted Run state needed to decide where a newly accepted user input belongs.
 */
data class LiveRunHead(
    val id: String,
    val status: RunStatus,
    val currentPass: Int,
    val lastSequence: Long,
) {
    init {
        require(status == RunStatus.ACTIVE || status == RunStatus.STOPPING)
        require(currentPass >= 0)
        require(lastSequence >= 0)
    }
}

sealed interface RunInputDecision {
    data class StartRun(
        val runId: String,
        val parentRunId: String?,
        /** A STOPPING Run that must be terminalized in the same transaction first. */
        val terminalizeRunId: String? = null,
        val runSequence: Long = 0,
        val consumedAtPass: Int = 0,
    ) : RunInputDecision

    data class AppendIntervention(
        val runId: String,
        val runSequence: Long,
        val consumedAtPass: Int? = null,
    ) : RunInputDecision
}

/**
 * Pure input placement. The Room transaction later executes this decision atomically.
 */
object RunWritePolicy {
    fun placeInput(
        newRunId: String,
        selectedLeafRunId: String?,
        liveRun: LiveRunHead?,
    ): RunInputDecision {
        require(newRunId.isNotBlank())
        return when (liveRun?.status) {
            RunStatus.ACTIVE -> RunInputDecision.AppendIntervention(
                runId = liveRun.id,
                runSequence = liveRun.lastSequence + 1,
            )

            RunStatus.STOPPING -> RunInputDecision.StartRun(
                runId = newRunId,
                parentRunId = liveRun.id,
                terminalizeRunId = liveRun.id,
            )

            null -> RunInputDecision.StartRun(
                runId = newRunId,
                parentRunId = selectedLeafRunId,
            )

            else -> error("LiveRunHead rejects terminal states")
        }
    }
}

sealed interface RunPassDecision {
    data class StartNextPass(
        val pass: Int,
        val claimedInputMessageIds: List<String>,
    ) : RunPassDecision

    data object CompleteRun : RunPassDecision
    data object AwaitStopFinalization : RunPassDecision
    data object IgnoreLateCompletion : RunPassDecision
}

/**
 * A provider Pass is internal to a Run. Pending inputs are claimed by the next Pass before any
 * provider request can start; otherwise this Pass completes the Run.
 */
object RunPassPolicy {
    fun afterPass(
        run: RunLifecycleState,
        currentPass: Int,
        pendingInputMessageIds: List<String>,
    ): RunPassDecision {
        require(currentPass >= 0)
        require(pendingInputMessageIds.size == pendingInputMessageIds.distinct().size) {
            "Pending input IDs must be unique"
        }
        if (run.status.isTerminal) return RunPassDecision.IgnoreLateCompletion
        if (run.status == RunStatus.STOPPING) return RunPassDecision.AwaitStopFinalization
        return if (pendingInputMessageIds.isEmpty()) {
            RunPassDecision.CompleteRun
        } else {
            RunPassDecision.StartNextPass(
                pass = currentPass + 1,
                claimedInputMessageIds = pendingInputMessageIds.toList(),
            )
        }
    }
}
