package com.newoether.agora.viewmodel.delegate

import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.model.ModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delegate for all settings write operations and provider management.
 *
 * Extracted from ChatViewModel to reduce its line count (~700 lines moved).
 * StateFlows stay in ChatViewModel for zero UI breakage.
 * Each method mirrors the original ChatViewModel setter exactly.
 */
class SettingsDelegate(
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao,
    private val scope: CoroutineScope
) {
    // ── Provider helpers ──────────────────────────────────────

    fun getProviderForModel(
        modelId: String,
        availableModels: Map<String, List<String>>
    ): String {
        if (modelId.contains(":")) {
            return ModelId.parse(modelId).providerName
        }
        availableModels.forEach { (providerName, models) ->
            if (models.contains(modelId)) return providerName
        }
        return ModelId.parse(modelId).providerName
    }

    fun isProviderConfigured(
        providerName: String,
        activeKey: String,
        builtInProviders: Set<String>,
        providerBaseUrls: Map<String, String>
    ): Boolean {
        val isCustom = providerName !in builtInProviders
        return when {
            providerName == "Unknown" -> false
            providerName == "Local" -> true
            isCustom || providerName == "Ollama" -> !providerBaseUrls[providerName].isNullOrBlank()
            else -> activeKey.isNotBlank()
        }
    }

    fun getEffectiveBaseUrl(
        providerName: String,
        providerBaseUrls: Map<String, String>,
        builtInProviders: Set<String>,
        providerInstance: LlmProvider?
    ): String? {
        return providerBaseUrls[providerName]
            ?: if (providerName !in builtInProviders) providerInstance?.defaultBaseUrl
            else null
    }

    // ── Model Selection ───────────────────────────────────────

    fun setSelectedModel(model: String) {
        scope.launch { settingsManager.saveSelectedModel(model) }
    }

    fun setEnabledModels(models: Set<String>, currentSelected: String) {
        scope.launch {
            settingsManager.saveEnabledModels(models)
            if (!models.contains(currentSelected)) {
                settingsManager.saveSelectedModel(models.firstOrNull() ?: "")
            }
        }
    }

    fun updateModelAlias(model: String, alias: String, currentAliases: Map<String, String>) {
        scope.launch {
            val updated = currentAliases.toMutableMap()
            if (alias.isBlank()) updated.remove(model) else updated[model] = alias
            settingsManager.saveModelAliases(updated)
        }
    }

    // ── API Keys ──────────────────────────────────────────────

    fun addApiKey(name: String, key: String, provider: String, currentKeys: List<ApiKeyEntry>) {
        scope.launch {
            val entry = ApiKeyEntry(name = name, key = key, provider = provider)
            settingsManager.saveApiKeys(currentKeys + entry)
            settingsManager.setActiveApiKeyId(provider, entry.id)
        }
    }

    /**
     * Store exactly one key for [provider]: update the existing entry in place if
     * there is one, otherwise add it — and drop any extra entries for the same
     * provider. Idempotent under repeated/concurrent calls, so onboarding (which
     * may fire the save more than once) never accumulates duplicates.
     */
    fun upsertApiKey(name: String, key: String, provider: String, currentKeys: List<ApiKeyEntry>) {
        scope.launch {
            val existing = currentKeys.firstOrNull { it.provider == provider }
            val entry = existing?.copy(name = name, key = key) ?: ApiKeyEntry(name = name, key = key, provider = provider)
            settingsManager.saveApiKeys(currentKeys.filter { it.provider != provider } + entry)
            settingsManager.setActiveApiKeyId(provider, entry.id)
        }
    }

    fun deleteApiKey(id: String, currentKeys: List<ApiKeyEntry>, activeApiKeyIds: Map<String, String>) {
        scope.launch {
            val entry = currentKeys.find { it.id == id } ?: return@launch
            val newList = currentKeys.filter { it.id != id }
            if (activeApiKeyIds[entry.provider] == id) {
                val other = newList.firstOrNull { it.provider == entry.provider }
                settingsManager.setActiveApiKeyId(entry.provider, other?.id)
            }
            settingsManager.saveApiKeys(newList)
        }
    }

    fun updateApiKey(id: String, name: String, key: String, currentKeys: List<ApiKeyEntry>) {
        scope.launch {
            settingsManager.saveApiKeys(currentKeys.map { if (it.id == id) it.copy(name = name, key = key) else it })
        }
    }

    fun setActiveApiKey(provider: String, id: String) {
        scope.launch { settingsManager.setActiveApiKeyId(provider, id) }
    }

    // ── System Prompts ────────────────────────────────────────

    fun addSystemPrompt(
        title: String, systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>, userPostpendItems: List<PromptTemplateItem>,
        currentPrompts: List<SystemPromptEntry>, currentActiveId: String?
    ) {
        scope.launch {
            val newList = currentPrompts + SystemPromptEntry(title = title, systemItems = systemItems, userPrependItems = userPrependItems, userPostpendItems = userPostpendItems)
            settingsManager.saveSystemPrompts(newList)
            if (currentActiveId == null) settingsManager.setActiveSystemPromptId(newList.last().id)
        }
    }

    fun deleteSystemPrompt(id: String, currentPrompts: List<SystemPromptEntry>, currentActiveId: String?) {
        scope.launch {
            val newList = currentPrompts.filter { it.id != id }
            settingsManager.saveSystemPrompts(newList)
            if (currentActiveId == id) settingsManager.setActiveSystemPromptId(newList.firstOrNull()?.id)
        }
    }

    fun updateSystemPrompt(
        id: String, title: String, systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>, userPostpendItems: List<PromptTemplateItem>,
        currentPrompts: List<SystemPromptEntry>
    ) {
        scope.launch {
            settingsManager.saveSystemPrompts(currentPrompts.map { if (it.id == id) it.copy(title = title, content = "", systemItems = systemItems, userPrependItems = userPrependItems, userPostpendItems = userPostpendItems) else it })
        }
    }

    fun setActiveSystemPrompt(id: String) {
        scope.launch { settingsManager.setActiveSystemPromptId(id) }
    }

    // ── Simple setting toggles ────────────────────────────────

    fun setMaxContextWindow(window: Int) = scope.launch { settingsManager.saveMaxContextWindow(window) }
    fun setVisualizeContextRollout(enabled: Boolean) = scope.launch { settingsManager.saveVisualizeContextRollout(enabled) }
    fun setProviderBaseUrl(provider: String, url: String) = scope.launch { settingsManager.saveProviderBaseUrl(provider, url) }
    fun setTitleGenerationEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTitleGenerationEnabled(enabled) }
    fun setTitleGenerationModel(model: String?) = scope.launch { settingsManager.saveTitleGenerationModel(model) }
    fun setTitleGenerationPrompt(prompt: String) = scope.launch { settingsManager.saveTitleGenerationPrompt(prompt) }
    fun setImageTranscriptionModel(model: String?) = scope.launch { settingsManager.saveImageTranscriptionModel(model) }
    fun setImageTranscriptionBatchSize(size: Int) = scope.launch { settingsManager.saveImageTranscriptionBatchSize(size) }
    fun setImageTranscriptionPrompt(prompt: String) = scope.launch { settingsManager.saveImageTranscriptionPrompt(prompt) }
    fun addImageTranscriptionModels(models: Set<String>, current: Set<String>) = scope.launch { settingsManager.saveImageTranscriptionEnabledModels(current + models) }
    fun removeImageTranscriptionModel(model: String, current: Set<String>) = scope.launch { settingsManager.saveImageTranscriptionEnabledModels(current - model) }
    fun setAccessPastConversations(enabled: Boolean) = scope.launch { settingsManager.saveAccessPastConversations(enabled) }
    fun setAccessSavedMemories(enabled: Boolean) = scope.launch { settingsManager.saveAccessSavedMemories(enabled) }
    fun setAccessActiveMemory(enabled: Boolean) = scope.launch { settingsManager.saveAccessActiveMemory(enabled) }
    fun setRagSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveRagSearchEnabled(enabled) }
    fun setAutoCacheEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAutoCacheEnabled(enabled) }
    fun setAutoUpdateCheck(enabled: Boolean) = scope.launch { settingsManager.saveAutoUpdateCheck(enabled) }
    fun setLastUpdateCheckTime(time: Long) = scope.launch { settingsManager.saveLastUpdateCheckTime(time) }
    fun setModelSearchMethod(method: String) = scope.launch { settingsManager.saveModelSearchMethod(method) }
    fun setManualSearchMethod(method: String) = scope.launch { settingsManager.saveManualSearchMethod(method) }
    fun setAppLanguage(language: String) = scope.launch { settingsManager.saveAppLanguage(language) }
    fun setWebSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveWebSearchEnabled(enabled) }
    fun setWebSearchProvider(provider: String) = scope.launch { settingsManager.saveWebSearchProvider(provider) }
    fun setWebSearchApiKey(provider: String, apiKey: String) = scope.launch { settingsManager.saveWebSearchApiKey(provider, apiKey) }
    fun setWebSearchNumResults(n: Int) = scope.launch { settingsManager.saveWebSearchNumResults(n) }
    fun setWebSearchBaseUrl(url: String) = scope.launch { settingsManager.saveWebSearchBaseUrl(url) }
    fun setImageGenEnabled(enabled: Boolean) = scope.launch { settingsManager.saveImageGenEnabled(enabled) }
    fun setImageGenModel(model: String?) = scope.launch { settingsManager.saveImageGenModel(model) }
    fun setImageGenSize(size: String) = scope.launch { settingsManager.saveImageGenSize(size) }
    fun setShowDocumentationFab(enabled: Boolean) = scope.launch { settingsManager.saveShowDocumentationFab(enabled) }
    fun setShellEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellEnabled(enabled) }
    fun setSandboxEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSandboxEnabled(enabled) }
    fun setThinkingEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingEnabled(enabled) }
    fun setThinkingLevel(level: String) = scope.launch { settingsManager.saveThinkingLevel(level) }
    fun setThinkingBudgetEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingBudgetEnabled(enabled) }
    fun setThinkingBudgetTokens(tokens: Int) = scope.launch { settingsManager.saveThinkingBudgetTokens(tokens) }
    fun setDefaultTemperature(v: Float?) = scope.launch { settingsManager.saveDefaultTemperature(v) }
    fun setDefaultMaxTokens(v: Int?) = scope.launch { settingsManager.saveDefaultMaxTokens(v) }
    fun setDefaultTopP(v: Float?) = scope.launch { settingsManager.saveDefaultTopP(v) }
    fun setDefaultFrequencyPenalty(v: Float?) = scope.launch { settingsManager.saveDefaultFrequencyPenalty(v) }
    fun setDefaultPresencePenalty(v: Float?) = scope.launch { settingsManager.saveDefaultPresencePenalty(v) }
    fun setThemeMode(mode: String) = scope.launch { settingsManager.saveThemeMode(mode) }
    fun setColorScheme(scheme: String) = scope.launch { settingsManager.saveColorScheme(scheme) }
    fun setDynamicColor(enabled: Boolean) = scope.launch { settingsManager.saveDynamicColor(enabled) }
    fun setBlurEffectsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveBlurEffectsEnabled(enabled) }
    fun setSchemeStyle(style: String) = scope.launch { settingsManager.saveSchemeStyle(style) }
    fun setSearchMatchLimit(n: Int) = scope.launch { settingsManager.saveSearchMatchLimit(n) }
    fun setSearchContextWindow(n: Int) = scope.launch { settingsManager.saveSearchContextWindow(n) }
    fun setRagThreshold(threshold: Float) = scope.launch { settingsManager.saveRagThreshold(threshold) }

    // ── Custom provider CRUD ──────────────────────────────────

    fun addCustomProvider(
        name: String, baseUrl: String,
        currentCustomProviders: List<CustomProviderConfig>,
        onProviderAdd: (String, CustomOpenAiProvider) -> Unit
    ) {
        scope.launch {
            settingsManager.saveProviderBaseUrl(name, baseUrl)
            settingsManager.saveCustomProviders(currentCustomProviders + CustomProviderConfig(name))
            onProviderAdd(name, CustomOpenAiProvider(name, baseUrl))
        }
    }

    fun renameCustomProvider(
        oldName: String, newName: String,
        providerBaseUrls: Map<String, String>,
        currentCustomProviders: List<CustomProviderConfig>,
        availableModels: Map<String, List<String>>,
        enabledModels: Set<String>,
        modelAliases: Map<String, String>,
        apiKeys: List<ApiKeyEntry>,
        activeApiKeyIds: Map<String, String>,
        onProviderRemove: (String) -> Unit,
        onProviderAdd: (String, CustomOpenAiProvider) -> Unit
    ) {
        val url = providerBaseUrls[oldName] ?: return
        scope.launch {
            onProviderRemove(oldName)
            val updated = currentCustomProviders.toMutableList()
            val idx = updated.indexOfFirst { it.name == oldName }
            if (idx >= 0) {
                updated[idx] = CustomProviderConfig(newName)
                settingsManager.saveCustomProviders(updated)
                settingsManager.saveProviderBaseUrl(oldName, "")
                settingsManager.saveProviderBaseUrl(newName, url)
                val models = availableModels.toMutableMap()
                models[newName] = models.remove(oldName) ?: emptyList()
                settingsManager.saveAvailableModels(newName, models[newName] ?: emptyList())
                settingsManager.saveAvailableModels(oldName, emptyList())
                val newEnabled = enabledModels.map { if (it.startsWith("$oldName:")) it.replace("$oldName:", "$newName:") else it }.toSet()
                settingsManager.saveEnabledModels(newEnabled)
                val newAliases = modelAliases.mapKeys { if (it.key.startsWith("$oldName:")) it.key.replace("$oldName:", "$newName:") else it.key }
                settingsManager.saveModelAliases(newAliases)
                settingsManager.setActiveApiKeyId(oldName, null)
                val newKeys = apiKeys.map { if (it.provider == oldName) it.copy(provider = newName) else it }
                settingsManager.saveApiKeys(newKeys)
                activeApiKeyIds[oldName]?.let { settingsManager.setActiveApiKeyId(newName, it) }
            }
            onProviderAdd(newName, CustomOpenAiProvider(newName, url))
        }
    }

    fun deleteCustomProvider(
        name: String,
        currentCustomProviders: List<CustomProviderConfig>,
        enabledModels: Set<String>,
        modelAliases: Map<String, String>,
        providerBaseUrls: Map<String, String>,
        apiKeys: List<ApiKeyEntry>,
        onProviderRemove: (String) -> Unit
    ) {
        scope.launch {
            settingsManager.saveCustomProviders(currentCustomProviders.filter { it.name != name })
            onProviderRemove(name)
            settingsManager.saveAvailableModels(name, emptyList())
            settingsManager.saveEnabledModels(enabledModels.filter { !it.startsWith("$name:") }.toSet())
            settingsManager.saveModelAliases(modelAliases.filterKeys { !it.startsWith("$name:") })
            settingsManager.saveProviderBaseUrl(name, "")
            settingsManager.saveApiKeys(apiKeys.filter { it.provider != name })
            settingsManager.setActiveApiKeyId(name, null)
        }
    }

    // ── Shell devices ─────────────────────────────────────────

    fun addShellDevice(device: com.newoether.agora.data.ShellDeviceConfig, current: List<com.newoether.agora.data.ShellDeviceConfig>) {
        scope.launch { settingsManager.saveShellDevices(current + device) }
    }

    fun updateShellDevice(device: com.newoether.agora.data.ShellDeviceConfig, current: List<com.newoether.agora.data.ShellDeviceConfig>) {
        scope.launch {
            val updated = current.toMutableList()
            val idx = updated.indexOfFirst { it.id == device.id }
            if (idx >= 0) { updated[idx] = device; settingsManager.saveShellDevices(updated) }
        }
    }

    fun removeShellDevice(deviceId: String, current: List<com.newoether.agora.data.ShellDeviceConfig>) {
        scope.launch { settingsManager.saveShellDevices(current.filter { it.id != deviceId }) }
    }

    fun setConversationSettings(convId: String, settings: ConversationSettings?) {
        scope.launch { settingsManager.saveConversationSettings(convId, settings) }
    }

    // ── Local chat model CRUD ─────────────────────────────────

    fun isLocalModelIdTaken(modelId: String, localChatModels: List<LocalChatModelConfig>, excludeId: String? = null): Boolean {
        return localChatModels.any { it.modelId == modelId && it.id != excludeId }
    }

    fun addLocalChatModel(
        config: LocalChatModelConfig,
        localChatModels: List<LocalChatModelConfig>,
        enabledModels: Set<String>,
        modelAliases: Map<String, String>
    ) {
        scope.launch {
            if (isLocalModelIdTaken(config.modelId, localChatModels)) return@launch
            settingsManager.saveLocalChatModels(localChatModels + config)
            val prefixed = "Local:${config.modelId}"
            settingsManager.saveEnabledModels(enabledModels + prefixed)
            settingsManager.saveModelAliases(modelAliases + (prefixed to config.alias))
        }
    }

    fun deleteLocalChatModel(uuid: String, localChatModels: List<LocalChatModelConfig>, enabledModels: Set<String>, modelAliases: Map<String, String>) {
        scope.launch(Dispatchers.IO) {
            val model = localChatModels.find { it.id == uuid }
            if (model != null) {
                if (model.localFilePath.isNotBlank()) java.io.File(model.localFilePath).delete()
                if (model.mmprojPath.isNotBlank()) java.io.File(model.mmprojPath).delete()
            }
            val updated = localChatModels.filter { it.id != uuid }
            settingsManager.saveLocalChatModels(updated)
            val prefixed = "Local:${model?.modelId ?: uuid}"
            settingsManager.saveEnabledModels(enabledModels - prefixed)
            settingsManager.saveAvailableModels("Local", updated.map { "Local:${it.modelId}" })
            settingsManager.saveModelAliases(modelAliases - prefixed)
        }
    }

    fun updateLocalChatModel(
        uuid: String, newModelId: String, newAlias: String,
        nCtx: Int, temperature: Float, topP: Float, maxTokens: Int,
        mmprojPath: String,
        localChatModels: List<LocalChatModelConfig>,
        enabledModels: Set<String>,
        modelAliases: Map<String, String>
    ) {
        scope.launch {
            if (isLocalModelIdTaken(newModelId, localChatModels, excludeId = uuid)) return@launch
            val oldModel = localChatModels.find { it.id == uuid } ?: return@launch
            if (oldModel.mmprojPath.isNotBlank() && oldModel.mmprojPath != mmprojPath) {
                java.io.File(oldModel.mmprojPath).delete()
            }
            val updated = localChatModels.map { if (it.id == uuid) it.copy(modelId = newModelId, alias = newAlias, nCtx = nCtx, temperature = temperature, topP = topP, maxTokens = maxTokens, mmprojPath = mmprojPath) else it }
            settingsManager.saveLocalChatModels(updated)
            if (oldModel.modelId != newModelId) {
                val oldPrefixed = "Local:${oldModel.modelId}"
                val newPrefixed = "Local:$newModelId"
                settingsManager.saveEnabledModels(enabledModels - oldPrefixed + newPrefixed)
                settingsManager.saveAvailableModels("Local", updated.map { "Local:${it.modelId}" })
                settingsManager.saveModelAliases(modelAliases - oldPrefixed + (newPrefixed to newAlias))
            } else {
                settingsManager.saveModelAliases(modelAliases + ("Local:$newModelId" to newAlias))
            }
        }
    }

    // ── Embedding model CRUD ──────────────────────────────────

    fun addEmbeddingModel(config: EmbeddingModelConfig, current: List<EmbeddingModelConfig>) {
        scope.launch {
            val wasEmpty = current.isEmpty()
            settingsManager.saveEmbeddingModels(current + config)
            if (wasEmpty) settingsManager.setActiveEmbeddingModelId(config.id)
        }
    }

    fun deleteEmbeddingModel(id: String, current: List<EmbeddingModelConfig>, activeEmbeddingModelId: String) {
        scope.launch(Dispatchers.IO) {
            val model = current.find { it.id == id }
            if (model?.type == EmbeddingModelType.LOCAL && model.localFilePath.isNotBlank()) {
                java.io.File(model.localFilePath).delete()
            }
            chatDao.deleteEmbeddingsByModel(id)
            settingsManager.saveEmbeddingModels(current.filter { it.id != id })
            if (activeEmbeddingModelId == id && current.size > 1) {
                settingsManager.setActiveEmbeddingModelId(current.first { it.id != id }.id)
            }
        }
    }

    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int?, current: List<EmbeddingModelConfig>) {
        scope.launch {
            settingsManager.saveEmbeddingModels(current.map { if (it.id == id) it.copy(name = newName, batchSize = batchSize ?: it.batchSize) else it })
        }
    }

    fun setActiveEmbeddingModel(id: String, currentId: String, current: List<EmbeddingModelConfig>) {
        if (id == currentId) return
        scope.launch(Dispatchers.IO) {
            settingsManager.setActiveEmbeddingModelId(id)
        }
    }
}
