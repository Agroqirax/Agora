package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataExporter(
    private val context: Context,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    enum class ExportCategory(val manifestKey: String) {
        CONVERSATIONS("conversations"),
        MEMORIES("memories"),
        SYSTEM_PROMPTS("system_prompts"),
        SETTINGS("settings"),
        API_KEYS("api_keys")
    }

    @Serializable
    private data class ExportManifest(
        @SerialName("agora_export_version") val version: Int = 1,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val categories: List<String>,
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

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
        val toolCallJson: String? = null
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
        val providerBaseUrls: Map<String, String>,
        val titleGenerationEnabled: Boolean,
        val titleGenerationModel: String?,
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
        val localChatModels: List<LocalChatModelConfig>,
        val activeLocalChatModelId: String,
        @SerialName("active_system_prompt_id") val activeSystemPromptId: String?
    )

    @Serializable
    private data class ExportApiKeys(
        val apiKeys: List<ApiKeyEntry>,
        val activeApiKeyIds: Map<String, String>,
        val webSearchApiKeys: Map<String, String>
    )

    suspend fun export(
        uri: Uri,
        categories: Set<ExportCategory>,
        includeApiKeys: Boolean,
        onProgress: (Float) -> Unit = {}
    ) {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val exportedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .format(java.util.Date())

        val manifest = ExportManifest(
            appVersion = appVersion,
            exportedAt = exportedAt,
            categories = categories.map { it.manifestKey },
            hasApiKeys = includeApiKeys && categories.contains(ExportCategory.API_KEYS)
        )

        val totalSteps = categories.size + 1 // +1 for manifest
        var completed = 0
        fun step() { completed++; onProgress(completed.toFloat() / totalSteps) }

        context.contentResolver.openOutputStream(uri)?.use { raw ->
            val zip = ZipOutputStream(BufferedOutputStream(raw))

            // Manifest
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            step()

            // Conversations
            if (ExportCategory.CONVERSATIONS in categories) {
                val conversations = chatDao.getAllConversationsList().map { c ->
                    ExportChatEntity(c.id, c.title, c.lastUpdated, c.selectedBranchesJson, c.systemPromptId, c.modelId)
                }
                val messages = chatDao.getAllMessagesList().map { m ->
                    ExportMessageEntity(m.id, m.conversationId, m.parentId, m.text, m.images,
                        m.thoughts, m.thoughtTitle, m.tokenCount, m.status.name, m.participant.name,
                        m.timestamp, m.thoughtTimeMs, m.modelName, m.toolCallJson)
                }
                zip.putNextEntry(ZipEntry("conversations.json"))
                zip.write(Json.encodeToString(ExportConversations(conversations, messages)).toByteArray())
                zip.closeEntry()
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
                for (name in memoryManager.listFiles()) {
                    val content = memoryManager.readFile(name)
                    zip.putNextEntry(ZipEntry("memories/memory_db/$name"))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                step()
            }

            // System Prompts
            if (ExportCategory.SYSTEM_PROMPTS in categories) {
                val prompts = settingsManager.systemPrompts.first()
                zip.putNextEntry(ZipEntry("system_prompts.json"))
                zip.write(Json.encodeToString(prompts).toByteArray())
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
                    providerBaseUrls = settingsManager.providerBaseUrls.first(),
                    titleGenerationEnabled = settingsManager.titleGenerationEnabled.first(),
                    titleGenerationModel = settingsManager.titleGenerationModel.first(),
                    accessPastConversations = settingsManager.accessPastConversations.first(),
                    accessSavedMemories = settingsManager.accessSavedMemories.first(),
                    accessActiveMemory = settingsManager.accessActiveMemory.first(),
                    ragSearchEnabled = settingsManager.ragSearchEnabled.first(),
                    modelSearchMethod = settingsManager.modelSearchMethod.first(),
                    manualSearchMethod = settingsManager.manualSearchMethod.first(),
                    embeddingModels = settingsManager.embeddingModels.first(),
                    activeEmbeddingModelId = settingsManager.activeEmbeddingModelId.first(),
                    appLanguage = settingsManager.appLanguage.first(),
                    webSearchEnabled = settingsManager.webSearchEnabled.first(),
                    webSearchProvider = settingsManager.webSearchProvider.first(),
                    webSearchBaseUrl = settingsManager.webSearchBaseUrl.first(),
                    ragThreshold = settingsManager.ragThreshold.first(),
                    localChatModels = settingsManager.localChatModels.first(),
                    activeLocalChatModelId = settingsManager.activeLocalChatModelId.first(),
                    activeSystemPromptId = settingsManager.activeSystemPromptId.first()
                )
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(Json.encodeToString(settings).toByteArray())
                zip.closeEntry()
                step()
            }

            // API Keys (opt-in)
            if (includeApiKeys && ExportCategory.API_KEYS in categories) {
                val keys = ExportApiKeys(
                    apiKeys = settingsManager.apiKeys.first(),
                    activeApiKeyIds = settingsManager.activeApiKeyIds.first(),
                    webSearchApiKeys = settingsManager.webSearchApiKeys.first()
                )
                zip.putNextEntry(ZipEntry("api_keys.json"))
                zip.write(Json.encodeToString(keys).toByteArray())
                zip.closeEntry()
                step()
            }

            zip.finish()
            zip.flush()
        }

        onProgress(1f)
    }
}
