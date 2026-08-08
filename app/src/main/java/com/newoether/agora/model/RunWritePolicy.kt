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
