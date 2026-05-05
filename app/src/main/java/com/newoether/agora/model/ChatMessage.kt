package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ToolCallData(
    val toolName: String,
    val arguments: String,
    val result: String,
    val signature: String? = null
)

@Serializable
data class MessageSegment(
    val type: String, // "thought" or "tool"
    val content: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val signature: String? = null
)

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
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val status: MessageStatus = MessageStatus.SUCCESS, // Default to SUCCESS for old messages
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis(),
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCall: ToolCallData? = null,
    val segments: List<MessageSegment>? = null
)

@Immutable
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val systemPromptId: String? = null,
    val modelId: String? = null
)
