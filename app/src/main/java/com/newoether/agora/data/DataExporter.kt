package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataExporter(
    private val context: Context,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    companion object {
        /** Bounds entity/string expansion while exporting databases with large chat histories. */
        private const val MESSAGE_PAGE_SIZE = 64
    }

    enum class ExportCategory(val manifestKey: String) {
        CONVERSATIONS("conversations"),
        MEMORIES("memories"),
        SYSTEM_PROMPTS("system_prompts"),
        SETTINGS("settings"),
        API_KEYS("api_keys");

        companion object {
            fun fromManifestKey(key: String): ExportCategory? =
                entries.find { it.manifestKey == key }
        }
    }

    @Serializable
    private data class ExportManifest(
        @SerialName("agora_export_version") val version: Int,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val categories: List<String>,
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

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
    )

    @Serializable
    private data class ExportRunEntity(
        val id: String,
        val conversationId: String,
        val parentRunId: String? = null,
        val status: String,
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
        /** Informational snapshot; importers recompute this device-local derived value. */
        val nextRunAt: Long,
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
        /** New v2 archives always emit the bounded default for legacy null values. */
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
        val attachmentMeta: String? = null,
        val runId: String,
        val runSequence: Long,
        val consumedAtPass: Int? = null,
    )

    @Serializable
    private data class ExportSettings(
        val selectedModel: String,
        val availableModels: Map<String, List<String>>,
        val enabledModels: Set<String>,
        val modelAliases: Map<String, String>,
        val maxContextWindow: Int,
        val visualizeContextRollout: Boolean,
        val codeExecutionEnabled: Boolean,
        val googleSearchEnabled: Boolean,
        val thinkingEnabled: Boolean,
        val thinkingLevel: String,
        val thinkingBudgetEnabled: Boolean,
        val thinkingBudgetTokens: Int,
        val autoCacheEnabled: Boolean,
        val providerBaseUrls: Map<String, String>,
        val titleGenerationEnabled: Boolean,
        val titleGenerationModel: String?,
        val titleGenerationPrompt: String? = null,
        val titleGenerationNotificationsEnabled: Boolean = true,
        val accessPastConversations: Boolean,
        val accessSavedMemories: Boolean,
        val accessActiveMemory: Boolean,
        val ragSearchEnabled: Boolean,
        val modelSearchMethod: String,
        val manualSearchMethod: String,
        val embeddingModels: List<EmbeddingModelConfig>,
        val activeEmbeddingModelId: String,
        val appLanguage: String,
        val webSearchEnabled: Boolean,
        val webSearchProvider: String,
        val webSearchBaseUrl: String,
        val ragThreshold: Float,
        val shellEnabled: Boolean = false,
        val shellDevices: List<ShellDeviceConfig> = emptyList(),
        val customProviders: List<CustomProviderConfig> = emptyList(),
        val localChatModels: List<LocalChatModelConfig>,
        @SerialName("active_system_prompt_id") val activeSystemPromptId: String?
    )

    @Serializable
    private data class ExportApiKeys(
        val apiKeys: List<ApiKeyEntry>,
        val activeApiKeyIds: Map<String, String>,
        val webSearchApiKeys: Map<String, String>,
        val shellApiKeys: Map<String, String> = emptyMap()
    )

    data class ExportResult(
        val imagesExported: Int = 0
    )

    private fun openImageStream(imgUri: String): java.io.InputStream? {
        val uri = Uri.parse(imgUri)
        // Handle content:// and file:// URIs
        if (uri.scheme == "content" || uri.scheme == "file") {
            return try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        }
        // Handle bare file paths (from processImages)
        val file = java.io.File(imgUri)
        if (file.exists()) return try { file.inputStream() } catch (_: Exception) { null }
        return null
    }

    private suspend fun forEachMessagePage(
        block: suspend (List<MessageEntity>) -> Unit,
    ) {
        var afterId: String? = null
        while (true) {
            val page = chatDao.getMessagesPage(afterId, MESSAGE_PAGE_SIZE)
            if (page.isEmpty()) break
            block(page)
            afterId = page.last().id
            if (page.size < MESSAGE_PAGE_SIZE) break
        }
    }

    private suspend fun forEachAttachmentReferencePage(
        block: suspend (List<MessageAttachmentReference>) -> Unit,
    ) {
        var afterId: String? = null
        while (true) {
            val page = chatDao.getMessageAttachmentReferencesPage(afterId, MESSAGE_PAGE_SIZE)
            if (page.isEmpty()) break
            block(page)
            afterId = page.last().id
            if (page.size < MESSAGE_PAGE_SIZE) break
        }
    }

    /** Copies one media stream directly into the archive without a heap-sized byte array. */
    private fun copyStreamToZipEntry(
        zip: ZipOutputStream,
        entryName: String,
        input: InputStream?,
    ): Boolean {
        if (input == null) return false
        return input.use { stream ->
            zip.putNextEntry(ZipEntry(entryName))
            try {
                stream.copyTo(zip) > 0L
            } finally {
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.writeJsonToken(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    /**
     * Writes the existing conversations.json shape one entity at a time. The archive format stays
     * compatible, but message bodies are never duplicated into an all-messages DTO list.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeConversationArchive(
        zip: ZipOutputStream,
        exportedImagesByMessage: Map<String, List<String>>,
    ) {
        zip.putNextEntry(ZipEntry("conversations.json"))
        try {
            zip.writeJsonToken("{\"conversations\":[")
            var first = true
            chatDao.getAllConversationsList().forEach { conversation ->
                if (!first) zip.write(','.code)
                first = false
                Json.encodeToStream(
                    ExportChatEntity(
                        id = conversation.id,
                        title = conversation.title,
                        lastUpdated = conversation.lastUpdated,
                        selectedBranchesJson = conversation.selectedBranchesJson,
                        systemPromptId = conversation.systemPromptId,
                        modelId = conversation.modelId,
                        taskId = conversation.taskId,
                        origin = conversation.origin,
                        graduated = conversation.graduated,
                        selectedRunBranchesJson = conversation.selectedRunBranchesJson,
                    ),
                    zip,
                )
            }

            zip.writeJsonToken("],\"runs\":[")
            first = true
            for (conversation in chatDao.getAllConversationsList()) {
                for (run in chatDao.getRunsForConversation(conversation.id).first()) {
                    if (!first) zip.write(','.code)
                    first = false
                    Json.encodeToStream(
                        ExportRunEntity(
                            id = run.id,
                            conversationId = run.conversationId,
                            parentRunId = run.parentRunId,
                            status = run.status.name,
                            startedAt = run.startedAt,
                            lastCheckpointAt = run.lastCheckpointAt,
                            stopRequestedAt = run.stopRequestedAt,
                            endedAt = run.endedAt,
                            endReason = run.endReason?.name,
                            currentPass = run.currentPass,
                            legacyAmbiguous = run.legacyAmbiguous,
                        ),
                        zip,
                    )
                }
            }

            zip.writeJsonToken("],\"messages\":[")
            first = true
            forEachMessagePage { page ->
                page.forEach { message ->
                    if (!first) zip.write(','.code)
                    first = false
                    Json.encodeToStream(
                        ExportMessageEntity(
                            id = message.id,
                            conversationId = message.conversationId,
                            parentId = message.parentId,
                            text = message.text,
                            images = exportedImagesByMessage[message.id] ?: emptyList(),
                            thoughts = message.thoughts,
                            thoughtTitle = message.thoughtTitle,
                            tokenCount = message.tokenCount,
                            status = message.status.name,
                            participant = message.participant.name,
                            timestamp = message.timestamp,
                            thoughtTimeMs = message.thoughtTimeMs,
                            modelName = message.modelName,
                            toolCallJson = message.toolCallJson,
                            attachmentMeta = message.attachmentMeta,
                            runId = message.runId,
                            runSequence = message.runSequence,
                            consumedAtPass = message.consumedAtPass,
                        ),
                        zip,
                    )
                }
            }

            zip.writeJsonToken("],\"tasks\":[")
            first = true
            chatDao.getAllTasksList().forEach { task ->
                if (!first) zip.write(','.code)
                first = false
                Json.encodeToStream(
                    ExportTaskEntity(
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
                    ),
                    zip,
                )
            }

            zip.writeJsonToken("],\"loops\":[")
            first = true
            chatDao.getAllLoopsList().forEach { loop ->
                if (!first) zip.write(','.code)
                first = false
                val sanitized = sanitizeImportedLoop(loop)
                Json.encodeToStream(
                    ExportLoopEntity(
                        conversationId = sanitized.conversationId,
                        intervalMs = sanitized.intervalMs,
                        prompt = sanitized.prompt,
                        nextFireAt = sanitized.nextFireAt,
                        cycleCount = sanitized.cycleCount,
                        maxCycles = sanitized.maxCycles,
                        active = sanitized.active,
                        revision = sanitized.revision,
                    ),
                    zip,
                )
            }
            zip.writeJsonToken("]}")
        } finally {
            zip.closeEntry()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(
        uri: Uri,
        categories: Set<ExportCategory>,
        includeApiKeys: Boolean,
        onProgress: (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val exportedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date())

        val manifest = ExportManifest(
            version = 3,
            appVersion = appVersion,
            exportedAt = exportedAt,
            categories = categories.map { it.manifestKey },
            hasApiKeys = includeApiKeys && categories.contains(ExportCategory.API_KEYS)
        )

        var imagesExportedTotal = 0
        val totalSteps = categories.size + 1 // +1 for manifest
        var completed = 0
        fun step() { completed++; onProgress(completed.toFloat() / totalSteps) }

        context.contentResolver.openOutputStream(uri)?.use { raw ->
            val zip = ZipOutputStream(BufferedOutputStream(raw))

            // Manifest
            zip.putNextEntry(ZipEntry("manifest.json"))
            Json.encodeToStream(manifest, zip)
            zip.closeEntry()
            step()

            // Conversations
            if (ExportCategory.CONVERSATIONS in categories) {
                val imageMap = mutableMapOf<String, List<String>>() // messageId -> list of image URIs to keep

                // Export media from attachment-only pages. Message bodies/thoughts/tool payloads
                // are deliberately absent from this pass.
                forEachAttachmentReferencePage { page ->
                    page.forEach { message ->
                        if (message.images.isNotEmpty()) {
                            val surviving = mutableListOf<String>()
                            message.images.forEachIndexed { index, imageUri ->
                                val copied = try {
                                    copyStreamToZipEntry(
                                        zip = zip,
                                        entryName = "images/${message.id}/$index",
                                        input = openImageStream(imageUri),
                                    )
                                } catch (_: Exception) {
                                    false
                                }
                                if (copied) {
                                    surviving.add(imageUri)
                                }
                            }
                            imagesExportedTotal += surviving.size
                            if (surviving.isNotEmpty()) {
                                imageMap[message.id] = surviving
                            }
                        }

                        val meta = try {
                            message.attachmentMeta?.let { Json.decodeFromString<AttachmentMeta>(it) }
                        } catch (_: Exception) {
                            null
                        }
                        meta?.items?.forEach { item ->
                            val videoUri = item.originalUri
                            if (item.type == "video" && !videoUri.isNullOrBlank() &&
                                videoUri.startsWith("file://")
                            ) {
                                val file = File(videoUri.removePrefix("file://"))
                                if (file.isFile) {
                                    try {
                                        copyStreamToZipEntry(
                                            zip = zip,
                                            entryName = "videos/${message.id}/${item.imageIndex ?: 0}",
                                            input = file.inputStream(),
                                        )
                                    } catch (_: Exception) {
                                        // Inaccessible media is omitted; the message still exports.
                                    }
                                }
                            }
                        }
                    }
                }

                writeConversationArchive(zip, imageMap)
                step()
            }

            // Memories
            if (ExportCategory.MEMORIES in categories) {
                val activeMemory = memoryManager.getActiveMemory()
                if (activeMemory.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry("memories/active_memory.md"))
                    zip.write(activeMemory.toByteArray())
                    zip.closeEntry()
                }
                for (file in memoryManager.listFiles()) {
                    val content = memoryManager.readFile(file.name)
                    zip.putNextEntry(ZipEntry("memories/memory_db/${file.name}"))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                val metaJson = memoryManager.getMetaJson()
                if (metaJson != "{}") {
                    zip.putNextEntry(ZipEntry("memories/memory_db/memory_meta.json"))
                    zip.write(metaJson.toByteArray())
                    zip.closeEntry()
                }
                step()
            }

            // System Prompts
            if (ExportCategory.SYSTEM_PROMPTS in categories) {
                val prompts = settingsManager.systemPrompts.first()
                zip.putNextEntry(ZipEntry("system_prompts.json"))
                Json.encodeToStream(prompts, zip)
                zip.closeEntry()
                step()
            }

            // Settings
            if (ExportCategory.SETTINGS in categories) {
                val settings = ExportSettings(
                    selectedModel = settingsManager.selectedModel.first(),
                    availableModels = settingsManager.availableModels.first(),
                    enabledModels = settingsManager.enabledModels.first(),
                    modelAliases = settingsManager.modelAliases.first(),
                    maxContextWindow = settingsManager.maxContextWindow.first(),
                    visualizeContextRollout = settingsManager.visualizeContextRollout.first(),
                    codeExecutionEnabled = settingsManager.codeExecutionEnabled.first(),
                    googleSearchEnabled = settingsManager.googleSearchEnabled.first(),
                    thinkingEnabled = settingsManager.thinkingEnabled.first(),
                    thinkingLevel = settingsManager.thinkingLevel.first(),
                    thinkingBudgetEnabled = settingsManager.thinkingBudgetEnabled.first(),
                    thinkingBudgetTokens = settingsManager.thinkingBudgetTokens.first(),
                    autoCacheEnabled = settingsManager.autoCacheEnabled.first(),
                    providerBaseUrls = settingsManager.providerBaseUrls.first(),
                    titleGenerationEnabled = settingsManager.titleGenerationEnabled.first(),
                    titleGenerationModel = settingsManager.titleGenerationModel.first(),
                    titleGenerationPrompt = settingsManager.titleGenerationPrompt.first(),
                    titleGenerationNotificationsEnabled =
                        settingsManager.titleGenerationNotificationsEnabled.first(),
                    accessPastConversations = settingsManager.accessPastConversations.first(),
                    accessSavedMemories = settingsManager.accessSavedMemories.first(),
                    accessActiveMemory = settingsManager.accessActiveMemory.first(),
                    ragSearchEnabled = settingsManager.ragSearchEnabled.first(),
                    modelSearchMethod = settingsManager.modelSearchMethod.first(),
                    manualSearchMethod = settingsManager.manualSearchMethod.first(),
                    embeddingModels = settingsManager.embeddingModels.first().map { it.copy(localFilePath = "") },
                    activeEmbeddingModelId = "", // cleared — embedding models are local GGUF, don't transfer
                    appLanguage = settingsManager.appLanguage.first(),
                    webSearchEnabled = settingsManager.webSearchEnabled.first(),
                    webSearchProvider = settingsManager.webSearchProvider.first(),
                    webSearchBaseUrl = settingsManager.webSearchBaseUrl.first(),
                    ragThreshold = settingsManager.ragThreshold.first(),
                    shellEnabled = settingsManager.shellEnabled.first(),
                    shellDevices = settingsManager.shellDevices.first().map { d ->
                        if (includeApiKeys) d else d.copy(apiKey = "")
                    },
                    customProviders = settingsManager.customProviders.first(),
                    localChatModels = settingsManager.localChatModels.first().map { it.copy(localFilePath = "") },
                    activeSystemPromptId = settingsManager.activeSystemPromptId.first()
                )
                zip.putNextEntry(ZipEntry("settings.json"))
                Json.encodeToStream(settings, zip)
                zip.closeEntry()
                step()

                // Extra settings (separate file to keep data class size manageable)
                val extra = ExportExtraSettings.toJsonObject(settingsManager, includeApiKeys)
                zip.putNextEntry(ZipEntry("extra_settings.json"))
                Json.encodeToStream(extra, zip)
                zip.closeEntry()
            }

            // API Keys (opt-in)
            if (includeApiKeys && ExportCategory.API_KEYS in categories) {
                val keys = ExportApiKeys(
                    apiKeys = settingsManager.apiKeys.first(),
                    activeApiKeyIds = settingsManager.activeApiKeyIds.first(),
                    webSearchApiKeys = settingsManager.webSearchApiKeys.first(),
                    shellApiKeys = settingsManager.shellDevices.first()
                        .filter { it.apiKey.isNotBlank() }
                        .associate { it.name to it.apiKey }
                )
                zip.putNextEntry(ZipEntry("api_keys.json"))
                Json.encodeToStream(keys, zip)
                zip.closeEntry()
                step()
            }

            // ── Custom font file ──
            val fontPath = settingsManager.customFontPath.first()
            if (fontPath.isNotBlank()) {
                val fontFile = File(fontPath)
                if (fontFile.exists()) {
                    zip.putNextEntry(ZipEntry("custom_font/${fontFile.name}"))
                    fontFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            zip.finish()
            zip.flush()
        }

        onProgress(1f)
        ExportResult(imagesExported = imagesExportedTotal)
    }
}
