package com.newoether.agora.viewmodel

import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns local (on-device GGUF) chat-model configuration: add / delete / update and
 * the surrounding bookkeeping (enabled-model list, model aliases, available-model
 * registry, and deletion of the backing model/mmproj files).
 *
 * Extracted out of [ChatViewModel] (Phase E5). Depends only on the settings layer;
 * ChatViewModel keeps thin delegating wrappers for the UI-facing API. Remote/provider
 * model fetching stays in ChatViewModel because it is bound to the live provider-instance
 * map.
 */
class ModelManager(
    private val settings: SettingsRepository,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
) {
    fun isLocalModelIdTaken(modelId: String, excludeId: String? = null): Boolean {
        return settings.localChatModels.value.any { it.modelId == modelId && it.id != excludeId }
    }

    fun addLocalChatModel(config: LocalChatModelConfig) {
        scope.launch {
            if (isLocalModelIdTaken(config.modelId)) return@launch
            val models = settings.localChatModels.value.toMutableList()
            models.add(config)
            settingsManager.saveLocalChatModels(models)
            val modelPrefixedId = "Local:${config.modelId}"
            settings.setEnabledModels(settings.enabledModels.value + modelPrefixedId)
            settingsManager.saveModelAliases(settings.modelAliases.value + (modelPrefixedId to config.alias))
        }
    }

    fun deleteLocalChatModel(uuid: String) {
        scope.launch(Dispatchers.IO) {
            val model = settings.localChatModels.value.find { it.id == uuid }
            if (model != null) {
                if (model.localFilePath.isNotBlank()) java.io.File(model.localFilePath).delete()
                if (model.mmprojPath.isNotBlank()) java.io.File(model.mmprojPath).delete()
            }
            val models = settings.localChatModels.value.filter { it.id != uuid }
            settingsManager.saveLocalChatModels(models)
            val modelPrefixedId = "Local:${model?.modelId ?: uuid}"
            settings.setEnabledModels(settings.enabledModels.value - modelPrefixedId)
            val updatedAvailable = settingsManager.availableModels.first().toMutableMap()
            updatedAvailable["Local"] = models.map { "Local:${it.modelId}" }
            settingsManager.saveAvailableModels("Local", updatedAvailable["Local"] ?: emptyList())
            settingsManager.saveModelAliases(settings.modelAliases.value - modelPrefixedId)
        }
    }

    fun updateLocalChatModel(
        uuid: String, newModelId: String, newAlias: String, nCtx: Int, temperature: Float, topP: Float, maxTokens: Int,
        mmprojPath: String = ""
    ) {
        scope.launch {
            if (isLocalModelIdTaken(newModelId, excludeId = uuid)) return@launch
            val oldModel = settings.localChatModels.value.find { it.id == uuid } ?: return@launch
            if (oldModel.mmprojPath.isNotBlank() && oldModel.mmprojPath != mmprojPath) {
                java.io.File(oldModel.mmprojPath).delete()
            }
            val models = settings.localChatModels.value.map {
                if (it.id == uuid) it.copy(modelId = newModelId, alias = newAlias, nCtx = nCtx, temperature = temperature, topP = topP, maxTokens = maxTokens, mmprojPath = mmprojPath)
                else it
            }
            settingsManager.saveLocalChatModels(models)
            // Update model references if modelId changed
            if (oldModel.modelId != newModelId) {
                val oldPrefixed = "Local:${oldModel.modelId}"
                val newPrefixed = "Local:$newModelId"
                settings.setEnabledModels(settings.enabledModels.value - oldPrefixed + newPrefixed)
                val avail = settingsManager.availableModels.first().toMutableMap()
                avail["Local"] = models.map { "Local:${it.modelId}" }
                settingsManager.saveAvailableModels("Local", avail["Local"] ?: emptyList())
                settingsManager.saveModelAliases(settings.modelAliases.value - oldPrefixed + (newPrefixed to newAlias))
            } else {
                settingsManager.saveModelAliases(settings.modelAliases.value + ("Local:$newModelId" to newAlias))
            }
        }
    }
}
