package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.util.Constants

/**
 * A deterministic snapshot of the branch that is actually visible in the conversation UI.
 *
 * The message tree remains the visual source of truth. Run ancestry is then closed explicitly so
 * persistence operations can never detach a selected Run from one of its parents.
 */
internal data class ConversationBranchPath(
    val structuralMessages: List<MessageEntity>,
    val visibleMessages: List<MessageEntity>,
    val runIds: List<String>,
)

internal fun resolveConversationBranchPath(
    messages: List<MessageEntity>,
    runs: List<RunEntity>,
    selectedChildren: Map<String?, String>,
    throughMessageId: String? = null,
): ConversationBranchPath? {
    if (messages.isEmpty()) {
        return ConversationBranchPath(emptyList(), emptyList(), emptyList())
    }

    val structuralPath = resolveStructuralMessagePath(messages, selectedChildren) ?: return null
    val visiblePath = structuralPath.filterNot { it.isSynthetic() }
    val endMessage = throughMessageId?.let { messageId ->
        visiblePath.firstOrNull { it.id == messageId } ?: return null
    }
    val structuralEndIndex = endMessage?.let { message ->
        structuralPath.indexOfFirst { it.id == message.id }
    } ?: structuralPath.lastIndex
    val selectedStructuralPath = structuralPath.take(structuralEndIndex + 1)

    val runsById = runs.associateBy { it.id }
    val orderedRunIds = linkedSetOf<String>()
    val visiting = mutableSetOf<String>()

    fun includeRunWithAncestors(runId: String): Boolean {
        if (runId in orderedRunIds) return true
        if (!visiting.add(runId)) return false
        val run = runsById[runId] ?: return false
        val parentIncluded = run.parentRunId?.let(::includeRunWithAncestors) ?: true
        visiting.remove(runId)
        if (!parentIncluded) return false
        orderedRunIds += runId
        return true
    }

    for (message in selectedStructuralPath) {
        if (!includeRunWithAncestors(message.runId)) return null
    }

    val includedRunIds = orderedRunIds.toSet()
    return ConversationBranchPath(
        structuralMessages = structuralPath.filter { it.runId in includedRunIds },
        visibleMessages = visiblePath.filter { it.runId in includedRunIds },
        runIds = orderedRunIds.toList(),
    )
}

/**
 * Mirrors [ConversationUiState.resolvePath], but retains synthetic tool/result rows so a cloned
 * protocol graph can preserve the exact selected edge at every parent.
 */
private fun resolveStructuralMessagePath(
    messages: List<MessageEntity>,
    selectedChildren: Map<String?, String>,
): List<MessageEntity>? {
    val messagesByParent = messages
        .groupBy { it.parentId }
        .mapValues { (_, siblings) ->
            siblings.sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
        }
    val path = mutableListOf<MessageEntity>()
    val visited = mutableSetOf<String>()
    var cursor: String? = null

    while (true) {
        val siblings = messagesByParent[cursor].orEmpty()
        if (siblings.isEmpty()) break
        val selectedId = selectedChildren[cursor]
        val visibleSiblings = siblings.filterNot { it.isSynthetic() }
        val selected = if (visibleSiblings.isNotEmpty()) {
            visibleSiblings.firstOrNull { it.id == selectedId } ?: visibleSiblings.last()
        } else {
            siblings.firstOrNull { it.id == selectedId } ?: siblings.last()
        }
        if (!visited.add(selected.id)) return null
        path += selected
        cursor = selected.id
    }
    return path
}

internal fun MessageEntity.isSynthetic(): Boolean =
    id.startsWith(Constants.TOOL_MSG_PREFIX) ||
        id.startsWith(Constants.RESULT_MSG_PREFIX)
