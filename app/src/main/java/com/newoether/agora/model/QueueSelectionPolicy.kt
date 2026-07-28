package com.newoether.agora.model

/**
 * Repairs the legacy message-path selection after removing one durable queue item.
 *
 * Pending inputs form a linear chain. Their database parent links are reparented transactionally;
 * this function performs the matching selection-map rewrite so the remaining queue drains onto
 * the same visible model boundary instead of becoming an unreachable orphan.
 */
internal fun repairSelectionsAfterQueuedRemoval(
    selections: Map<String?, String>,
    removedMessageId: String,
    removedParentId: String?,
    reparentedChildIds: List<String>,
): Map<String?, String> {
    val repaired = selections.toMutableMap()
    repaired.remove(removedMessageId)
    repaired.entries.removeAll { it.value == removedMessageId }
    reparentedChildIds.firstOrNull()?.let { replacementId ->
        repaired[removedParentId] = replacementId
    }
    return repaired
}
