package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.util.UUID
import java.io.File
import java.util.zip.ZipFile

class DataImporter(
    private val context: Context,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    enum class ImportStrategy { MERGE, REPLACE, SKIP }

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
        val memoryCount: Int = 0,
        val systemPromptCount: Int = 0,
        val settingsPresent: Boolean = false,
        val apiKeysPresent: Boolean = false
    )

    data class ImportResult(
        val conversationsImported: Int = 0,
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

    private fun readAllEntries(uri: Uri): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        // Copy SAF content to temp file so we can use ZipFile (more reliable than ZipInputStream)
        val tmpFile = File(context.cacheDir, "agora_import_tmp.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { out -> input.copyTo(out) }
            }
            ZipFile(tmpFile).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                if (manifestEntry != null) {
                    entries["manifest.json"] = zip.getInputStream(manifestEntry).readBytes()
                }
                // Read other entries lazily - just get names for now
                val enum = zip.entries()
                while (enum.hasMoreElements()) {
                    val e = enum.nextElement()
                    if (!e.isDirectory && e.name != "manifest.json") {
                        entries[e.name] = zip.getInputStream(e).readBytes()
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through: entries will be empty
        } finally {
            tmpFile.delete()
        }
        return entries
    }

    suspend fun readManifest(uri: Uri): ImportManifest? {
        return withContext(Dispatchers.IO) {
            val entries = readAllEntries(uri)
            if (entries.isEmpty()) return@withContext null
            val manifestJson = entries["manifest.json"]?.decodeToString() ?: return@withContext null
            try {
                Json.decodeFromString<ImportManifest>(manifestJson)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun preview(uri: Uri): ImportPreview {
        return withContext(Dispatchers.IO) {
            val entries = readAllEntries(uri)
            val manifestJson = entries["manifest.json"]?.decodeToString() ?: return@withContext ImportPreview(
                ImportManifest(version = 0)
            )
            val manifest = try {
                Json.decodeFromString<ImportManifest>(manifestJson)
            } catch (_: Exception) {
                return@withContext ImportPreview(ImportManifest(version = 0))
            }

            var conversationCount = 0
            var systemPromptCount = 0
            val memoryCount = entries.keys.count { it.startsWith("memories/") }
            val settingsPresent = entries.containsKey("settings.json")
            val apiKeysPresent = entries.containsKey("api_keys.json")

            entries["conversations.json"]?.let { json ->
                try {
                    val data = Json.decodeFromString<ExportConversations>(json.decodeToString())
                    conversationCount = data.conversations.size
                } catch (_: Exception) {}
            }

            entries["system_prompts.json"]?.let { json ->
                try {
                    val data = Json.decodeFromString<List<SystemPromptEntry>>(json.decodeToString())
                    systemPromptCount = data.size
                } catch (_: Exception) {}
            }

            ImportPreview(
                manifest = manifest,
                conversationCount = conversationCount,
                memoryCount = memoryCount,
                systemPromptCount = systemPromptCount,
                settingsPresent = settingsPresent,
                apiKeysPresent = apiKeysPresent
            )
        }
    }

    suspend fun import(
        uri: Uri,
        decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>,
        onProgress: (Float) -> Unit = {}
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            val entries = readAllEntries(uri)
            val errors = mutableListOf<String>()
            var conversationsImported = 0
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
                try {
                    entries["conversations.json"]?.decodeToString()?.let { json ->
                        val data = Json.decodeFromString<ExportConversations>(json)
                        val convEntities = data.conversations.map { c ->
                            ChatEntity(c.id, c.title, c.lastUpdated, c.selectedBranchesJson, c.systemPromptId, c.modelId)
                        }
                        val msgEntities = data.messages.map { m ->
                            MessageEntity(m.id, m.conversationId, m.parentId, m.text, m.images,
                                m.thoughts, m.thoughtTitle, m.tokenCount,
                                try { MessageStatus.valueOf(m.status) } catch (_: Exception) { MessageStatus.SUCCESS },
                                try { Participant.valueOf(m.participant) } catch (_: Exception) { Participant.MODEL },
                                m.timestamp, m.thoughtTimeMs, m.modelName, m.toolCallJson, m.attachmentMeta)
                        }
                        // Restore image files from ZIP to app storage
                        val imagesDir = java.io.File(context.filesDir, "images")
                        imagesDir.mkdirs()
                        val imageEntries = entries.filter { it.key.startsWith("images/") }
                        val restoredImages = mutableMapOf<String, MutableList<String>>() // messageId -> file paths
                        for ((path, bytes) in imageEntries) {
                            // path format: images/<messageId>/<index>
                            val parts = path.removePrefix("images/").split("/")
                            if (parts.size == 2) {
                                val msgId = parts[0]
                                val ext = detectImageExtension(bytes)
                                val imgFile = java.io.File(imagesDir, "${msgId}_${parts[1]}.$ext")
                                imgFile.writeBytes(bytes)
                                val contentUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    imgFile
                                )
                                restoredImages.getOrPut(msgId) { mutableListOf() }.add(contentUri.toString())
                            }
                        }

                        // Update message entities with restored image paths
                        val finalMsgEntities = msgEntities.map { msg ->
                            val imgs = restoredImages[msg.id]
                            if (imgs != null) msg.copy(images = imgs) else msg
                        }

                        if (convDecision == ImportStrategy.REPLACE) {
                            chatDao.deleteAllConversations()
                            convEntities.forEach { chatDao.upsertConversation(it) }
                            finalMsgEntities.forEach { chatDao.upsertMessage(it) }
                            conversationsImported = data.conversations.size
                        } else {
                            // MERGE: upsert conversations, insert new messages,
                            // and update existing messages that have restored images
                            convEntities.forEach { chatDao.upsertConversation(it) }
                            val existingIds = chatDao.findExistingMessageIds(finalMsgEntities.map { it.id })
                            val existingSet = existingIds.toSet()
                            finalMsgEntities.filter { it.id !in existingSet }.forEach { chatDao.upsertMessage(it) }
                            finalMsgEntities.filter { it.id in existingSet && it.images.isNotEmpty() }.forEach { chatDao.upsertMessage(it) }
                            conversationsImported = data.conversations.size
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Conversations: ${e.localizedMessage ?: "Unknown error"}")
                }
                step()
            }

            // Memories
            val memDecision = decisions[DataExporter.ExportCategory.MEMORIES]
            if (memDecision != null && memDecision != ImportStrategy.SKIP) {
                try {
                    val memEntries = entries.filter { it.key.startsWith("memories/") }
                    if (memDecision == ImportStrategy.REPLACE) {
                        for (name in memoryManager.listFiles()) {
                            memoryManager.deleteFile(name)
                        }
                        val activeMem = memoryManager.getActiveMemory()
                        if (activeMem.isNotEmpty()) {
                            memoryManager.updateActiveMemory("", "replace")
                        }
                    }
                    for ((path, content) in memEntries) {
                        val text = content.decodeToString()
                        if (path == "memories/active_memory.md" && text.isNotBlank()) {
                            if (memDecision == ImportStrategy.REPLACE || memoryManager.getActiveMemory().isEmpty()) {
                                memoryManager.updateActiveMemory(text, "replace")
                            }
                            memoriesImported++
                        } else if (path.startsWith("memories/memory_db/")) {
                            val name = path.removePrefix("memories/memory_db/")
                            if (memDecision == ImportStrategy.REPLACE || !memoryManager.listFiles().contains(name)) {
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
                    entries["system_prompts.json"]?.decodeToString()?.let { json ->
                        val prompts = Json.decodeFromString<List<SystemPromptEntry>>(json)
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
                    entries["settings.json"]?.decodeToString()?.let { json ->
                        val s = Json.decodeFromString<ExportSettings>(json)
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
                        for ((provider, url) in s.providerBaseUrls) {
                            settingsManager.saveProviderBaseUrl(provider, url)
                        }
                        settingsManager.saveTitleGenerationEnabled(s.titleGenerationEnabled)
                        s.titleGenerationModel?.let { settingsManager.saveTitleGenerationModel(it) }
                        settingsManager.saveAccessPastConversations(s.accessPastConversations)
                        settingsManager.saveAccessSavedMemories(s.accessSavedMemories)
                        settingsManager.saveAccessActiveMemory(s.accessActiveMemory)
                        settingsManager.saveRagSearchEnabled(s.ragSearchEnabled)
                        settingsManager.saveModelSearchMethod(s.modelSearchMethod)
                        settingsManager.saveManualSearchMethod(s.manualSearchMethod)
                        // Skip embedding models — GGUF files don't exist on this device
                        settingsManager.saveAppLanguage(s.appLanguage)
                        settingsManager.saveWebSearchEnabled(s.webSearchEnabled)
                        settingsManager.saveWebSearchProvider(s.webSearchProvider)
                        settingsManager.saveWebSearchBaseUrl(s.webSearchBaseUrl)
                        settingsManager.saveRagThreshold(s.ragThreshold)
                        // Skip local chat models — GGUF files don't exist on this device
                        s.activeSystemPromptId?.let { settingsManager.setActiveSystemPromptId(it) }
                        settingsImported = true
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
                    entries["api_keys.json"]?.decodeToString()?.let { json ->
                        val data = Json.decodeFromString<ExportApiKeys>(json)
                        if (keysDecision == ImportStrategy.REPLACE) {
                            settingsManager.saveApiKeys(data.apiKeys)
                            data.webSearchApiKeys.forEach { (provider, key) ->
                                settingsManager.saveWebSearchApiKey(provider, key)
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

            onProgress(1f)
            ImportResult(
                conversationsImported = conversationsImported,
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
    private data class ExportConversations(
        val conversations: List<ExportChatEntity>,
        val messages: List<ExportMessageEntity>
    )

    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null
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
        val providerBaseUrls: Map<String, String> = emptyMap(),
        val titleGenerationEnabled: Boolean = true,
        val titleGenerationModel: String? = null,
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
        val localChatModels: List<LocalChatModelConfig> = emptyList(),
        val activeLocalChatModelId: String = "",
        @SerialName("active_system_prompt_id") val activeSystemPromptId: String? = null
    )

    @Serializable
    private data class ExportApiKeys(
        val apiKeys: List<ApiKeyEntry> = emptyList(),
        val activeApiKeyIds: Map<String, String> = emptyMap(),
        val webSearchApiKeys: Map<String, String> = emptyMap()
    )
}
