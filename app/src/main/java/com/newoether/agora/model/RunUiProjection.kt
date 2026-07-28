package com.newoether.agora.model

import com.newoether.agora.util.Constants

data class RunMessagePresentation(
    val showActions: Boolean = false,
    val copyText: String? = null,
    val showBranchSelector: Boolean = false,
    val branchIndex: Int = 0,
    val totalBranches: Int = 1,
    val branchAnchorParentId: String? = null,
    val branchAnchorMessageId: String? = null,
)

/**
 * Derives Run-boundary UI affordances from the selected message path.
 *
 * Message rows remain the rendering unit, but action/branch affordances are Run-scoped:
 * intermediate Pass input/output and synthetic tool/result rows never expose their own bars.
 */
object RunUiProjection {
    fun project(
        visibleMessages: List<ChatMessage>,
        allMessages: List<ChatMessage>,
    ): Map<String, RunMessagePresentation> {
        if (visibleMessages.isEmpty()) return emptyMap()

        val boundaryInputByRun = allMessages
            .asSequence()
            .filter(::isVisibleUserInput)
            .filter { !it.runId.isNullOrBlank() }
            .groupBy { checkNotNull(it.runId) }
            .mapValues { (_, messages) -> messages.minWithOrNull(messageOrder)!! }
        val boundarySiblings = boundaryInputByRun.values
            .groupBy { it.parentId }
            .mapValues { (_, messages) -> messages.sortedWith(messageOrder) }

        val result = visibleMessages.associate { it.id to RunMessagePresentation() }.toMutableMap()
        val visibleByRun = visibleMessages
            .filter { !it.runId.isNullOrBlank() }
            .groupBy { checkNotNull(it.runId) }

        for ((runId, rawMessages) in visibleByRun) {
            val messages = rawMessages.sortedWith(messageOrder)
            val userMessages = messages.filter(::isVisibleUserInput)
            val modelMessages = messages.filter(::isVisibleModelOutput)
            val userBoundary = userMessages.firstOrNull()
            val outputBoundary = modelMessages.lastOrNull() ?: messages.lastOrNull {
                !isSynthetic(it)
            } ?: continue

            val branchBoundary = boundaryInputByRun[runId] ?: userBoundary
            val siblings = branchBoundary?.let { boundarySiblings[it.parentId].orEmpty() }.orEmpty()
            val branchIndex = branchBoundary?.let { boundary ->
                siblings.indexOfFirst { it.id == boundary.id }.coerceAtLeast(0)
            } ?: 0
            val branchPresentation = RunMessagePresentation(
                showBranchSelector = siblings.size > 1,
                branchIndex = branchIndex,
                totalBranches = siblings.size.coerceAtLeast(1),
                branchAnchorParentId = branchBoundary?.parentId,
                branchAnchorMessageId = branchBoundary?.id,
            )

            if (userBoundary != null) {
                result[userBoundary.id] = result.getValue(userBoundary.id).copy(
                    showActions = true,
                    copyText = userBoundary.text.takeIf { it.isNotBlank() },
                    showBranchSelector = branchPresentation.showBranchSelector,
                    branchIndex = branchPresentation.branchIndex,
                    totalBranches = branchPresentation.totalBranches,
                    branchAnchorParentId = branchPresentation.branchAnchorParentId,
                    branchAnchorMessageId = branchPresentation.branchAnchorMessageId,
                )
            }

            result[outputBoundary.id] = result.getValue(outputBoundary.id).copy(
                showActions = true,
                copyText = outputBoundary.text.takeIf { it.isNotBlank() },
                showBranchSelector = branchPresentation.showBranchSelector,
                branchIndex = branchPresentation.branchIndex,
                totalBranches = branchPresentation.totalBranches,
                branchAnchorParentId = branchPresentation.branchAnchorParentId,
                branchAnchorMessageId = branchPresentation.branchAnchorMessageId,
            )
        }
        return result
    }

    private val messageOrder =
        compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
            .thenBy { it.timestamp }
            .thenBy { it.id }

    private fun isVisibleUserInput(message: ChatMessage): Boolean =
        message.participant == Participant.USER && !isSynthetic(message)

    private fun isVisibleModelOutput(message: ChatMessage): Boolean =
        message.participant == Participant.MODEL && !isSynthetic(message)

    private fun isSynthetic(message: ChatMessage): Boolean =
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX)
}
