package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import java.util.UUID

enum class Participant {
    USER, MODEL, ERROR
}

enum class MessageStatus {
    SENDING, THINKING, SUCCESS, STOPPED, ERROR
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val text: String,
    val thoughts: String? = null,
    val tokenCount: Int = 0,
    val status: MessageStatus = MessageStatus.SUCCESS, // Default to SUCCESS for old messages
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList()
)
