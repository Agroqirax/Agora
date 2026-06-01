package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

/**
 * Full message preparation pipeline: context window truncation, consecutive
 * same-role merge, then tool message validation. All providers MUST call this
 * before converting messages to their API format.
 */
fun prepareMessages(messages: List<ChatMessage>, maxUserMessages: Int): List<ChatMessage> {
    return validateToolMessages(mergeConsecutiveSameRole(limitContext(messages, maxUserMessages)))
}

/**
 * Merges consecutive non-tool messages that share the same participant.
 * This handles orphans left by message deletion (e.g. two user messages
 * in a row after removing an assistant reply) and keeps the message list
 * compliant with providers that require strict role alternation.
 *
 * Tool messages (tool_/result_) pass through unchanged — they are validated
 * separately by [validateToolMessages].
 */
fun mergeConsecutiveSameRole(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val result = mutableListOf<ChatMessage>()
    var i = 0
    while (i < messages.size) {
        val current = messages[i]
        val isTool = current.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            current.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (isTool) {
            result.add(current)
            i++
            continue
        }
        // Find consecutive messages with the same participant
        var j = i + 1
        while (j < messages.size) {
            val next = messages[j]
            val nextIsTool = next.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                next.id.startsWith(Constants.RESULT_MSG_PREFIX)
            if (nextIsTool || next.participant != current.participant) break
            j++
        }
        if (j == i + 1) {
            // No merge needed
            result.add(current)
        } else {
            // Merge messages[i..j-1] into one
            val merged = messages.subList(i, j)
            val mergedText = merged.joinToString("\n") { it.text }
            val mergedImages = merged.flatMap { it.images }
            result.add(current.copy(text = mergedText, images = mergedImages))
        }
        i = j
    }
    return result
}

/**
 * Validates tool_ / result_ message pairing and fixes ID mismatches.
 *
 * Rules enforced:
 *  - Every tool_ message must be immediately followed by >= 1 result_ message
 *  - Every result_ message must be immediately preceded by a tool_ message
 *  - Each result_ segment's toolCallId matches the corresponding tool_use segment
 *
 * Orphaned tool_ and result_ messages are dropped. All non-tool messages
 * pass through unchanged.
 */
fun validateToolMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        when {
            msg.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                val resultMessages = mutableListOf<ChatMessage>()
                var j = i + 1
                while (j < messages.size && messages[j].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                    resultMessages.add(messages[j])
                    j++
                }
                if (resultMessages.isNotEmpty()) {
                    result.add(msg)
                    result.addAll(fixToolIds(msg, resultMessages))
                    i = j
                } else {
                    i++ // orphan tool_ — drop
                }
            }
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                i++ // orphan result_ — drop
            }
            else -> {
                result.add(msg)
                i++
            }
        }
    }
    return result
}

/**
 * Fixes toolCallId mismatches between a tool_ message's tool-use segments and
 * the following result_ messages. Match is by position: Nth result_ → Nth tool-use.
 */
private fun fixToolIds(
    toolMsg: ChatMessage,
    resultMessages: List<ChatMessage>
): List<ChatMessage> {
    val toolSegments = toolMsg.segments?.filter { it.type == "tool" } ?: return resultMessages
    if (toolSegments.isEmpty()) return resultMessages
    val useIds = toolSegments.mapNotNull { it.toolCallId }
    if (useIds.size != toolSegments.size) return resultMessages

    return resultMessages.mapIndexed { idx, resultMsg ->
        if (idx >= useIds.size) return@mapIndexed resultMsg
        val correctId = useIds[idx]

        val fixedSegments = resultMsg.segments?.map { seg ->
            if (seg.type == "tool" && seg.toolCallId != correctId) seg.copy(toolCallId = correctId) else seg
        }
        val fixedToolCall = resultMsg.toolCall?.let { tc ->
            if (tc.toolCallId != correctId) tc.copy(toolCallId = correctId) else tc
        }
        if (fixedSegments != resultMsg.segments || fixedToolCall != resultMsg.toolCall) {
            resultMsg.copy(segments = fixedSegments, toolCall = fixedToolCall)
        } else resultMsg
    }
}
