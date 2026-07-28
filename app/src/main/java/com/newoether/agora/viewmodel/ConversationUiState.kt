package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

data class ConversationUiState(
    val path: List<ChatMessage> = emptyList(),
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMsg: ChatMessage? = null,
    val isLoading: Boolean = false,
    val selectedChildren: Map<String?, String> = emptyMap()
) {
    companion object {
        /** Walk the conversation tree to produce the visible path. */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            val path = mutableListOf<ChatMessage>()
            // An intervention is durable as soon as Send succeeds, but while the current model
            // Pass is still on screen it belongs exclusively to the queue banner. Advancing the
            // visible message path here would make the live model cease to be the last message,
            // incorrectly rendering its in-flight status as a terminal warning. Once the Pass
            // releases (or Stop/recovery clears the overlay), the durable input becomes visible.
            val messagesForPath = if (streamingMsg != null) {
                allMessages.filterNot(::isPendingVisibleIntervention)
            } else {
                allMessages
            }
            val messagesByParent = messagesForPath.groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            var cursor: String? = null

            while (true) {
                val siblings = messagesByParent[cursor] ?: break
                if (siblings.isEmpty()) break

                val selectedId = selectedChildren[cursor]
                val visibleSiblings = siblings.filterNot(::isSynthetic)
                var selected = if (visibleSiblings.isNotEmpty()) {
                    visibleSiblings.find { it.id == selectedId } ?: visibleSiblings.last()
                } else {
                    siblings.find { it.id == selectedId } ?: siblings.last()
                }
                // Substitute streaming message if it matches
                if (streamingMsg != null && selected.id == streamingMsg.id) {
                    selected = streamingMsg
                }
                val isSynthetic = isSynthetic(selected)
                if (!isSynthetic || (streamingMsg != null && selected.id == streamingMsg.id)) {
                    path.add(selected)
                }
                cursor = selected.id
            }
            // Append streaming message if not yet in path
            if (streamingMsg != null && path.none { it.id == streamingMsg.id }) {
                val lastId = path.lastOrNull()?.id
                if (streamingMsg.parentId == lastId || (streamingMsg.parentId == null && path.isEmpty())) {
                    path.add(streamingMsg)
                }
            }
            return path
        }

        /**
         * Only a real user intervention can be queue-only while a Pass is streaming. Tool-result
         * rows also use Participant.USER and consumedAtPass=null, but they are durable protocol
         * edges: filtering one severs every visible descendant after that tool round.
         */
        private fun isPendingVisibleIntervention(message: ChatMessage): Boolean =
            message.participant == Participant.USER &&
                !isSynthetic(message) &&
                !message.runId.isNullOrBlank() &&
                message.consumedAtPass == null

        private fun isSynthetic(message: ChatMessage): Boolean =
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    }
}
