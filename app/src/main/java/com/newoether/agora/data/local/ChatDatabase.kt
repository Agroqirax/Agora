package com.newoether.agora.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.repairSelectionsAfterQueuedRemoval
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.data.local.migration.MIGRATION_16_17
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MessageConverters {
    @TypeConverter
    fun fromParticipant(value: Participant) = value.name
    @TypeConverter
    fun toParticipant(value: String) = Participant.valueOf(value)

    @TypeConverter
    fun fromStatus(value: MessageStatus) = value.name
    @TypeConverter
    fun toStatus(value: String) = MessageStatus.valueOf(value)

    @TypeConverter
    fun fromRunStatus(value: RunStatus) = value.name
    @TypeConverter
    fun toRunStatus(value: String) = RunStatus.valueOf(value)

    @TypeConverter
    fun fromRunEndReason(value: RunEndReason?) = value?.name
    @TypeConverter
    fun toRunEndReason(value: String?) = value?.let(RunEndReason::valueOf)
    
    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return if (value != null) Json.encodeToString(value) else ""
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            Json.decodeFromString<List<String>>(value)
        } catch (_: Exception) {
            // Backward compatibility: old format used "|||" delimiter
            value.split("|||")
        }
    }
}

@Entity(tableName = "conversations", indices = [Index(value = ["taskId"])])
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val selectedBranchesJson: String? = null,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    /** Which task spawned this conversation; null = ordinary user conversation. */
    val taskId: String? = null,
    /** How this conversation was created: "user" | "task" | "loop". */
    val origin: String = "user",
    /** True once the user has taken over a task execution, promoting it into the main list. */
    val graduated: Boolean = false,
    /** Unsent composer text for per-conversation draft persistence. */
    val draftText: String = "",
    /** JSON-serialized list of [com.newoether.agora.model.SelectedAttachment]; null = no draft attachments. */
    val draftAttachments: String? = null,
    /** Run-level branch selections. Message-level selections remain for legacy compatibility. */
    val selectedRunBranchesJson: String? = null,
)

/** A saved automation: a prompt + schedule that fans out a fresh conversation on each run. */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Replayed as the first user message of every execution. */
    val prompt: String,
    val systemPrompt: String? = null,
    /** null = use the app default model. */
    val modelId: String? = null,
    /** 5-field cron expression driving a RECURRING schedule; blank for a one-shot. */
    val cronExpr: String,
    /** One-shot fire time. A 5-field cron has no year, so "once on a date" cannot be a cron —
     *  it is an absolute epoch instead, and the task disables itself after it fires.
     *  Mutually exclusive with [cronExpr]: exactly one of the two is set on a scheduled task. */
    val runAt: Long? = null,
    /** Local derived value; imports clear it until the user explicitly re-enables the task. */
    val nextRunAt: Long,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)

/** A loop attached to a single conversation: periodically re-injects a user turn in-context. */
@Entity(
    tableName = "loops",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LoopEntity(
    @PrimaryKey val conversationId: String,
    val intervalMs: Long,
    /** null = a generic "continue" turn. */
    val prompt: String? = null,
    val nextFireAt: Long,
    val cycleCount: Int = 0,
    /**
     * Safety ceiling. The column remains nullable for schema/backward compatibility, but domain
     * code normalizes legacy nulls to the bounded LoopPolicy default.
     */
    val maxCycles: Int? = null,
    val active: Boolean = true,
    /** Configuration generation used to keep a stale in-flight cycle from overwriting stop/restart. */
    val revision: Long = 0L
)

@Entity(
    tableName = "embeddings",
    indices = [Index(value = ["messageId", "modelId"], unique = true)]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val modelId: String,
    val embedding: ByteArray,
    val chunkText: String,
    val dimension: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id && messageId == other.messageId && modelId == other.modelId
            && embedding.contentEquals(other.embedding) && chunkText == other.chunkText && dimension == other.dimension
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + dimension
        return result
    }
}

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"]), Index(value = ["runId"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant,
    val timestamp: Long,
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCallJson: String? = null,
    val attachmentMeta: String? = null,
    val runId: String,
    val runSequence: Long = UNASSIGNED_RUN_SEQUENCE,
    /** Non-null only for visible user input; null for model/tool/result rows. */
    val consumedAtPass: Int? = null,
) {
    companion object {
        const val UNASSIGNED_RUN_SEQUENCE = -1L
    }
}

/**
 * Partial Room entity used for durable streaming checkpoints.
 *
 * Keeping only fields that can change while a model is generating prevents a checkpoint from
 * overwriting stable relationship/model metadata. [ChatDao.updateMessageCheckpoint] is an UPDATE,
 * not an upsert: if a concurrent delete removed the placeholder, streaming must not resurrect it.
 */
data class MessageStreamCheckpoint(
    val id: String,
    val text: String,
    val images: List<String>,
    val thoughts: String?,
    val thoughtTitle: String?,
    val tokenCount: Int,
    val status: MessageStatus,
    val thoughtTimeMs: Long?,
    val toolCallJson: String?,
)

/** Attachment-only projection used by sweeps and media export.
 *
 * These callers do not need message bodies, thoughts, or tool payloads. Returning a full
 * [MessageEntity] for every row can otherwise expand a large database past Android's heap limit.
 */
data class MessageAttachmentReference(
    val id: String,
    val images: List<String>,
    val attachmentMeta: String? = null,
)

/** Draft-only projection used by the orphaned attachment sweep. */
data class ConversationDraftAttachmentReference(
    val id: String,
    val draftAttachments: String,
)

/** Minimal payload needed to generate an embedding. */
data class IndexableMessage(
    val id: String,
    val text: String,
)

@Dao
interface ChatDao {
    // Main list hides un-graduated task executions; they surface only via the task's
    // own execution log until the user takes one over (graduated = 1).
    @Query("SELECT * FROM conversations WHERE taskId IS NULL OR graduated = 1 ORDER BY lastUpdated DESC")
    fun getAllConversations(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM conversations WHERE taskId = :taskId ORDER BY lastUpdated DESC")
    fun getExecutionsForTask(taskId: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ChatEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun observeConversation(conversationId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Upsert
    suspend fun upsertConversation(conversation: ChatEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    // Runs
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RunEntity)

    @Upsert
    suspend fun upsertRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun getRun(runId: String): RunEntity?

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt, id")
    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt, id")
    suspend fun getRunsForConversationSnapshot(conversationId: String): List<RunEntity>

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

    /**
     * Atomically removes a Run subtree (via the self-referencing CASCADE), its embeddings, and
     * every branch-selection reference that the caller repaired from the same locked snapshot.
     * Attachment files are intentionally deleted only after this transaction commits.
     */
    @Transaction
    suspend fun deleteRunGraph(
        conversationId: String,
        rootRunId: String,
        staleMessageIds: List<String>,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Boolean {
        val root = getRun(rootRunId) ?: return false
        require(root.conversationId == conversationId) {
            "Run $rootRunId does not belong to conversation $conversationId"
        }
        if (staleMessageIds.isNotEmpty()) {
            deleteEmbeddingsByMessageIds(staleMessageIds)
        }
        check(
            updateSelectionsForRunDeletion(
                conversationId,
                selectedBranchesJson,
                selectedRunBranchesJson,
                at,
            ) == 1
        ) { "Conversation $conversationId disappeared during Run deletion" }
        check(deleteRun(rootRunId) == 1) { "Run $rootRunId disappeared during deletion" }
        return true
    }

    @Query(
        "SELECT * FROM runs WHERE conversationId = :conversationId AND activeSlot = 1 LIMIT 1"
    )
    suspend fun getLiveRun(conversationId: String): RunEntity?

    @Query("SELECT COALESCE(MAX(runSequence), -1) + 1 FROM messages WHERE runId = :runId")
    suspend fun nextRunSequence(runId: String): Long

    @Query("UPDATE runs SET lastCheckpointAt = :at WHERE id = :runId")
    suspend fun touchRun(runId: String, at: Long): Int

    @Transaction
    suspend fun createRunWithMessages(run: RunEntity, messages: List<MessageEntity>) {
        require(run.status == RunStatus.ACTIVE)
        require(run.activeSlot == 1)
        require(messages.isNotEmpty())
        require(messages.all { it.runId == run.id })
        require(messages.map { it.runSequence } == messages.indices.map { it.toLong() })
        insertRun(run)
        messages.forEach { insertMessage(it) }
    }

    @Transaction
    suspend fun importRunGraph(runs: List<RunEntity>, messages: List<MessageEntity>) {
        require(runs.all { it.status.isTerminal }) {
            "Imported Runs must be terminal"
        }
        val incomingRunIds = runs.mapTo(mutableSetOf()) { it.id }
        require(messages.all { it.runId in incomingRunIds || getRun(it.runId) != null }) {
            "Every imported message must reference an imported or existing Run"
        }
        for (run in runs) {
            if (getRun(run.id) == null) insertRun(run)
        }
        messages.forEach { upsertMessage(it) }
    }

    @Transaction
    suspend fun appendMessageToRun(message: MessageEntity): MessageEntity {
        val run = getRun(message.runId) ?: error("Run ${message.runId} does not exist")
        check(run.status == RunStatus.ACTIVE) { "Cannot append to ${run.status} Run ${run.id}" }
        val assigned = message.copy(runSequence = nextRunSequence(run.id))
        insertMessage(assigned)
        touchRun(run.id, maxOf(run.lastCheckpointAt, assigned.timestamp))
        return assigned
    }

    /** A provider tool round is protocol-atomic: assistant tool_calls and every result commit
     * together, or none of them do. */
    @Transaction
    suspend fun appendToolRoundToRun(messages: List<MessageEntity>): List<MessageEntity> {
        require(messages.size >= 2) { "A tool round requires a tool row and at least one result" }
        val runId = messages.first().runId
        require(messages.all { it.runId == runId }) { "One tool round cannot span Runs" }
        val run = getRun(runId) ?: error("Run $runId does not exist")
        check(run.status == RunStatus.ACTIVE) { "Cannot append to ${run.status} Run ${run.id}" }
        val firstSequence = nextRunSequence(runId)
        val assigned = messages.mapIndexed { index, message ->
            message.copy(runSequence = firstSequence + index)
        }
        assigned.forEach { insertMessage(it) }
        touchRun(runId, maxOf(run.lastCheckpointAt, assigned.maxOf { it.timestamp }))
        return assigned
    }

    @Query(
        """
        UPDATE runs
        SET status = 'STOPPING', stopRequestedAt = :at, lastCheckpointAt = :at
        WHERE id = :runId AND status = 'ACTIVE' AND activeSlot = 1
        """
    )
    suspend fun markRunStopping(runId: String, at: Long): Int

    @Query(
        """
        UPDATE runs
        SET status = :status, activeSlot = NULL, lastCheckpointAt = :at, endedAt = :at,
            endReason = :reason
        WHERE id = :runId AND activeSlot = 1
        """
    )
    suspend fun terminalizeLiveRun(
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        at: Long,
    ): Int

    /**
     * The only user-Stop terminal writer. Checkpoints and Run terminalization commit together, so
     * tree operations never observe a STOPPED message inside a still-live Run (or the inverse).
     */
    @Transaction
    suspend fun finishStoppedGeneration(
        checkpoints: List<MessageStreamCheckpoint>,
        runId: String?,
        at: Long,
    ): Boolean {
        checkpoints.forEach { updateMessageCheckpoint(it) }
        return runId == null || terminalizeLiveRun(
            runId,
            RunStatus.STOPPED,
            RunEndReason.USER_STOPPED,
            at,
        ) == 1
    }

    @Query(
        """
        SELECT * FROM messages
        WHERE runId = :runId
          AND participant = 'USER'
          AND id NOT LIKE 'tool_%'
          AND id NOT LIKE 'result_%'
          AND consumedAtPass IS NULL
        ORDER BY runSequence
        """
    )
    suspend fun getPendingRunInputs(runId: String): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE runId = :runId
          AND parentId = :parentId
          AND participant = 'USER'
          AND consumedAtPass IS NULL
        ORDER BY runSequence, timestamp, id
        """
    )
    suspend fun getPendingRunInputChildren(
        runId: String,
        parentId: String,
    ): List<MessageEntity>

    @Query(
        """
        UPDATE messages
        SET parentId = :replacementParentId
        WHERE runId = :runId
          AND parentId = :removedMessageId
          AND participant = 'USER'
          AND consumedAtPass IS NULL
        """
    )
    suspend fun reparentPendingRunInputChildren(
        runId: String,
        removedMessageId: String,
        replacementParentId: String?,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson, lastUpdated = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateMessageSelectionsAfterPendingRemoval(
        conversationId: String,
        selectedBranchesJson: String,
        at: Long,
    ): Int

    @Transaction
    suspend fun removePendingRunInput(messageId: String): RemovedPendingRunInput? {
        val message = getMessage(messageId) ?: return null
        check(message.participant == Participant.USER && message.consumedAtPass == null) {
            "Only an unconsumed user intervention can be removed from the queue"
        }
        val children = getPendingRunInputChildren(message.runId, message.id)
        check(
            reparentPendingRunInputChildren(
                runId = message.runId,
                removedMessageId = message.id,
                replacementParentId = message.parentId,
            ) == children.size
        )
        deleteEmbeddingsByMessageIds(listOf(message.id))
        deleteMessagesByIds(listOf(message.id))
        val conversation = checkNotNull(getConversation(message.conversationId)) {
            "Conversation ${message.conversationId} disappeared during queue removal"
        }
        val selections = conversation.selectedBranchesJson?.let { raw ->
            runCatching {
                Json.decodeFromString<Map<String, String>>(raw)
                    .mapKeys { if (it.key == "null") null else it.key }
            }.getOrDefault(emptyMap())
        }.orEmpty()
        val repairedSelections = repairSelectionsAfterQueuedRemoval(
            selections = selections,
            removedMessageId = message.id,
            removedParentId = message.parentId,
            reparentedChildIds = children.map { it.id },
        )
        check(
            updateMessageSelectionsAfterPendingRemoval(
                conversationId = message.conversationId,
                selectedBranchesJson = Json.encodeToString(
                    repairedSelections.mapKeys { it.key ?: "null" }
                ),
                at = System.currentTimeMillis(),
            ) == 1
        )
        return RemovedPendingRunInput(
            message = message,
            reparentedChildIds = children.map { it.id },
            repairedSelections = repairedSelections,
        )
    }

    @Query(
        "UPDATE messages SET consumedAtPass = :pass WHERE id IN (:messageIds) AND consumedAtPass IS NULL"
    )
    suspend fun markInputsConsumed(messageIds: List<String>, pass: Int): Int

    @Query(
        """
        UPDATE runs
        SET currentPass = :pass, lastCheckpointAt = :at
        WHERE id = :runId AND status = 'ACTIVE' AND currentPass = :previousPass
        """
    )
    suspend fun advanceRunPass(runId: String, previousPass: Int, pass: Int, at: Long): Int

    @Transaction
    suspend fun claimPendingRunInputs(runId: String, at: Long): ClaimedRunPass? {
        val run = getRun(runId) ?: return null
        if (run.status != RunStatus.ACTIVE) return null
        val pending = getPendingRunInputs(runId)
        if (pending.isEmpty()) return null
        val pass = run.currentPass + 1
        check(markInputsConsumed(pending.map { it.id }, pass) == pending.size)
        check(advanceRunPass(runId, run.currentPass, pass, at) == 1)
        return ClaimedRunPass(runId, pass, pending.map { it.id })
    }

    @Query(
        """
        UPDATE messages
        SET status = 'STOPPED'
        WHERE runId IN (
            SELECT id FROM runs
            WHERE activeSlot = 1 AND status IN ('ACTIVE', 'STOPPING')
        )
        AND participant = 'MODEL'
        AND status IN ('SENDING', 'THINKING', 'TOOL_CALLING', 'TRANSCRIBING')
        """
    )
    suspend fun markOrphanedRunMessagesStopped(): Int

    @Query(
        """
        UPDATE runs
        SET status = 'STOPPED', activeSlot = NULL, lastCheckpointAt = :at, endedAt = :at,
            endReason = 'PROCESS_RECOVERED'
        WHERE activeSlot = 1 AND status IN ('ACTIVE', 'STOPPING')
        """
    )
    suspend fun terminalizeOrphanedRuns(at: Long): Int

    @Transaction
    suspend fun recoverOrphanedRuns(at: Long): Int {
        markOrphanedRunMessagesStopped()
        return terminalizeOrphanedRuns(at)
    }

    @Update(entity = MessageEntity::class)
    suspend fun updateMessageCheckpoint(checkpoint: MessageStreamCheckpoint): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<String>)

    @Query("DELETE FROM embeddings WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteEmbeddingsByConversation(conversationId: String)

    @Query("DELETE FROM embeddings WHERE NOT EXISTS (SELECT 1 FROM messages WHERE messages.id = embeddings.messageId)")
    suspend fun deleteOrphanedEmbeddings()

    /** [query] must be pre-escaped for LIKE (see ConversationRepository.escapeLikePattern). */
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE (c.taskId IS NULL OR c.graduated = 1) AND (m.text LIKE '%' || :query || '%' ESCAPE '\\' OR c.title LIKE '%' || :query || '%' ESCAPE '\\') AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' ORDER BY m.timestamp DESC LIMIT :limit")
    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?

    /** Message invalidations for task execution summaries. Unlike getExecutionsForTask(),
     * this Flow observes the messages table, so terminal status/snippet changes are emitted. */
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId = :taskId ORDER BY m.timestamp ASC")
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>>

    // Embeddings
    @Upsert
    suspend fun upsertEmbedding(embedding: EmbeddingEntity)

    @Query("SELECT * FROM embeddings WHERE messageId = :messageId LIMIT 1")
    suspend fun getEmbedding(messageId: String): EmbeddingEntity?

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE messageId = :messageId")
    suspend fun deleteEmbedding(messageId: String)

    @Query("SELECT e.* FROM embeddings e INNER JOIN messages m ON e.messageId = m.id INNER JOIN conversations c ON m.conversationId = c.id WHERE e.modelId = :modelId AND (c.taskId IS NULL OR c.graduated = 1) AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
    suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE modelId = :modelId")
    suspend fun deleteEmbeddingsByModel(modelId: String)

    @Query("SELECT COUNT(*) FROM embeddings e INNER JOIN messages m ON e.messageId = m.id INNER JOIN conversations c ON m.conversationId = c.id WHERE e.modelId = :modelId AND (c.taskId IS NULL OR c.graduated = 1) AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
    suspend fun getEmbeddingCountByModel(modelId: String): Int

    @Query("SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE (c.taskId IS NULL OR c.graduated = 1) AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
    suspend fun getIndexableMessageCount(): Int

    @Query(
        """
        SELECT m.id, m.text
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE (c.taskId IS NULL OR c.graduated = 1)
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%'
          AND NOT EXISTS (
              SELECT 1 FROM embeddings e
              WHERE e.messageId = m.id AND e.modelId = :modelId
          )
          AND (:afterId IS NULL OR m.id > :afterId)
        ORDER BY m.id
        LIMIT :limit
        """
    )
    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage>

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id IN (:ids) AND (c.taskId IS NULL OR c.graduated = 1) AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'")
    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id = :messageId AND (c.taskId IS NULL OR c.graduated = 1) AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%')")
    suspend fun isMessageSearchable(messageId: String): Boolean

    /** Atomically enforces the search-visibility invariant for incremental indexing. */
    @Transaction
    suspend fun upsertEmbeddingIfSearchable(embedding: EmbeddingEntity): Boolean {
        if (!isMessageSearchable(embedding.messageId)) {
            deleteEmbedding(embedding.messageId)
            return false
        }
        upsertEmbedding(embedding)
        return true
    }

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND (taskId IS NULL OR graduated = 1)")
    suspend fun getSearchableConversation(conversationId: String): ChatEntity?

    @Query("SELECT * FROM conversations WHERE taskId IS NULL OR graduated = 1 ORDER BY lastUpdated ASC")
    suspend fun getSearchableConversationsList(): List<ChatEntity>

    @Query("UPDATE conversations SET draftText = :text, draftAttachments = :attachments WHERE id = :id")
    suspend fun updateDraft(id: String, text: String, attachments: String?)

    // Bulk export/import
    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsList(): List<ChatEntity>

    @Query("SELECT id FROM conversations")
    suspend fun getAllConversationIds(): List<String>

    @Query("SELECT id FROM tasks")
    suspend fun getAllTaskIds(): List<String>

    @Query(
        """
        SELECT *
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessagesPage(afterId: String?, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessageAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference>

    @Query(
        """
        SELECT id, draftAttachments
        FROM conversations
        WHERE (:afterId IS NULL OR id > :afterId)
          AND draftAttachments IS NOT NULL
          AND draftAttachments != ''
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getConversationDraftAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<ConversationDraftAttachmentReference>

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun findExistingMessageIds(ids: List<String>): List<String>

    // ── Tasks ─────────────────────────────────────────────────
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE enabled = 1")
    suspend fun getEnabledTasks(): List<TaskEntity>

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    /** Clock-change CAS: never overwrite a concurrent edit/disable/execution advancement. */
    @Query(
        """
        UPDATE tasks SET nextRunAt = :replacementNextRunAt
        WHERE id = :id AND enabled = 1 AND cronExpr = :expectedCronExpr
          AND nextRunAt = :expectedNextRunAt
        """
    )
    suspend fun updateTaskNextRunAtIfUnchanged(
        id: String,
        expectedCronExpr: String,
        expectedNextRunAt: Long,
        replacementNextRunAt: Long,
    ): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    // Bulk export/import
    @Query("SELECT * FROM tasks")
    suspend fun getAllTasksList(): List<TaskEntity>

    // ── Loops ─────────────────────────────────────────────────
    @Query("SELECT * FROM loops WHERE conversationId = :conversationId")
    fun getLoop(conversationId: String): Flow<LoopEntity?>

    @Query("SELECT * FROM loops WHERE active = 1")
    suspend fun getActiveLoops(): List<LoopEntity>

    @Query("SELECT * FROM loops WHERE active = 1")
    fun observeActiveLoops(): Flow<List<LoopEntity>>

    @Upsert
    suspend fun upsertLoop(loop: LoopEntity)

    /** Clock-change CAS: only the observed active loop revision/cycle may be moved. */
    @Query(
        """
        UPDATE loops SET nextFireAt = :replacementNextFireAt
        WHERE conversationId = :conversationId AND active = 1
          AND revision = :expectedRevision AND cycleCount = :expectedCycleCount
          AND intervalMs = :expectedIntervalMs AND nextFireAt = :expectedNextFireAt
        """
    )
    suspend fun updateLoopNextFireAtIfUnchanged(
        conversationId: String,
        expectedRevision: Long,
        expectedCycleCount: Int,
        expectedIntervalMs: Long,
        expectedNextFireAt: Long,
        replacementNextFireAt: Long,
    ): Int

    /** Safely quarantines an invalid legacy loop without reviving or clobbering a newer state. */
    @Query(
        """
        UPDATE loops
        SET active = 0, nextFireAt = 0, revision = revision + 1,
            maxCycles = :normalizedMaxCycles
        WHERE conversationId = :conversationId AND active = 1
          AND revision = :expectedRevision AND cycleCount = :expectedCycleCount
          AND intervalMs = :expectedIntervalMs AND nextFireAt = :expectedNextFireAt
        """
    )
    suspend fun deactivateLoopIfUnchanged(
        conversationId: String,
        expectedRevision: Long,
        expectedCycleCount: Int,
        expectedIntervalMs: Long,
        expectedNextFireAt: Long,
        normalizedMaxCycles: Int,
    ): Int

    @Query("DELETE FROM loops WHERE conversationId = :conversationId")
    suspend fun deleteLoop(conversationId: String)

    @Query("DELETE FROM loops")
    suspend fun deleteAllLoops()

    @Query("SELECT * FROM loops")
    suspend fun getAllLoopsList(): List<LoopEntity>
}

@Database(
    entities = [
        ChatEntity::class,
        RunEntity::class,
        MessageEntity::class,
        EmbeddingEntity::class,
        TaskEntity::class,
        LoopEntity::class,
    ],
    version = ChatDatabase.CURRENT_VERSION,
    exportSchema = true
)@TypeConverters(MessageConverters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val CURRENT_VERSION = 17
        const val DB_NAME = "agora_db"

        val ALL_MIGRATIONS = listOf(
            // v1 → v2 added messages.images (List<String> stored as TEXT via converter,
            // NOT NULL with "" representing an empty list). This step was missing, so any
            // device still on schema v1 crashed on launch with "migration 1 to 2 not found".
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN images TEXT NOT NULL DEFAULT ''")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN selectedBranchesJson TEXT")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTimeMs INTEGER")
                }
            },
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")
                }
            },
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN systemPromptId TEXT")
                }
            },
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN modelId TEXT")
                }
            },
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTitle TEXT")
                }
            },
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN toolCallJson TEXT")
                }
            },
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS embeddings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            messageId TEXT NOT NULL,
                            embedding BLOB NOT NULL,
                            chunkText TEXT NOT NULL,
                            dimension INTEGER NOT NULL
                        )
                    """)
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId ON embeddings (messageId)")
                }
            },
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE embeddings ADD COLUMN modelId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("DROP INDEX IF EXISTS index_embeddings_messageId")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId_modelId ON embeddings (messageId, modelId)")
                }
            },
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMeta TEXT")
                }
            },
            // v12 → v13 adds the automation layer: tasks + loops tables, and the
            // task-execution columns on conversations (origin/taskId/graduated).
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN taskId TEXT")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN graduated INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_taskId ON conversations (taskId)")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS tasks (
                            id TEXT PRIMARY KEY NOT NULL,
                            name TEXT NOT NULL,
                            prompt TEXT NOT NULL,
                            systemPrompt TEXT,
                            modelId TEXT,
                            cronExpr TEXT NOT NULL,
                            nextRunAt INTEGER NOT NULL,
                            enabled INTEGER NOT NULL DEFAULT 1,
                            createdAt INTEGER NOT NULL,
                            lastRunAt INTEGER
                        )
                    """)
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS loops (
                            conversationId TEXT PRIMARY KEY NOT NULL,
                            intervalMs INTEGER NOT NULL,
                            prompt TEXT,
                            nextFireAt INTEGER NOT NULL,
                            cycleCount INTEGER NOT NULL DEFAULT 0,
                            maxCycles INTEGER,
                            active INTEGER NOT NULL DEFAULT 1,
                            FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                        )
                    """)
                }
            },
            // v13 → v14 adds an optimistic revision to loop state. A cycle that was
            // already running can then observe a stop/restart and avoid overwriting it.
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE loops ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                }
            },
            // v14 → v15 adds per-conversation draft persistence for the composer.
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftText TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftAttachments TEXT")
                }
            },
            // v15 → v16 adds one-shot ("run once at an instant") tasks, which a cron cannot express.
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE tasks ADD COLUMN runAt INTEGER")
                }
            },
            MIGRATION_16_17,
        )

        fun getStoredVersion(context: Context): Int {
            val dbPath = context.getDatabasePath(DB_NAME)
            if (!dbPath.exists()) return 0
            return try {
                val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
                val version = db.version
                db.close()
                version
            } catch (e: Exception) {
                0
            }
        }

        fun build(context: Context): ChatDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                DB_NAME
            ).addMigrations(*ALL_MIGRATIONS.toTypedArray())
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}
