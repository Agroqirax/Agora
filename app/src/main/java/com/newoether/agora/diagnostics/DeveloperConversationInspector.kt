package com.newoether.agora.diagnostics

import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationRuntimeTrace
import com.newoether.agora.model.ConversationRuntimeTraceEntry

data class DeveloperMessageInspection(
    val index: Int,
    val messageIdHash: String,
    val parentIdHash: String?,
    val participant: String,
    val status: String,
    val textChars: Int,
    val thoughtChars: Int,
    val imageCount: Int,
    val segmentCount: Int,
    val tokenCount: Int,
    val model: String?,
    val runIdHash: String?,
    val hasToolCall: Boolean,
    val hasAttachment: Boolean,
)

data class DeveloperRuntimeTransitionInspection(
    val sequence: Long,
    val runIdHash: String?,
    val pass: Int,
    val effectIdHash: String?,
    val oldState: String,
    val commandType: String,
    val newState: String,
    val effectTypes: List<String>,
    val timestamp: Long,
)

data class DeveloperConversationInspection(
    val conversationIdHash: String,
    val model: String?,
    val origin: String,
    val taskLinked: Boolean,
    val messageCount: Int,
    val omittedMessageCount: Int,
    val totalTokens: Int,
    val isLoading: Boolean,
    val participantCounts: Map<String, Int>,
    val statusCounts: Map<String, Int>,
    val messages: List<DeveloperMessageInspection>,
    val runtimeTransitions: List<DeveloperRuntimeTransitionInspection>,
)

object DeveloperConversationInspector {
    fun inspect(
        conversation: ChatConversation?,
        messages: List<ChatMessage>,
        totalTokens: Int,
        isLoading: Boolean,
        runtimeTransitions: List<ConversationRuntimeTraceEntry>,
    ): DeveloperConversationInspection? {
        conversation ?: return null
        val retainedMessages = messages.takeLast(MAX_MESSAGES)
        return DeveloperConversationInspection(
            conversationIdHash = hash(conversation.id),
            model = conversation.modelId?.let(DiagnosticRedactor::safeIdentifier),
            origin = DiagnosticRedactor.safeIdentifier(conversation.origin),
            taskLinked = conversation.taskId != null,
            messageCount = messages.size,
            omittedMessageCount = (messages.size - retainedMessages.size).coerceAtLeast(0),
            totalTokens = totalTokens.coerceAtLeast(0),
            isLoading = isLoading,
            participantCounts = messages
                .groupingBy { it.participant.name }
                .eachCount()
                .toSortedMap(),
            statusCounts = messages
                .groupingBy { it.status.name }
                .eachCount()
                .toSortedMap(),
            messages = retainedMessages.mapIndexed { retainedIndex, message ->
                DeveloperMessageInspection(
                    index = messages.size - retainedMessages.size + retainedIndex,
                    messageIdHash = hash(message.id),
                    parentIdHash = message.parentId?.let(::hash),
                    participant = message.participant.name,
                    status = message.status.name,
                    textChars = message.text.length,
                    thoughtChars = message.thoughts?.length ?: 0,
                    imageCount = message.images.size,
                    segmentCount = message.segments?.size ?: 0,
                    tokenCount = message.tokenCount.coerceAtLeast(0),
                    model = message.modelName?.let(DiagnosticRedactor::safeIdentifier),
                    runIdHash = message.runId?.let(::hash),
                    hasToolCall = message.toolCall != null,
                    hasAttachment = message.attachmentMeta != null,
                )
            },
            runtimeTransitions = runtimeTransitions
                .takeLast(MAX_RUNTIME_TRANSITIONS)
                .map { transition ->
                    DeveloperRuntimeTransitionInspection(
                        sequence = transition.sequence,
                        runIdHash = transition.runId?.let(::hash),
                        pass = transition.pass,
                        effectIdHash = transition.effectId?.let(::hash),
                        oldState = transition.oldState,
                        commandType = transition.commandType,
                        newState = transition.newState,
                        effectTypes = transition.effectTypes,
                        timestamp = transition.timestamp,
                    )
                },
        )
    }

    fun format(inspection: DeveloperConversationInspection): String = buildString {
        appendLine("conversation=" + inspection.conversationIdHash)
        appendLine("model=" + inspection.model.orEmpty())
        appendLine("origin=" + inspection.origin)
        appendLine("taskLinked=" + inspection.taskLinked)
        appendLine("messages=" + inspection.messageCount)
        appendLine("omittedMessages=" + inspection.omittedMessageCount)
        appendLine("totalTokens=" + inspection.totalTokens)
        appendLine("isLoading=" + inspection.isLoading)
        appendLine("participants=" + inspection.participantCounts)
        appendLine("statuses=" + inspection.statusCounts)
        appendLine("runtimeTransitions=" + inspection.runtimeTransitions.size)
        inspection.messages.forEach { message ->
            appendLine()
            appendLine(
                "message[" + message.index + "]=" + message.participant + "/" + message.status,
            )
            appendLine("id=" + message.messageIdHash)
            appendLine("parent=" + message.parentIdHash.orEmpty())
            appendLine("textChars=" + message.textChars)
            appendLine("thoughtChars=" + message.thoughtChars)
            appendLine("images=" + message.imageCount)
            appendLine("segments=" + message.segmentCount)
            appendLine("tokens=" + message.tokenCount)
            appendLine("model=" + message.model.orEmpty())
            appendLine("run=" + message.runIdHash.orEmpty())
            appendLine("toolCall=" + message.hasToolCall)
            appendLine("attachment=" + message.hasAttachment)
        }
        inspection.runtimeTransitions.forEach { transition ->
            appendLine()
            appendLine(
                "transition[" + transition.sequence + "]=" +
                    transition.commandType + " " +
                    transition.oldState + " → " + transition.newState,
            )
            appendLine("run=" + transition.runIdHash.orEmpty())
            appendLine("pass=" + transition.pass)
            appendLine("effect=" + transition.effectIdHash.orEmpty())
            appendLine("effects=" + transition.effectTypes.joinToString())
            appendLine("timestamp=" + transition.timestamp)
        }
    }

    private fun hash(value: String): String =
        ConversationRuntimeTrace.hashConversationId(value)

    private const val MAX_MESSAGES = 256
    private const val MAX_RUNTIME_TRANSITIONS = 128
}
