package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

/**
 * Full message preparation pipeline: context window truncation, consecutive
 * same-role merge, empty-turn stripping, then tool message validation. All providers MUST
 * call this before converting messages to their API format.
 */
fun prepareMessages(messages: List<ChatMessage>, maxUserMessages: Int): List<ChatMessage> {
    return validateToolMessages(
        stripEmptyTurns(mergeConsecutiveSameRole(limitContext(messages, maxUserMessages)))
    )
}

/**
 * Converts durable terminal generation rows into model-visible status events without presenting
 * client/provider failures as genuine assistant output.
 *
 * The database/UI message remains untouched. In the API-only path, ERROR and STOPPED rows become
 * user-role status text with all assistant/tool payload removed. When the next row is a normal user
 * message, the status is prepended to it so context-window truncation cannot retain the follow-up
 * while silently dropping the immediately preceding status.
 */
fun projectGenerationStatusesForApi(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.none(ChatMessage::isGenerationStatusMessage)) return messages

    val projected = mutableListOf<ChatMessage>()
    val pendingStatuses = mutableListOf<ChatMessage>()

    fun flushPending() {
        projected.addAll(pendingStatuses.map(ChatMessage::asGenerationStatusEvent))
        pendingStatuses.clear()
    }

    messages.forEach { message ->
        when {
            message.isGenerationStatusMessage() -> pendingStatuses += message
            pendingStatuses.isNotEmpty() &&
                message.participant == Participant.USER &&
                !message.isToolProtocolMessage() -> {
                val statusText = pendingStatuses.joinToString("\n\n") {
                    it.generationStatusEventText()
                }
                projected += message.copy(
                    text = listOf(statusText, message.text)
                        .filter(String::isNotBlank)
                        .joinToString("\n\n")
                )
                pendingStatuses.clear()
            }
            else -> {
                flushPending()
                projected += message
            }
        }
    }
    flushPending()
    return projected
}

private fun ChatMessage.isGenerationStatusMessage(): Boolean =
    !isToolProtocolMessage() &&
        (participant == Participant.ERROR ||
            status == MessageStatus.ERROR ||
            status == MessageStatus.STOPPED)

private fun ChatMessage.asGenerationStatusEvent(): ChatMessage = copy(
    text = generationStatusEventText(),
    images = emptyList(),
    thoughts = null,
    thoughtTitle = null,
    tokenCount = 0,
    status = MessageStatus.SUCCESS,
    participant = Participant.USER,
    thoughtTimeMs = null,
    modelName = null,
    toolCall = null,
    segments = null,
    attachmentMeta = null,
    retryText = null,
)

private fun ChatMessage.generationStatusEventText(): String {
    val detail = text.trim()
    return when {
        participant == Participant.ERROR || status == MessageStatus.ERROR ->
            buildString {
                append("[Generation status: ERROR]\n")
                append("The previous assistant generation failed before completing.")
                if (detail.isNotEmpty()) {
                    append("\nDetails:\n")
                    append(detail)
                }
            }
        else ->
            buildString {
                append("[Generation status: STOPPED]\n")
                append("The previous assistant generation was stopped before completing.")
                if (detail.isNotEmpty()) {
                    append("\nPartial output:\n")
                    append(detail)
                }
            }
    }
}

/**
 * Drops turns that would serialize to an empty/whitespace-only content block.
 *
 * Anthropic hard-rejects those with `400 messages: text content blocks must contain
 * non-whitespace text`, and other providers silently degrade on them. Such turns are
 * routine in practice: a generation stopped before its first token, an interrupted turn
 * that emitted only a newline, or two blank messages merged with "\n".
 *
 * A turn survives if it carries anything else of substance — images, or tool protocol
 * payload (tool_/result_ rows, whose content lives in segments/toolCall, not text).
 * Runs AFTER the merge so a blank fragment absorbed into a non-blank neighbor is kept.
 */
fun stripEmptyTurns(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filter { msg ->
        msg.text.isNotBlank() ||
            msg.images.isNotEmpty() ||
            msg.isToolProtocolMessage() ||
            msg.toolCall != null ||
            msg.segments?.any { it.type == "tool" } == true
    }

/**
 * Builds an API-only view where assistant-generated images remain available for
 * visual follow-ups without being serialized as assistant-side image content.
 *
 * Chat completion schemas treat images as user inputs. Agora stores generated
 * images on model messages for display, so the latest generated image set is
 * projected onto the latest normal user message when images are being sent.
 */
fun projectAssistantImagesToLatestUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean
): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val latestUserIndex = messages.indexOfLast { it.isNormalUserMessage() }
    val generatedImages = if (includeImages && latestUserIndex >= 0) {
        messages
            .asSequence()
            .take(latestUserIndex)
            .filter { it.isNormalAssistantMessage() && it.images.isNotEmpty() }
            .lastOrNull()
            ?.images
            ?.filter { it.isNotBlank() }
            .orEmpty()
    } else {
        emptyList()
    }

    var changed = false
    val projected = messages.mapIndexed { index, msg ->
        var next = msg
        if (msg.isNormalAssistantMessage() && msg.images.isNotEmpty()) {
            next = next.copy(images = emptyList())
            changed = true
        }
        if (index == latestUserIndex && generatedImages.isNotEmpty()) {
            next = next.copy(
                text = addGeneratedImageContextNote(next.text, generatedImages.size),
                images = (generatedImages + next.images).distinct()
            )
            changed = true
        }
        next
    }

    return if (changed) projected else messages
}

private fun ChatMessage.isNormalUserMessage(): Boolean =
    participant == Participant.USER && !isToolProtocolMessage()

private fun ChatMessage.isNormalAssistantMessage(): Boolean =
    participant == Participant.MODEL && !isToolProtocolMessage()

internal fun ChatMessage.isToolProtocolMessage(): Boolean =
    id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)

private fun addGeneratedImageContextNote(text: String, imageCount: Int): String {
    val note = if (imageCount == 1) {
        "[Visual context: the first attached image was generated by the assistant earlier in this conversation.]"
    } else {
        "[Visual context: the first $imageCount attached images were generated by the assistant earlier in this conversation.]"
    }
    return if (text.isBlank()) note else "$note\n\n$text"
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
 *  - Every tool_ message must be immediately followed by one result per emitted tool call
 *  - Every result_ message must be immediately preceded by a tool_ message
 *  - Each result_ segment's toolCallId matches the corresponding tool_use segment
 *
 * An incomplete parallel tool round is dropped as a whole from the API-only path. Keeping a
 * partially answered assistant tool_calls turn is protocol-invalid on OpenAI-compatible APIs and
 * changes its semantics if the call list is silently truncated.
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
                val toolUseIds = extractToolUseIds(msg)
                val normalizedResults = toolUseIds?.let { normalizeToolResults(it, resultMessages) }
                if (normalizedResults != null) {
                    result.add(msg)
                    result.addAll(normalizedResults)
                    i = j
                } else {
                    // Drop the assistant tool call plus every immediately-following partial/extra
                    // result. Otherwise the next loop would reinterpret those rows as orphans.
                    i = j
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

private fun extractToolUseIds(toolMsg: ChatMessage): List<String>? {
    val toolSegments = toolMsg.segments?.filter { it.type == "tool" }.orEmpty()
    val ids = if (toolSegments.isNotEmpty()) {
        toolSegments.map { it.toolCallId?.takeIf(String::isNotBlank) ?: return null }
    } else {
        listOf(toolMsg.toolCall?.toolCallId?.takeIf(String::isNotBlank) ?: return null)
    }
    return ids.takeIf { it.isNotEmpty() && it.distinct().size == it.size }
}

/**
 * Normalizes every result payload against the assistant's call IDs by position. A synthetic result
 * row normally carries one payload, but legacy imports can carry several segments in one row; both
 * forms are counted correctly. Returns null unless every tool call has exactly one usable result.
 * Extra result rows/segments are dropped.
 */
private fun normalizeToolResults(
    useIds: List<String>,
    resultMessages: List<ChatMessage>
): List<ChatMessage>? {
    val normalized = mutableListOf<ChatMessage>()
    var useIndex = 0
    for (resultMsg in resultMessages) {
        if (useIndex >= useIds.size) break
        val toolSegments = resultMsg.segments?.filter { it.type == "tool" }.orEmpty()
        if (toolSegments.isNotEmpty()) {
            val kept = toolSegments.take(useIds.size - useIndex).map { segment ->
                segment.copy(toolCallId = useIds[useIndex++])
            }
            normalized += resultMsg.copy(
                segments = kept,
                toolCall = resultMsg.toolCall?.takeIf { kept.size == 1 }?.copy(
                    toolCallId = kept.single().toolCallId
                ),
            )
        } else {
            val toolCall = resultMsg.toolCall ?: continue
            normalized += resultMsg.copy(
                toolCall = toolCall.copy(toolCallId = useIds[useIndex++])
            )
        }
    }
    return normalized.takeIf { useIndex == useIds.size }
}
