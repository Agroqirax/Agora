package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface ToolExecutionEvent {
    /** Incremental user-visible output. It is never sent to the model as a partial result. */
    data class OutputDelta(val text: String) : ToolExecutionEvent

    /** The concrete device selected after resolving optional tool arguments. */
    data class TargetResolved(val target: String) : ToolExecutionEvent

    /** A low-volume lifecycle update. It is not command output. */
    data class Progress(val message: String) : ToolExecutionEvent

    /** Exactly one authoritative model-facing result. */
    data class Completed(val result: String) : ToolExecutionEvent
}

/**
 * Interface for tool providers that supply tool definitions and execution
 * logic to the LLM generation pipeline. Each implementation manages a
 * specific category of tools (memory, web search, RAG, shell, etc.).
 */
interface ToolProvider {
    /** The tool definitions this provider exposes for the given context.
     *  Returns empty list when the provider is disabled. */
    fun definitions(ctx: GenerationContext): List<ToolDefinition>

    /** Execute a named tool with the given JSON arguments string.
     *  Returns the result string (usually JSON). */
    suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String

    /**
     * Streaming execution contract. One-shot providers inherit the adapter; streaming providers
     * emit progress/deltas and finish with exactly one [ToolExecutionEvent.Completed].
     */
    fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(execute(name, arguments, ctx)))
    }

    /** Whether this provider can execute the given tool name. */
    fun handles(name: String): Boolean
}
