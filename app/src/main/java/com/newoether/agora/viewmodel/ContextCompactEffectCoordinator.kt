package com.newoether.agora.viewmodel

import com.newoether.agora.model.CompactOutcome
import com.newoether.agora.model.RunEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Executes one isolated Context Compact through the ordinary conversation generation slot. */
internal class ContextCompactEffectCoordinator(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Execution {
        data class Settled(val result: CompactResult) : Execution
        data object Busy : Execution
        data object Superseded : Execution
    }

    suspend fun execute(
        state: ConversationGenerationState,
        block: suspend (RunEffect.RunCompact) -> CompactResult,
    ): Execution {
        val operationId = idFactory()
        val compactRunId = "compact_run_$operationId"
        val effectId = "compact-$operationId"
        val effect = state.queueMutationMutex.withLock {
            state.commands.requestCompact(compactRunId, effectId)
        } ?: return Execution.Busy

        val ownerJob = requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])
        if (!state.attachGenerationJob(effect.identity.ownerToken, ownerJob)) {
            settleFailure(state, effect)
            return Execution.Superseded
        }

        val result = try {
            block(effect)
        } catch (cancelled: CancellationException) {
            settleFailure(state, effect)
            throw cancelled
        } catch (error: Exception) {
            settleFailure(state, effect)
            throw error
        }

        val outcome = result.toRuntimeOutcome()
        val transition = withContext(NonCancellable) {
            state.finishCompact(effect.identity, outcome)
        }
        if (!transition.accepted) {
            return Execution.Superseded
        }
        check(
            transition.effects.any { it is RunEffect.CompactFailed } ==
                (outcome == CompactOutcome.FAILED),
        )
        check(transition.effects.any { it is RunEffect.ReleaseSlot })
        return Execution.Settled(result)
    }

    private suspend fun settleFailure(
        state: ConversationGenerationState,
        effect: RunEffect.RunCompact,
    ) {
        withContext(NonCancellable) {
            try {
                state.finishCompact(effect.identity, CompactOutcome.FAILED)
            } catch (_: Exception) {
                // Runtime disposal already removed the only possible continuation authority.
            }
        }
    }
}

private fun CompactResult.toRuntimeOutcome(): CompactOutcome = when (this) {
    is CompactResult.Created -> CompactOutcome.CREATED
    CompactResult.NotNeeded -> CompactOutcome.NOT_NEEDED
    is CompactResult.Failed -> CompactOutcome.FAILED
}
