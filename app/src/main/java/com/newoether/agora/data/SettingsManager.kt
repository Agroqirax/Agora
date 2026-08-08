package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = Constants.PROVIDER_GOOGLE
)

@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)

@Serializable
enum class McpTransportType {
    @SerialName("streamable_http")
    STREAMABLE_HTTP,

    @SerialName("sse")
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    /** Raw MCP tool names disabled for this server. New tools stay enabled by default. */
    val disabledTools: Set<String> = emptySet(),
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList()
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()
}

@Serializable
data class ConversationSettings(
    /** Provider-visible conversation token budget. Values <=100 are legacy message windows. */
    val contextWindow: Int? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val codeExecutionEnabled: Boolean? = null,
    val googleSearchEnabled: Boolean? = null,
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val thinkingBudgetEnabled: Boolean? = null,
    val thinkingBudgetTokens: Int? = null,
    val openAiServiceTierEnabled: Boolean? = null,
    val openAiServiceTier: String? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null
) {
    fun isAllNull() = contextWindow == null && temperature == null && maxTokens == null && topP == null
        && frequencyPenalty == null && presencePenalty == null
        && codeExecutionEnabled == null && googleSearchEnabled == null && thinkingEnabled == null
        && thinkingLevel == null && thinkingBudgetEnabled == null && thinkingBudgetTokens == null
        && openAiServiceTierEnabled == null && openAiServiceTier == null
        && webSearchEnabled == null && shellEnabled == null
}

class SettingsManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val AVAILABLE_MODELS_JSON = stringPreferencesKey("available_models_json")
        val CUSTOM_MODELS = stringSetPreferencesKey("custom_models")
        val ENABLED_MODELS = stringSetPreferencesKey("enabled_models")
        
        val API_KEYS_JSON = stringPreferencesKey("api_keys_json")
        val ACTIVE_API_KEY_IDS_JSON = stringPreferencesKey("active_api_key_ids_json")
        
        val SYSTEM_PROMPTS_JSON = stringPreferencesKey("system_prompts_json")
        val ACTIVE_SYSTEM_PROMPT_ID = stringPreferencesKey("active_system_prompt_id")
        val MODEL_ALIASES_JSON = stringPreferencesKey("model_aliases_json")
        val CONTEXT_TOKEN_BUDGET = stringPreferencesKey("context_token_budget")
        /** Legacy logical-message window, retained only as a migration source. */
        val MAX_CONTEXT_WINDOW = stringPreferencesKey("max_context_window")
        val VISUALIZE_CONTEXT_ROLLOUT = booleanPreferencesKey("visualize_context_rollout")
        val CONTEXT_COMPACT_ENABLED = booleanPreferencesKey("context_compact_enabled")
        val CONTEXT_COMPACT_MODEL = stringPreferencesKey("context_compact_model")
        val CONTEXT_COMPACT_PROMPT = stringPreferencesKey("context_compact_prompt")
        val CONTEXT_COMPACT_RETAIN_COUNT = intPreferencesKey("context_compact_retain_count")
        val CODE_EXECUTION_ENABLED = booleanPreferencesKey("code_execution_enabled")
        val GOOGLE_SEARCH_ENABLED = booleanPreferencesKey("google_search_enabled")
        val THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val THINKING_BUDGET_ENABLED = booleanPreferencesKey("thinking_budget_enabled")
        val THINKING_BUDGET_TOKENS = intPreferencesKey("thinking_budget_tokens")
        val OPENAI_SERVICE_TIER_ENABLED = booleanPreferencesKey("openai_service_tier_enabled")
        val OPENAI_SERVICE_TIER = stringPreferencesKey("openai_service_tier")
        val PROVIDER_BASE_URLS = stringPreferencesKey("provider_base_urls")
        val CUSTOM_ENDPOINT_RESOLUTIONS_JSON = stringPreferencesKey("custom_endpoint_resolutions_json")
        val TITLE_GENERATION_ENABLED = booleanPreferencesKey("title_generation_enabled")
        val TITLE_GENERATION_MODEL = stringPreferencesKey("title_generation_model")
        val TITLE_GENERATION_PROMPT = stringPreferencesKey("title_generation_prompt")
        val TITLE_GENERATION_NOTIFICATIONS_ENABLED =
            booleanPreferencesKey("title_generation_notifications_enabled")
        val IMAGE_TRANSCRIPTION_ENABLED =
            booleanPreferencesKey("image_transcription_enabled")
        val IMAGE_TRANSCRIPTION_ENABLED_MODELS = stringSetPreferencesKey("image_transcription_enabled_models")
        val IMAGE_TRANSCRIPTION_MODEL = stringPreferencesKey("image_transcription_model")
        val IMAGE_TRANSCRIPTION_BATCH_SIZE = intPreferencesKey("image_transcription_batch_size")
        val IMAGE_TRANSCRIPTION_PROMPT = stringPreferencesKey("image_transcription_prompt")
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
        val WEB_SEARCH_API_KEYS_JSON = stringPreferencesKey("web_search_api_keys_json")
        val WEB_SEARCH_NUM_RESULTS = intPreferencesKey("web_search_num_results")
        val WEB_SEARCH_BASE_URL = stringPreferencesKey("web_search_base_url")
        val IMAGE_GEN_ENABLED = booleanPreferencesKey("image_gen_enabled")
        // Selected image model as "Provider:modelId"; provider creds are reused (no separate key/url).
        val IMAGE_GEN_MODEL = stringPreferencesKey("image_gen_model")
        val IMAGE_GEN_SIZE = stringPreferencesKey("image_gen_size")
        val SEARCH_CONTEXT_WINDOW = intPreferencesKey("search_context_window")
        val SEARCH_MATCH_LIMIT = intPreferencesKey("search_match_limit")
        val RAG_THRESHOLD = stringPreferencesKey("rag_threshold")
        val AUTO_CACHE_ENABLED = booleanPreferencesKey("auto_cache_enabled")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        val LOCAL_CHAT_MODELS_JSON = stringPreferencesKey("local_chat_models_json")
        val CUSTOM_PROVIDERS_JSON = stringPreferencesKey("custom_providers_json")
        val SHELL_ENABLED = booleanPreferencesKey("shell_enabled")
        val AUTOMATION_TOOLS_ENABLED = booleanPreferencesKey("automation_tools_enabled")
        val EXACT_EXECUTION_ENABLED = booleanPreferencesKey("exact_execution_enabled")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = stringPreferencesKey("proxy_port")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val PROXY_BYPASS = stringPreferencesKey("proxy_bypass")
        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = "7890"
        const val DEFAULT_PROXY_BYPASS = "localhost\n127.0.0.1\n10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n::1"
        val SHELL_CONFIRM_ENABLED = booleanPreferencesKey("shell_confirm_enabled")
        val SHELL_DEVICES_JSON = stringPreferencesKey("shell_devices_json")
        val MCP_SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
        val SANDBOX_ENABLED = booleanPreferencesKey("sandbox_enabled")
        val SANDBOX_SHARED_STORAGE_ENABLED =
            booleanPreferencesKey("sandbox_shared_storage_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val BLUR_EFFECTS_ENABLED = booleanPreferencesKey("blur_effects_enabled")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val DETAILED_TOKEN_USAGE = booleanPreferencesKey("detailed_token_usage")
        val TOOL_CALL_DISPLAY_MODE = stringPreferencesKey("tool_call_display_mode")
        val THINKING_SEGMENT_DISPLAY_MODE = stringPreferencesKey("thinking_segment_display_mode")
        val AUTO_EXPAND_ACTIVE_GROUP = booleanPreferencesKey("auto_expand_active_group")
        val SCHEME_STYLE = stringPreferencesKey("scheme_style")
        val FONT_PREFERENCE = stringPreferencesKey("font_preference")
        val CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
        val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name")
        val FIRST_LAUNCH_TIME = longPreferencesKey("first_launch_time")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val RATING_PROMPT_SUBMITTED = booleanPreferencesKey("rating_prompt_submitted")
        val RATING_PROMPT_DISMISSED = booleanPreferencesKey("rating_prompt_dismissed")
        val SHOW_DOCUMENTATION_FAB = booleanPreferencesKey("show_documentation_fab")
        val TOTAL_MESSAGES_SENT = intPreferencesKey("total_messages_sent")
        val DEFAULT_TEMPERATURE = stringPreferencesKey("default_temperature")
        val DEFAULT_MAX_TOKENS = intPreferencesKey("default_max_tokens")
        val DEFAULT_TOP_P = stringPreferencesKey("default_top_p")
        val DEFAULT_FREQUENCY_PENALTY = stringPreferencesKey("default_frequency_penalty")
        val DEFAULT_PRESENCE_PENALTY = stringPreferencesKey("default_presence_penalty")
        val CONVERSATION_SETTINGS_JSON = stringPreferencesKey("conversation_settings_json")
        // ── Auto Backup ───────────────────────────────────────────
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_PERIOD_HOURS = intPreferencesKey("auto_backup_period_hours")
        val AUTO_BACKUP_CATEGORIES = stringPreferencesKey("auto_backup_categories")
        val AUTO_BACKUP_DIRECTORY = stringPreferencesKey("auto_backup_directory")
        val AUTO_DELETE_ENABLED = booleanPreferencesKey("auto_delete_enabled")
        val AUTO_DELETE_PERIOD_HOURS = intPreferencesKey("auto_delete_period_hours")
        val LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
        val LAST_MODELS_FETCH_FINGERPRINT = stringPreferencesKey("last_models_fetch_fingerprint")
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { it[SELECTED_MODEL] ?: Constants.EXAMPLE_MODEL_ID }
    
    val providerBaseUrls: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[PROVIDER_BASE_URLS] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode providerBaseUrls", e); emptyMap() }
    }

    val customEndpointResolutions: Flow<Map<String, CustomEndpointResolution>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: "{}"
        try {
            json.decodeFromString<Map<String, CustomEndpointResolution>>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customEndpointResolutions", e)
            emptyMap()
        }
    }

    val availableModels: Flow<Map<String, List<String>>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[AVAILABLE_MODELS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, List<String>>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode availableModels", e); emptyMap() }
    }

    val customModels: Flow<Set<String>> =
        context.dataStore.data.map { it[CUSTOM_MODELS] ?: emptySet() }

    val enabledModels: Flow<Set<String>> = context.dataStore.data.map { it[ENABLED_MODELS] ?: emptySet() }

    val modelAliases: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[MODEL_ALIASES_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val apiKeys: Flow<List<ApiKeyEntry>> = context.dataStore.data.map { pref ->
        val jsonStr = com.newoether.agora.util.SecretCrypto.decrypt(pref[API_KEYS_JSON] ?: "[]")
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

    val maxContextWindow: Flow<Int> = context.dataStore.data.map { preferences ->
        ContextBudget.normalize(
            preferences[CONTEXT_TOKEN_BUDGET]?.toIntOrNull()
                ?: preferences[MAX_CONTEXT_WINDOW]?.toIntOrNull()
        )
    }
    val visualizeContextRollout: Flow<Boolean> = context.dataStore.data.map { it[VISUALIZE_CONTEXT_ROLLOUT] ?: false }
    val contextCompactEnabled: Flow<Boolean> = context.dataStore.data.map { it[CONTEXT_COMPACT_ENABLED] ?: false }
    val contextCompactModel: Flow<String?> = context.dataStore.data.map { it[CONTEXT_COMPACT_MODEL] }
    val contextCompactPrompt: Flow<String> = context.dataStore.data.map { pref ->
        pref[CONTEXT_COMPACT_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.CONTEXT_COMPACT_SYSTEM
    }
    val contextCompactRetainCount: Flow<Int> = context.dataStore.data.map {
        it[CONTEXT_COMPACT_RETAIN_COUNT] ?: 6
    }
    val codeExecutionEnabled: Flow<Boolean> = context.dataStore.data.map { it[CODE_EXECUTION_ENABLED] ?: false }
    val googleSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[GOOGLE_SEARCH_ENABLED] ?: false }
    val thinkingEnabled: Flow<Boolean> = context.dataStore.data.map { it[THINKING_ENABLED] ?: true }
    val thinkingLevel: Flow<String> = context.dataStore.data.map { ThinkingLevels.normalize(it[THINKING_LEVEL]) }
    val thinkingBudgetEnabled: Flow<Boolean> = context.dataStore.data.map { pref ->
        pref[THINKING_BUDGET_ENABLED] ?: (ThinkingLevels.legacyBudgetTokens(pref[THINKING_LEVEL]) != null)
    }
    val thinkingBudgetTokens: Flow<Int> = context.dataStore.data.map { pref ->
        pref[THINKING_BUDGET_TOKENS]
            ?: ThinkingLevels.legacyBudgetTokens(pref[THINKING_LEVEL])
            ?: ThinkingLevels.DefaultBudgetTokens
    }
    val openAiServiceTierEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[OPENAI_SERVICE_TIER_ENABLED] ?: false }
    val openAiServiceTier: Flow<String> = context.dataStore.data.map { pref ->
        OpenAiServiceTiers.normalize(pref[OPENAI_SERVICE_TIER])
    }
    val titleGenerationEnabled: Flow<Boolean> = context.dataStore.data.map { it[TITLE_GENERATION_ENABLED] ?: true }
    val titleGenerationModel: Flow<String?> = context.dataStore.data.map { it[TITLE_GENERATION_MODEL] }
    val titleGenerationPrompt: Flow<String> = context.dataStore.data.map { pref ->
        pref[TITLE_GENERATION_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.TITLE_GENERATION_SYSTEM
    }
    val titleGenerationNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[TITLE_GENERATION_NOTIFICATIONS_ENABLED] ?: true
    }
    val imageTranscriptionEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[IMAGE_TRANSCRIPTION_ENABLED] ?: true
    }
    val imageTranscriptionEnabledModels: Flow<Set<String>> = context.dataStore.data.map { it[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet() }
    val imageTranscriptionModel: Flow<String?> = context.dataStore.data.map { it[IMAGE_TRANSCRIPTION_MODEL] }
    val imageTranscriptionBatchSize: Flow<Int> = context.dataStore.data.map { it[IMAGE_TRANSCRIPTION_BATCH_SIZE] ?: 3 }
    val imageTranscriptionPrompt: Flow<String> = context.dataStore.data.map { pref ->
        pref[IMAGE_TRANSCRIPTION_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.IMAGE_TRANSCRIPTION_USER
    }

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
    val webSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[WEB_SEARCH_ENABLED] ?: true }
    val webSearchProvider: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_PROVIDER] ?: "duckduckgo" }
    val webSearchApiKeys: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = com.newoether.agora.util.SecretCrypto.decrypt(pref[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode webSearchApiKeys", e); emptyMap() }
    }
    val webSearchNumResults: Flow<Int> = context.dataStore.data.map { it[WEB_SEARCH_NUM_RESULTS] ?: 5 }
    val webSearchBaseUrl: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_BASE_URL] ?: "" }

    // ── Image generation ──────────────────────────────────────
    val imageGenEnabled: Flow<Boolean> = context.dataStore.data.map { it[IMAGE_GEN_ENABLED] ?: false }
    // Selected image model "Provider:modelId" (null = none chosen). Creds reused from that provider.
    val imageGenModel: Flow<String?> = context.dataStore.data.map { it[IMAGE_GEN_MODEL] }
    val imageGenSize: Flow<String> = context.dataStore.data.map { it[IMAGE_GEN_SIZE] ?: "1024x1024" }
    val searchContextWindow: Flow<Int> = context.dataStore.data.map { it[SEARCH_CONTEXT_WINDOW] ?: 8 }
    val searchMatchLimit: Flow<Int> = context.dataStore.data.map { it[SEARCH_MATCH_LIMIT] ?: 10 }
    val ragThreshold: Flow<Float> = context.dataStore.data.map { it[RAG_THRESHOLD]?.toFloatOrNull() ?: 0.5f }
    val defaultTemperature: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_TEMPERATURE]?.toFloatOrNull() }
    val defaultMaxTokens: Flow<Int?> = context.dataStore.data.map { it[DEFAULT_MAX_TOKENS] }
    val defaultTopP: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_TOP_P]?.toFloatOrNull() }
    val defaultFrequencyPenalty: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_FREQUENCY_PENALTY]?.toFloatOrNull() }
    val defaultPresencePenalty: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_PRESENCE_PENALTY]?.toFloatOrNull() }
    val conversationSettings: Flow<Map<String, ConversationSettings>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[CONVERSATION_SETTINGS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, ConversationSettings>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }
    val autoCacheEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CACHE_ENABLED] ?: true }
    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data.map { it[AUTO_UPDATE_CHECK] ?: true }
    val lastUpdateCheckTime: Flow<Long> = context.dataStore.data.map { it[LAST_UPDATE_CHECK_TIME] ?: 0L }
    val localChatModels: Flow<List<LocalChatModelConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[LOCAL_CHAT_MODELS_JSON] ?: "[]"
        try { json.decodeFromString<List<LocalChatModelConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val customProviders: Flow<List<CustomProviderConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_PROVIDERS_JSON] ?: "[]"
        try {
            val decoded = json.decodeFromString<List<CustomProviderConfig>>(jsonStr)
            val sanitized = CustomProviderNamePolicy.sanitize(decoded)
            if (sanitized.rejected.isNotEmpty()) {
                DebugLog.w(
                    "SettingsManager",
                    "Quarantined invalid custom provider names: " +
                        sanitized.rejected.joinToString { it.name },
                )
            }
            sanitized.accepted
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customProviders", e)
            emptyList()
        }
    }

    val showDocumentationFab: Flow<Boolean> = context.dataStore.data.map { it[SHOW_DOCUMENTATION_FAB] ?: true }

    val shellEnabled: Flow<Boolean> = context.dataStore.data.map { it[SHELL_ENABLED] ?: true }
    val automationToolsEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTOMATION_TOOLS_ENABLED] ?: false }
    val exactExecutionEnabled: Flow<Boolean> = context.dataStore.data.map { it[EXACT_EXECUTION_ENABLED] ?: false }
    val proxyEnabled: Flow<Boolean> = context.dataStore.data.map { it[PROXY_ENABLED] ?: false }
    val proxyType: Flow<String> = context.dataStore.data.map { it[PROXY_TYPE] ?: "http" }
    val proxyHost: Flow<String> = context.dataStore.data.map { it[PROXY_HOST] ?: DEFAULT_PROXY_HOST }
    val proxyPort: Flow<String> = context.dataStore.data.map { it[PROXY_PORT] ?: DEFAULT_PROXY_PORT }
    val proxyUsername: Flow<String> = context.dataStore.data.map { it[PROXY_USERNAME] ?: "" }
    val proxyPassword: Flow<String> = context.dataStore.data.map { it[PROXY_PASSWORD] ?: "" }
    val proxyBypass: Flow<String> = context.dataStore.data.map { it[PROXY_BYPASS] ?: DEFAULT_PROXY_BYPASS }
    // Confirm before the model runs state-changing commands on remote shell servers. Default on.
    val shellConfirmEnabled: Flow<Boolean> = context.dataStore.data.map { it[SHELL_CONFIRM_ENABLED] ?: true }
    val shellDevices: Flow<List<ShellDeviceConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = com.newoether.agora.util.SecretCrypto.decrypt(pref[SHELL_DEVICES_JSON] ?: "[]")
        try { json.decodeFromString<List<ShellDeviceConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val mcpServers: Flow<List<McpServerConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = com.newoether.agora.util.SecretCrypto.decrypt(pref[MCP_SERVERS_JSON] ?: "[]")
        try {
            json.decodeFromString<List<McpServerConfig>>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode MCP servers", e)
            emptyList()
        }
    }
    val sandboxEnabled: Flow<Boolean> = context.dataStore.data.map { it[SANDBOX_ENABLED] ?: false }
    val sandboxSharedStorageEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[SANDBOX_SHARED_STORAGE_ENABLED] ?: false }

    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "FOLLOW_DEVICE" }
    val colorScheme: Flow<String> = context.dataStore.data.map { it[COLOR_SCHEME] ?: "DEFAULT" }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[DYNAMIC_COLOR] ?: true }
    val blurEffectsEnabled: Flow<Boolean> = context.dataStore.data.map { it[BLUR_EFFECTS_ENABLED] ?: true }
    val reduceMotion: Flow<Boolean> = context.dataStore.data.map { it[REDUCE_MOTION] ?: false }
    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[HAPTICS_ENABLED] ?: true }
    val detailedTokenUsage: Flow<Boolean> =
        context.dataStore.data.map { it[DETAILED_TOKEN_USAGE] ?: false }
    val toolCallDisplayMode: Flow<String> = context.dataStore.data.map { ToolCallDisplayModes.normalize(it[TOOL_CALL_DISPLAY_MODE]) }
    val thinkingSegmentDisplayMode: Flow<String> = context.dataStore.data.map {
        ThinkingSegmentDisplayModes.normalize(it[THINKING_SEGMENT_DISPLAY_MODE])
    }
    val autoExpandActiveGroup: Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_EXPAND_ACTIVE_GROUP] ?: true }
    val schemeStyle: Flow<String> = context.dataStore.data.map { it[SCHEME_STYLE] ?: "TONAL_SPOT" }
    val fontPreference: Flow<String> = context.dataStore.data.map { it[FONT_PREFERENCE] ?: "app_default" }
    val customFontPath: Flow<String> = context.dataStore.data.map { it[CUSTOM_FONT_PATH] ?: "" }
    val customFontName: Flow<String> = context.dataStore.data.map { it[CUSTOM_FONT_NAME] ?: "" }
    val firstLaunchTime: Flow<Long?> = context.dataStore.data.map { it[FIRST_LAUNCH_TIME] }
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    val ratingPromptSubmitted: Flow<Boolean> = context.dataStore.data.map { it[RATING_PROMPT_SUBMITTED] ?: false }
    val ratingPromptDismissed: Flow<Boolean> = context.dataStore.data.map { it[RATING_PROMPT_DISMISSED] ?: false }
    val totalMessagesSent: Flow<Int> = context.dataStore.data.map { it[TOTAL_MESSAGES_SENT] ?: 0 }

    // ── Auto Backup ───────────────────────────────────────────
    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_ENABLED] ?: true }
    val autoBackupPeriodHours: Flow<Int> = context.dataStore.data.map { it[AUTO_BACKUP_PERIOD_HOURS] ?: 24 }
    val autoBackupCategories: Flow<String> = context.dataStore.data.map { it[AUTO_BACKUP_CATEGORIES] ?: "conversations,memories,system_prompts,settings" }
    val autoBackupDirectory: Flow<String> = context.dataStore.data.map { it[AUTO_BACKUP_DIRECTORY] ?: "Download/Agora/Backup" }
    val autoDeleteEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_DELETE_ENABLED] ?: true }
    val autoDeletePeriodHours: Flow<Int> = context.dataStore.data.map { it[AUTO_DELETE_PERIOD_HOURS] ?: 168 }
    val lastBackupTimestamp: Flow<Long> = context.dataStore.data.map { it[LAST_BACKUP_TIMESTAMP] ?: 0L }
    val lastModelsFetchFingerprint: Flow<String> = context.dataStore.data.map { it[LAST_MODELS_FETCH_FINGERPRINT] ?: "" }

    suspend fun saveProviderBaseUrl(provider: String, url: String) {
        // Blank = "use the provider's default base URL". Persisting "" would poison the map
        // (callers that resolve an effective URL treat "" as a real override, not as absent),
        // so a blank value removes the key entirely — "absent" is the canonical "default" state.
        // rename/delete pass "" to clear an entry, which is exactly this semantics.
        if (url.isBlank()) {
            context.dataStore.edit { prefs ->
                val current = prefs[PROVIDER_BASE_URLS] ?: return@edit
                val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { return@edit }
                if (map.remove(provider) != null) prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
            }
            return
        }
        context.dataStore.edit { prefs ->
            val current = prefs[PROVIDER_BASE_URLS] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = url
            prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
        }
    }

    suspend fun saveProviderBaseUrls(urls: Map<String, String>) {
        val normalized = urls
            .mapValues { (_, value) -> value.trim() }
            .filterValues { it.isNotBlank() }
        context.dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(PROVIDER_BASE_URLS)
            } else {
                prefs[PROVIDER_BASE_URLS] = json.encodeToString(normalized)
            }
        }
    }

    suspend fun saveCustomEndpointResolution(
        provider: String,
        resolution: CustomEndpointResolution?,
    ) {
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: "{}"
            val map = try {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(current)
            } catch (e: Exception) {
                mutableMapOf()
            }
            if (resolution == null) {
                map.remove(provider)
            } else {
                map[provider] = resolution
            }
            if (map.isEmpty()) {
                prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            } else {
                prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(map)
            }
        }
    }

    suspend fun renameCustomEndpointResolution(oldName: String, newName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: return@edit
            val map = try {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(current)
            } catch (e: Exception) {
                return@edit
            }
            val resolution = map.remove(oldName) ?: return@edit
            map[newName] = resolution
            prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(map)
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

    suspend fun saveCustomModels(models: Set<String>) {
        context.dataStore.edit { it[CUSTOM_MODELS] = models }
    }

    suspend fun addCustomModel(modelId: String, alias: String) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_MODELS] = (prefs[CUSTOM_MODELS] ?: emptySet()) + modelId
            val enabledModels = (prefs[ENABLED_MODELS] ?: emptySet()) + modelId
            prefs[ENABLED_MODELS] = enabledModels
            if (prefs[SELECTED_MODEL].isNullOrBlank()) {
                prefs[SELECTED_MODEL] = modelId
            }

            val aliases = try {
                json.decodeFromString<Map<String, String>>(
                    prefs[MODEL_ALIASES_JSON] ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }.toMutableMap()
            if (alias.isBlank()) {
                aliases.remove(modelId)
            } else {
                aliases[modelId] = alias.trim()
            }
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
        }
    }

    suspend fun replaceCustomModel(
        oldModelId: String,
        newModelId: String?,
        alias: String,
    ) {
        if (oldModelId == newModelId) {
            context.dataStore.edit { prefs ->
                val aliases = try {
                    json.decodeFromString<Map<String, String>>(
                        prefs[MODEL_ALIASES_JSON] ?: "{}",
                    )
                } catch (_: Exception) {
                    emptyMap()
                }.replaceCustomModelAlias(oldModelId, newModelId, alias)
                prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
            }
            return
        }

        context.dataStore.edit { prefs ->
            val customModels = prefs[CUSTOM_MODELS] ?: emptySet()
            if (oldModelId !in customModels) return@edit

            prefs[CUSTOM_MODELS] =
                customModels.replaceModelReference(oldModelId, newModelId)

            val updatedEnabled =
                (prefs[ENABLED_MODELS] ?: emptySet())
                    .replaceModelReference(oldModelId, newModelId)
            prefs[ENABLED_MODELS] = updatedEnabled

            val aliases = try {
                json.decodeFromString<Map<String, String>>(
                    prefs[MODEL_ALIASES_JSON] ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }.replaceCustomModelAlias(oldModelId, newModelId, alias)
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)

            if (prefs[SELECTED_MODEL] == oldModelId) {
                prefs[SELECTED_MODEL] =
                    newModelId ?: updatedEnabled.firstOrNull().orEmpty()
            }

            val updatedTranscriptionTargets =
                (prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet())
                    .replaceModelReference(oldModelId, newModelId)
            prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = updatedTranscriptionTargets

            fun replaceNullableReference(key: androidx.datastore.preferences.core.Preferences.Key<String>) {
                when (
                    val updated = prefs[key].replaceModelReference(
                        oldModelId,
                        newModelId,
                    )
                ) {
                    null -> prefs.remove(key)
                    else -> prefs[key] = updated
                }
            }

            replaceNullableReference(TITLE_GENERATION_MODEL)
            replaceNullableReference(IMAGE_TRANSCRIPTION_MODEL)
            replaceNullableReference(IMAGE_GEN_MODEL)
            replaceNullableReference(CONTEXT_COMPACT_MODEL)
        }
    }

    suspend fun saveEnabledModels(models: Set<String>) {
        context.dataStore.edit { it[ENABLED_MODELS] = models }
    }

    suspend fun saveModelAliases(aliases: Map<String, String>) {
        context.dataStore.edit { it[MODEL_ALIASES_JSON] = json.encodeToString(aliases) }
    }

    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) {
        context.dataStore.edit { it[API_KEYS_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(keys)) }
    }

    suspend fun saveActiveApiKeyIds(ids: Map<String, String>) {
        context.dataStore.edit { prefs ->
            if (ids.isEmpty()) {
                prefs.remove(ACTIVE_API_KEY_IDS_JSON)
            } else {
                prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(ids)
            }
        }
    }

    suspend fun setActiveApiKeyId(provider: String, id: String?) {
        context.dataStore.edit { prefs ->
            val current = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            if (id == null) map.remove(provider) else map[provider] = id
            prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(map)
        }
    }

    /**
     * Atomically rename the provider field on every API key entry for [oldProvider] to
     * [newProvider] and remap the active-key-id in the same DataStore edit. Decryption or
     * parse failures leave the raw encrypted blobs completely untouched (fail-preserving),
     * so a rename can never wipe keys due to a transient Keystore error.
     */
    suspend fun renameApiKeyProvider(oldProvider: String, newProvider: String) {
        context.dataStore.edit { prefs ->
            val rawKeys = prefs[API_KEYS_JSON] ?: return@edit
            val decrypted = runCatching {
                com.newoether.agora.util.SecretCrypto.decrypt(rawKeys)
            }.getOrDefault(rawKeys)
            val keys = runCatching {
                json.decodeFromString<List<ApiKeyEntry>>(decrypted)
            }.getOrNull() ?: return@edit
            val renamed = keys.map { entry ->
                if (entry.provider == oldProvider) entry.copy(provider = newProvider) else entry
            }
            if (renamed != keys) {
                prefs[API_KEYS_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(
                    json.encodeToString(renamed)
                )
            }
            // Remap active-key-id in the same edit so the active key follows the rename.
            val rawIds = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val ids = runCatching {
                json.decodeFromString<MutableMap<String, String>>(rawIds)
            }.getOrNull()
            if (ids != null && ids.containsKey(oldProvider)) {
                ids[newProvider] = ids.remove(oldProvider)!!
                prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(ids)
            }
        }
    }

    suspend fun saveSystemPrompts(prompts: List<SystemPromptEntry>) {
        context.dataStore.edit { it[SYSTEM_PROMPTS_JSON] = json.encodeToString(prompts) }
    }

    suspend fun initializeFirstInstallDefaults(
        locale: Locale = Locale.getDefault(),
        now: Long = System.currentTimeMillis()
    ) {
        context.dataStore.edit { prefs ->
            val firstLaunchMissing = prefs[FIRST_LAUNCH_TIME] == null
            val looksLikeFreshInstall = firstLaunchMissing && prefs[ONBOARDING_COMPLETED] != true
            if (firstLaunchMissing) {
                prefs[FIRST_LAUNCH_TIME] = now
            }
            val currentPrompts = try {
                json.decodeFromString<List<SystemPromptEntry>>(prefs[SYSTEM_PROMPTS_JSON] ?: "[]")
            } catch (_: Exception) {
                emptyList()
            }
            val migratedPrompts = migrateLegacyDefaultPromptTitle(currentPrompts, locale)
            val runtimeMigrated = migrateOldRuntimeContext(migratedPrompts, locale)
            if (runtimeMigrated != currentPrompts) {
                prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(runtimeMigrated)
            }
            if (looksLikeFreshInstall) {
                if (runtimeMigrated.isEmpty()) {
                    val defaultPrompt = DefaultSystemPrompt.create(locale)
                    prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(listOf(defaultPrompt))
                    if (prefs[ACTIVE_SYSTEM_PROMPT_ID] == null) {
                        prefs[ACTIVE_SYSTEM_PROMPT_ID] = defaultPrompt.id
                    }
                }
            }
        }
    }

    private fun migrateOldRuntimeContext(
        prompts: List<SystemPromptEntry>,
        locale: Locale
    ): List<SystemPromptEntry> {
        if (prompts.isEmpty()) return prompts
        val newDefault = DefaultSystemPrompt.create(locale)
        return prompts.map { entry ->
            if (DefaultSystemPrompt.hasOldRuntimeContext(entry)) {
                entry.copy(
                    systemItems = newDefault.systemItems,
                    userPrependItems = newDefault.userPrependItems,
                    userPostpendItems = newDefault.userPostpendItems,
                )
            } else {
                entry
            }
        }
    }

    private fun migrateLegacyDefaultPromptTitle(
        prompts: List<SystemPromptEntry>,
        locale: Locale
    ): List<SystemPromptEntry> {
        if (prompts.isEmpty()) return prompts
        val localizedTitle = DefaultSystemPrompt.titleForLocale(locale)
        val defaultPrompt = DefaultSystemPrompt.create(locale)
        return prompts.map { entry ->
            val legacyLowercaseEnglish = entry.title == "default"
            val legacySimplifiedTitleInTraditionalLocale =
                entry.title == "\u9ed8\u8ba4" && localizedTitle == "\u9810\u8a2d"
            if ((legacyLowercaseEnglish || legacySimplifiedTitleInTraditionalLocale) &&
                entry.sameTemplateAs(defaultPrompt)
            ) {
                entry.copy(title = localizedTitle)
            } else {
                entry
            }
        }
    }

    private fun SystemPromptEntry.sameTemplateAs(other: SystemPromptEntry): Boolean =
        resolvedSystemItems.sameTemplateItems(other.resolvedSystemItems) &&
            userPrependItems.sameTemplateItems(other.userPrependItems) &&
            userPostpendItems.sameTemplateItems(other.userPostpendItems)

    private fun List<PromptTemplateItem>.sameTemplateItems(other: List<PromptTemplateItem>): Boolean =
        size == other.size && zip(other).all { (left, right) ->
            left.type == right.type && left.value == right.value
        }

    suspend fun setActiveSystemPromptId(id: String?) {
        context.dataStore.edit { 
            if (id == null) it.remove(ACTIVE_SYSTEM_PROMPT_ID) else it[ACTIVE_SYSTEM_PROMPT_ID] = id 
        }
    }

    suspend fun saveMaxContextWindow(window: Int) {
        context.dataStore.edit {
            it[CONTEXT_TOKEN_BUDGET] = ContextBudget.normalize(window).toString()
        }
    }

    /** Remaps every configured model reference whose provider component was renamed. */
    suspend fun renameProviderModelReferences(oldProvider: String, newProvider: String) {
        val oldPrefix = "$oldProvider:"
        val newPrefix = "$newProvider:"
        fun String.remapProvider(): String =
            if (startsWith(oldPrefix)) newPrefix + removePrefix(oldPrefix) else this

        context.dataStore.edit { prefs ->
            prefs[CUSTOM_MODELS] = (prefs[CUSTOM_MODELS] ?: emptySet()).mapTo(linkedSetOf()) {
                it.remapProvider()
            }
            prefs[ENABLED_MODELS] = (prefs[ENABLED_MODELS] ?: emptySet()).mapTo(linkedSetOf()) {
                it.remapProvider()
            }
            prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] =
                (prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet()).mapTo(linkedSetOf()) {
                    it.remapProvider()
                }
            val aliases = runCatching {
                json.decodeFromString<Map<String, String>>(prefs[MODEL_ALIASES_JSON] ?: "{}")
            }.getOrDefault(emptyMap())
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(
                aliases.mapKeys { (modelId, _) -> modelId.remapProvider() }
            )
            listOf(
                SELECTED_MODEL,
                TITLE_GENERATION_MODEL,
                IMAGE_TRANSCRIPTION_MODEL,
                IMAGE_GEN_MODEL,
                CONTEXT_COMPACT_MODEL,
            ).forEach { key ->
                prefs[key]?.let { modelId -> prefs[key] = modelId.remapProvider() }
            }
        }
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

    suspend fun saveThinkingLevel(level: String) {
        context.dataStore.edit { it[THINKING_LEVEL] = ThinkingLevels.normalize(level) }
    }

    suspend fun saveThinkingBudgetEnabled(enabled: Boolean) {
        context.dataStore.edit { it[THINKING_BUDGET_ENABLED] = enabled }
    }

    suspend fun saveThinkingBudgetTokens(tokens: Int) {
        context.dataStore.edit { it[THINKING_BUDGET_TOKENS] = tokens.coerceAtLeast(1) }
    }

    suspend fun saveOpenAiServiceTierEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OPENAI_SERVICE_TIER_ENABLED] = enabled }
    }

    suspend fun saveOpenAiServiceTier(tier: String) {
        context.dataStore.edit {
            it[OPENAI_SERVICE_TIER] = OpenAiServiceTiers.normalize(tier)
        }
    }

    suspend fun saveTitleGenerationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TITLE_GENERATION_ENABLED] = enabled }
    }

    suspend fun saveTitleGenerationNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TITLE_GENERATION_NOTIFICATIONS_ENABLED] = enabled }
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
    suspend fun saveAutoCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_CACHE_ENABLED] = enabled }
    }
    suspend fun saveAutoUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_CHECK] = enabled }
    }
    suspend fun saveLastUpdateCheckTime(time: Long) {
        context.dataStore.edit { it[LAST_UPDATE_CHECK_TIME] = time }
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

    suspend fun saveWebSearchApiKey(provider: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            val current = com.newoether.agora.util.SecretCrypto.decrypt(prefs[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            if (apiKey.isBlank()) map.remove(provider) else map[provider] = apiKey
            prefs[WEB_SEARCH_API_KEYS_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(map))
        }
    }

    suspend fun saveWebSearchApiKeys(keys: Map<String, String>) {
        val nonBlank = keys.filterValues { it.isNotBlank() }
        context.dataStore.edit { prefs ->
            if (nonBlank.isEmpty()) {
                prefs.remove(WEB_SEARCH_API_KEYS_JSON)
            } else {
                prefs[WEB_SEARCH_API_KEYS_JSON] =
                    com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(nonBlank))
            }
        }
    }

    suspend fun saveWebSearchNumResults(n: Int) {
        context.dataStore.edit { it[WEB_SEARCH_NUM_RESULTS] = n.coerceIn(1, 10) }
    }
    suspend fun saveWebSearchBaseUrl(url: String) {
        context.dataStore.edit { it[WEB_SEARCH_BASE_URL] = url }
    }

    suspend fun saveImageGenEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IMAGE_GEN_ENABLED] = enabled }
    }
    suspend fun saveImageGenModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(IMAGE_GEN_MODEL) else it[IMAGE_GEN_MODEL] = model
        }
    }
    suspend fun saveImageGenSize(size: String) {
        context.dataStore.edit { it[IMAGE_GEN_SIZE] = size }
    }
    suspend fun saveSearchMatchLimit(n: Int) {
        context.dataStore.edit { it[SEARCH_MATCH_LIMIT] = n }
    }
    suspend fun saveSearchContextWindow(n: Int) {
        context.dataStore.edit { it[SEARCH_CONTEXT_WINDOW] = n }
    }
    suspend fun saveRagThreshold(threshold: Float) {
        context.dataStore.edit { it[RAG_THRESHOLD] = threshold.toString() }
    }
    suspend fun saveDefaultTemperature(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_TEMPERATURE) else prefs[DEFAULT_TEMPERATURE] = value.toString()
        }
    }
    suspend fun saveDefaultMaxTokens(value: Int?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_MAX_TOKENS) else prefs[DEFAULT_MAX_TOKENS] = value
        }
    }
    suspend fun saveDefaultTopP(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_TOP_P) else prefs[DEFAULT_TOP_P] = value.toString()
        }
    }
    suspend fun saveDefaultFrequencyPenalty(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_FREQUENCY_PENALTY) else prefs[DEFAULT_FREQUENCY_PENALTY] = value.toString()
        }
    }
    suspend fun saveDefaultPresencePenalty(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_PRESENCE_PENALTY) else prefs[DEFAULT_PRESENCE_PENALTY] = value.toString()
        }
    }
    suspend fun saveConversationSettings(conversationId: String, settings: ConversationSettings?) {
        context.dataStore.edit { prefs ->
            val current = prefs[CONVERSATION_SETTINGS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, ConversationSettings>>(current) } catch (e: Exception) { mutableMapOf() }
            if (settings == null || settings.isAllNull()) map.remove(conversationId)
            else map[conversationId] = settings
            prefs[CONVERSATION_SETTINGS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveConversationSettingsMap(settings: Map<String, ConversationSettings>) {
        val nonEmpty = settings.filterValues { !it.isAllNull() }
        context.dataStore.edit { prefs ->
            if (nonEmpty.isEmpty()) {
                prefs.remove(CONVERSATION_SETTINGS_JSON)
            } else {
                prefs[CONVERSATION_SETTINGS_JSON] = json.encodeToString(nonEmpty)
            }
        }
    }

    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) {
        context.dataStore.edit { it[LOCAL_CHAT_MODELS_JSON] = json.encodeToString(models) }
    }
    suspend fun saveCustomProviders(providers: List<CustomProviderConfig>) {
        val sanitized = CustomProviderNamePolicy.sanitize(providers)
        context.dataStore.edit {
            it[CUSTOM_PROVIDERS_JSON] = json.encodeToString(sanitized.accepted)
        }
    }

    suspend fun saveContextCompactEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CONTEXT_COMPACT_ENABLED] = enabled }
    }

    suspend fun saveContextCompactModel(model: String?) {
        context.dataStore.edit { prefs ->
            if (model == null) prefs.remove(CONTEXT_COMPACT_MODEL) else prefs[CONTEXT_COMPACT_MODEL] = model
        }
    }

    suspend fun saveContextCompactPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            if (prompt.isBlank()) prefs.remove(CONTEXT_COMPACT_PROMPT) else prefs[CONTEXT_COMPACT_PROMPT] = prompt
        }
    }

    suspend fun saveContextCompactRetainCount(count: Int) {
        require(count >= 0)
        context.dataStore.edit { it[CONTEXT_COMPACT_RETAIN_COUNT] = count }
    }

    suspend fun saveTitleGenerationModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(TITLE_GENERATION_MODEL)
            else it[TITLE_GENERATION_MODEL] = model
        }
    }

    suspend fun saveTitleGenerationPrompt(prompt: String) {
        context.dataStore.edit {
            if (prompt.isBlank()) it.remove(TITLE_GENERATION_PROMPT)
            else it[TITLE_GENERATION_PROMPT] = prompt
        }
    }

    suspend fun saveImageTranscriptionEnabledModels(models: Set<String>) {
        context.dataStore.edit { it[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = models }
    }

    suspend fun saveImageTranscriptionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IMAGE_TRANSCRIPTION_ENABLED] = enabled }
    }

    suspend fun saveImageTranscriptionModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(IMAGE_TRANSCRIPTION_MODEL)
            else it[IMAGE_TRANSCRIPTION_MODEL] = model
        }
    }

    suspend fun saveImageTranscriptionBatchSize(size: Int) {
        context.dataStore.edit { it[IMAGE_TRANSCRIPTION_BATCH_SIZE] = size.coerceIn(1, 10) }
    }

    suspend fun saveImageTranscriptionPrompt(prompt: String) {
        context.dataStore.edit {
            if (prompt.isBlank()) it.remove(IMAGE_TRANSCRIPTION_PROMPT)
            else it[IMAGE_TRANSCRIPTION_PROMPT] = prompt
        }
    }

    suspend fun saveShowDocumentationFab(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_DOCUMENTATION_FAB] = enabled }
    }

    suspend fun saveShellEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SHELL_ENABLED] = enabled }
    }

    suspend fun saveAutomationToolsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTOMATION_TOOLS_ENABLED] = enabled }
    }

    suspend fun saveExactExecutionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[EXACT_EXECUTION_ENABLED] = enabled }
    }
    suspend fun saveProxyEnabled(enabled: Boolean) { context.dataStore.edit { it[PROXY_ENABLED] = enabled } }
    suspend fun saveProxyType(type: String) { context.dataStore.edit { it[PROXY_TYPE] = type } }
    suspend fun saveProxyHost(host: String) { context.dataStore.edit { it[PROXY_HOST] = host } }
    suspend fun saveProxyPort(port: String) { context.dataStore.edit { it[PROXY_PORT] = port } }
    suspend fun saveProxyUsername(user: String) { context.dataStore.edit { it[PROXY_USERNAME] = user } }
    suspend fun saveProxyPassword(pass: String) { context.dataStore.edit { it[PROXY_PASSWORD] = pass } }
    suspend fun saveProxyBypass(bypass: String) { context.dataStore.edit { it[PROXY_BYPASS] = bypass } }

    suspend fun saveShellConfirmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SHELL_CONFIRM_ENABLED] = enabled }
    }

    suspend fun saveShellDevices(devices: List<ShellDeviceConfig>) {
        context.dataStore.edit { it[SHELL_DEVICES_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(devices)) }
    }

    suspend fun saveMcpServers(servers: List<McpServerConfig>) {
        context.dataStore.edit {
            it[MCP_SERVERS_JSON] =
                com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(servers))
        }
    }

    suspend fun saveSandboxEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SANDBOX_ENABLED] = enabled }
    }

    suspend fun saveSandboxSharedStorageEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SANDBOX_SHARED_STORAGE_ENABLED] = enabled }
    }

    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }
    suspend fun saveColorScheme(scheme: String) {
        context.dataStore.edit { it[COLOR_SCHEME] = scheme }
    }
    suspend fun saveDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun saveBlurEffectsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BLUR_EFFECTS_ENABLED] = enabled }
    }

    suspend fun saveReduceMotion(enabled: Boolean) {
        context.dataStore.edit { it[REDUCE_MOTION] = enabled }
    }

    suspend fun saveHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[HAPTICS_ENABLED] = enabled }
    }

    suspend fun saveDetailedTokenUsage(enabled: Boolean) {
        context.dataStore.edit { it[DETAILED_TOKEN_USAGE] = enabled }
    }

    suspend fun saveToolCallDisplayMode(mode: String) {
        context.dataStore.edit { it[TOOL_CALL_DISPLAY_MODE] = ToolCallDisplayModes.normalize(mode) }
    }

    suspend fun saveThinkingSegmentDisplayMode(mode: String) {
        context.dataStore.edit {
            it[THINKING_SEGMENT_DISPLAY_MODE] = ThinkingSegmentDisplayModes.normalize(mode)
        }
    }

    suspend fun saveAutoExpandActiveGroup(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_EXPAND_ACTIVE_GROUP] = enabled }
    }

    suspend fun saveFontPreference(value: String) {
        context.dataStore.edit { it[FONT_PREFERENCE] = value }
    }
    suspend fun saveCustomFontPath(value: String) {
        context.dataStore.edit { it[CUSTOM_FONT_PATH] = value }
    }
    suspend fun saveCustomFontName(value: String) {
        context.dataStore.edit { it[CUSTOM_FONT_NAME] = value }
    }

    suspend fun saveSchemeStyle(style: String) {
        context.dataStore.edit { it[SCHEME_STYLE] = style }
    }

    suspend fun saveFirstLaunchTime(time: Long) {
        context.dataStore.edit { it[FIRST_LAUNCH_TIME] = time }
    }

    suspend fun saveOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    suspend fun saveRatingPromptSubmitted(submitted: Boolean) {
        context.dataStore.edit { it[RATING_PROMPT_SUBMITTED] = submitted }
    }

    suspend fun saveRatingPromptDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[RATING_PROMPT_DISMISSED] = dismissed }
    }

    suspend fun incrementMessagesSent() {
        context.dataStore.edit { it[TOTAL_MESSAGES_SENT] = (it[TOTAL_MESSAGES_SENT] ?: 0) + 1 }
    }

    // ── Auto Backup ───────────────────────────────────────────
    suspend fun saveAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_ENABLED] = enabled }
    }
    suspend fun saveAutoBackupPeriodHours(hours: Int) {
        context.dataStore.edit { it[AUTO_BACKUP_PERIOD_HOURS] = hours }
    }
    suspend fun saveAutoBackupCategories(categories: String) {
        context.dataStore.edit { it[AUTO_BACKUP_CATEGORIES] = categories }
    }
    suspend fun saveAutoBackupDirectory(path: String) {
        context.dataStore.edit { it[AUTO_BACKUP_DIRECTORY] = path }
    }
    suspend fun saveAutoDeleteEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_DELETE_ENABLED] = enabled }
    }
    suspend fun saveAutoDeletePeriodHours(hours: Int) {
        context.dataStore.edit { it[AUTO_DELETE_PERIOD_HOURS] = hours }
    }
    suspend fun saveLastBackupTimestamp(timestamp: Long) {
        context.dataStore.edit { it[LAST_BACKUP_TIMESTAMP] = timestamp }
    }

    suspend fun saveLastModelsFetchFingerprint(fingerprint: String) {
        context.dataStore.edit { it[LAST_MODELS_FETCH_FINGERPRINT] = fingerprint }
    }

    /**
     * Clears only settings that are portable across devices. Secrets, conversation-scoped
     * overrides, local model files, sandbox state, onboarding/rating metadata, and auto-backup
     * configuration are deliberately outside this reset boundary.
     *
     * Composite portable records (remote embedding models, shell devices, MCP servers, and the
     * custom font) are rebuilt separately by the importer so device-local records and credentials
     * can be retained or cleared according to their own import category.
     */
    suspend fun resetPortableSettingsForImport() {
        context.dataStore.edit { prefs ->
            prefs.remove(SELECTED_MODEL)
            prefs.remove(CUSTOM_MODELS)
            prefs.remove(ENABLED_MODELS)
            prefs.remove(ACTIVE_SYSTEM_PROMPT_ID)
            prefs.remove(MODEL_ALIASES_JSON)
            prefs.remove(CONTEXT_TOKEN_BUDGET)
            prefs.remove(MAX_CONTEXT_WINDOW)
            prefs.remove(VISUALIZE_CONTEXT_ROLLOUT)
            prefs.remove(CONTEXT_COMPACT_ENABLED)
            prefs.remove(CONTEXT_COMPACT_MODEL)
            prefs.remove(CONTEXT_COMPACT_PROMPT)
            prefs.remove(CONTEXT_COMPACT_RETAIN_COUNT)
            prefs.remove(CODE_EXECUTION_ENABLED)
            prefs.remove(GOOGLE_SEARCH_ENABLED)
            prefs.remove(THINKING_ENABLED)
            prefs.remove(THINKING_LEVEL)
            prefs.remove(THINKING_BUDGET_ENABLED)
            prefs.remove(THINKING_BUDGET_TOKENS)
            prefs.remove(OPENAI_SERVICE_TIER_ENABLED)
            prefs.remove(OPENAI_SERVICE_TIER)
            prefs.remove(PROVIDER_BASE_URLS)
            prefs.remove(TITLE_GENERATION_ENABLED)
            prefs.remove(TITLE_GENERATION_MODEL)
            prefs.remove(TITLE_GENERATION_PROMPT)
            prefs.remove(TITLE_GENERATION_NOTIFICATIONS_ENABLED)
            prefs.remove(IMAGE_TRANSCRIPTION_ENABLED)
            prefs.remove(IMAGE_TRANSCRIPTION_ENABLED_MODELS)
            prefs.remove(IMAGE_TRANSCRIPTION_MODEL)
            prefs.remove(IMAGE_TRANSCRIPTION_BATCH_SIZE)
            prefs.remove(IMAGE_TRANSCRIPTION_PROMPT)
            prefs.remove(ACCESS_PAST_CONVERSATIONS)
            prefs.remove(ACCESS_SAVED_MEMORIES)
            prefs.remove(ACCESS_ACTIVE_MEMORY)
            prefs.remove(RAG_SEARCH_ENABLED)
            prefs.remove(MODEL_SEARCH_METHOD)
            prefs.remove(MANUAL_SEARCH_METHOD)
            prefs.remove(APP_LANGUAGE)
            prefs.remove(WEB_SEARCH_ENABLED)
            prefs.remove(WEB_SEARCH_PROVIDER)
            prefs.remove(WEB_SEARCH_NUM_RESULTS)
            prefs.remove(WEB_SEARCH_BASE_URL)
            prefs.remove(IMAGE_GEN_ENABLED)
            prefs.remove(IMAGE_GEN_MODEL)
            prefs.remove(IMAGE_GEN_SIZE)
            prefs.remove(SEARCH_CONTEXT_WINDOW)
            prefs.remove(SEARCH_MATCH_LIMIT)
            prefs.remove(RAG_THRESHOLD)
            prefs.remove(AUTO_CACHE_ENABLED)
            prefs.remove(AUTO_UPDATE_CHECK)
            prefs.remove(CUSTOM_PROVIDERS_JSON)
            prefs.remove(SHELL_ENABLED)
            prefs.remove(AUTOMATION_TOOLS_ENABLED)
            prefs.remove(EXACT_EXECUTION_ENABLED)
            prefs.remove(PROXY_ENABLED)
            prefs.remove(PROXY_TYPE)
            prefs.remove(PROXY_HOST)
            prefs.remove(PROXY_PORT)
            prefs.remove(PROXY_USERNAME)
            prefs.remove(PROXY_BYPASS)
            prefs.remove(SHELL_CONFIRM_ENABLED)
            prefs.remove(THEME_MODE)
            prefs.remove(COLOR_SCHEME)
            prefs.remove(DYNAMIC_COLOR)
            prefs.remove(BLUR_EFFECTS_ENABLED)
            prefs.remove(REDUCE_MOTION)
            prefs.remove(HAPTICS_ENABLED)
            prefs.remove(DETAILED_TOKEN_USAGE)
            prefs.remove(TOOL_CALL_DISPLAY_MODE)
            prefs.remove(THINKING_SEGMENT_DISPLAY_MODE)
            prefs.remove(AUTO_EXPAND_ACTIVE_GROUP)
            prefs.remove(SCHEME_STYLE)
            prefs.remove(FONT_PREFERENCE)
            prefs.remove(SHOW_DOCUMENTATION_FAB)
            prefs.remove(DEFAULT_TEMPERATURE)
            prefs.remove(DEFAULT_MAX_TOKENS)
            prefs.remove(DEFAULT_TOP_P)
            prefs.remove(DEFAULT_FREQUENCY_PENALTY)
            prefs.remove(DEFAULT_PRESENCE_PENALTY)

            // Derived fetch state is never restored. Invalidate it when portable provider/model
            // configuration is replaced so stale results cannot masquerade as imported data.
            prefs.remove(AVAILABLE_MODELS_JSON)
            prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            prefs.remove(LAST_MODELS_FETCH_FINGERPRINT)
        }
    }

    suspend fun invalidatePortableModelCaches() {
        context.dataStore.edit { prefs ->
            prefs.remove(AVAILABLE_MODELS_JSON)
            prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            prefs.remove(LAST_MODELS_FETCH_FINGERPRINT)
        }
    }
}
