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
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingLevels
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

/** Prevents a missing Task row from making an imported execution permanently unreachable. */
internal fun sanitizeImportedConversation(
    conversation: ChatEntity,
    availableTaskIds: Set<String>,
): ChatEntity = if (conversation.taskId != null && conversation.taskId !in availableTaskIds) {
    conversation.copy(taskId = null, origin = "user", graduated = true)
} else {
    conversation
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
        val loops: List<LoopEntity>,
        val availableConversationIds: Set<String>,
    )

    private data class RestoredMedia(
        val imagesByMessage: Map<String, List<String>>,
        val videosByMessage: Map<String, String>,
        val createdFiles: List<File>,
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
    ): ConversationGraphHeaders {
        var rawConversations = emptyList<ExportChatEntity>()
        var rawTasks = emptyList<ExportTaskEntity>()
        var rawLoops = emptyList<ExportLoopEntity>()
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "conversations" -> rawConversations = reader.readSerializableArray()
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
                    systemPromptId = conversation.systemPromptId,
                    modelId = conversation.modelId,
                    taskId = conversation.taskId,
                    origin = conversation.origin,
                    graduated = conversation.graduated,
                ),
                availableTaskIds,
            )
        }
        val availableConversationIds = if (strategy == ImportStrategy.MERGE) {
            chatDao.getAllConversationIds().toMutableSet()
        } else {
            mutableSetOf()
        }.apply { addAll(conversations.map { it.id }) }

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
            loops = loops,
            availableConversationIds = availableConversationIds,
        )
    }

    private fun restoreConversationMedia(archive: Archive): RestoredMedia {
        val imagesByMessage = mutableMapOf<String, MutableList<String>>()
        val videosByMessage = mutableMapOf<String, String>()
        val createdFiles = mutableListOf<File>()
        val names = archive.names()
        try {
            val imagesDir = File(context.filesDir, "images")
            imagesDir.mkdirs()
            names.filter { it.startsWith("images/") }.forEach { path ->
                val parts = path.removePrefix("images/").split("/")
                if (parts.size != 2) return@forEach
                archive.stream(path)?.buffered()?.use { input ->
                    input.mark(16)
                    val header = ByteArray(16)
                    val headerSize = input.read(header).coerceAtLeast(0)
                    input.reset()
                    val extension = detectImageExtension(header.copyOf(headerSize))
                    val imageFile = File(
                        imagesDir,
                        "img_import_${UUID.randomUUID()}.$extension",
                    )
                    val copied = imageFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                    if (copied > 0L) {
                        createdFiles.add(imageFile)
                        val contentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            imageFile,
                        )
                        imagesByMessage.getOrPut(parts[0]) { mutableListOf() }
                            .add(contentUri.toString())
                    } else {
                        imageFile.delete()
                    }
                }
            }

            names.filter { it.startsWith("videos/") }.forEach { path ->
                val parts = path.removePrefix("videos/").split("/")
                if (parts.size != 2) return@forEach
                archive.stream(path)?.buffered()?.use { input ->
                    input.mark(16)
                    val header = ByteArray(16)
                    val headerSize = input.read(header).coerceAtLeast(0)
                    input.reset()
                    val extension = detectVideoExtension(header.copyOf(headerSize))
                    val videoFile = File(
                        context.filesDir,
                        "vid_import_${UUID.randomUUID()}.$extension",
                    )
                    val copied = videoFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                    if (copied > 0L) {
                        createdFiles.add(videoFile)
                        videosByMessage[parts[0]] = "file://${videoFile.absolutePath}"
                    } else {
                        videoFile.delete()
                    }
                }
            }
        } catch (error: Exception) {
            createdFiles.forEach { runCatching { it.delete() } }
            throw error
        }

        return RestoredMedia(
            imagesByMessage = imagesByMessage,
            videosByMessage = videosByMessage,
            createdFiles = createdFiles,
        )
    }

    private fun ExportMessageEntity.toMessageEntity(restoredMedia: RestoredMedia): MessageEntity {
        var message = MessageEntity(
            id = id,
            conversationId = conversationId,
            parentId = parentId,
            text = text,
            images = restoredMedia.imagesByMessage[id] ?: images,
            thoughts = thoughts,
            thoughtTitle = thoughtTitle,
            tokenCount = tokenCount,
            status = try {
                MessageStatus.valueOf(status)
            } catch (_: Exception) {
                MessageStatus.SUCCESS
            },
            participant = try {
                Participant.valueOf(participant)
            } catch (_: Exception) {
                Participant.MODEL
            },
            timestamp = timestamp,
            thoughtTimeMs = thoughtTimeMs,
            modelName = modelName,
            toolCallJson = toolCallJson,
            attachmentMeta = attachmentMeta,
        )
        val restoredVideo = restoredMedia.videosByMessage[id]
        if (restoredVideo != null && message.attachmentMeta != null) {
            try {
                val meta = importJson.decodeFromString<AttachmentMeta>(message.attachmentMeta)
                val adjustedItems = meta.items.map { item ->
                    if (item.type == "video") item.copy(originalUri = restoredVideo) else item
                }
                message = message.copy(
                    attachmentMeta = Json.encodeToString(AttachmentMeta(items = adjustedItems))
                )
            } catch (error: Exception) {
                DebugLog.e("DataImporter", "Failed to parse attachment metadata", error)
            }
        }
        return message
    }

    private suspend fun importMessagesFromGraph(
        stream: InputStream,
        strategy: ImportStrategy,
        availableConversationIds: Set<String>,
        restoredMedia: RestoredMedia,
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
                        batch.add(exported.toMessageEntity(restoredMedia))
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

    private suspend fun importConversationGraph(
        archive: Archive,
        strategy: ImportStrategy,
        headers: ConversationGraphHeaders,
        restoredMedia: RestoredMedia,
    ) {
        database.withTransaction {
            if (strategy == ImportStrategy.REPLACE) {
                chatDao.deleteAllLoops()
                chatDao.deleteAllConversations()
                chatDao.deleteAllTasks()
                chatDao.deleteOrphanedEmbeddings()
            }
            headers.tasks.forEach { chatDao.upsertTask(it) }
            headers.conversations.forEach { chatDao.upsertConversation(it) }
            archive.stream("conversations.json")?.use { stream ->
                importMessagesFromGraph(
                    stream = stream,
                    strategy = strategy,
                    availableConversationIds = headers.availableConversationIds,
                    restoredMedia = restoredMedia,
                )
            } ?: error("conversations.json is missing")
            headers.loops.forEach { chatDao.upsertLoop(it) }
        }
    }

    suspend fun readManifest(uri: Uri): ImportManifest? {
        return withContext(Dispatchers.IO) {
            Archive.open(context, uri)?.use { archive ->
                val manifestJson = archive["manifest.json"]?.decodeToString() ?: return@use null
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
                val manifestJson = archive["manifest.json"]?.decodeToString() ?: return@use empty
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
                val settingsPresent = archive.has("settings.json")
                val apiKeysPresent = archive.has("api_keys.json")

                archive.stream("conversations.json")?.use { stream ->
                    try {
                        val counts = countConversationGraph(stream)
                        conversationCount = counts.conversations
                        taskCount = counts.tasks
                        loopCount = counts.loops
                    } catch (e: Exception) { DebugLog.e("DataImporter", "Failed to parse conversations.json", e) }
                }

                archive["system_prompts.json"]?.let { json ->
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

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun import(
        uri: Uri,
        decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>,
        onProgress: (Float) -> Unit = {}
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            val archive = Archive.open(context, uri)
                ?: return@withContext ImportResult(errors = listOf("Could not open backup archive"))
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
            fun step() { completed++; onProgress(completed.toFloat() / totalSteps.coerceAtLeast(1)) }

            // Conversations
            val convDecision = decisions[DataExporter.ExportCategory.CONVERSATIONS]
            if (convDecision != null && convDecision != ImportStrategy.SKIP) {
                var restoredMedia: RestoredMedia? = null
                try {
                    val headers = archive.stream("conversations.json")?.use { stream ->
                        readConversationGraphHeaders(stream, convDecision)
                    } ?: error("conversations.json is missing")
                    restoredMedia = restoreConversationMedia(archive)
                    importConversationGraph(
                        archive = archive,
                        strategy = convDecision,
                        headers = headers,
                        restoredMedia = restoredMedia,
                    )
                    conversationsImported = headers.conversations.size
                    tasksImported = headers.tasks.size
                    loopsImported = headers.loops.size
                } catch (e: Exception) {
                    restoredMedia?.createdFiles?.forEach { runCatching { it.delete() } }
                    errors.add("Conversations: ${e.localizedMessage ?: "Unknown error"}")
                }
                step()
            }

            // Memories
            val memDecision = decisions[DataExporter.ExportCategory.MEMORIES]
            if (memDecision != null && memDecision != ImportStrategy.SKIP) {
                try {
                    val memNames = archive.names().filter { it.startsWith("memories/") }
                    if (memDecision == ImportStrategy.REPLACE) {
                        for (file in memoryManager.listFiles()) {
                            memoryManager.deleteFile(file.name)
                        }
                        val activeMem = memoryManager.getActiveMemory()
                        if (activeMem.isNotEmpty()) {
                            memoryManager.updateActiveMemory("", "replace")
                        }
                    }
                    val existingNames = memoryManager.listFiles().map { it.name }.toSet()
                    for (path in memNames) {
                        val text = archive.bytes(path)?.decodeToString() ?: continue
                        if (path == "memories/active_memory.md" && text.isNotBlank()) {
                            if (memDecision == ImportStrategy.REPLACE || memoryManager.getActiveMemory().isEmpty()) {
                                memoryManager.updateActiveMemory(text, "replace")
                            }
                            memoriesImported++
                        } else if (path == "memories/memory_db/memory_meta.json") {
                            if (memDecision == ImportStrategy.REPLACE || memoryManager.getMetaJson() == "{}") {
                                memoryManager.saveMetaJson(text)
                            }
                        } else if (path.startsWith("memories/memory_db/")) {
                            val name = path.removePrefix("memories/memory_db/")
                            if (memDecision == ImportStrategy.REPLACE || name !in existingNames) {
                                try {
                                    memoryManager.createFile(name, text)
                                } catch (_: Exception) {
                                    memoryManager.editFile(name, text)
                                }
                            }
                            memoriesImported++
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Memories: ${e.localizedMessage ?: "Unknown error"}")
                }
                step()
            }

            // System Prompts
            val promptsDecision = decisions[DataExporter.ExportCategory.SYSTEM_PROMPTS]
            if (promptsDecision != null && promptsDecision != ImportStrategy.SKIP) {
                try {
                    archive["system_prompts.json"]?.decodeToString()?.let { json ->
                        val prompts = importJson.decodeFromString<List<SystemPromptEntry>>(json)
                        if (promptsDecision == ImportStrategy.REPLACE) {
                            settingsManager.saveSystemPrompts(prompts)
                        } else {
                            // MERGE: append with new IDs
                            val existing = settingsManager.systemPrompts.first().toMutableList()
                            val existingTitles = existing.map { it.title }.toSet()
                            for (p in prompts) {
                                val newId = UUID.randomUUID().toString()
                                val title = if (p.title in existingTitles) "${p.title} (imported)" else p.title
                                existing.add(p.copy(id = newId, title = title))
                            }
                            settingsManager.saveSystemPrompts(existing)
                        }
                        systemPromptsImported = prompts.size
                    }
                } catch (e: Exception) {
                    errors.add("System prompts: ${e.localizedMessage ?: "Unknown error"}")
                }
                step()
            }

            // Settings
            val settingsDecision = decisions[DataExporter.ExportCategory.SETTINGS]
            if (settingsDecision != null && settingsDecision != ImportStrategy.SKIP) {
                try {
                    archive["settings.json"]?.decodeToString()?.let { json ->
                        val s = importJson.decodeFromString<ExportSettings>(json)
                        settingsManager.saveSelectedModel(s.selectedModel)
                        for ((provider, models) in s.availableModels) {
                            settingsManager.saveAvailableModels(provider, models)
                        }
                        settingsManager.saveEnabledModels(s.enabledModels)
                        settingsManager.saveModelAliases(s.modelAliases)
                        settingsManager.saveMaxContextWindow(s.maxContextWindow)
                        settingsManager.saveVisualizeContextRollout(s.visualizeContextRollout)
                        settingsManager.saveCodeExecutionEnabled(s.codeExecutionEnabled)
                        settingsManager.saveGoogleSearchEnabled(s.googleSearchEnabled)
                        settingsManager.saveThinkingEnabled(s.thinkingEnabled)
                        val legacyBudgetTokens = ThinkingLevels.legacyBudgetTokens(s.thinkingLevel)
                        settingsManager.saveThinkingLevel(ThinkingLevels.normalize(s.thinkingLevel))
                        settingsManager.saveThinkingBudgetEnabled(s.thinkingBudgetEnabled || legacyBudgetTokens != null)
                        settingsManager.saveThinkingBudgetTokens(s.thinkingBudgetTokens ?: legacyBudgetTokens ?: ThinkingLevels.DefaultBudgetTokens)
                        settingsManager.saveAutoCacheEnabled(s.autoCacheEnabled)
                        for ((provider, url) in s.providerBaseUrls) {
                            settingsManager.saveProviderBaseUrl(provider, url)
                        }
                        settingsManager.saveTitleGenerationEnabled(s.titleGenerationEnabled)
                        s.titleGenerationModel?.let { settingsManager.saveTitleGenerationModel(it) }
                        s.titleGenerationPrompt?.let { settingsManager.saveTitleGenerationPrompt(it) }
                        settingsManager.saveAccessPastConversations(s.accessPastConversations)
                        settingsManager.saveAccessSavedMemories(s.accessSavedMemories)
                        settingsManager.saveAccessActiveMemory(s.accessActiveMemory)
                        settingsManager.saveRagSearchEnabled(s.ragSearchEnabled)
                        settingsManager.saveModelSearchMethod(s.modelSearchMethod)
                        settingsManager.saveManualSearchMethod(s.manualSearchMethod)
                        // Skip embedding models — local GGUF/index, don't transfer across devices
                        settingsManager.saveCustomProviders(s.customProviders)
                        settingsManager.saveAppLanguage(s.appLanguage)
                        settingsManager.saveWebSearchEnabled(s.webSearchEnabled)
                        settingsManager.saveWebSearchProvider(s.webSearchProvider)
                        settingsManager.saveWebSearchBaseUrl(s.webSearchBaseUrl)
                        settingsManager.saveRagThreshold(s.ragThreshold)
                        settingsManager.saveShellEnabled(s.shellEnabled)
                        settingsManager.saveShellDevices(s.shellDevices)
                        // Skip local chat models — GGUF files don't exist on this device
                        s.activeSystemPromptId?.let { settingsManager.setActiveSystemPromptId(it) }
                        settingsImported = true
                    }

                    // Restore extra settings if present (hoisted — independent of settings.json)
                    archive["extra_settings.json"]?.decodeToString()?.let { json ->
                        try {
                            val obj = Json.parseToJsonElement(json).jsonObject
                            ExportExtraSettings.restoreFromJsonObject(obj, settingsManager)
                        } catch (_: Exception) { /* older exports may not have extra_settings.json */ }
                    }

                    // Restore custom font file
                    for (path in archive.names()) {
                        if (!path.startsWith("custom_font/")) continue
                        val fileName = path.removePrefix("custom_font/")
                        val fontFile = java.io.File(context.filesDir, "custom_font_$fileName")
                        archive.stream(path)?.use { input ->
                            fontFile.outputStream().buffered().use { output -> input.copyTo(output) }
                        } ?: continue
                        // Update the font path to point to the restored file
                        settingsManager.saveCustomFontPath(fontFile.absolutePath)
                        // Re-read font name from the restored file
                        try {
                            val name = com.newoether.agora.util.readFontName(fontFile)
                            settingsManager.saveCustomFontName(name)
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    errors.add("Settings: ${e.localizedMessage ?: "Unknown error"}")
                }
                step()
            }

            // API Keys
            val keysDecision = decisions[DataExporter.ExportCategory.API_KEYS]
            if (keysDecision != null && keysDecision != ImportStrategy.SKIP) {
                try {
                    archive["api_keys.json"]?.decodeToString()?.let { json ->
                        val data = importJson.decodeFromString<ExportApiKeys>(json)
                        if (keysDecision == ImportStrategy.REPLACE) {
                            settingsManager.saveApiKeys(data.apiKeys)
                            data.webSearchApiKeys.forEach { (provider, key) ->
                                settingsManager.saveWebSearchApiKey(provider, key)
                            }
                            data.shellApiKeys.forEach { (name, key) ->
                                val devices = settingsManager.shellDevices.first().toMutableList()
                                val idx = devices.indexOfFirst { it.name == name }
                                if (idx >= 0) {
                                    devices[idx] = devices[idx].copy(apiKey = key)
                                } else {
                                    devices.add(ShellDeviceConfig(name = name, apiKey = key))
                                }
                                settingsManager.saveShellDevices(devices)
                            }
                        } else {
                            // MERGE: add non-duplicate keys
                            val existing = settingsManager.apiKeys.first().toMutableList()
                            val existingProviders = existing.map { it.provider to it.key }.toSet()
                            for (key in data.apiKeys) {
                                if ((key.provider to key.key) !in existingProviders) {
                                    existing.add(key)
                                }
                            }
                            settingsManager.saveApiKeys(existing)
                            data.webSearchApiKeys.forEach { (provider, key) ->
                                val current = settingsManager.webSearchApiKeys.first()
                                if (provider !in current) {
                                    settingsManager.saveWebSearchApiKey(provider, key)
                                }
                            }
                            val currentDevices = settingsManager.shellDevices.first().toMutableList()
                            var changed = false
                            data.shellApiKeys.forEach { (name, key) ->
                                val idx = currentDevices.indexOfFirst { it.name == name }
                                if (idx >= 0 && currentDevices[idx].apiKey.isBlank()) {
                                    currentDevices[idx] = currentDevices[idx].copy(apiKey = key)
                                    changed = true
                                } else if (idx < 0) {
                                    currentDevices.add(ShellDeviceConfig(name = name, apiKey = key))
                                    changed = true
                                }
                            }
                            if (changed) settingsManager.saveShellDevices(currentDevices)
                        }
                        // Apply active key IDs
                        for ((provider, id) in data.activeApiKeyIds) {
                            settingsManager.setActiveApiKeyId(provider, id)
                        }
                        apiKeysImported = true
                    }
                } catch (e: Exception) {
                    errors.add("API keys: ${e.localizedMessage ?: "Unknown error"}")
                }
                step()
            }

            archive.close()
            onProgress(1f)
            ImportResult(
                conversationsImported = conversationsImported,
                tasksImported = tasksImported,
                loopsImported = loopsImported,
                memoriesImported = memoriesImported,
                systemPromptsImported = systemPromptsImported,
                settingsImported = settingsImported,
                apiKeysImported = apiKeysImported,
                errors = errors
            )
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
        val graduated: Boolean = false
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
        val nextFireAt: Long,
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
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null
    )

    @Serializable
    private data class ExportSettings(
        val selectedModel: String = "",
        val availableModels: Map<String, List<String>> = emptyMap(),
        val enabledModels: Set<String> = emptySet(),
        val modelAliases: Map<String, String> = emptyMap(),
        val maxContextWindow: Int = 20,
        val visualizeContextRollout: Boolean = false,
        val codeExecutionEnabled: Boolean = false,
        val googleSearchEnabled: Boolean = false,
        val thinkingEnabled: Boolean = true,
        val thinkingLevel: String = "medium",
        val thinkingBudgetEnabled: Boolean = false,
        val thinkingBudgetTokens: Int? = null,
        val autoCacheEnabled: Boolean = true,
        val providerBaseUrls: Map<String, String> = emptyMap(),
        val titleGenerationEnabled: Boolean = true,
        val titleGenerationModel: String? = null,
        val titleGenerationPrompt: String? = null,
        val accessPastConversations: Boolean = true,
        val accessSavedMemories: Boolean = true,
        val accessActiveMemory: Boolean = true,
        val ragSearchEnabled: Boolean = false,
        val modelSearchMethod: String = "keyword",
        val manualSearchMethod: String = "keyword",
        val embeddingModels: List<EmbeddingModelConfig> = emptyList(),
        val activeEmbeddingModelId: String = "",
        val appLanguage: String = "system",
        val webSearchEnabled: Boolean = false,
        val webSearchProvider: String = "brave",
        val webSearchBaseUrl: String = "",
        val ragThreshold: Float = 0.5f,
        val shellEnabled: Boolean = false,
        val shellDevices: List<ShellDeviceConfig> = emptyList(),
        val customProviders: List<CustomProviderConfig> = emptyList(),
        val localChatModels: List<LocalChatModelConfig> = emptyList(),
        @SerialName("active_system_prompt_id") val activeSystemPromptId: String? = null
    )

    @Serializable
    private data class ExportApiKeys(
        val apiKeys: List<ApiKeyEntry> = emptyList(),
        val activeApiKeyIds: Map<String, String> = emptyMap(),
        val webSearchApiKeys: Map<String, String> = emptyMap(),
        val shellApiKeys: Map<String, String> = emptyMap()
    )
}
