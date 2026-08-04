package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One atomic render snapshot for the open conversation.
 *
 * Message rows, the streaming overlay, and selected graph edges jointly determine the visible
 * path. Keeping them in independent StateFlows allowed Compose to observe combinations that never
 * existed durably (for example, a newly inserted edit branch with the old selected edge). Every
 * structural transition now commits through this store as one StateFlow value.
 */
internal data class ConversationRenderSnapshot(
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val selectedChildren: Map<String?, String> = emptyMap(),
)

internal class ConversationRenderStore {
    private val _snapshot = MutableStateFlow(ConversationRenderSnapshot())
    val snapshot: StateFlow<ConversationRenderSnapshot> = _snapshot.asStateFlow()
    private val streamingHandoffLock = Any()
    private var retiredStreamingMessageId: String? = null

    val allMessages: List<ChatMessage> get() = _snapshot.value.allMessages
    val streamingMessage: ChatMessage? get() = _snapshot.value.streamingMessage
    val selectedChildren: Map<String?, String> get() = _snapshot.value.selectedChildren

    fun replaceConversation(
        allMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage? = null,
    ) {
        synchronized(streamingHandoffLock) {
            retiredStreamingMessageId = null
            _snapshot.value = ConversationRenderSnapshot(
                allMessages = allMessages,
                streamingMessage = streamingMessage,
                selectedChildren = selectedChildren.toMap(),
            )
        }
    }

    fun clear() {
        synchronized(streamingHandoffLock) {
            retiredStreamingMessageId = null
            _snapshot.value = ConversationRenderSnapshot()
        }
    }

    fun setAllMessages(messages: List<ChatMessage>) {
        synchronized(streamingHandoffLock) {
            val retiredId = retiredStreamingMessageId
            if (retiredId == null) {
                _snapshot.update { it.copy(allMessages = messages) }
                return
            }

            val incoming = messages.firstOrNull { it.id == retiredId }
            val currentTerminal = _snapshot.value.allMessages.firstOrNull {
                it.id == retiredId && !it.status.isStreamingStatus()
            }
            when {
                // A deletion is authoritative; never retain a terminal row as a ghost.
                incoming == null -> {
                    retiredStreamingMessageId = null
                    _snapshot.update { it.copy(allMessages = messages) }
                }
                // Room has caught up to a terminal checkpoint. Keep the monotonic fence until a
                // new stream starts (or the row is deleted), because an older projection may
                // already have completed its off-main mapping and still be queued for delivery.
                !incoming.status.isStreamingStatus() -> {
                    _snapshot.update { it.copy(allMessages = messages) }
                }
                // A projection that started before the terminal transaction must not regress the
                // visible row from SUCCESS/ERROR/STOPPED back to Answering.
                currentTerminal != null -> {
                    _snapshot.update { snapshot ->
                        snapshot.copy(
                            allMessages = messages.map { message ->
                                if (message.id == retiredId) currentTerminal else message
                            },
                        )
                    }
                }
                else -> {
                    retiredStreamingMessageId = null
                    _snapshot.update { it.copy(allMessages = messages) }
                }
            }
        }
    }

    fun updateAllMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        _snapshot.update { current -> current.copy(allMessages = transform(current.allMessages)) }
    }

    fun setStreamingMessage(message: ChatMessage?) {
        synchronized(streamingHandoffLock) {
            // Stop finalization commits the terminal row and retires its overlay atomically.
            // A combined-flow emission that was already queued before that handoff must not put
            // the same STOPPED overlay back for one frame.
            if (message?.id == retiredStreamingMessageId) return
            if (message != null) retiredStreamingMessageId = null
            _snapshot.update { it.copy(streamingMessage = message) }
        }
    }

    /**
     * Hands a terminal streaming message from the in-memory overlay to the persisted-message
     * projection in one render snapshot. Compose therefore never observes the older Room
     * checkpoint between overlay removal and Room's terminal invalidation.
     */
    fun commitTerminalStreamingMessage(message: ChatMessage) {
        synchronized(streamingHandoffLock) {
            val current = _snapshot.value
            val ownsVisibleOverlay =
                current.streamingMessage == null || current.streamingMessage.id == message.id
            if (ownsVisibleOverlay) retiredStreamingMessageId = message.id
            _snapshot.value = current.copy(
                allMessages = UiMessageCommitPolicy.upsert(
                    existing = current.allMessages,
                    committed = listOf(message),
                ),
                streamingMessage =
                    if (ownsVisibleOverlay) null else current.streamingMessage,
            )
        }
    }

    fun setSelectedChildren(selections: Map<String?, String>) {
        _snapshot.update { it.copy(selectedChildren = selections.toMap()) }
    }

    fun commitGraph(
        committedMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage?,
    ) {
        synchronized(streamingHandoffLock) {
            if (streamingMessage != null && streamingMessage.id != retiredStreamingMessageId) {
                retiredStreamingMessageId = null
            }
            _snapshot.update { current ->
                current.copy(
                    allMessages = UiMessageCommitPolicy.upsert(
                        existing = current.allMessages,
                        committed = committedMessages,
                    ),
                    streamingMessage = streamingMessage,
                    selectedChildren = selectedChildren.toMap(),
                )
            }
        }
    }

    fun replaceGraph(
        allMessages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
    ) {
        _snapshot.update {
            it.copy(
                allMessages = allMessages,
                selectedChildren = selectedChildren.toMap(),
            )
        }
    }
}

private fun MessageStatus.isStreamingStatus(): Boolean =
    this == MessageStatus.TRANSCRIBING ||
        this == MessageStatus.SENDING ||
        this == MessageStatus.THINKING ||
        this == MessageStatus.TOOL_CALLING
