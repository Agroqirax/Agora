package com.newoether.agora.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * Process-scoped serialization gate for conversation mutations that include generation.
 *
 * Both the foreground chat path and headless automation must acquire the same coordinator
 * before they resolve a leaf, append a user/model pair, and run the provider. A private mutex
 * in either caller is insufficient: the two paths would still be able to create sibling turns.
 *
 * Entries are reference counted so task-created conversation ids do not accumulate forever.
 * The coordinator is intentionally non-reentrant for a given id.
 */
class ConversationExecutionCoordinator {
    private class Entry {
        val mutex = Mutex()
        var references: Int = 0
    }

    private val monitor = Any()
    private val entries = mutableMapOf<String, Entry>()

    private val _activeConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val activeConversationIds: StateFlow<Set<String>> = _activeConversationIds.asStateFlow()

    suspend fun <T> withConversationLock(
        conversationId: String,
        block: suspend () -> T,
    ): T {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }

        val entry = synchronized(monitor) {
            entries.getOrPut(conversationId) { Entry() }.also { it.references++ }
        }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            _activeConversationIds.update { it + conversationId }
            return block()
        } finally {
            if (acquired) {
                // Clear before unlocking: a queued owner will add the id again after it acquires.
                _activeConversationIds.update { it - conversationId }
                entry.mutex.unlock()
            }
            synchronized(monitor) {
                entry.references--
                if (entry.references == 0) entries.remove(conversationId, entry)
            }
        }
    }

    fun isExecuting(conversationId: String): Boolean =
        conversationId in activeConversationIds.value

    internal fun trackedConversationCount(): Int = synchronized(monitor) { entries.size }
}
