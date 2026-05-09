package com.newoether.agora.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = "Google"
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String
)

class SettingsManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val AVAILABLE_MODELS_JSON = stringPreferencesKey("available_models_json")
        val ENABLED_MODELS = stringSetPreferencesKey("enabled_models")
        
        val API_KEYS_JSON = stringPreferencesKey("api_keys_json")
        val ACTIVE_API_KEY_IDS_JSON = stringPreferencesKey("active_api_key_ids_json")
        
        val SYSTEM_PROMPTS_JSON = stringPreferencesKey("system_prompts_json")
        val ACTIVE_SYSTEM_PROMPT_ID = stringPreferencesKey("active_system_prompt_id")
        val MODEL_ALIASES_JSON = stringPreferencesKey("model_aliases_json")
        val MAX_CONTEXT_WINDOW = stringPreferencesKey("max_context_window")
        val VISUALIZE_CONTEXT_ROLLOUT = booleanPreferencesKey("visualize_context_rollout")
        val CODE_EXECUTION_ENABLED = booleanPreferencesKey("code_execution_enabled")
        val GOOGLE_SEARCH_ENABLED = booleanPreferencesKey("google_search_enabled")
        val THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
        val PROVIDER_BASE_URLS = stringPreferencesKey("provider_base_urls")
        val TITLE_GENERATION_ENABLED = booleanPreferencesKey("title_generation_enabled")
        val TITLE_GENERATION_MODEL = stringPreferencesKey("title_generation_model")
        val ACCESS_PAST_CONVERSATIONS = booleanPreferencesKey("access_past_conversations")
        val ACCESS_SAVED_MEMORIES = booleanPreferencesKey("access_saved_memories")
        val ACCESS_ACTIVE_MEMORY = booleanPreferencesKey("access_active_memory")
        val RAG_SEARCH_ENABLED = booleanPreferencesKey("rag_search_enabled")
        val MODEL_SEARCH_METHOD = stringPreferencesKey("model_search_method")
        val MANUAL_SEARCH_METHOD = stringPreferencesKey("manual_search_method")
        val EMBEDDING_MODELS_JSON = stringPreferencesKey("embedding_models_json")
        val ACTIVE_EMBEDDING_MODEL_ID = stringPreferencesKey("active_embedding_model_id")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val WEB_SEARCH_API_KEY = stringPreferencesKey("web_search_api_key")
        val WEB_SEARCH_BASE_URL = stringPreferencesKey("web_search_base_url")
        val RAG_THRESHOLD = stringPreferencesKey("rag_threshold")
        val LOCAL_CHAT_MODELS_JSON = stringPreferencesKey("local_chat_models_json")
        val ACTIVE_LOCAL_CHAT_MODEL_ID = stringPreferencesKey("active_local_chat_model_id")
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { it[SELECTED_MODEL] ?: "gemini-1.5-flash" }
    
    val providerBaseUrls: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[PROVIDER_BASE_URLS] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { Log.e("SettingsManager", "Failed to decode providerBaseUrls", e); emptyMap() }
    }

    val availableModels: Flow<Map<String, List<String>>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[AVAILABLE_MODELS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, List<String>>>(jsonStr) } catch (e: Exception) { Log.e("SettingsManager", "Failed to decode availableModels", e); emptyMap() }
    }

    val enabledModels: Flow<Set<String>> = context.dataStore.data.map { it[ENABLED_MODELS] ?: emptySet() }

    val modelAliases: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[MODEL_ALIASES_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val apiKeys: Flow<List<ApiKeyEntry>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[API_KEYS_JSON] ?: "[]"
        try { json.decodeFromString<List<ApiKeyEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    
    val activeApiKeyIds: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val systemPrompts: Flow<List<SystemPromptEntry>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[SYSTEM_PROMPTS_JSON] ?: "[]"
        try { json.decodeFromString<List<SystemPromptEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    
    val activeSystemPromptId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_SYSTEM_PROMPT_ID] }

    val maxContextWindow: Flow<Int> = context.dataStore.data.map { it[MAX_CONTEXT_WINDOW]?.toIntOrNull() ?: 20 }
    val visualizeContextRollout: Flow<Boolean> = context.dataStore.data.map { it[VISUALIZE_CONTEXT_ROLLOUT] ?: false }
    val codeExecutionEnabled: Flow<Boolean> = context.dataStore.data.map { it[CODE_EXECUTION_ENABLED] ?: false }
    val googleSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[GOOGLE_SEARCH_ENABLED] ?: false }
    val thinkingEnabled: Flow<Boolean> = context.dataStore.data.map { it[THINKING_ENABLED] ?: true }

    val titleGenerationEnabled: Flow<Boolean> = context.dataStore.data.map { it[TITLE_GENERATION_ENABLED] ?: true }
    val titleGenerationModel: Flow<String?> = context.dataStore.data.map { it[TITLE_GENERATION_MODEL] }

    val accessPastConversations: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_PAST_CONVERSATIONS] ?: true }
    val accessSavedMemories: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_SAVED_MEMORIES] ?: true }
    val accessActiveMemory: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_ACTIVE_MEMORY] ?: true }
    val ragSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[RAG_SEARCH_ENABLED] ?: false }
    val modelSearchMethod: Flow<String> = context.dataStore.data.map { it[MODEL_SEARCH_METHOD] ?: "keyword" }
    val manualSearchMethod: Flow<String> = context.dataStore.data.map { it[MANUAL_SEARCH_METHOD] ?: "keyword" }
    val embeddingModels: Flow<List<EmbeddingModelConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[EMBEDDING_MODELS_JSON] ?: "[]"
        try { json.decodeFromString<List<EmbeddingModelConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val activeEmbeddingModelId: Flow<String> = context.dataStore.data.map { it[ACTIVE_EMBEDDING_MODEL_ID] ?: "" }

    val appLanguage: Flow<String> = context.dataStore.data.map { it[APP_LANGUAGE] ?: "system" }
    val webSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[WEB_SEARCH_ENABLED] ?: false }
    val webSearchProvider: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_PROVIDER] ?: "brave" }
    val webSearchApiKey: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_API_KEY] ?: "" }
    val webSearchBaseUrl: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_BASE_URL] ?: "" }
    val ragThreshold: Flow<Float> = context.dataStore.data.map { it[RAG_THRESHOLD]?.toFloatOrNull() ?: 0.5f }
    val localChatModels: Flow<List<LocalChatModelConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[LOCAL_CHAT_MODELS_JSON] ?: "[]"
        try { json.decodeFromString<List<LocalChatModelConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val activeLocalChatModelId: Flow<String> = context.dataStore.data.map { it[ACTIVE_LOCAL_CHAT_MODEL_ID] ?: "" }

    suspend fun saveProviderBaseUrl(provider: String, url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PROVIDER_BASE_URLS] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = url
            prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
        }
    }

    suspend fun saveSelectedModel(model: String) {
        context.dataStore.edit { it[SELECTED_MODEL] = model }
    }

    suspend fun saveAvailableModels(provider: String, models: List<String>) {
        context.dataStore.edit { prefs ->
            val current = prefs[AVAILABLE_MODELS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, List<String>>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = models
            prefs[AVAILABLE_MODELS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveEnabledModels(models: Set<String>) {
        context.dataStore.edit { it[ENABLED_MODELS] = models }
    }

    suspend fun saveModelAliases(aliases: Map<String, String>) {
        context.dataStore.edit { it[MODEL_ALIASES_JSON] = json.encodeToString(aliases) }
    }

    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) {
        context.dataStore.edit { it[API_KEYS_JSON] = json.encodeToString(keys) }
    }

    suspend fun setActiveApiKeyId(provider: String, id: String?) {
        context.dataStore.edit { prefs ->
            val current = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            if (id == null) map.remove(provider) else map[provider] = id
            prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveSystemPrompts(prompts: List<SystemPromptEntry>) {
        context.dataStore.edit { it[SYSTEM_PROMPTS_JSON] = json.encodeToString(prompts) }
    }

    suspend fun setActiveSystemPromptId(id: String?) {
        context.dataStore.edit { 
            if (id == null) it.remove(ACTIVE_SYSTEM_PROMPT_ID) else it[ACTIVE_SYSTEM_PROMPT_ID] = id 
        }
    }

    suspend fun saveMaxContextWindow(window: Int) {
        context.dataStore.edit { it[MAX_CONTEXT_WINDOW] = window.toString() }
    }

    suspend fun saveVisualizeContextRollout(enabled: Boolean) {
        context.dataStore.edit { it[VISUALIZE_CONTEXT_ROLLOUT] = enabled }
    }

    suspend fun saveCodeExecutionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CODE_EXECUTION_ENABLED] = enabled }
    }

    suspend fun saveGoogleSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[GOOGLE_SEARCH_ENABLED] = enabled }
    }

    suspend fun saveThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[THINKING_ENABLED] = enabled }
    }

    suspend fun saveTitleGenerationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TITLE_GENERATION_ENABLED] = enabled }
    }

    suspend fun saveAccessPastConversations(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_PAST_CONVERSATIONS] = enabled }
    }

    suspend fun saveAccessSavedMemories(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_SAVED_MEMORIES] = enabled }
    }
    suspend fun saveAccessActiveMemory(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_ACTIVE_MEMORY] = enabled }
    }
    suspend fun saveRagSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[RAG_SEARCH_ENABLED] = enabled }
    }
    suspend fun saveModelSearchMethod(method: String) {
        context.dataStore.edit { it[MODEL_SEARCH_METHOD] = method }
    }
    suspend fun saveManualSearchMethod(method: String) {
        context.dataStore.edit { it[MANUAL_SEARCH_METHOD] = method }
    }
    suspend fun saveEmbeddingModels(models: List<EmbeddingModelConfig>) {
        context.dataStore.edit { it[EMBEDDING_MODELS_JSON] = json.encodeToString(models) }
    }
    suspend fun setActiveEmbeddingModelId(id: String) {
        context.dataStore.edit { it[ACTIVE_EMBEDDING_MODEL_ID] = id }
    }
    suspend fun saveAppLanguage(language: String) {
        context.dataStore.edit { it[APP_LANGUAGE] = language }
    }

    suspend fun saveWebSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WEB_SEARCH_ENABLED] = enabled }
    }

    suspend fun saveWebSearchProvider(provider: String) {
        context.dataStore.edit { it[WEB_SEARCH_PROVIDER] = provider }
    }

    suspend fun saveWebSearchApiKey(apiKey: String) {
        context.dataStore.edit { it[WEB_SEARCH_API_KEY] = apiKey }
    }

    suspend fun saveWebSearchBaseUrl(url: String) {
        context.dataStore.edit { it[WEB_SEARCH_BASE_URL] = url }
    }
    suspend fun saveRagThreshold(threshold: Float) {
        context.dataStore.edit { it[RAG_THRESHOLD] = threshold.toString() }
    }

    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) {
        context.dataStore.edit { it[LOCAL_CHAT_MODELS_JSON] = json.encodeToString(models) }
    }
    suspend fun setActiveLocalChatModelId(id: String) {
        context.dataStore.edit { it[ACTIVE_LOCAL_CHAT_MODEL_ID] = id }
    }

    suspend fun saveTitleGenerationModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(TITLE_GENERATION_MODEL)
            else it[TITLE_GENERATION_MODEL] = model
        }
    }
}
