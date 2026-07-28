package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ConversationDraftAttachmentReference
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.IndexableMessage
import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.MessageStreamCheckpoint
import com.newoether.agora.data.local.RemovedPendingRunInput
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.ClaimedRunPass
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRepository(
    private val chatDao: ChatDao
) {
    private val runRecoveryMutex = Mutex()
    @Volatile private var runRecoveryComplete = false

    suspend fun ensureRunRecovery() {
        if (runRecoveryComplete) return
        runRecoveryMutex.withLock {
            if (runRecoveryComplete) return
            chatDao.recoverOrphanedRuns(System.currentTimeMillis())
            runRecoveryComplete = true
        }
    }
    // ── Conversations ─────────────────────────────────────────

    private fun ChatEntity.toConversation() = ChatConversation(
        id = id, title = title, systemPromptId = systemPromptId, modelId = modelId,
        taskId = taskId, origin = origin, graduated = graduated
    )

    fun getAllConversations(): Flow<List<ChatConversation>> =
        chatDao.getAllConversations().map { entities -> entities.map { it.toConversation() } }

    fun observeConversation(id: String): Flow<ChatConversation?> =
        chatDao.observeConversation(id).map { it?.toConversation() }

    /** Executions spawned by [taskId], newest first — the task's execution log. */
    fun getExecutionsForTask(taskId: String): Flow<List<ChatConversation>> =
        chatDao.getExecutionsForTask(taskId).map { entities -> entities.map { it.toConversation() } }

    /** Observes message-level changes for every execution belonging to [taskId]. */
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>> =
        chatDao.observeExecutionMessagesForTask(taskId)

    /** Promotes a task/loop execution into the main list once the user takes it over.
     *  Returns true only for the transition that made the conversation searchable. */
    suspend fun graduateConversation(id: String): Boolean {
        val conv = chatDao.getConversation(id) ?: return false
        if (conv.origin != "user" && !conv.graduated) {
            chatDao.upsertConversation(conv.copy(graduated = true, lastUpdated = System.currentTimeMillis()))
            return true
        }
        return false
    }

    suspend fun getConversation(id: String): ChatEntity? =
        chatDao.getConversation(id)

    suspend fun createConversation(title: String, systemPromptId: String? = null, modelId: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        chatDao.upsertConversation(ChatEntity(id = id, title = title, systemPromptId = systemPromptId, modelId = modelId))
        return id
    }

    suspend fun upsertConversation(entity: ChatEntity) = chatDao.upsertConversation(entity)

    suspend fun deleteConversation(id: String) {
        val messages = chatDao.getMessagesForConversation(id).first()
        deleteAttachmentFilesFromEntities(messages)
        chatDao.deleteEmbeddingsByConversation(id)
        chatDao.deleteMessagesByConversation(id)
        chatDao.deleteConversation(id)
    }

    // ── Messages ──────────────────────────────────────────────

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        chatDao.getMessagesForConversation(conversationId)

    suspend fun getMessagesForConversationSnapshot(conversationId: String): List<MessageEntity> =
        chatDao.getMessagesForConversation(conversationId).first()

    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity? =
        chatDao.getLastMessageForConversation(conversationId)

    suspend fun upsertMessage(entity: MessageEntity) {
        require(entity.runId.isNotBlank()) { "Message ${entity.id} has no Run" }
        require(entity.runSequence >= 0) { "Message ${entity.id} has no Run sequence" }
        chatDao.upsertMessage(entity)
    }

    suspend fun createRunWithMessages(run: RunEntity, messages: List<MessageEntity>) {
        ensureRunRecovery()
        chatDao.createRunWithMessages(run, messages)
    }

    suspend fun importRunGraph(runs: List<RunEntity>, messages: List<MessageEntity>) =
        chatDao.importRunGraph(runs, messages)

    suspend fun appendMessageToRun(message: MessageEntity): MessageEntity {
        ensureRunRecovery()
        require(message.runId.isNotBlank()) { "Message ${message.id} has no Run" }
        return chatDao.appendMessageToRun(message)
    }

    suspend fun appendToolRoundToRun(messages: List<MessageEntity>): List<MessageEntity> {
        ensureRunRecovery()
        require(messages.isNotEmpty() && messages.all { it.runId.isNotBlank() })
        return chatDao.appendToolRoundToRun(messages)
    }

    suspend fun getRun(runId: String): RunEntity? = chatDao.getRun(runId)

    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>> =
        chatDao.getRunsForConversation(conversationId)

    suspend fun getRunsForConversationSnapshot(conversationId: String): List<RunEntity> =
        chatDao.getRunsForConversationSnapshot(conversationId)

    suspend fun getMessagesForRuns(runIds: List<String>): List<MessageEntity> =
        if (runIds.isEmpty()) emptyList() else chatDao.getMessagesForRuns(runIds)

    suspend fun deleteMessageSubtree(
        conversationId: String,
        rootMessageId: String,
        staleMessageIds: List<String>,
        rootRunIdsToDelete: List<String>,
        messageSelections: Map<String?, String>,
        runSelections: Map<String?, String>,
        at: Long = System.currentTimeMillis(),
    ): Boolean = chatDao.deleteMessageSubtree(
        conversationId = conversationId,
        rootMessageId = rootMessageId,
        staleMessageIds = staleMessageIds,
        rootRunIdsToDelete = rootRunIdsToDelete,
        selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
        selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
        at = at,
    )

    suspend fun getLiveRun(conversationId: String): RunEntity? =
        chatDao.getLiveRun(conversationId)

    suspend fun requestRunStop(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        chatDao.markRunStopping(runId, at) == 1

    suspend fun finishRunStopped(
        runId: String,
        reason: RunEndReason = RunEndReason.USER_STOPPED,
        at: Long = System.currentTimeMillis(),
    ): Boolean = chatDao.terminalizeLiveRun(runId, RunStatus.STOPPED, reason, at) == 1

    suspend fun completeRun(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        chatDao.terminalizeLiveRun(
            runId,
            RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED,
            at,
        ) == 1

    suspend fun failRun(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        chatDao.terminalizeLiveRun(
            runId,
            RunStatus.FAILED,
            RunEndReason.PROVIDER_ERROR,
            at,
        ) == 1

    suspend fun claimPendingRunInputs(
        runId: String,
        at: Long = System.currentTimeMillis(),
    ): ClaimedRunPass? = chatDao.claimPendingRunInputs(runId, at)

    suspend fun removePendingRunInput(messageId: String): RemovedPendingRunInput? =
        chatDao.removePendingRunInput(messageId)

    suspend fun recoverOrphanedRuns(at: Long = System.currentTimeMillis()): Int {
        val recovered = chatDao.recoverOrphanedRuns(at)
        runRecoveryComplete = true
        return recovered
    }

    /**
     * Persist the mutable portion of an in-flight model message without creating a missing row.
     * Returns false when the placeholder was deleted while generation was still unwinding.
     */
    suspend fun updateStreamingMessageCheckpoint(message: ChatMessage): Boolean =
        chatDao.updateMessageCheckpoint(message.toStreamCheckpoint()) > 0

    /** Atomically persists the final stopped snapshot(s) and terminalizes their Run. */
    suspend fun finishStoppedGeneration(
        messages: List<ChatMessage>,
        runId: String?,
        at: Long = System.currentTimeMillis(),
    ): Boolean = chatDao.finishStoppedGeneration(
        checkpoints = messages.map { it.copy(status = MessageStatus.STOPPED).toStreamCheckpoint() },
        runId = runId,
        at = at,
    )

    private fun ChatMessage.toStreamCheckpoint(): MessageStreamCheckpoint {
        val persistedSegments = segments?.takeIf { it.isNotEmpty() } ?: toolCall?.let {
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = it.toolName,
                    toolArgs = it.arguments,
                    toolResult = it.result,
                    signature = it.signature,
                    toolCallId = it.toolCallId,
                )
            )
        }
        return MessageStreamCheckpoint(
            id = id,
            text = MessagePersistenceGuard.clipText(text),
            images = images,
            thoughts = thoughts,
            thoughtTitle = thoughtTitle,
            tokenCount = tokenCount,
            status = status,
            thoughtTimeMs = thoughtTimeMs,
            toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(persistedSegments),
        )
    }

    suspend fun deleteMessagesByIds(ids: List<String>) = chatDao.deleteMessagesByIds(ids)

    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity> =
        chatDao.getMessagesByIds(ids)

    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity> =
        if (ids.isEmpty()) emptyList() else chatDao.getSearchableMessagesByIds(ids)

    suspend fun isMessageSearchable(messageId: String): Boolean =
        chatDao.isMessageSearchable(messageId)

    // ── Branch Selection ──────────────────────────────────────

    suspend fun saveBranchSelections(conversationId: String, selections: Map<String?, String>) {
        val conversation = chatDao.getConversation(conversationId) ?: return
        val stringKeyMap = selections.mapKeys { it.key ?: "null" }
        val json = Json.encodeToString(stringKeyMap)
        if (conversation.selectedBranchesJson != json) {
            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = json, lastUpdated = System.currentTimeMillis()))
        }
    }

    suspend fun restoreBranchSelections(conversationId: String): Map<String?, String> {
        val conversation = chatDao.getConversation(conversationId) ?: return emptyMap()
        val raw = conversation.selectedBranchesJson ?: return emptyMap()
        return try {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            map.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun saveRunBranchSelections(conversationId: String, selections: Map<String?, String>) {
        val conversation = chatDao.getConversation(conversationId) ?: return
        val stored = Json.encodeToString(selections.mapKeys { it.key ?: "null" })
        if (conversation.selectedRunBranchesJson != stored) {
            chatDao.upsertConversation(
                conversation.copy(
                    selectedRunBranchesJson = stored,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun restoreRunBranchSelections(conversationId: String): Map<String?, String> {
        val raw = chatDao.getConversation(conversationId)?.selectedRunBranchesJson ?: return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
                .mapKeys { if (it.key == "null") null else it.key }
        }.getOrDefault(emptyMap())
    }

    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
    ) {
        val selections = restoreRunBranchSelections(conversationId).toMutableMap()
        selections[parentRunId] = runId
        saveRunBranchSelections(conversationId, selections)
    }

    /** Persists Run and legacy message selection maps in the same row update. */
    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
        messageSelections: Map<String?, String>,
        at: Long = System.currentTimeMillis(),
    ) {
        val runSelections = restoreRunBranchSelections(conversationId).toMutableMap()
        runSelections[parentRunId] = runId
        check(
            chatDao.updateSelectionsForRunDeletion(
                conversationId = conversationId,
                selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
                selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
                at = at,
            ) == 1
        ) { "Conversation $conversationId disappeared during branch selection" }
    }

    // ── Stuck Message Fixer ───────────────────────────────────

    suspend fun fixStuckMessages(conversationId: String) {
        val stuckMessages = chatDao.getMessagesForConversation(conversationId).first()
            .filter {
                it.status == MessageStatus.SENDING ||
                it.status == MessageStatus.THINKING ||
                it.status == MessageStatus.TOOL_CALLING ||
                it.status == MessageStatus.TRANSCRIBING
            }
        stuckMessages.forEach { msg ->
            chatDao.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
        }
    }

    // ── Embeddings ────────────────────────────────────────────

    suspend fun deleteEmbeddingsByConversation(conversationId: String) =
        chatDao.deleteEmbeddingsByConversation(conversationId)

    suspend fun deleteOrphanedEmbeddings() =
        chatDao.deleteOrphanedEmbeddings()

    suspend fun deleteEmbeddingsByModel(modelId: String) =
        chatDao.deleteEmbeddingsByModel(modelId)

    suspend fun upsertEmbedding(entity: EmbeddingEntity) =
        chatDao.upsertEmbedding(entity)

    suspend fun upsertEmbeddingIfSearchable(entity: EmbeddingEntity): Boolean =
        chatDao.upsertEmbeddingIfSearchable(entity)

    suspend fun deleteAllConversations() =
        chatDao.deleteAllConversations()

    suspend fun findExistingMessageIds(ids: List<String>): List<String> =
        chatDao.findExistingMessageIds(ids)

    suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingEntity> =
        chatDao.getEmbeddingsByModel(modelId)

    suspend fun deleteEmbedding(messageId: String) =
        chatDao.deleteEmbedding(messageId)

    suspend fun getEmbeddingCountByModel(modelId: String): Int =
        chatDao.getEmbeddingCountByModel(modelId)

    suspend fun getIndexableMessageCount(): Int =
        chatDao.getIndexableMessageCount()

    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage> =
        chatDao.getUnembeddedMessagesPage(modelId, afterId, limit)

    // ── Search ────────────────────────────────────────────────

    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity> =
        chatDao.searchMessages(escapeLikePattern(query), limit)

    /** Escapes LIKE wildcards so a literal "%"/"_" in the user's query matches itself
     *  instead of matching everything (paired with ESCAPE '\' in the DAO query). */
    private fun escapeLikePattern(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    suspend fun getAllConversationsList(): List<ChatEntity> =
        chatDao.getAllConversationsList()

    suspend fun getSearchableConversation(id: String): ChatEntity? =
        chatDao.getSearchableConversation(id)

    suspend fun getSearchableConversationsList(): List<ChatEntity> =
        chatDao.getSearchableConversationsList()

    suspend fun getMessageAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference> =
        chatDao.getMessageAttachmentReferencesPage(afterId, limit)

    suspend fun getConversationDraftAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
    ): List<ConversationDraftAttachmentReference> =
        chatDao.getConversationDraftAttachmentReferencesPage(afterId, limit)

    /** Persists the composer draft (text + serialized attachments) for a conversation. */
    suspend fun updateDraft(conversationId: String, draftText: String, draftAttachments: String?) {
        chatDao.updateDraft(conversationId, draftText, draftAttachments)
    }

    /** Deletes all on-disk attachment files referenced by [messages]. Safe to call with
     *  an empty list. Errors per-file are swallowed so one bad path never aborts a delete. */
    suspend fun deleteMessageFiles(messages: List<MessageEntity>) = deleteAttachmentFilesFromEntities(messages)

    /** Overload for the in-memory [ChatMessage] form used by the VM's cascade-delete path. */
    fun deleteMessageFiles(messages: List<ChatMessage>) {
        for (msg in messages) {
            for (imagePath in msg.images) {
                runCatching { java.io.File(imagePath).delete() }
            }
            msg.attachmentMeta?.items?.forEach { item ->
                val uri = item.originalUri ?: return@forEach
                if ((item.type == "video" || item.type == "image" || item.type == "file") &&
                    uri.startsWith("file://")
                ) {
                    runCatching { java.io.File(uri.removePrefix("file://")).delete() }
                }
            }
        }
    }

    private fun deleteAttachmentFilesFromEntities(messages: List<MessageEntity>) {
        for (msg in messages) {
            for (imagePath in msg.images) {
                runCatching { java.io.File(imagePath).delete() }
            }
            if (msg.attachmentMeta != null) {
                runCatching {
                    val meta = Json.decodeFromString<AttachmentMeta>(msg.attachmentMeta)
                    for (item in meta.items) {
                        val uri = item.originalUri ?: continue
                        if ((item.type == "video" || item.type == "image" || item.type == "file") &&
                            uri.startsWith("file://")
                        ) {
                            runCatching { java.io.File(uri.removePrefix("file://")).delete() }
                        }
                    }
                }
            }
        }
    }
}
