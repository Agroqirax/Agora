package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.openai.DeepSeekProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.openai.OpenRouterProvider
import com.newoether.agora.api.openai.QwenProvider
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the set of LLM providers — built-in plus user-defined custom OpenAI-compatible
 * ones — and all logic for resolving a model/provider to a concrete [LlmProvider]
 * instance, its effective base URL, and its configured/credentialed status.
 *
 * Extracted from [ChatViewModel] so provider lifecycle (registration, rename, delete,
 * credential reconciliation) and model discovery live in one cohesive place. The live
 * [all] map is shared by reference with the generation pipeline, so it is a
 * [ConcurrentHashMap]: mutated by the sync collectors while read on `Dispatchers.IO`
 * during generation.
 */
class ProviderRegistry(
    private val settings: SettingsRepository,
    localProvider: LocalProvider,
    private val scope: CoroutineScope,
) {
    private val builtInProviders: Map<String, LlmProvider> = mapOf(
        "Google" to GeminiProvider(),
        "OpenAI" to OpenAiProvider(),
        "Anthropic" to AnthropicProvider(),
        "DeepSeek" to DeepSeekProvider(),
        "Qwen" to QwenProvider(),
        "Ollama" to OllamaProvider(),
        "Open Router" to OpenRouterProvider(),
        "Local" to localProvider
    )

    // Declared as MutableMap so `in`/`contains` keep Map (containsKey) semantics (KT-18053).
    private val providers: MutableMap<String, LlmProvider> = ConcurrentHashMap(builtInProviders)

    /** Live, thread-safe read view shared with the generation pipeline. */
    val all: Map<String, LlmProvider> get() = providers

    fun isBuiltIn(name: String): Boolean = name in builtInProviders

    fun getInstance(name: String): LlmProvider = providers[name] ?: GeminiProvider()

    fun getEffectiveBaseUrl(providerName: String): String? =
        settings.providerBaseUrls.value[providerName]
            ?: if (!isBuiltIn(providerName)) getInstance(providerName).defaultBaseUrl else null

    fun isConfigured(providerName: String, activeKey: String): Boolean = when {
        providerName == "Unknown" -> false
        providerName == "Local" -> true
        !isBuiltIn(providerName) || providerName == "Ollama" -> !getEffectiveBaseUrl(providerName).isNullOrBlank()
        else -> activeKey.isNotBlank()
    }

    fun providerForModel(modelId: String): String {
        // Prefixed IDs (e.g. "OpenAI:gpt-4"): extract provider directly
        if (modelId.contains(":")) return ModelId.parse(modelId).providerName
        // Unprefixed IDs: user-registered providers take priority over heuristics
        settings.availableModels.value.forEach { (providerName, models) ->
            if (models.contains(modelId)) return providerName
        }
        // Heuristic fallback for legacy unprefixed IDs
        return ModelId.parse(modelId).providerName
    }

    // ── Custom provider CRUD ──────────────────────────────────
    // Settings persists the config; the callbacks keep the live `providers` map in sync.

    fun addCustom(name: String, baseUrl: String) {
        providers[name] = CustomOpenAiProvider(name, baseUrl)
        settings.addCustomProvider(name, baseUrl) { n, p -> providers[n] = p }
    }

    fun renameCustom(oldName: String, newName: String) {
        val url = settings.providerBaseUrls.value[oldName] ?: return
        providers.remove(oldName)
        providers[newName] = CustomOpenAiProvider(newName, url)
        settings.renameCustomProvider(oldName, newName, { providers.remove(it) }, { n, p -> providers[n] = p })
    }

    fun deleteCustom(name: String) {
        settings.deleteCustomProvider(name) { providers.remove(it) }
    }

    /** Registers any persisted custom provider not yet present in the live map. */
    fun ensureCustomProvidersRegistered() {
        settings.customProviders.value.forEach { config ->
            if (config.name !in providers) {
                providers[config.name] = CustomOpenAiProvider(config.name, settings.providerBaseUrls.value[config.name] ?: "")
            }
        }
    }

    /**
     * Fetches the live model list for a single provider and caches it. Unlike a full
     * sync this carries no global side effects (no snackbar, no syncing flag).
     */
    suspend fun fetchModelsForProvider(name: String): List<String> {
        if (name == "Local") return emptyList()
        ensureCustomProvidersRegistered()
        val provider = providers[name] ?: return emptyList()
        val activeKey = settings.apiKeys.value.find { it.id == settings.activeApiKeyIds.value[name] }?.key ?: ""
        if (!isConfigured(name, activeKey)) return emptyList()
        val baseUrl = if (!isBuiltIn(name)) {
            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        } else {
            settings.providerBaseUrls.value[name]
        }
        val raw = withTimeout(10_000L) { provider.fetchModels(activeKey, baseUrl) }
        val prefixed = raw.map { "$name:${it.removePrefix("models/")}" }
        if (prefixed.isNotEmpty()) settings.saveAvailableModels(name, prefixed)
        return prefixed
    }

    /** Identity fingerprint of all providers' credentials/URLs — used to skip redundant syncs. */
    fun computeFingerprint(): String = providers.map { (name, _) ->
        val keyId = settings.activeApiKeyIds.value[name] ?: ""
        val url = settings.providerBaseUrls.value[name] ?: ""
        "$name|$keyId|$url"
    }.sorted().joinToString(",").hashCode().toString()

    /** Starts the long-lived collectors that keep the provider map and caches consistent. */
    fun launchSyncJobs() {
        // Sync custom providers into the live map whenever the persisted set changes.
        scope.launch {
            settings.customProviders.collect { custom ->
                providers.keys.filter { !isBuiltIn(it) }.forEach { providers.remove(it) }
                val baseUrls = settings.getProviderBaseUrls()
                custom.forEach { config ->
                    providers[config.name] = CustomOpenAiProvider(config.name, baseUrls[config.name] ?: "")
                }
            }
        }
        // Auto-clear cached available models when a provider loses its credentials.
        scope.launch {
            var prevConfigured = emptyMap<String, Boolean>()
            combine(
                settings.apiKeys,
                settings.activeApiKeyIds,
                settings.providerBaseUrls
            ) { keys, activeIds, baseUrls -> Triple(keys, activeIds, baseUrls) }
                .collect { (keys, activeIds, _) ->
                    if (keys.isEmpty() && activeIds.isEmpty()) return@collect

                    val current = mutableMapOf<String, Boolean>()
                    providers.toMap().forEach { (name, _) ->
                        val activeKey = keys.find { it.id == activeIds[name] }?.key ?: ""
                        current[name] = isConfigured(name, activeKey)
                    }

                    var changed = false
                    current.forEach { (name, configured) ->
                        if (prevConfigured[name] == true && !configured) {
                            val existing = settings.getAvailableModels()[name]
                            if (!existing.isNullOrEmpty()) {
                                settings.saveAvailableModels(name, emptyList())
                                changed = true
                            }
                        }
                    }
                    prevConfigured = current

                    if (changed) {
                        val allAvailable = settings.getAvailableModels().values.flatten().toSet()
                        val newEnabled = settings.enabledModels.value.intersect(allAvailable)
                        if (newEnabled != settings.enabledModels.value) {
                            settings.setEnabledModels(newEnabled)
                        }
                    }
                }
        }
    }
}
