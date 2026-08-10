package com.newoether.agora.data.local

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun decodeCompactSelections(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

private fun encodeCompactSelections(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/**
 * Context-Compact graph transactions inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the atomic graph contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatContextCompactDao {
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun getRun(runId: String): RunEntity?

    @Query("SELECT * FROM messages WHERE runId IN (:runIds) ORDER BY runSequence, timestamp, id")
    suspend fun getMessagesForRuns(runIds: List<String>): List<MessageEntity>

    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteRun(runId: String): Int

    @Query("DELETE FROM embeddings WHERE messageId IN (:messageIds)")
    suspend fun deleteEmbeddingsByMessageIds(messageIds: List<String>)

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson,
            selectedRunBranchesJson = :selectedRunBranchesJson,
            lastUpdated = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateSelectionsForRunDeletion(
        conversationId: String,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Int

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId AND activeSlot = 1 LIMIT 1")
    suspend fun getLiveRun(conversationId: String): RunEntity?

    @Query("SELECT COALESCE(MAX(runSequence), -1) + 1 FROM messages WHERE runId = :runId")
    suspend fun nextRunSequence(runId: String): Long

    @Query("UPDATE runs SET lastCheckpointAt = :at WHERE id = :runId")
    suspend fun touchRun(runId: String, at: Long): Int

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)

    @Query("UPDATE messages SET parentId = :replacementParentId WHERE parentId = :removedMessageId")
    suspend fun reparentMessageChildren(removedMessageId: String, replacementParentId: String?): Int

    @Query("UPDATE runs SET parentRunId = :replacementParentRunId WHERE parentRunId = :removedRunId")
    suspend fun reparentRunChildren(removedRunId: String, replacementParentRunId: String?): Int

    @Query("UPDATE messages SET parentId = :newParentId WHERE id = :messageId")
    suspend fun updateMessageParent(messageId: String, newParentId: String?): Int

    @Query(
        """
        UPDATE messages
        SET text = :text
        WHERE id = :messageId AND runId = :runId AND status = 'SENDING'
          AND EXISTS (
              SELECT 1 FROM runs
              WHERE runs.id = :runId AND runs.status = 'ACTIVE' AND runs.activeSlot = 1
                AND (:expectedPass IS NULL OR runs.currentPass = :expectedPass)
          )
        """
    )
    suspend fun updateContextCompactCheckpoint(
        messageId: String,
        runId: String,
        expectedPass: Int?,
        text: String,
    ): Int

    @Query(
        """
        UPDATE messages
        SET text = '', status = 'SENDING', modelName = :modelName
        WHERE id = :messageId AND status IN ('SUCCESS', 'ERROR', 'STOPPED')
        """
    )
    suspend fun restartContextCompactMessage(messageId: String, modelName: String): Int

    @Query(
        """
        UPDATE messages
        SET text = :text
        WHERE id = :messageId AND status = 'SENDING'
        """
    )
    suspend fun updateRecompactCheckpoint(messageId: String, text: String): Int

    @Query(
        """
        UPDATE messages
        SET text = :text, status = :status
        WHERE id = :messageId AND status = 'SENDING'
        """
    )
    suspend fun settleRecompactMessage(
        messageId: String,
        text: String,
        status: MessageStatus,
    ): Int

    @Query(
        """
        UPDATE messages
        SET text = :text, status = :status
        WHERE id = :messageId AND runId = :runId AND status = 'SENDING'
        """
    )
    suspend fun settleContextCompactMessage(
        messageId: String,
        runId: String,
        text: String,
        status: MessageStatus,
    ): Int

    @Query(
        """
        UPDATE runs
        SET status = :status, activeSlot = NULL, lastCheckpointAt = :at, endedAt = :at,
            endReason = :reason
        WHERE id = :runId AND status = 'ACTIVE' AND activeSlot = 1
        """
    )
    suspend fun terminalizeManualContextCompactRun(
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        at: Long,
    ): Int

    @Query(
        "UPDATE messages SET runSequence = runSequence + 1 " +
            "WHERE runId = :runId AND runSequence >= :fromSequence"
    )
    suspend fun shiftRunSequencesForInsertion(runId: String, fromSequence: Long): Int

    /** Creates the automatic Compact's durable streaming row before its Provider pass starts. */
    @Transaction
    suspend fun beginAutomaticContextCompact(
        message: MessageEntity,
        childMessageId: String?,
        expectedPass: Int,
        expectedSelectedBranchesJson: String,
        selectedBranchesJson: String,
        at: Long,
    ): MessageEntity {
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        require(message.parentId != null)
        require(message.status == MessageStatus.SENDING)
        val run = getRun(message.runId) ?: error("Compact Run ${message.runId} disappeared")
        check(run.conversationId == message.conversationId)
        check(run.status == RunStatus.ACTIVE && run.activeSlot == 1 && run.currentPass == expectedPass) {
            "Cannot append automatic Compact to stale Run ${run.id} Pass $expectedPass"
        }
        val parent = getMessage(message.parentId)
            ?: error("Compact parent ${message.parentId} disappeared")
        check(parent.conversationId == message.conversationId)
        val child = childMessageId?.let { id ->
            getMessage(id)?.also {
                check(it.conversationId == message.conversationId)
                check(it.parentId == parent.id) { "Compact child $id moved before insertion" }
                check(it.runId == run.id) { "Automatic Compact child must belong to Run ${run.id}" }
            } ?: error("Compact child $id disappeared")
        }
        val sequence = child?.runSequence ?: nextRunSequence(run.id)
        if (child != null) shiftRunSequencesForInsertion(run.id, sequence)
        val assigned = message.copy(runSequence = sequence)
        insertMessage(assigned)
        if (child != null) check(updateMessageParent(child.id, assigned.id) == 1)
        val conversation = getConversation(message.conversationId)
            ?: error("Conversation ${message.conversationId} disappeared")
        check(
            decodeCompactSelections(conversation.selectedBranchesJson) ==
                decodeCompactSelections(expectedSelectedBranchesJson)
        ) { "Selected message branch changed before automatic Compact insertion" }
        check(
            updateSelectionsForRunDeletion(
                conversationId = message.conversationId,
                selectedBranchesJson = selectedBranchesJson,
                selectedRunBranchesJson = conversation.selectedRunBranchesJson ?: "{}",
                at = at,
            ) == 1
        )
        touchRun(run.id, maxOf(run.lastCheckpointAt, at))
        return assigned
    }

    /** Restarts one selected terminal Compact row in place without disturbing its descendants. */
    @Transaction
    suspend fun beginRecompactContextCompact(
        messageId: String,
        modelName: String,
        expectedSelectedBranchesJson: String,
    ): MessageEntity {
        val message = getMessage(messageId) ?: error("Compact disappeared")
        check(getLiveRun(message.conversationId) == null) {
            "Conversation became busy during Recompact"
        }
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        check(
            message.status in setOf(
                MessageStatus.SUCCESS,
                MessageStatus.ERROR,
                MessageStatus.STOPPED,
            )
        ) { "Compact message is not terminal" }
        val conversation = getConversation(message.conversationId)
            ?: error("Conversation ${message.conversationId} disappeared")
        check(
            decodeCompactSelections(conversation.selectedBranchesJson) ==
                decodeCompactSelections(expectedSelectedBranchesJson)
        ) { "Selected message branch changed before Recompact" }
        check(restartContextCompactMessage(message.id, modelName) == 1)
        return message.copy(text = "", status = MessageStatus.SENDING, modelName = modelName)
    }

    /** Creates one live manual Compact Run and its durable streaming row atomically. */
    @Transaction
    suspend fun beginManualContextCompact(
        run: RunEntity,
        message: MessageEntity,
        expectedSelectedBranchesJson: String,
        selectedBranchesJson: String,
        at: Long,
    ): MessageEntity {
        require(run.status == RunStatus.ACTIVE && run.activeSlot == 1)
        require(message.runId == run.id)
        require(message.parentId != null)
        require(message.status == MessageStatus.SENDING)
        check(getLiveRun(message.conversationId) == null) {
            "Conversation ${message.conversationId} became busy during manual Compact"
        }
        val conversation = getConversation(message.conversationId)
            ?: error("Conversation ${message.conversationId} disappeared")
        check(
            decodeCompactSelections(conversation.selectedBranchesJson) ==
                decodeCompactSelections(expectedSelectedBranchesJson)
        ) { "Selected message branch changed before manual Compact insertion" }
        val parent = getMessage(message.parentId)
            ?: error("Compact parent ${message.parentId} disappeared")
        check(parent.conversationId == message.conversationId)
        check(parent.runId == run.parentRunId)
        insertRun(run)
        insertMessage(message.copy(runSequence = 0))
        val runSelections = decodeCompactSelections(conversation.selectedRunBranchesJson).apply {
            put(run.parentRunId, run.id)
        }
        check(
            updateSelectionsForRunDeletion(
                conversationId = message.conversationId,
                selectedBranchesJson = selectedBranchesJson,
                selectedRunBranchesJson = encodeCompactSelections(runSelections),
                at = at,
            ) == 1
        )
        return message.copy(runSequence = 0)
    }

    /** Settles an automatic Compact only while its owning Run/pass is still current. */
    @Transaction
    suspend fun settleAutomaticContextCompact(
        messageId: String,
        runId: String,
        expectedPass: Int,
        text: String,
        status: MessageStatus,
        at: Long,
    ): Boolean {
        require(status == MessageStatus.SUCCESS || status == MessageStatus.ERROR)
        val message = getMessage(messageId) ?: return false
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        check(message.runId == runId)
        if (message.status != MessageStatus.SENDING) {
            return message.status == status && message.text == text
        }
        val run = getRun(runId) ?: return false
        if (
            run.status != RunStatus.ACTIVE ||
            run.activeSlot != 1 ||
            run.currentPass != expectedPass
        ) return false
        if (settleContextCompactMessage(messageId, runId, text, status) != 1) return false
        touchRun(runId, maxOf(run.lastCheckpointAt, at))
        return true
    }

    /** Settles a manual Compact message and its dedicated live Run in one transaction. */
    @Transaction
    suspend fun settleManualContextCompact(
        messageId: String,
        runId: String,
        text: String,
        messageStatus: MessageStatus,
        runStatus: RunStatus,
        reason: RunEndReason,
        at: Long,
    ): Boolean {
        require(messageStatus in setOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED))
        require(runStatus.isTerminal)
        val message = getMessage(messageId) ?: return false
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        check(message.runId == runId)
        val run = getRun(runId) ?: return false
        if (run.status.isTerminal) {
            return message.status == messageStatus &&
                message.text == text &&
                run.status == runStatus &&
                run.endReason == reason
        }
        if (run.status != RunStatus.ACTIVE || run.activeSlot != 1) return false
        check(settleContextCompactMessage(messageId, runId, text, messageStatus) == 1)
        check(terminalizeManualContextCompactRun(runId, runStatus, reason, at) == 1)
        return true
    }

    @Transaction
    suspend fun removeContextCompact(messageId: String): Boolean {
        val message = getMessage(messageId) ?: return false
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        val conversation = getConversation(message.conversationId) ?: return false
        val compactRun = getRun(message.runId) ?: return false
        val compactOwnsDedicatedRun =
            compactRun.id.startsWith("compact_run_") &&
                getMessagesForRuns(listOf(compactRun.id)).map(MessageEntity::id) == listOf(message.id)
        reparentMessageChildren(message.id, message.parentId)
        deleteEmbeddingsByMessageIds(listOf(message.id))
        deleteMessagesByIds(listOf(message.id))

        val selections = decodeCompactSelections(conversation.selectedBranchesJson)
        val selectedCompact = selections[message.parentId] == message.id
        val selectedSuffixChildId = selections.remove(message.id)
        if (selectedCompact) {
            if (selectedSuffixChildId == null) selections.remove(message.parentId)
            else selections[message.parentId] = selectedSuffixChildId
        }
        val runSelections = decodeCompactSelections(conversation.selectedRunBranchesJson)
        if (compactOwnsDedicatedRun) {
            val selectedCompactRun = runSelections[compactRun.parentRunId] == compactRun.id
            val selectedCompactRunChild = runSelections.remove(compactRun.id)
            if (selectedCompactRun) {
                if (selectedCompactRunChild == null) runSelections.remove(compactRun.parentRunId)
                else runSelections[compactRun.parentRunId] = selectedCompactRunChild
            }
        }
        check(
            updateSelectionsForRunDeletion(
                conversationId = message.conversationId,
                selectedBranchesJson = encodeCompactSelections(selections),
                selectedRunBranchesJson = encodeCompactSelections(runSelections),
                at = System.currentTimeMillis(),
            ) == 1
        )
        if (compactOwnsDedicatedRun) {
            reparentRunChildren(compactRun.id, compactRun.parentRunId)
            check(deleteRun(compactRun.id) == 1)
        }
        return true
    }
}
