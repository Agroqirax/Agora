package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.local.migration.LegacyMessageRecord
import com.newoether.agora.data.local.migration.LegacyRunBackfillPlanner
import com.newoether.agora.data.local.migration.PlannedMessageAssignment
import com.newoether.agora.data.local.migration.RegenerationTreeRepairPlanner
import com.newoether.agora.data.local.migration.V17MessageRecord
import com.newoether.agora.data.local.migration.V17RunRecord
import com.newoether.agora.data.local.migration.regenerationInputFingerprint
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Imported automations are content, not permission to spend tokens in the background. Preserve a
 * valid cron for the user to review, but always restore the task disabled with no armed epoch.
 */
internal fun sanitizeImportedTask(task: TaskEntity): TaskEntity {
    val cron = task.cronExpr.trim()
    return task.copy(
        name = task.name.trim(),
        prompt = task.prompt.trim(),
        cronExpr = cron,
        nextRunAt = 0L,
        enabled = false,
    )
}

/**
 * Converts legacy unbounded loops to the bounded default. Invalid cadence/cycle state is kept
 * visible for diagnostics where useful, but is always made inactive so it cannot be scheduled.
 */
internal fun sanitizeImportedLoop(loop: LoopEntity): LoopEntity {
    val importedMaxCycles = loop.maxCycles
    val maxCycles = importedMaxCycles
        ?.takeIf { it in LoopPolicy.MIN_MAX_CYCLES..LoopPolicy.MAX_MAX_CYCLES }
        ?: LoopPolicy.DEFAULT_MAX_CYCLES
    return loop.copy(
        prompt = LoopPolicy.normalizePrompt(loop.prompt),
        cycleCount = loop.cycleCount.coerceAtLeast(0),
        maxCycles = maxCycles,
        // Importing a backup never authorizes an automatic model call. Keep the state for review,
        // but require an explicit restart on this device.
        active = false,
        nextFireAt = 0L,
    )
}

private fun decodeStoredSelections(raw: String?): Map<String?, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.decodeFromString<Map<String, String>>(raw)
            .mapKeys { if (it.key == "null") null else it.key }
    }.getOrDefault(emptyMap())
}

private fun encodeStoredSelections(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/** Prevents a missing Task row from making an imported execution permanently unreachable. */
internal fun sanitizeImportedConversation(
    conversation: ChatEntity,
    availableTaskIds: Set<String>,
): ChatEntity {
    val withoutDeviceReadState = conversation.copy(hasUnreadGeneration = false)
    return if (
        withoutDeviceReadState.taskId != null &&
        withoutDeviceReadState.taskId !in availableTaskIds
    ) {
        withoutDeviceReadState.copy(taskId = null, origin = "user", graduated = true)
    } else {
        withoutDeviceReadState
    }
}

class DataImporter(
    private val context: Context,
    private val database: ChatDatabase,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    enum class ImportStrategy { MERGE, REPLACE, SKIP }

    companion object {
        private const val IMPORT_MESSAGE_BATCH_SIZE = 64
        private const val MAX_CUSTOM_FONT_BYTES = 64L * 1024L * 1024L
    }

    private val importJson = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ImportManifest(
        @SerialName("agora_export_version") val version: Int = 1,
        @SerialName("app_version") val appVersion: String = "",
        @SerialName("exported_at") val exportedAt: String = "",
        val categories: List<String> = emptyList(),
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

    data class ImportPreview(
        val manifest: ImportManifest,
        val conversationCount: Int = 0,
        val taskCount: Int = 0,
        val loopCount: Int = 0,
        val memoryCount: Int = 0,
        val systemPromptCount: Int = 0,
        val settingsPresent: Boolean = false,
        val apiKeysPresent: Boolean = false
    ) {
        val hasConversationGraph: Boolean
            get() = conversationCount > 0 || taskCount > 0 || loopCount > 0
        val isSupportedVersion: Boolean
            get() = NativeBackupFormat.isSupported(manifest.version)
    }

    data class ImportResult(
        val conversationsImported: Int = 0,
        val tasksImported: Int = 0,
        val loopsImported: Int = 0,
        val memoriesImported: Int = 0,
        val systemPromptsImported: Int = 0,
        val settingsImported: Boolean = false,
        val apiKeysImported: Boolean = false,
        val errors: List<String> = emptyList()
    )

    private fun detectImageExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "jpg"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "webp"
            else -> "jpg"
        }
    }

    private fun detectVideoExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "mp4"
        return when {
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "webm"
            else -> "mp4"
        }
    }

    /**
     * On-demand, memory-bounded reader over a backup ZIP. Entries are decoded
     * only when requested and one at a time, so large image/video blobs never
     * accumulate in memory (the previous implementation buffered *every* entry
     * into a `Map<String, ByteArray>` up front — a real OOM risk for backups with
     * many media attachments). The SAF stream is first copied to a temp file
     * because [ZipFile] needs random access; [close] disposes both.
     */
    private class Archive private constructor(
        private val zip: ZipFile,
        private val tmp: File
    ) : Closeable {
        fun has(name: String): Boolean = zip.getEntry(name) != null
        fun size(name: String): Long = zip.getEntry(name)?.size ?: -1L
        fun bytes(name: String): ByteArray? =
            zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        /** Map-style accessor so existing `archive["x"]` call sites read unchanged. */
        operator fun get(name: String): ByteArray? = bytes(name)
        fun stream(name: String): InputStream? = zip.getEntry(name)?.let { zip.getInputStream(it) }
        fun names(): List<String> =
            zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()

        override fun close() {
            try { zip.close() } finally { tmp.delete() }
        }

        companion object {
            fun open(context: Context, uri: Uri): Archive? {
                // Copy SAF content to a temp file so we can use ZipFile (random access,
                // more reliable than ZipInputStream).
                val tmp = File(context.cacheDir, "agora_import_tmp.zip")
                return try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } ?: run { tmp.delete(); return null }
                    Archive(ZipFile(tmp), tmp)
                } catch (_: Exception) {
                    tmp.delete()
                    null
                }
            }
        }
    }

    private data class ConversationGraphCounts(
        val conversations: Int = 0,
        val tasks: Int = 0,
        val loops: Int = 0,
    )

    private data class ConversationGraphHeaders(
        val tasks: List<TaskEntity>,
        val conversations: List<ChatEntity>,
        val runs: List<RunEntity>,
        val sourceRunIdsWereUnique: Boolean,
        val loops: List<LoopEntity>,
        val availableConversationIds: Set<String>,
        val conversationSettings: Map<String, ConversationSettings>,
    )

    private data class RestoredMediaFile(
        val absolutePath: String,
        val uri: String,
    )

    private data class RestoredMedia(
        val archiveFiles: Map<String, RestoredMediaFile>,
        val legacyImagesByMessage: Map<String, List<String>>,
        val legacyVideosByMessage: Map<String, Map<Int, String>>,
        val createdFiles: List<File>,
    )

    private data class PromptImportResult(
        val importedCount: Int = 0,
        val idMap: Map<String, String> = emptyMap(),
        val availableIds: Set<String> = emptySet(),
    ) {
        fun resolve(id: String?): String? =
            id?.let { original -> idMap[original] ?: original.takeIf(availableIds::contains) }
    }

    private data class PlannedNativeRunGraph(
        val runs: List<RunEntity>,
        val assignments: Map<String, PlannedMessageAssignment>,
        val recoveredRunIds: Set<String> = emptySet(),
        val legacyRunSelections: Map<String, Map<String?, String>> = emptyMap(),
        val messageSelectionOverrides: Map<String, Map<String?, String>> = emptyMap(),
        val deletedMessageIds: Set<String> = emptySet(),
        val messageParentOverrides: Map<String, String> = emptyMap(),
    )

    /** Reads one JSON value only; callers retain at most one exported entity at a time. */
    private fun readJsonElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val values = linkedMapOf<String, JsonElement>()
            reader.beginObject()
            while (reader.hasNext()) {
                values[reader.nextName()] = readJsonElement(reader)
            }
            reader.endObject()
            JsonObject(values)
        }
        JsonToken.BEGIN_ARRAY -> {
            val values = mutableListOf<JsonElement>()
            reader.beginArray()
            while (reader.hasNext()) {
                values.add(readJsonElement(reader))
            }
            reader.endArray()
            JsonArray(values)
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> importJson.parseToJsonElement(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> {
            reader.nextNull()
            JsonNull
        }
        else -> error("Unexpected JSON token ${reader.peek()}")
    }

    private inline fun <reified T> JsonReader.readSerializableArray(): List<T> {
        val values = mutableListOf<T>()
        beginArray()
        while (hasNext()) {
            values.add(importJson.decodeFromJsonElement(readJsonElement(this)))
        }
        endArray()
        return values
    }

    private fun countArray(reader: JsonReader): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            reader.skipValue()
            count++
        }
        reader.endArray()
        return count
    }

    /** Counts graph headers without deserializing the messages array. */
    private fun countConversationGraph(stream: InputStream): ConversationGraphCounts {
        var conversations = 0
        var tasks = 0
        var loops = 0
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "conversations" -> conversations = countArray(reader)
                    "tasks" -> tasks = countArray(reader)
                    "loops" -> loops = countArray(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return ConversationGraphCounts(conversations, tasks, loops)
    }

    private suspend fun readConversationGraphHeaders(
        stream: InputStream,
        strategy: ImportStrategy,
        restoredMedia: RestoredMedia,
        resolveSystemPromptId: (String?) -> String?,
    ): ConversationGraphHeaders {
        var rawConversations = emptyList<ExportChatEntity>()
        var rawRuns = emptyList<ExportRunEntity>()
        var rawTasks = emptyList<ExportTaskEntity>()
        var rawLoops = emptyList<ExportLoopEntity>()
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "conversations" -> rawConversations = reader.readSerializableArray()
                    "runs" -> rawRuns = reader.readSerializableArray()
                    "tasks" -> rawTasks = reader.readSerializableArray()
                    "loops" -> rawLoops = reader.readSerializableArray()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val tasks = rawTasks.map { task ->
            sanitizeImportedTask(TaskEntity(
                id = task.id,
                name = task.name,
                prompt = task.prompt,
                systemPrompt = task.systemPrompt,
                modelId = task.modelId,
                cronExpr = task.cronExpr,
                runAt = task.runAt,
                nextRunAt = task.nextRunAt,
                enabled = task.enabled,
                createdAt = task.createdAt,
                lastRunAt = task.lastRunAt,
            ))
        }
        val availableTaskIds = if (strategy == ImportStrategy.MERGE) {
            chatDao.getAllTaskIds().toMutableSet()
        } else {
            mutableSetOf()
        }.apply { addAll(tasks.map { it.id }) }

        val conversations = rawConversations.map { conversation ->
            sanitizeImportedConversation(
                ChatEntity(
                    id = conversation.id,
                    title = conversation.title,
                    lastUpdated = conversation.lastUpdated,
                    selectedBranchesJson = conversation.selectedBranchesJson,
                    systemPromptId = resolveSystemPromptId(conversation.systemPromptId),
                    modelId = conversation.modelId,
                    taskId = conversation.taskId,
                    origin = conversation.origin,
                    graduated = conversation.graduated,
                    draftText = conversation.draftText,
                    draftAttachments = restoreDraftAttachments(
                        conversation.draftAttachments,
                        restoredMedia,
                    ),
                    selectedRunBranchesJson = conversation.selectedRunBranchesJson,
                    // Unread is durable on one device, but it is not user content and must never
                    // become a false cross-device notification after restore.
                    hasUnreadGeneration = false,
                ),
                availableTaskIds,
            )
        }
        val availableConversationIds = if (strategy == ImportStrategy.MERGE) {
            chatDao.getAllConversationIds().toMutableSet()
        } else {
            mutableSetOf()
        }.apply { addAll(conversations.map { it.id }) }

        val availableRawRuns = rawRuns.filter {
            it.conversationId in availableConversationIds
        }
        val sourceRunIdsWereUnique =
            availableRawRuns.map { it.id }.distinct().size == availableRawRuns.size
        val runs = NativeRunArchivePolicy.orderByParent(
            availableRawRuns.map { NativeRunArchivePolicy.terminalize(it.toArchivedSnapshot()) }
        )

        val loops = rawLoops
            .filter { it.conversationId in availableConversationIds }
            .map { loop ->
                sanitizeImportedLoop(LoopEntity(
                    conversationId = loop.conversationId,
                    intervalMs = loop.intervalMs,
                    prompt = loop.prompt,
                    nextFireAt = loop.nextFireAt,
                    cycleCount = loop.cycleCount,
                    maxCycles = loop.maxCycles,
                    active = loop.active,
                    revision = loop.revision,
                ))
            }
        return ConversationGraphHeaders(
            tasks = tasks,
            conversations = conversations,
            runs = runs,
            sourceRunIdsWereUnique = sourceRunIdsWereUnique,
            loops = loops,
            availableConversationIds = availableConversationIds,
            conversationSettings = rawConversations.mapNotNull { conversation ->
                conversation.conversationSettings?.let { conversation.id to it }
            }.toMap(),
        )
    }

    private fun restoreConversationMedia(archive: Archive): RestoredMedia {
        val archiveFiles = mutableMapOf<String, RestoredMediaFile>()
        val legacyImagesByMessage =
            mutableMapOf<String, MutableList<Pair<Int, String>>>()
        val legacyVideosByMessage =
            mutableMapOf<String, MutableMap<Int, String>>()
        val createdFiles = mutableListOf<File>()
        val names = archive.names()
        try {
            val imagesDir = File(context.filesDir, "images")
            imagesDir.mkdirs()

            fun restoreEntry(path: String, kind: String): RestoredMediaFile? {
                return archive.stream(path)?.buffered()?.use { input ->
                    input.mark(16)
                    val header = ByteArray(16)
                    val headerSize = input.read(header).coerceAtLeast(0)
                    input.reset()
                    val extension = when (kind) {
                        "image" -> detectImageExtension(header.copyOf(headerSize))
                        "video" -> detectVideoExtension(header.copyOf(headerSize))
                        else -> path.substringAfterLast('.', "bin")
                            .lowercase()
                            .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
                            ?: "bin"
                    }
                    val targetDir = if (kind == "image") imagesDir else context.filesDir
                    val prefix = when (kind) {
                        "image" -> "img_import_"
                        "video" -> "vid_import_"
                        else -> "draft_import_"
                    }
                    val target = File(targetDir, "$prefix${UUID.randomUUID()}.$extension")
                    val copied = target.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                    if (copied <= 0L) {
                        target.delete()
                        null
                    } else {
                        createdFiles += target
                        val uri = if (kind == "image") {
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                target,
                            ).toString()
                        } else {
                            "file://${target.absolutePath}"
                        }
                        RestoredMediaFile(target.absolutePath, uri)
                    }
                }
            }

            names.asSequence()
                .filter {
                    it.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) ||
                        it.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) ||
                        it.startsWith(NativeBackupFormat.DRAFT_MEDIA_PREFIX)
                }
                .forEach { path ->
                    val kind = when {
                        path.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) -> "image"
                        path.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) -> "video"
                        else -> "draft"
                    }
                    restoreEntry(path, kind)?.let { archiveFiles[path] = it }
                }

            // v1-v3 media layout. Sort by the explicit numeric index instead of trusting ZIP
            // enumeration order.
            names.filter { it.startsWith("images/") }.forEach { path ->
                val parts = path.removePrefix("images/").split("/")
                if (parts.size != 2) return@forEach
                val index = parts[1].toIntOrNull() ?: return@forEach
                restoreEntry(path, "image")?.let { restored ->
                    legacyImagesByMessage
                        .getOrPut(parts[0]) { mutableListOf() }
                        .add(index to restored.uri)
                }
            }

            names.filter { it.startsWith("videos/") }.forEach { path ->
                val parts = path.removePrefix("videos/").split("/")
                if (parts.size != 2) return@forEach
                val index = parts[1].toIntOrNull() ?: return@forEach
                restoreEntry(path, "video")?.let { restored ->
                    legacyVideosByMessage
                        .getOrPut(parts[0]) { mutableMapOf() }[index] = restored.uri
                }
            }
        } catch (error: Exception) {
            createdFiles.forEach { runCatching { it.delete() } }
            throw error
        }

        return RestoredMedia(
            archiveFiles = archiveFiles,
            legacyImagesByMessage = legacyImagesByMessage.mapValues { (_, indexed) ->
                indexed.sortedBy { it.first }.map { it.second }
            },
            legacyVideosByMessage = legacyVideosByMessage,
            createdFiles = createdFiles,
        )
    }

    private fun restoreDraftAttachments(
        raw: String?,
        restoredMedia: RestoredMedia,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val attachments = runCatching {
            importJson.decodeFromString<List<SelectedAttachment>>(raw)
        }.getOrNull() ?: return null
        val restored = attachments.mapNotNull { attachment ->
            val primary = restoredMedia.archiveFiles[attachment.localPath]
                ?: restoredMedia.archiveFiles[attachment.uri]
                ?: return@mapNotNull null
            attachment.copy(
                uri = primary.uri,
                localPath = primary.absolutePath,
                processedFrames = attachment.processedFrames
                    ?.mapNotNull { restoredMedia.archiveFiles[it]?.absolutePath }
                    ?.takeIf { it.isNotEmpty() },
                preRenderedPaths = attachment.preRenderedPaths
                    ?.mapNotNull { restoredMedia.archiveFiles[it]?.absolutePath }
                    ?.takeIf { it.isNotEmpty() },
            )
        }
        return restored.takeIf { it.isNotEmpty() }?.let(importJson::encodeToString)
    }

    private fun ExportMessageEntity.toMessageEntity(
        restoredMedia: RestoredMedia,
        assignment: PlannedMessageAssignment,
        recoveredRunIds: Set<String>,
        archiveVersion: Int,
    ): MessageEntity {
        val parsedParticipant = try {
            Participant.valueOf(participant)
        } catch (_: Exception) {
            Participant.MODEL
        }
        val parsedStatus = try {
            MessageStatus.valueOf(status)
        } catch (_: Exception) {
            MessageStatus.SUCCESS
        }
        val restoredImages = if (archiveVersion >= 4) {
            images.mapNotNull { restoredMedia.archiveFiles[it]?.uri }
        } else {
            restoredMedia.legacyImagesByMessage[id].orEmpty()
        }
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            parentId = parentId,
            text = text,
            images = restoredImages,
            thoughts = thoughts,
            thoughtTitle = thoughtTitle,
            tokenCount = tokenCount,
            inputTokenCount = inputTokenCount,
            cachedInputTokenCount = cachedInputTokenCount,
            uncachedInputTokenCount = uncachedInputTokenCount,
            outputTokenCount = outputTokenCount,
            reasoningTokenCount = reasoningTokenCount,
            status = if (
                assignment.runId in recoveredRunIds &&
                parsedParticipant == Participant.MODEL &&
                parsedStatus in setOf(
                    MessageStatus.SENDING,
                    MessageStatus.THINKING,
                    MessageStatus.TOOL_CALLING,
                    MessageStatus.TRANSCRIBING,
                )
            ) MessageStatus.STOPPED else parsedStatus,
            participant = parsedParticipant,
            timestamp = timestamp,
            thoughtTimeMs = thoughtTimeMs,
            modelName = modelName,
            toolCallJson = NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = toolCallJson,
                archiveVersion = archiveVersion,
                restoredPathForArchiveEntry = { entry ->
                    restoredMedia.archiveFiles[entry]?.absolutePath
                },
            ),
            attachmentMeta = NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = attachmentMeta,
                archiveVersion = archiveVersion,
                legacyVideoUris = restoredMedia.legacyVideosByMessage[id].orEmpty(),
                restoredUriForArchiveEntry = { entry ->
                    restoredMedia.archiveFiles[entry]?.uri
                },
            ),
            runId = assignment.runId,
            runSequence = assignment.runSequence,
            consumedAtPass = assignment.consumedAtPass,
        )
    }

    private suspend fun importMessagesFromGraph(
        stream: InputStream,
        strategy: ImportStrategy,
        availableConversationIds: Set<String>,
        restoredMedia: RestoredMedia,
        assignments: Map<String, PlannedMessageAssignment>,
        recoveredRunIds: Set<String>,
        deletedMessageIds: Set<String>,
        messageParentOverrides: Map<String, String>,
        archiveVersion: Int,
    ) {
        val batch = mutableListOf<MessageEntity>()

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val existingIds = if (strategy == ImportStrategy.MERGE) {
                chatDao.findExistingMessageIds(batch.map { it.id }).toSet()
            } else {
                emptySet()
            }
            batch.forEach { message ->
                if (message.id !in existingIds || message.images.isNotEmpty()) {
                    chatDao.upsertMessage(message)
                }
            }
            batch.clear()
        }

        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "messages") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    val exported = importJson.decodeFromJsonElement<ExportMessageEntity>(
                        readJsonElement(reader)
                    )
                    if (exported.conversationId in availableConversationIds) {
                        if (exported.id in deletedMessageIds) continue
                        var message = exported.toMessageEntity(
                                restoredMedia,
                                checkNotNull(assignments[exported.id]) {
                                    "Message ${exported.id} has no planned Run assignment"
                                },
                                recoveredRunIds,
                                archiveVersion,
                            )
                        messageParentOverrides[exported.id]?.let { repairedParentId ->
                            message = message.copy(parentId = repairedParentId)
                        }
                        batch.add(message)
                        if (batch.size >= IMPORT_MESSAGE_BATCH_SIZE) {
                            flushBatch()
                        }
                    }
                }
                reader.endArray()
            }
            reader.endObject()
        }
        flushBatch()
    }

    private fun planNativeRunGraph(
        stream: InputStream,
        headers: ConversationGraphHeaders,
    ): PlannedNativeRunGraph {
        val messagesByConversation = mutableMapOf<String, MutableList<LegacyMessageRecord>>()
        val repairMessagesByConversation =
            mutableMapOf<String, MutableList<V17MessageRecord>>()
        val archivedOwnership = mutableListOf<ArchivedMessageRunOwnership>()
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "messages") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    val exported = importJson.decodeFromJsonElement<ExportMessageEntity>(
                        readJsonElement(reader)
                    )
                    if (exported.conversationId in headers.availableConversationIds) {
                        val participant = try {
                            Participant.valueOf(exported.participant)
                        } catch (_: Exception) {
                            Participant.MODEL
                        }
                        val status = try {
                            MessageStatus.valueOf(exported.status)
                        } catch (_: Exception) {
                            MessageStatus.SUCCESS
                        }
                        archivedOwnership += ArchivedMessageRunOwnership(
                            messageId = exported.id,
                            conversationId = exported.conversationId,
                            runId = exported.runId,
                            runSequence = exported.runSequence,
                            consumedAtPass = exported.consumedAtPass,
                        )
                        messagesByConversation.getOrPut(exported.conversationId) { mutableListOf() }
                            .add(
                                LegacyMessageRecord(
                                    id = exported.id,
                                    parentId = exported.parentId,
                                    participant = participant,
                                    status = status,
                                    timestamp = exported.timestamp,
                                )
                            )
                        val runId = exported.runId
                        val runSequence = exported.runSequence
                        if (runId != null && runSequence != null) {
                            repairMessagesByConversation
                                .getOrPut(exported.conversationId) { mutableListOf() }
                                .add(
                                    V17MessageRecord(
                                        id = exported.id,
                                        parentId = exported.parentId,
                                        participant = participant,
                                        timestamp = exported.timestamp,
                                        runId = runId,
                                        runSequence = runSequence,
                                        inputFingerprint = if (participant == Participant.USER) {
                                            regenerationInputFingerprint(
                                                exported.text,
                                                exported.images.size,
                                                exported.attachmentMeta,
                                            )
                                        } else {
                                            ""
                                        },
                                    )
                                )
                        }
                    }
                }
                reader.endArray()
            }
            reader.endObject()
        }

        val archiveOwnershipIsComplete = NativeRunArchivePolicy.hasCompleteOwnership(
            runs = headers.runs,
            ownership = archivedOwnership,
            sourceRunIdsWereUnique = headers.sourceRunIdsWereUnique,
        )
        if (archiveOwnershipIsComplete) {
            val runsByConversation = headers.runs.groupBy { it.conversationId }
            val conversationsById = headers.conversations.associateBy { it.id }
            val runParentUpdates = mutableMapOf<String, String>()
            val deletedMessageIds = mutableSetOf<String>()
            val messageParentOverrides = mutableMapOf<String, String>()
            val runSequenceOverrides = mutableMapOf<String, Long>()
            val messageSelectionOverrides =
                mutableMapOf<String, Map<String?, String>>()
            val runSelectionOverrides =
                mutableMapOf<String, Map<String?, String>>()

            for (conversationId in headers.availableConversationIds) {
                val conversation = conversationsById[conversationId] ?: continue
                val repair = RegenerationTreeRepairPlanner.plan(
                    runs = runsByConversation[conversationId].orEmpty().map {
                        V17RunRecord(it.id, it.parentRunId, it.startedAt)
                    },
                    messages = repairMessagesByConversation[conversationId].orEmpty(),
                    messageSelections = decodeStoredSelections(conversation.selectedBranchesJson),
                    runSelections = decodeStoredSelections(conversation.selectedRunBranchesJson),
                )
                if (repair.inferredRunIds.isEmpty()) continue
                runParentUpdates += repair.runParentUpdates
                deletedMessageIds += repair.deletedMessageIds
                messageParentOverrides += repair.messageParentUpdates
                runSequenceOverrides += repair.runSequenceUpdates
                messageSelectionOverrides[conversationId] = repair.messageSelections
                runSelectionOverrides[conversationId] = repair.runSelections
            }

            val repairedRuns = NativeRunArchivePolicy.orderByParent(
                headers.runs.map { run ->
                    runParentUpdates[run.id]?.let { parentRunId ->
                        run.copy(
                            parentRunId = parentRunId,
                            legacyAmbiguous = true,
                        )
                    } ?: run
                }
            )
            val assignments = archivedOwnership
                .asSequence()
                .filter { it.messageId !in deletedMessageIds }
                .associate { ownership ->
                ownership.messageId to PlannedMessageAssignment(
                    messageId = ownership.messageId,
                    runId = checkNotNull(ownership.runId),
                    runSequence = runSequenceOverrides[ownership.messageId]
                        ?: checkNotNull(ownership.runSequence),
                    consumedAtPass = ownership.consumedAtPass,
                )
            }
            return PlannedNativeRunGraph(
                runs = repairedRuns,
                assignments = assignments,
                recoveredRunIds = repairedRuns
                    .filter { it.endReason == RunEndReason.PROCESS_RECOVERED }
                    .mapTo(mutableSetOf()) { it.id },
                legacyRunSelections = runSelectionOverrides,
                messageSelectionOverrides = messageSelectionOverrides,
                deletedMessageIds = deletedMessageIds,
                messageParentOverrides = messageParentOverrides,
            )
        }

        val runs = mutableListOf<RunEntity>()
        val assignments = mutableMapOf<String, PlannedMessageAssignment>()
        val legacyRunSelections = mutableMapOf<String, Map<String?, String>>()
        val conversationsById = headers.conversations.associateBy { it.id }
        for (conversation in headers.conversations) {
            val conversationId = conversation.id
            val messages = messagesByConversation[conversationId].orEmpty()
            val plan = LegacyRunBackfillPlanner.plan(conversationId, messages)
            runs += plan.runs.map {
                RunEntity(
                    id = it.id,
                    conversationId = it.conversationId,
                    parentRunId = it.parentRunId,
                    status = it.status,
                    activeSlot = null,
                    startedAt = it.startedAt,
                    lastCheckpointAt = it.endedAt,
                    endedAt = it.endedAt,
                    endReason = it.endReason,
                    legacyAmbiguous = it.legacyAmbiguous,
                )
            }
            plan.assignments.forEach { assignments[it.messageId] = it }
            val messageSelections = conversationsById[conversationId]
                ?.selectedBranchesJson
                ?.let { raw ->
                    runCatching {
                        importJson.decodeFromString<Map<String, String>>(raw)
                            .mapKeys { if (it.key == "null") null else it.key }
                    }.getOrDefault(emptyMap())
                }
                .orEmpty()
            legacyRunSelections[conversationId] = LegacyRunBackfillPlanner.selectedRunBranches(
                messages,
                plan,
                messageSelections,
            )
        }
        return PlannedNativeRunGraph(
            runs = NativeRunArchivePolicy.orderByParent(runs),
            assignments = assignments,
            legacyRunSelections = legacyRunSelections,
        )
    }

    private suspend fun importConversationGraph(
        archive: Archive,
        strategy: ImportStrategy,
        headers: ConversationGraphHeaders,
        restoredMedia: RestoredMedia,
        archiveVersion: Int,
    ) {
        val plannedRunGraph = archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)?.use { stream ->
            planNativeRunGraph(stream, headers)
        } ?: error("${NativeBackupFormat.CONVERSATIONS_ENTRY} is missing")
        database.withTransaction {
            if (strategy == ImportStrategy.REPLACE) {
                chatDao.deleteAllLoops()
                chatDao.deleteAllConversations()
                chatDao.deleteAllTasks()
                chatDao.deleteOrphanedEmbeddings()
            }
            headers.tasks.forEach { chatDao.upsertTask(it) }
            headers.conversations.forEach { conversation ->
                val derivedRunSelections = plannedRunGraph.legacyRunSelections[conversation.id]
                val derivedMessageSelections =
                    plannedRunGraph.messageSelectionOverrides[conversation.id]
                chatDao.upsertConversation(
                    conversation.copy(
                        selectedBranchesJson = derivedMessageSelections
                            ?.let(::encodeStoredSelections)
                            ?: conversation.selectedBranchesJson,
                        selectedRunBranchesJson = derivedRunSelections
                            ?.let(::encodeStoredSelections)
                            ?: conversation.selectedRunBranchesJson,
                    )
                )
            }
            for (run in plannedRunGraph.runs) {
                if (chatDao.getRun(run.id) == null) chatDao.insertRun(run)
            }
            archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)?.use { stream ->
                importMessagesFromGraph(
                    stream = stream,
                    strategy = strategy,
                    availableConversationIds = headers.availableConversationIds,
                    restoredMedia = restoredMedia,
                    assignments = plannedRunGraph.assignments,
                    recoveredRunIds = plannedRunGraph.recoveredRunIds,
                    deletedMessageIds = plannedRunGraph.deletedMessageIds,
                    messageParentOverrides = plannedRunGraph.messageParentOverrides,
                    archiveVersion = archiveVersion,
                )
            } ?: error("${NativeBackupFormat.CONVERSATIONS_ENTRY} is missing")
            headers.loops.forEach { chatDao.upsertLoop(it) }
        }

        val currentSettings = settingsManager.conversationSettings.first()
        val importedSettings = headers.conversationSettings
            .filterKeys(headers.conversations.mapTo(mutableSetOf()) { it.id }::contains)
        settingsManager.saveConversationSettingsMap(
            if (strategy == ImportStrategy.REPLACE) {
                importedSettings
            } else {
                currentSettings + importedSettings
            },
        )
    }

    suspend fun readManifest(uri: Uri): ImportManifest? {
        return withContext(Dispatchers.IO) {
            Archive.open(context, uri)?.use { archive ->
                val manifestJson = archive[NativeBackupFormat.MANIFEST_ENTRY]
                    ?.decodeToString() ?: return@use null
                try {
                    importJson.decodeFromString<ImportManifest>(manifestJson)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun preview(uri: Uri): ImportPreview {
        return withContext(Dispatchers.IO) {
            val empty = ImportPreview(ImportManifest(version = 0))
            val archive = Archive.open(context, uri) ?: return@withContext empty
            archive.use {
                val manifestJson = archive[NativeBackupFormat.MANIFEST_ENTRY]
                    ?.decodeToString() ?: return@use empty
                val manifest = try {
                    importJson.decodeFromString<ImportManifest>(manifestJson)
                } catch (_: Exception) {
                    return@use empty
                }

                var conversationCount = 0
                var taskCount = 0
                var loopCount = 0
                var systemPromptCount = 0
                val memoryCount = archive.names().count { it.startsWith("memories/") }
                val settingsPresent = archive.has(NativeBackupFormat.SETTINGS_ENTRY)
                val apiKeysPresent = archive.has(NativeBackupFormat.SECRETS_ENTRY)

                archive.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)?.use { stream ->
                    try {
                        val counts = countConversationGraph(stream)
                        conversationCount = counts.conversations
                        taskCount = counts.tasks
                        loopCount = counts.loops
                    } catch (e: Exception) { DebugLog.e("DataImporter", "Failed to parse conversations.json", e) }
                }

                archive[NativeBackupFormat.SYSTEM_PROMPTS_ENTRY]?.let { json ->
                    try {
                        val data = importJson.decodeFromString<List<SystemPromptEntry>>(json.decodeToString())
                        systemPromptCount = data.size
                    } catch (e: Exception) { DebugLog.e("DataImporter", "Failed to parse system_prompts.json", e) }
                }

                ImportPreview(
                    manifest = manifest,
                    conversationCount = conversationCount,
                    taskCount = taskCount,
                    loopCount = loopCount,
                    memoryCount = memoryCount,
                    systemPromptCount = systemPromptCount,
                    settingsPresent = settingsPresent,
                    apiKeysPresent = apiKeysPresent
                )
            }
        }
    }

    private suspend fun importSystemPrompts(
        archive: Archive,
        strategy: ImportStrategy,
    ): PromptImportResult {
        val bytes = archive[NativeBackupFormat.SYSTEM_PROMPTS_ENTRY]
            ?: error("${NativeBackupFormat.SYSTEM_PROMPTS_ENTRY} is missing")
        val imported = importJson.decodeFromString<List<SystemPromptEntry>>(bytes.decodeToString())
        if (strategy == ImportStrategy.REPLACE) {
            settingsManager.saveSystemPrompts(imported)
            return PromptImportResult(
                importedCount = imported.size,
                idMap = imported.associate { it.id to it.id },
                availableIds = imported.mapTo(mutableSetOf()) { it.id },
            )
        }

        val merged = settingsManager.systemPrompts.first().toMutableList()
        val usedTitles = merged.mapTo(mutableSetOf()) { it.title }
        val idMap = mutableMapOf<String, String>()
        for (prompt in imported) {
            val sameId = merged.firstOrNull { it.id == prompt.id }
            if (sameId == prompt) {
                idMap[prompt.id] = sameId.id
                continue
            }

            val targetId = if (sameId == null) prompt.id else UUID.randomUUID().toString()
            var targetTitle = prompt.title
            if (targetTitle in usedTitles) {
                val base = "${prompt.title} (imported)"
                targetTitle = base
                var suffix = 2
                while (targetTitle in usedTitles) {
                    targetTitle = "$base $suffix"
                    suffix++
                }
            }
            val restored = prompt.copy(id = targetId, title = targetTitle)
            merged += restored
            usedTitles += targetTitle
            idMap[prompt.id] = targetId
        }
        settingsManager.saveSystemPrompts(merged)
        return PromptImportResult(
            importedCount = imported.size,
            idMap = idMap,
            availableIds = merged.mapTo(mutableSetOf()) { it.id },
        )
    }

    private fun restoreCustomFont(
        archive: Archive,
        archiveVersion: Int,
    ): RestoredCustomFont? {
        val entry = if (archiveVersion >= 4) {
            NativeBackupFormat.CUSTOM_FONT_ENTRY.takeIf(archive::has)
        } else {
            archive.names().firstOrNull { path ->
                path.startsWith("custom_font/") && !path.removePrefix("custom_font/").contains('/')
            }
        } ?: return null
        val declaredSize = archive.size(entry)
        if (declaredSize > MAX_CUSTOM_FONT_BYTES) {
            throw IOException("Custom font exceeds the ${MAX_CUSTOM_FONT_BYTES / (1024 * 1024)} MB limit")
        }

        val temporary = File(context.filesDir, ".custom_font_import_${UUID.randomUUID()}.tmp")
        val target = File(context.filesDir, "custom_font_import_${UUID.randomUUID()}")
        try {
            archive.stream(entry)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_CUSTOM_FONT_BYTES) {
                            throw IOException("Custom font exceeds the import size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
            val displayName = com.newoether.agora.util.readFontName(temporary)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            return RestoredCustomFont(target.absolutePath, displayName)
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun import(
        uri: Uri,
        decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>,
        onProgress: (Float) -> Unit = {}
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            val archive = Archive.open(context, uri)
                ?: return@withContext ImportResult(errors = listOf("Could not open backup archive"))
            archive.use { opened ->
                val manifest = opened[NativeBackupFormat.MANIFEST_ENTRY]
                    ?.decodeToString()
                    ?.let { raw ->
                        runCatching { importJson.decodeFromString<ImportManifest>(raw) }.getOrNull()
                    }
                    ?: return@withContext ImportResult(
                        errors = listOf("${NativeBackupFormat.MANIFEST_ENTRY} is missing or invalid"),
                    )
                if (!NativeBackupFormat.isSupported(manifest.version)) {
                    return@withContext ImportResult(
                        errors = listOf(
                            "Unsupported backup version ${manifest.version}; this app supports " +
                                "${NativeBackupFormat.MIN_SUPPORTED_VERSION}–" +
                                "${NativeBackupFormat.CURRENT_VERSION}",
                        ),
                    )
                }

                val errors = mutableListOf<String>()
                var conversationsImported = 0
                var tasksImported = 0
                var loopsImported = 0
                var memoriesImported = 0
                var systemPromptsImported = 0
                var settingsImported = false
                var apiKeysImported = false

                val activeCategories = decisions.filter { it.value != ImportStrategy.SKIP }.keys
                val totalSteps = activeCategories.size
                var completed = 0
                fun step() {
                    completed++
                    onProgress(completed.toFloat() / totalSteps.coerceAtLeast(1))
                }

                val keysDecision = decisions[DataExporter.ExportCategory.API_KEYS]
                val allowLegacySecrets =
                    manifest.version < NativeBackupFormat.CURRENT_VERSION &&
                        keysDecision != null &&
                        keysDecision != ImportStrategy.SKIP

                // Import prompts before conversations/settings so every archived prompt reference
                // can be resolved after MERGE ID collision handling.
                var promptImport = PromptImportResult(
                    availableIds = settingsManager.systemPrompts.first()
                        .mapTo(mutableSetOf()) { it.id },
                )
                val promptsDecision = decisions[DataExporter.ExportCategory.SYSTEM_PROMPTS]
                if (promptsDecision != null && promptsDecision != ImportStrategy.SKIP) {
                    try {
                        promptImport = importSystemPrompts(opened, promptsDecision)
                        systemPromptsImported = promptImport.importedCount
                    } catch (error: Exception) {
                        errors += "System prompts: ${error.localizedMessage ?: "Unknown error"}"
                    }
                    step()
                }

                val convDecision = decisions[DataExporter.ExportCategory.CONVERSATIONS]
                if (convDecision != null && convDecision != ImportStrategy.SKIP) {
                    var restoredMedia: RestoredMedia? = null
                    try {
                        val media = restoreConversationMedia(opened)
                        restoredMedia = media
                        val headers = opened.stream(NativeBackupFormat.CONVERSATIONS_ENTRY)
                            ?.use { stream ->
                                readConversationGraphHeaders(
                                    stream = stream,
                                    strategy = convDecision,
                                    restoredMedia = media,
                                    resolveSystemPromptId = promptImport::resolve,
                                )
                            }
                            ?: error("${NativeBackupFormat.CONVERSATIONS_ENTRY} is missing")
                        importConversationGraph(
                            archive = opened,
                            strategy = convDecision,
                            headers = headers,
                            restoredMedia = media,
                            archiveVersion = manifest.version,
                        )
                        conversationsImported = headers.conversations.size
                        tasksImported = headers.tasks.size
                        loopsImported = headers.loops.size
                    } catch (error: Exception) {
                        restoredMedia?.createdFiles?.forEach { runCatching { it.delete() } }
                        errors += "Conversations: ${error.localizedMessage ?: "Unknown error"}"
                    }
                    step()
                }

                val memDecision = decisions[DataExporter.ExportCategory.MEMORIES]
                if (memDecision != null && memDecision != ImportStrategy.SKIP) {
                    try {
                        val memNames = opened.names().filter { it.startsWith("memories/") }
                        if (memDecision == ImportStrategy.REPLACE) {
                            memoryManager.listFiles().forEach { memoryManager.deleteFile(it.name) }
                            if (memoryManager.getActiveMemory().isNotEmpty()) {
                                memoryManager.updateActiveMemory("", "replace")
                            }
                        }
                        val existingNames = memoryManager.listFiles().map { it.name }.toSet()
                        for (path in memNames) {
                            val text = opened.bytes(path)?.decodeToString() ?: continue
                            when {
                                path == "memories/active_memory.md" && text.isNotBlank() -> {
                                    if (
                                        memDecision == ImportStrategy.REPLACE ||
                                        memoryManager.getActiveMemory().isEmpty()
                                    ) {
                                        memoryManager.updateActiveMemory(text, "replace")
                                    }
                                    memoriesImported++
                                }
                                path == "memories/memory_db/memory_meta.json" -> {
                                    if (
                                        memDecision == ImportStrategy.REPLACE ||
                                        memoryManager.getMetaJson() == "{}"
                                    ) {
                                        memoryManager.saveMetaJson(text)
                                    }
                                }
                                path.startsWith("memories/memory_db/") -> {
                                    val name = path.removePrefix("memories/memory_db/")
                                    if (
                                        memDecision == ImportStrategy.REPLACE ||
                                        name !in existingNames
                                    ) {
                                        try {
                                            memoryManager.createFile(name, text)
                                        } catch (_: Exception) {
                                            memoryManager.editFile(name, text)
                                        }
                                    }
                                    memoriesImported++
                                }
                            }
                        }
                    } catch (error: Exception) {
                        errors += "Memories: ${error.localizedMessage ?: "Unknown error"}"
                    }
                    step()
                }

                val settingsDecision = decisions[DataExporter.ExportCategory.SETTINGS]
                if (settingsDecision != null && settingsDecision != ImportStrategy.SKIP) {
                    var restoredFont: RestoredCustomFont? = null
                    var fontApplied = false
                    try {
                        val settingsObject = opened[NativeBackupFormat.SETTINGS_ENTRY]
                            ?.decodeToString()
                            ?.let { Json.parseToJsonElement(it).jsonObject }
                            ?: error("${NativeBackupFormat.SETTINGS_ENTRY} is missing")
                        restoredFont = try {
                            restoreCustomFont(opened, manifest.version)
                        } catch (error: Exception) {
                            errors += "Settings: custom font skipped: " +
                                (error.localizedMessage ?: "invalid font file")
                            null
                        }
                        val warnings = PortableSettingsArchive.restoreFromJsonObject(
                            obj = settingsObject,
                            sm = settingsManager,
                            replace = settingsDecision == ImportStrategy.REPLACE,
                            allowLegacySecrets = allowLegacySecrets,
                            restoredCustomFont = restoredFont,
                            resolveSystemPromptId = promptImport::resolve,
                        )
                        warnings.forEach { errors += "Settings: $it" }
                        fontApplied = restoredFont != null && manifest.version >= 4

                        if (manifest.version < 4) {
                            if (restoredFont != null) {
                                settingsManager.saveCustomFontPath(restoredFont.path)
                                settingsManager.saveCustomFontName(restoredFont.displayName)
                                fontApplied = true
                            }
                            opened[NativeBackupFormat.LEGACY_EXTRA_SETTINGS_ENTRY]
                                ?.decodeToString()
                                ?.let { Json.parseToJsonElement(it).jsonObject }
                                ?.let { legacy ->
                                    ExportExtraSettings.restoreLegacyFromJsonObject(
                                        obj = legacy,
                                        sm = settingsManager,
                                        replace = settingsDecision == ImportStrategy.REPLACE,
                                        allowSecrets = allowLegacySecrets,
                                        allowedConversationIds =
                                            chatDao.getAllConversationIds().toSet(),
                                    )
                                }
                            if (
                                settingsManager.fontPreference.first() == "custom" &&
                                settingsManager.customFontPath.first()
                                    .takeIf(String::isNotBlank)
                                    ?.let(::File)
                                    ?.isFile != true
                            ) {
                                settingsManager.saveFontPreference("app_default")
                                settingsManager.saveCustomFontPath("")
                                settingsManager.saveCustomFontName("")
                            }
                        }
                        settingsImported = true
                    } catch (error: Exception) {
                        if (!fontApplied) restoredFont?.path?.let(::File)?.delete()
                        errors += "Settings: ${error.localizedMessage ?: "Unknown error"}"
                    }
                    step()
                }

                if (keysDecision != null && keysDecision != ImportStrategy.SKIP) {
                    try {
                        val data = opened[NativeBackupFormat.SECRETS_ENTRY]
                            ?.decodeToString()
                            ?.let { importJson.decodeFromString<NativeBackupSecrets>(it) }
                            ?: error("${NativeBackupFormat.SECRETS_ENTRY} is missing")
                        NativeBackupSecretsPolicy.restore(
                            data = data,
                            sm = settingsManager,
                            replace = keysDecision == ImportStrategy.REPLACE,
                        ).forEach { errors += "API keys: $it" }
                        apiKeysImported = true
                    } catch (error: Exception) {
                        errors += "API keys: ${error.localizedMessage ?: "Unknown error"}"
                    }
                    step()
                }

                onProgress(1f)
                ImportResult(
                    conversationsImported = conversationsImported,
                    tasksImported = tasksImported,
                    loopsImported = loopsImported,
                    memoriesImported = memoriesImported,
                    systemPromptsImported = systemPromptsImported,
                    settingsImported = settingsImported,
                    apiKeysImported = apiKeysImported,
                    errors = errors,
                )
            }
        }
    }

    // Internal data classes for parsing export files
    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val taskId: String? = null,
        val origin: String = "user",
        val graduated: Boolean = false,
        val selectedRunBranchesJson: String? = null,
        val draftText: String = "",
        val draftAttachments: String? = null,
        val conversationSettings: ConversationSettings? = null,
        /** v1-v3 compatibility only; never restored across devices. */
        val hasUnreadGeneration: Boolean = false,
    )

    @Serializable
    private data class ExportRunEntity(
        val id: String,
        val conversationId: String,
        val parentRunId: String? = null,
        val status: String = "COMPLETED",
        val startedAt: Long,
        val lastCheckpointAt: Long,
        val stopRequestedAt: Long? = null,
        val endedAt: Long? = null,
        val endReason: String? = null,
        val currentPass: Int = 0,
        val legacyAmbiguous: Boolean = false,
    )

    @Serializable
    private data class ExportTaskEntity(
        val id: String,
        val name: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val modelId: String? = null,
        val cronExpr: String,
        /** One-shot fire instant; null for a recurring (cron) task. */
        val runAt: Long? = null,
        /** Informational only; import always clears this device-local schedule epoch. */
        val nextRunAt: Long = 0L,
        val enabled: Boolean = true,
        val createdAt: Long,
        val lastRunAt: Long? = null
    )

    @Serializable
    private data class ExportLoopEntity(
        val conversationId: String,
        val intervalMs: Long,
        val prompt: String? = null,
        val nextFireAt: Long = 0L,
        val cycleCount: Int = 0,
        /** Nullable so an explicit null from an early v2 backup can be decoded and normalized. */
        val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
        val active: Boolean = true,
        val revision: Long = 0L
    )

    @Serializable
    private data class ExportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val inputTokenCount: Int? = null,
        val cachedInputTokenCount: Int? = null,
        val uncachedInputTokenCount: Int? = null,
        val outputTokenCount: Int? = null,
        val reasoningTokenCount: Int? = null,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null,
        val runId: String? = null,
        val runSequence: Long? = null,
        val consumedAtPass: Int? = null,
    )

    private fun ExportRunEntity.toArchivedSnapshot() = ArchivedRunSnapshot(
        id = id,
        conversationId = conversationId,
        parentRunId = parentRunId,
        status = status,
        startedAt = startedAt,
        lastCheckpointAt = lastCheckpointAt,
        stopRequestedAt = stopRequestedAt,
        endedAt = endedAt,
        endReason = endReason,
        currentPass = currentPass,
        legacyAmbiguous = legacyAmbiguous,
    )

}
