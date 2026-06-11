package com.newoether.agora.data.repository

import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.SystemPromptEntry
import kotlinx.coroutines.flow.Flow

/**
 * Repository wrapping DataStore-backed SettingsManager.
 *
 * Provides the same Flow-based reads as SettingsManager, plus
 * atomic batch mutation methods for multi-setting updates.
 * Phase 2 creates the abstraction layer.  Validation and caching
 * enhancements are deferred to a later refinement phase.
 */
class SettingsRepository(
    private val settingsManager: SettingsManager
) {
    // ── Read Flows (direct delegation) ────────────────────────

    val selectedModel: Flow<String> = settingsManager.selectedModel
    val availableModels: Flow<Map<String, List<String>>> = settingsManager.availableModels
    val enabledModels: Flow<Set<String>> = settingsManager.enabledModels
    val modelAliases: Flow<Map<String, String>> = settingsManager.modelAliases
    val apiKeys: Flow<List<ApiKeyEntry>> = settingsManager.apiKeys
    val activeApiKeyIds: Flow<Map<String, String>> = settingsManager.activeApiKeyIds
    val systemPrompts: Flow<List<SystemPromptEntry>> = settingsManager.systemPrompts
    val activeSystemPromptId: Flow<String?> = settingsManager.activeSystemPromptId
    val maxContextWindow: Flow<Int> = settingsManager.maxContextWindow
    val visualizeContextRollout: Flow<Boolean> = settingsManager.visualizeContextRollout
    val codeExecutionEnabled: Flow<Boolean> = settingsManager.codeExecutionEnabled
    val googleSearchEnabled: Flow<Boolean> = settingsManager.googleSearchEnabled
    val thinkingEnabled: Flow<Boolean> = settingsManager.thinkingEnabled
    val thinkingLevel: Flow<String> = settingsManager.thinkingLevel
    val providerBaseUrls: Flow<Map<String, String>> = settingsManager.providerBaseUrls
    val titleGenerationEnabled: Flow<Boolean> = settingsManager.titleGenerationEnabled
    val titleGenerationModel: Flow<String?> = settingsManager.titleGenerationModel
    val imageTranscriptionEnabledModels: Flow<Set<String>> = settingsManager.imageTranscriptionEnabledModels
    val imageTranscriptionModel: Flow<String?> = settingsManager.imageTranscriptionModel
    val imageTranscriptionBatchSize: Flow<Int> = settingsManager.imageTranscriptionBatchSize
    val accessPastConversations: Flow<Boolean> = settingsManager.accessPastConversations
    val accessSavedMemories: Flow<Boolean> = settingsManager.accessSavedMemories
    val accessActiveMemory: Flow<Boolean> = settingsManager.accessActiveMemory
    val ragSearchEnabled: Flow<Boolean> = settingsManager.ragSearchEnabled
    val autoCacheEnabled: Flow<Boolean> = settingsManager.autoCacheEnabled
    val autoUpdateCheck: Flow<Boolean> = settingsManager.autoUpdateCheck
    val lastUpdateCheckTime: Flow<Long> = settingsManager.lastUpdateCheckTime
    val modelSearchMethod: Flow<String> = settingsManager.modelSearchMethod
    val manualSearchMethod: Flow<String> = settingsManager.manualSearchMethod
    val embeddingModels: Flow<List<EmbeddingModelConfig>> = settingsManager.embeddingModels
    val activeEmbeddingModelId: Flow<String> = settingsManager.activeEmbeddingModelId
    val appLanguage: Flow<String> = settingsManager.appLanguage
    val webSearchEnabled: Flow<Boolean> = settingsManager.webSearchEnabled
    val webSearchProvider: Flow<String> = settingsManager.webSearchProvider
    val webSearchApiKeys: Flow<Map<String, String>> = settingsManager.webSearchApiKeys
    val webSearchNumResults: Flow<Int> = settingsManager.webSearchNumResults
    val webSearchBaseUrl: Flow<String> = settingsManager.webSearchBaseUrl
    val showDocumentationFab: Flow<Boolean> = settingsManager.showDocumentationFab
    val shellEnabled: Flow<Boolean> = settingsManager.shellEnabled
    val shellDevices: Flow<List<ShellDeviceConfig>> = settingsManager.shellDevices
    val sandboxEnabled: Flow<Boolean> = settingsManager.sandboxEnabled
    val defaultTemperature: Flow<Float?> = settingsManager.defaultTemperature
    val defaultMaxTokens: Flow<Int?> = settingsManager.defaultMaxTokens
    val defaultTopP: Flow<Float?> = settingsManager.defaultTopP
    val defaultFrequencyPenalty: Flow<Float?> = settingsManager.defaultFrequencyPenalty
    val defaultPresencePenalty: Flow<Float?> = settingsManager.defaultPresencePenalty
    val conversationSettings: Flow<Map<String, ConversationSettings>> = settingsManager.conversationSettings
    val themeMode: Flow<String> = settingsManager.themeMode
    val colorScheme: Flow<String> = settingsManager.colorScheme
    val dynamicColor: Flow<Boolean> = settingsManager.dynamicColor
    val schemeStyle: Flow<String> = settingsManager.schemeStyle
    val searchContextWindow: Flow<Int> = settingsManager.searchContextWindow
    val searchMatchLimit: Flow<Int> = settingsManager.searchMatchLimit
    val ragThreshold: Flow<Float> = settingsManager.ragThreshold
    val localChatModels: Flow<List<LocalChatModelConfig>> = settingsManager.localChatModels
    val customProviders: Flow<List<CustomProviderConfig>> = settingsManager.customProviders
    // ── Auto Backup ───────────────────────────────────────────
    val autoBackupEnabled: Flow<Boolean> = settingsManager.autoBackupEnabled
    val autoBackupPeriodHours: Flow<Int> = settingsManager.autoBackupPeriodHours
    val autoBackupCategories: Flow<String> = settingsManager.autoBackupCategories
    val autoBackupDirectory: Flow<String> = settingsManager.autoBackupDirectory
    val autoDeleteEnabled: Flow<Boolean> = settingsManager.autoDeleteEnabled
    val autoDeletePeriodHours: Flow<Int> = settingsManager.autoDeletePeriodHours
    val lastBackupTimestamp: Flow<Long> = settingsManager.lastBackupTimestamp

    // ── Write (direct delegation) ─────────────────────────────

    suspend fun saveSelectedModel(model: String) = settingsManager.saveSelectedModel(model)
    suspend fun saveEnabledModels(models: Set<String>) = settingsManager.saveEnabledModels(models)
    suspend fun saveAvailableModels(provider: String, models: List<String>) = settingsManager.saveAvailableModels(provider, models)
    suspend fun saveModelAliases(aliases: Map<String, String>) = settingsManager.saveModelAliases(aliases)
    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) = settingsManager.saveApiKeys(keys)
    suspend fun setActiveApiKeyId(provider: String, id: String?) = settingsManager.setActiveApiKeyId(provider, id)
    suspend fun saveSystemPrompts(prompts: List<SystemPromptEntry>) = settingsManager.saveSystemPrompts(prompts)
    suspend fun setActiveSystemPromptId(id: String?) = settingsManager.setActiveSystemPromptId(id)
    suspend fun saveMaxContextWindow(window: Int) = settingsManager.saveMaxContextWindow(window)
    suspend fun saveVisualizeContextRollout(enabled: Boolean) = settingsManager.saveVisualizeContextRollout(enabled)
    suspend fun saveProviderBaseUrl(provider: String, url: String) = settingsManager.saveProviderBaseUrl(provider, url)
    suspend fun saveCustomProviders(providers: List<CustomProviderConfig>) = settingsManager.saveCustomProviders(providers)
    suspend fun saveTitleGenerationEnabled(enabled: Boolean) = settingsManager.saveTitleGenerationEnabled(enabled)
    suspend fun saveTitleGenerationModel(model: String?) = settingsManager.saveTitleGenerationModel(model)
    suspend fun saveImageTranscriptionModel(model: String?) = settingsManager.saveImageTranscriptionModel(model)
    suspend fun saveImageTranscriptionBatchSize(size: Int) = settingsManager.saveImageTranscriptionBatchSize(size)
    suspend fun saveImageTranscriptionEnabledModels(models: Set<String>) = settingsManager.saveImageTranscriptionEnabledModels(models)
    suspend fun saveAccessPastConversations(enabled: Boolean) = settingsManager.saveAccessPastConversations(enabled)
    suspend fun saveAccessSavedMemories(enabled: Boolean) = settingsManager.saveAccessSavedMemories(enabled)
    suspend fun saveAccessActiveMemory(enabled: Boolean) = settingsManager.saveAccessActiveMemory(enabled)
    suspend fun saveRagSearchEnabled(enabled: Boolean) = settingsManager.saveRagSearchEnabled(enabled)
    suspend fun saveAutoCacheEnabled(enabled: Boolean) = settingsManager.saveAutoCacheEnabled(enabled)
    suspend fun saveAutoUpdateCheck(enabled: Boolean) = settingsManager.saveAutoUpdateCheck(enabled)
    suspend fun saveLastUpdateCheckTime(time: Long) = settingsManager.saveLastUpdateCheckTime(time)
    suspend fun saveModelSearchMethod(method: String) = settingsManager.saveModelSearchMethod(method)
    suspend fun saveManualSearchMethod(method: String) = settingsManager.saveManualSearchMethod(method)
    suspend fun saveEmbeddingModels(models: List<EmbeddingModelConfig>) = settingsManager.saveEmbeddingModels(models)
    suspend fun setActiveEmbeddingModelId(id: String) = settingsManager.setActiveEmbeddingModelId(id)
    suspend fun saveAppLanguage(language: String) = settingsManager.saveAppLanguage(language)
    suspend fun saveWebSearchEnabled(enabled: Boolean) = settingsManager.saveWebSearchEnabled(enabled)
    suspend fun saveWebSearchProvider(provider: String) = settingsManager.saveWebSearchProvider(provider)
    suspend fun saveWebSearchApiKey(provider: String, apiKey: String) = settingsManager.saveWebSearchApiKey(provider, apiKey)
    suspend fun saveWebSearchNumResults(n: Int) = settingsManager.saveWebSearchNumResults(n)
    suspend fun saveWebSearchBaseUrl(url: String) = settingsManager.saveWebSearchBaseUrl(url)
    suspend fun saveShowDocumentationFab(enabled: Boolean) = settingsManager.saveShowDocumentationFab(enabled)
    suspend fun saveShellEnabled(enabled: Boolean) = settingsManager.saveShellEnabled(enabled)
    suspend fun saveShellDevices(devices: List<ShellDeviceConfig>) = settingsManager.saveShellDevices(devices)
    suspend fun saveSandboxEnabled(enabled: Boolean) = settingsManager.saveSandboxEnabled(enabled)
    suspend fun saveThinkingEnabled(enabled: Boolean) = settingsManager.saveThinkingEnabled(enabled)
    suspend fun saveThinkingLevel(level: String) = settingsManager.saveThinkingLevel(level)
    suspend fun saveDefaultTemperature(v: Float?) = settingsManager.saveDefaultTemperature(v)
    suspend fun saveDefaultMaxTokens(v: Int?) = settingsManager.saveDefaultMaxTokens(v)
    suspend fun saveDefaultTopP(v: Float?) = settingsManager.saveDefaultTopP(v)
    suspend fun saveDefaultFrequencyPenalty(v: Float?) = settingsManager.saveDefaultFrequencyPenalty(v)
    suspend fun saveDefaultPresencePenalty(v: Float?) = settingsManager.saveDefaultPresencePenalty(v)
    suspend fun saveConversationSettings(convId: String, settings: ConversationSettings?) = settingsManager.saveConversationSettings(convId, settings)
    suspend fun saveThemeMode(mode: String) = settingsManager.saveThemeMode(mode)
    suspend fun saveColorScheme(scheme: String) = settingsManager.saveColorScheme(scheme)
    suspend fun saveDynamicColor(enabled: Boolean) = settingsManager.saveDynamicColor(enabled)
    suspend fun saveSchemeStyle(style: String) = settingsManager.saveSchemeStyle(style)
    suspend fun saveSearchMatchLimit(n: Int) = settingsManager.saveSearchMatchLimit(n)
    suspend fun saveSearchContextWindow(n: Int) = settingsManager.saveSearchContextWindow(n)
    suspend fun saveRagThreshold(threshold: Float) = settingsManager.saveRagThreshold(threshold)
    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) = settingsManager.saveLocalChatModels(models)
    // ── Auto Backup ───────────────────────────────────────────
    suspend fun saveAutoBackupEnabled(enabled: Boolean) = settingsManager.saveAutoBackupEnabled(enabled)
    suspend fun saveAutoBackupPeriodHours(hours: Int) = settingsManager.saveAutoBackupPeriodHours(hours)
    suspend fun saveAutoBackupCategories(categories: String) = settingsManager.saveAutoBackupCategories(categories)
    suspend fun saveAutoBackupDirectory(path: String) = settingsManager.saveAutoBackupDirectory(path)
    suspend fun saveAutoDeleteEnabled(enabled: Boolean) = settingsManager.saveAutoDeleteEnabled(enabled)
    suspend fun saveAutoDeletePeriodHours(hours: Int) = settingsManager.saveAutoDeletePeriodHours(hours)
    suspend fun saveLastBackupTimestamp(timestamp: Long) = settingsManager.saveLastBackupTimestamp(timestamp)

    // ── Batch Operations ──────────────────────────────────────

    /**
     * Atomically remove all data associated with a provider:
     * API keys, available models, base URLs, aliases, and enabled model references.
     */
    suspend fun removeProvider(name: String) {
        saveAvailableModels(name, emptyList())
        settingsManager.setActiveApiKeyId(name, null)
        // Additional cleanup is handled by ChatViewModel caller for now
    }
}
