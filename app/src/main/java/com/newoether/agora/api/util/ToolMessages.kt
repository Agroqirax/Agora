package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Full fail-closed message preparation pipeline shared by every provider.
 *
 * The result is a canonical history:
 *  - duplicate persisted rows are removed by id;
 *  - status rows are projected into model-visible user events;
 *  - tool calls/results are complete atomic rounds with globally unique call ids;
 *  - context truncation never splits a tool round;
 *  - the history starts with a normal user turn and has no empty normal turns.
 */
fun prepareMessages(messages: List<ChatMessage>, maxUserMessages: Int): List<ChatMessage> {
    val canonical = validateToolMessages(
        stripEmptyTurns(
            projectGenerationStatusesForApi(messages.distinctBy(ChatMessage::id))
        )
    )
    val merged = stripEmptyTurns(mergeConsecutiveSameRole(canonical))
    return stripEmptyTurns(
        mergeConsecutiveSameRole(limitContext(merged, maxUserMessages))
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
 * Validates and canonicalizes complete tool_ / result_ protocol rounds.
 *
 * Rules enforced:
 *  - Every tool_ message must be immediately followed by one result per emitted tool call
 *  - Every result_ message must be immediately preceded by a tool_ message
 *  - Each result_ segment's toolCallId matches the corresponding tool_use segment
 *
 * Missing legacy IDs may be synthesized only when every result is unambiguously paired by
 * cardinality and position. Explicit conflicting IDs, duplicate IDs, malformed arguments, and
 * extra/missing results are never "repaired" by relabeling or truncation: the whole round is
 * replayed as ordinary context instead, so an invalid protocol sequence cannot reach a provider
 * and a result can never be attached to the wrong call.
 */
fun validateToolMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    val seenToolCallIds = mutableSetOf<String>()
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
                val normalizedCalls = normalizeToolCalls(msg, seenToolCallIds)
                val normalizedResults = normalizedCalls?.let { calls ->
                    normalizeToolResults(calls, resultMessages)
                }
                if (normalizedCalls != null && normalizedResults != null) {
                    result.add(normalizedCalls.message)
                    result.addAll(normalizedResults)
                    seenToolCallIds.addAll(normalizedCalls.callIds)
                    i = j
                } else {
                    result += toolRoundAsPlainContext(
                        toolMessage = msg,
                        resultMessages = resultMessages,
                        reason = "the stored tool round was incomplete or damaged",
                    )
                    i = j
                }
            }
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                result += toolRoundAsPlainContext(
                    toolMessage = null,
                    resultMessages = listOf(msg),
                    reason = "the stored tool result had no matching tool call",
                )
                i++
            }
            else -> {
                result.add(msg)
                i++
            }
        }
    }
    return result
}

private data class NormalizedToolCalls(
    val message: ChatMessage,
    val segments: List<MessageSegment>,
    val callIds: List<String>,
)

private val toolJson = Json { ignoreUnknownKeys = true }
private val safeToolCallId = safeWireToolCallId

private fun normalizeToolCalls(
    toolMsg: ChatMessage,
    alreadySeenIds: Set<String>,
): NormalizedToolCalls? {
    val sourceSegments = toolMsg.segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            val toolCall = toolMsg.toolCall ?: return null
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = toolCall.toolName,
                    toolArgs = toolCall.arguments,
                    toolResult = toolCall.result,
                    toolCallId = toolCall.toolCallId,
                    signature = toolCall.signature,
                )
            )
        }
    if (sourceSegments.isEmpty()) return null

    val explicitIds = sourceSegments.map { it.toolCallId?.takeIf(String::isNotBlank) }
    if (
        explicitIds.filterNotNull().any { !it.matches(safeToolCallId) } ||
        explicitIds.filterNotNull().distinct().size != explicitIds.filterNotNull().size ||
        explicitIds.filterNotNull().any(alreadySeenIds::contains)
    ) {
        return null
    }

    val reserved = alreadySeenIds.toMutableSet()
    val normalized = sourceSegments.mapIndexed { index, segment ->
        val name = segment.toolName
            ?.takeIf { it.matches(safeWireToolName) }
            ?: return null
        val arguments = normalizeArgumentsOrNull(segment.toolArgs) ?: return null
        val callId = segment.toolCallId?.takeIf(String::isNotBlank)
            ?: buildToolCallId("$name:${toolMsg.id}:$index", arguments)
        if (!reserved.add(callId)) return null
        segment.copy(
            toolName = name,
            toolArgs = arguments,
            toolCallId = callId,
            // Results belong only to the following result_ row in the API representation.
            toolResult = null,
        )
    }
    val normalizedToolCall = toolMsg.toolCall?.takeIf { normalized.size == 1 }?.copy(
        toolName = normalized.single().toolName.orEmpty(),
        arguments = normalized.single().toolArgs.orEmpty(),
        toolCallId = normalized.single().toolCallId,
    )
    var normalizedIndex = 0
    val rebuiltSegments = toolMsg.segments
        ?.map { segment ->
            if (segment.type == "tool") normalized[normalizedIndex++] else segment
        }
        ?: normalized
    return NormalizedToolCalls(
        message = toolMsg.copy(
            // Signed thought blocks are protocol state for Anthropic/Gemini. Replace only tool
            // segments and preserve every non-tool segment in its original order.
            segments = rebuiltSegments,
            toolCall = normalizedToolCall,
        ),
        segments = normalized,
        callIds = normalized.map { checkNotNull(it.toolCallId) },
    )
}

/**
 * Replaces provider-specific tool rounds that cannot safely be replayed with ordinary user
 * context. This is the last-resort compatibility path for opaque thought signatures: the model
 * still sees what ran and what it returned, while no foreign signature reaches the target API.
 */
fun adaptToolRoundsForProvider(
    messages: List<ChatMessage>,
    providerName: String,
    isCompatible: (ChatMessage) -> Boolean,
): List<ChatMessage> {
    if (messages.none(ChatMessage::isToolProtocolMessage)) return messages
    val result = mutableListOf<ChatMessage>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (!message.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX)) result += message
            index++
            continue
        }

        val resultMessages = mutableListOf<ChatMessage>()
        var end = index + 1
        while (
            end < messages.size &&
            messages[end].id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            resultMessages += messages[end++]
        }
        if (isCompatible(message)) {
            result += message
            result += resultMessages
        } else {
            result += toolRoundAsPlainContext(
                toolMessage = message,
                resultMessages = resultMessages,
                reason = "its opaque protocol metadata is not compatible with $providerName",
            )
        }
        index = end
    }
    return stripEmptyTurns(mergeConsecutiveSameRole(result))
}

/**
 * Normalizes every result payload against the assistant's call IDs by position. A synthetic result
 * row normally carries one payload, but legacy imports can carry several segments in one row; both
 * forms are counted correctly. Returns null unless every tool call has exactly one usable result.
 * Extra result rows/segments are dropped.
 */
private fun normalizeToolResults(
    calls: NormalizedToolCalls,
    resultMessages: List<ChatMessage>
): List<ChatMessage>? {
    data class ResultRef(
        val message: ChatMessage,
        val segment: MessageSegment?,
        val explicitId: String?,
    )

    val resultRefs = resultMessages.flatMap { message ->
        val segments = message.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
        if (segments.isNotEmpty()) {
            segments.map { segment ->
                ResultRef(message, segment, segment.toolCallId?.takeIf(String::isNotBlank))
            }
        } else {
            listOf(
                ResultRef(
                    message,
                    null,
                    message.toolCall?.toolCallId?.takeIf(String::isNotBlank),
                )
            )
        }
    }
    if (resultRefs.size != calls.segments.size) return null

    val explicitResultIds = resultRefs.map(ResultRef::explicitId)
    val hasExplicitResultIds = explicitResultIds.any { it != null }
    if (
        explicitResultIds.filterNotNull().any { !it.matches(safeToolCallId) } ||
        (hasExplicitResultIds && explicitResultIds.any { it == null }) ||
        explicitResultIds.filterNotNull().distinct().size != explicitResultIds.filterNotNull().size ||
        (hasExplicitResultIds && explicitResultIds.filterNotNull().toSet() != calls.callIds.toSet())
    ) {
        return null
    }

    val callById = calls.segments.associateBy { checkNotNull(it.toolCallId) }
    val normalized = mutableListOf<ChatMessage>()
    var callIndex = 0
    for (resultMsg in resultMessages) {
        val toolSegments = resultMsg.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
        if (toolSegments.isNotEmpty()) {
            val kept = toolSegments.map { segment ->
                val call = if (hasExplicitResultIds) {
                    segment.toolCallId?.let(callById::get) ?: return null
                } else {
                    calls.segments.getOrNull(callIndex) ?: return null
                }
                callIndex++
                segment.copy(
                    toolName = call.toolName,
                    toolArgs = call.toolArgs,
                    toolCallId = call.toolCallId,
                    toolResult = nonEmptyToolResult(segment.toolResult ?: resultMsg.text),
                    signature = call.signature,
                    signatureProvider = call.signatureProvider,
                )
            }
            normalized += resultMsg.copy(
                segments = kept,
                toolCall = resultMsg.toolCall?.takeIf { kept.size == 1 }?.copy(
                    toolCallId = kept.single().toolCallId
                ),
            )
        } else {
            val toolCall = resultMsg.toolCall
            val call = if (hasExplicitResultIds) {
                toolCall?.toolCallId?.let(callById::get) ?: return null
            } else {
                calls.segments.getOrNull(callIndex) ?: return null
            }
            callIndex++
            val result = nonEmptyToolResult(toolCall?.result ?: resultMsg.text)
            normalized += resultMsg.copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.toolArgs,
                        toolResult = result,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                        signatureProvider = call.signatureProvider,
                    )
                ),
                toolCall = toolCall?.copy(
                    toolName = call.toolName.orEmpty(),
                    arguments = call.toolArgs.orEmpty(),
                    result = result,
                    toolCallId = call.toolCallId,
                ),
            )
        }
    }
    return normalized.takeIf { callIndex == calls.segments.size }
}

private fun normalizeArgumentsOrNull(arguments: String?): String? {
    val parsed = runCatching {
        toolJson.parseToJsonElement(arguments?.takeIf(String::isNotBlank) ?: "{}")
    }.getOrNull()
    return (parsed as? JsonObject)?.toString()
}

private fun normalizeArguments(arguments: String?): String =
    normalizeArgumentsOrNull(arguments) ?: arguments.orEmpty().ifBlank { "{}" }

private fun nonEmptyToolResult(result: String): String =
    result.takeIf(String::isNotBlank) ?: "[Tool returned no textual output]"

private fun toolRoundAsPlainContext(
    toolMessage: ChatMessage?,
    resultMessages: List<ChatMessage>,
    reason: String,
): ChatMessage {
    val calls = toolMessage
        ?.segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            toolMessage?.toolCall?.let { call ->
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.arguments,
                        toolResult = call.result,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                    )
                )
            }.orEmpty()
        }
    val results = resultMessages.flatMap { message ->
        message.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
            .ifEmpty {
                message.toolCall?.let { call ->
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = call.toolName,
                            toolArgs = call.arguments,
                            toolResult = call.result.ifBlank { message.text },
                            toolCallId = call.toolCallId,
                        )
                    )
                } ?: listOf(
                    MessageSegment(
                        type = "tool",
                        toolResult = message.text,
                    )
                )
            }
    }
    val fallbackById = results
        .filter { !it.toolCallId.isNullOrBlank() }
        .associateBy { it.toolCallId }
    val unusedResults = results.toMutableList()
    val details = buildString {
        append("[Tool history notice: ")
        append(reason)
        append(". Replayed as plain context.]\n")
        if (calls.isEmpty()) {
            results.forEachIndexed { index, result ->
                append("\nResult ")
                append(index + 1)
                append(":\n")
                append(result.toolResult.orEmpty())
                append('\n')
            }
        } else {
            calls.forEachIndexed { index, call ->
                val exact = call.toolCallId?.let(fallbackById::get)
                if (exact != null) unusedResults.remove(exact)
                val paired = exact ?: unusedResults.removeFirstOrNull() ?: call
                append("\nTool ")
                append(index + 1)
                append(": ")
                append(call.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                append("\nArguments: ")
                append(normalizeArguments(call.toolArgs))
                append("\nResult:\n")
                append(paired.toolResult.orEmpty())
                append('\n')
            }
        }
    }.trimEnd()
    val source = toolMessage ?: resultMessages.first()
    val stableIdSeed = buildString {
        append(toolMessage?.id.orEmpty())
        resultMessages.forEach { append(':').append(it.id) }
        append(':').append(reason)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(stableIdSeed.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return ChatMessage(
        id = "protocol_notice_$digest",
        parentId = source.parentId,
        text = details,
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        timestamp = source.timestamp,
        runId = source.runId,
        runSequence = source.runSequence,
    )
}
