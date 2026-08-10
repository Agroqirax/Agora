package com.newoether.agora.model

import com.newoether.agora.util.Constants

/**
 * One user-visible generation: the nearest real USER input and the ordinary assistant messages
 * that follow it until the next real USER input. Run ids and runSequence are deliberately absent:
 * they are persistence/lifecycle metadata and are not message-boundary authority.
 */
internal data class MessageGenerationBoundary(
    val input: ChatMessage?,
    val firstAssistant: ChatMessage?,
    val lastAssistant: ChatMessage?,
)

internal object MessageGenerationBoundaryResolver {
    fun resolve(visibleMessages: List<ChatMessage>): List<MessageGenerationBoundary> {
        val boundaries = mutableListOf<MessageGenerationBoundary>()
        var input: ChatMessage? = null
        var firstAssistant: ChatMessage? = null
        var lastAssistant: ChatMessage? = null

        fun finishBoundary() {
            if (input != null || firstAssistant != null) {
                boundaries += MessageGenerationBoundary(input, firstAssistant, lastAssistant)
            }
        }

        visibleMessages.distinctBy(ChatMessage::id).forEach { message ->
            when {
                isRealUser(message) -> {
                    finishBoundary()
                    input = message
                    firstAssistant = null
                    lastAssistant = null
                }
                isOrdinaryAssistant(message) -> {
                    if (firstAssistant == null) firstAssistant = message
                    lastAssistant = message
                }
            }
        }
        finishBoundary()
        return boundaries
    }

    fun containing(
        visibleMessages: List<ChatMessage>,
        messageId: String,
    ): MessageGenerationBoundary? = resolve(visibleMessages).firstOrNull { boundary ->
        boundary.input?.id == messageId ||
            boundary.lastAssistant?.id == messageId
    }

    /** Cycle-safe structural lookup used to revalidate a UI boundary against a Room snapshot. */
    fun nearestInputAncestorId(
        allMessages: List<ChatMessage>,
        messageId: String,
    ): String? {
        val byId = allMessages.distinctBy(ChatMessage::id).associateBy(ChatMessage::id)
        var parentId = byId[messageId]?.parentId
        val visited = hashSetOf<String>()
        while (parentId != null && visited.add(parentId)) {
            val parent = byId[parentId] ?: return null
            if (isRealUser(parent)) return parent.id
            parentId = parent.parentId
        }
        return null
    }

    fun isRealUser(message: ChatMessage): Boolean =
        message.participant == Participant.USER &&
            !isProtocolRow(message) &&
            !message.isContextCompact()

    fun isOrdinaryAssistant(message: ChatMessage): Boolean =
        message.participant == Participant.MODEL &&
            !isProtocolRow(message) &&
            !message.isContextCompact()

    private fun isProtocolRow(message: ChatMessage): Boolean =
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX)
}
