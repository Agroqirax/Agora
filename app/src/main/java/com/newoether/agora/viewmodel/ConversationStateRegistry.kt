package com.newoether.agora.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped registry of per-conversation generation state. Each conversation gets its own
 * [ConversationGenerationState] on first use; the entry is removed (and its scope cancelled) when
 * the conversation is deleted.
 *
 * This is the structural fix for the process-global single-slot generation state that caused
 * cross-conversation races (G2–G10): two conversations now hold independent `streamingMessage` /
 * `isLoading` / `generationJob` / `persistId` / `sendGate`, so a generation on conversation B
 * cannot clobber conversation A's UI mirror, skip A's DB persist, or get killed by A's Stop.
 *
 * The registry itself holds no generation logic — it only owns the lifecycle of the per-
 * conversation state objects. Generation entry points ([MessageGenerationController]) obtain a
 * state via [getOrCreate] and operate on it; ChatViewModel mirrors the currently-open
 * conversation's private flows into the global UI StateFlows.
 */
class ConversationStateRegistry {

    private val states = ConcurrentHashMap<String, ConversationGenerationState>()
    private val uiCallbackLock = Any()
    private var uiCallbackOwner: Any? = null
    private var uiCallbackBinder: ((ConversationGenerationState) -> Unit)? = null

    private val _activeConversationIds = MutableStateFlow<Set<String>>(emptySet())
    /** Conversation ids that currently have an active generation. Drives Stop-button visibility
     *  per conversation and the multi-conversation generating indicator. */
    val activeConversationIds: StateFlow<Set<String>> = _activeConversationIds.asStateFlow()

    fun getOrCreate(conversationId: String): ConversationGenerationState {
        val state = states.computeIfAbsent(conversationId) {
            ConversationGenerationState(
                conversationId = it,
                onRegistryActive = ::markActive,
                onRegistryIdle = ::markIdle,
            )
        }
        // Re-applying the current binder is idempotent and closes the race where a state is
        // created concurrently with an Activity/ViewModel attaching its UI observer.
        synchronized(uiCallbackLock) {
            uiCallbackBinder?.invoke(state)
        }
        return state
    }

    fun get(conversationId: String): ConversationGenerationState? = states[conversationId]

    /**
     * Bind process-owned generation states to the currently alive ChatViewModel. Reopening the
     * Activity replaces only these UI callbacks; jobs, STOPPING ownership, queues and streams stay
     * in this registry. [owner] prevents a late onCleared from an older ViewModel detaching a newer
     * observer.
     */
    fun attachUiCallbacks(
        owner: Any,
        binder: (ConversationGenerationState) -> Unit,
    ) {
        synchronized(uiCallbackLock) {
            uiCallbackOwner = owner
            uiCallbackBinder = binder
            states.values.forEach(binder)
        }
    }

    fun detachUiCallbacks(owner: Any) {
        synchronized(uiCallbackLock) {
            if (uiCallbackOwner !== owner) return
            states.values.forEach { state ->
                state.onActive = null
                state.onIdle = null
                state.onStreamCommit = null
            }
            uiCallbackOwner = null
            uiCallbackBinder = null
        }
    }

    /** Mark a conversation as actively generating. */
    fun markActive(conversationId: String) {
        _activeConversationIds.update { it + conversationId }
    }

    /** Mark a conversation as no longer generating. */
    fun markIdle(conversationId: String) {
        _activeConversationIds.update { it - conversationId }
    }

    fun isActive(conversationId: String): Boolean = conversationId in _activeConversationIds.value

    /** Remove and cancel a conversation's state. Called when the conversation is deleted. */
    fun remove(conversationId: String) {
        states.remove(conversationId)?.also {
            // stop() first: cancelScope alone cancels the coroutines but cannot interrupt a
            // blocking OkHttp read — the socket would linger until its read timeout. stop()
            // severs this conversation's in-flight streams immediately.
            it.stop()
            it.cancelScope()
            // Queue entries mirror already-persisted intervention rows. Conversation deletion
            // owns their attachment cleanup; dropping the in-memory mirror must not delete files
            // independently of Room.
            it.takeQueuedSends()
        }
        markIdle(conversationId)
    }

    /** Stop a specific conversation's generation (only that one). Returns null if no state. */
    fun stop(conversationId: String): ConversationGenerationState.StopResult? {
        val state = states[conversationId] ?: return null
        return state.stop()
    }

    /** Cancel every conversation's state (e.g. on ViewModel cleared). */
    fun cancelAll() {
        synchronized(uiCallbackLock) {
            uiCallbackOwner = null
            uiCallbackBinder = null
        }
        states.values.forEach {
            it.streamScope.cancelAll()
            it.cancelScope()
        }
        states.clear()
        _activeConversationIds.value = emptySet()
    }
}
