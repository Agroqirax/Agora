package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.api.*
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.api.LocalEmbeddingEngine
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SearchResultFormatter
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.service.AgoraForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import androidx.compose.foundation.lazy.LazyListState

class ChatViewModel(
    application: Application,
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao,
    val memoryManager: MemoryManager,
    private val appContext: Context
) : AndroidViewModel(application) {

    init {
        viewModelScope.launch {
            val models = settingsManager.embeddingModels.first()
            val activeId = settingsManager.activeEmbeddingModelId.first()
            val active = models.find { it.id == activeId }
            if (active != null && !active.cached) {
                _snackbarMessage.emit(SnackbarEvent(
                    "Embedding model \"${active.name}\" is not cached.",
                    "Cache Now"
                ) { cacheMessagesForModel(active.id) })
            }
        }
    }

    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generationJob: Job? = null

    private val generationManager by lazy {
        GenerationManager(
            app = application,
            chatDao = chatDao,
            memoryManager = memoryManager,
            providers = providers,
            context = appContext
        ).also { gm ->
            gm.onMessagePersisted = { messageId, text ->
                if (modelSearchMethod.value == "rag" || manualSearchMethod.value == "rag") {
                    indexMessageForRag(messageId, text)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationScope.coroutineContext[Job]?.cancel()
    }

    val listState = LazyListState()
    val messageHeights = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    private val providers = mapOf(
        "Google" to GeminiProvider(),
        "OpenAI" to OpenAiProvider(),
        "Anthropic" to AnthropicProvider(),
        "DeepSeek" to DeepSeekProvider(),
        "Qwen" to QwenProvider(),
        "Ollama" to OllamaProvider(),
        "Open Router" to OpenRouterProvider()
    )

    fun getProviderInstance(name: String): LlmProvider {
        return providers[name] ?: GeminiProvider()
    }


    private val _scrollToMessage = MutableSharedFlow<String?>(replay = 0)
    val scrollToMessage = _scrollToMessage.asSharedFlow()

    fun triggerScrollToMessage(messageId: String? = null) {
        viewModelScope.launch {
            _scrollToMessage.emit(messageId)
        }
    }

    val selectedModel = settingsManager.selectedModel.stateIn(viewModelScope, SharingStarted.Eagerly, "gemini-1.5-flash")
    private val _currentActiveModel = MutableStateFlow<String?>(null)
    val currentActiveModel = kotlinx.coroutines.flow.combine(_currentActiveModel, selectedModel) { active, default ->
        active ?: default
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "gemini-1.5-flash")

    fun getProviderForModel(modelId: String): String {
        // If the modelId already has a prefix (e.g., "OpenAI:gpt-4"), extract it.
        if (modelId.contains(":")) {
            return modelId.substringBefore(":")
        }
        
        // Fallback for existing or unprefixed models
        availableModels.value.forEach { (providerName, models) ->
            if (models.contains(modelId)) return providerName
        }
        
        return when {
            modelId.startsWith("gpt-") || modelId.startsWith("o1") || modelId.startsWith("o3") -> "OpenAI"
            modelId.startsWith("claude-") -> "Anthropic"
            modelId.contains("deepseek") -> "DeepSeek"
            modelId.contains("qwen") -> "Qwen"
            modelId.contains("models/") || modelId.startsWith("gemini") -> "Google"
            else -> "Google"
        }
    }
    
    val availableModels = settingsManager.availableModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val enabledModels = settingsManager.enabledModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val modelAliases = settingsManager.modelAliases.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val apiKeys = settingsManager.apiKeys.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeApiKeyIds = settingsManager.activeApiKeyIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val systemPrompts = settingsManager.systemPrompts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeSystemPromptId = settingsManager.activeSystemPromptId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
        
        val maxContextWindow = settingsManager.maxContextWindow.stateIn(viewModelScope, SharingStarted.Eagerly, 20)
        val visualizeContextRollout = settingsManager.visualizeContextRollout.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val codeExecutionEnabled = settingsManager.codeExecutionEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val googleSearchEnabled = settingsManager.googleSearchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val thinkingEnabled = settingsManager.thinkingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
        val providerBaseUrls = settingsManager.providerBaseUrls.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val titleGenerationEnabled = settingsManager.titleGenerationEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val titleGenerationModel = settingsManager.titleGenerationModel.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val accessPastConversations = settingsManager.accessPastConversations.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val accessSavedMemories = settingsManager.accessSavedMemories.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val accessActiveMemory = settingsManager.accessActiveMemory.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val ragSearchEnabled = settingsManager.ragSearchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val modelSearchMethod = settingsManager.modelSearchMethod.stateIn(viewModelScope, SharingStarted.Eagerly, "keyword")
    val manualSearchMethod = settingsManager.manualSearchMethod.stateIn(viewModelScope, SharingStarted.Eagerly, "keyword")
    val embeddingModels = settingsManager.embeddingModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeEmbeddingModelId = settingsManager.activeEmbeddingModelId.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val activeEmbeddingModel = combine(embeddingModels, activeEmbeddingModelId) { models, id ->
        models.find { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val appLanguage = settingsManager.appLanguage.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val webSearchEnabled = settingsManager.webSearchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val webSearchProvider = settingsManager.webSearchProvider.stateIn(viewModelScope, SharingStarted.Eagerly, "brave")
    val webSearchApiKey = settingsManager.webSearchApiKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val webSearchBaseUrl = settingsManager.webSearchBaseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")

        val conversations: StateFlow<List<ChatConversation>> = chatDao.getAllConversations()
            .map { entities ->
                entities.map { ChatConversation(id = it.id, title = it.title, systemPromptId = it.systemPromptId, modelId = it.modelId) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()

    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _selectedChildren = MutableStateFlow<Map<String?, String>>(emptyMap())

    val messages: StateFlow<List<ChatMessage>> = combine(
        _allMessages,
        _streamingMessage,
        _selectedChildren
    ) { allMsgs, streaming, selectedChildren ->
        val path = mutableListOf<ChatMessage>()
        var currentParentId: String? = null
        
        while (true) {
            val siblings = allMsgs.filter { it.parentId == currentParentId }
                .sortedBy { it.timestamp }
            
            if (siblings.isEmpty()) break
            
            val selectedId = selectedChildren[currentParentId]
            val visibleSiblings = siblings.filter {
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            var selectedMessage = if (visibleSiblings.isNotEmpty()) {
                visibleSiblings.find { it.id == selectedId } ?: visibleSiblings.last()
            } else {
                siblings.find { it.id == selectedId } ?: siblings.last()
            }

            if (streaming != null && selectedMessage.id == streaming.id) {
                selectedMessage = streaming
            }

            // Skip synthetic tool call/result messages (hidden from UI, API context only)
            val isSynthetic = selectedMessage.id.startsWith(Constants.TOOL_MSG_PREFIX) || selectedMessage.id.startsWith(Constants.RESULT_MSG_PREFIX)
            if (!isSynthetic || (streaming != null && selectedMessage.id == streaming.id)) {
                path.add(selectedMessage)
            }
            currentParentId = selectedMessage.id
        }

        if (streaming != null && path.none { it.id == streaming.id }) {
            if (streaming.parentId == path.lastOrNull()?.id || (streaming.parentId == null && path.isEmpty())) {
                path.add(streaming)
            }
        }
        path
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalTokens: StateFlow<Int> = _allMessages.map { list ->
        list.sumOf { it.tokenCount }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _generatingInConversationId = MutableStateFlow<String?>(null)
    val generatingInConversationId: StateFlow<String?> = _generatingInConversationId.asStateFlow()

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private var switchingJob: Job? = null

    fun setSwitching(switching: Boolean) {
        _isSwitching.value = switching
    }

    fun clearMessageHeights() {
        messageHeights.clear()
    }

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> = _isTransitioningToNewChat.asStateFlow()

    private val _pendingSystemPromptId = MutableStateFlow<String?>(null)
    val pendingSystemPromptId: StateFlow<String?> = _pendingSystemPromptId.asStateFlow()

    fun setPendingSystemPrompt(promptId: String?) {
        _pendingSystemPromptId.value = promptId
    }

    private val _branchSwitchTrigger = MutableStateFlow<String?>(null)
    val branchSwitchTrigger: StateFlow<String?> = _branchSwitchTrigger.asStateFlow()

    init {
        viewModelScope.launch {
            _currentConversationId.collectLatest { id ->
                if (id != null) {
                    // Fix stuck sending states when loading conversation
                    val stuckMessages = chatDao.getMessagesForConversation(id).first()
                        .filter { it.status == MessageStatus.SENDING || it.status == MessageStatus.THINKING }
                    
                    stuckMessages.forEach { msg ->
                        chatDao.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
                    }

                    // Restore selected branches
                    val conversation = chatDao.getConversation(id)
                    if (conversation?.selectedBranchesJson != null) {
                        try {
                            val map = Json.decodeFromString<Map<String, String>>(conversation.selectedBranchesJson)
                            val decodedMap = map.mapKeys { if (it.key == "null") null else it.key }
                            _selectedChildren.value = decodedMap
                        } catch (e: Exception) {
                            _selectedChildren.value = emptyMap()
                        }
                    } else {
                        _selectedChildren.value = emptyMap()
                    }

                    chatDao.getMessagesForConversation(id).collect { entities ->
                        val mapped = entities.map {
                            ChatMessage(
                                id = it.id,
                                parentId = it.parentId,
                                text = SearchResultFormatter.format(it.text, appContext),
                                images = it.images,
                                thoughts = it.thoughts,
                                tokenCount = it.tokenCount,
                                status = it.status,
                                participant = it.participant,
                                timestamp = it.timestamp,
                                thoughtTimeMs = it.thoughtTimeMs,
                                modelName = it.modelName,
                                segments = it.toolCallJson?.let { json ->
                                    try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null }
                                },
                                toolCall = it.toolCallJson?.let { json ->
                                    try {
                                        val segs = Json.decodeFromString<List<MessageSegment>>(json)
                                        segs.lastOrNull { s -> s.type == "tool" }?.let { s ->
                                            val rawResult = s.toolResult ?: ""
                                            ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", SearchResultFormatter.format(rawResult, appContext))
                                        }
                                    } catch (_: Exception) { null }
                                }
                            )
                        }
                        // Backfill toolCall for old result_ messages persisted without toolCallJson.
                        // They inherit the parent tool_ message's ToolCallData so the provider can
                        // format them as proper "tool" role messages with matching tool_call_id.
                        _allMessages.value = mapped.map { msg ->
                            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                val parentTool = mapped.find { it.id == msg.parentId }
                                if (parentTool != null && parentTool.toolCall != null) {
                                    msg.copy(toolCall = parentTool.toolCall)
                                } else msg
                            } else msg
                        }
                    }
                } else {
                    _allMessages.value = emptyList()
                    _selectedChildren.value = emptyMap()
                    messageHeights.clear()
                }
                // Prune stale height entries for messages no longer in the conversation
                if (_allMessages.value.isNotEmpty()) {
                    val currentIds = _allMessages.value.map { it.id }.toSet()
                    messageHeights.keys.retainAll { it in currentIds }
                }
            }
        }
        
        viewModelScope.launch {
            _selectedChildren.collect { childrenMap ->
                val id = _currentConversationId.value
                if (id != null) {
                    val conversation = chatDao.getConversation(id)
                    if (conversation != null) {
                        val stringKeyMap = childrenMap.mapKeys { it.key ?: "null" }
                        val json = Json.encodeToString(stringKeyMap)
                        if (conversation.selectedBranchesJson != json) {
                            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = json))
                        }
                    }
                }
            }
        }
    }

    // Settings logic
        fun setSelectedModel(model: String) {
            viewModelScope.launch { settingsManager.saveSelectedModel(model) }
        }
    
    fun setEnabledModels(models: Set<String>) { 
        viewModelScope.launch { 
            settingsManager.saveEnabledModels(models) 
            if (!models.contains(selectedModel.value)) {
                settingsManager.saveSelectedModel(models.firstOrNull() ?: "")
            }
        } 
    }

    fun updateModelAlias(model: String, alias: String) {
        viewModelScope.launch {
            val currentAliases = modelAliases.value.toMutableMap()
            if (alias.isBlank()) {
                currentAliases.remove(model)
            } else {
                currentAliases[model] = alias
            }
            settingsManager.saveModelAliases(currentAliases)
        }
    }

    fun addApiKey(name: String, key: String, provider: String) {
        viewModelScope.launch {
            val entry = ApiKeyEntry(name = name, key = key, provider = provider)
            val newList = apiKeys.value + entry
            settingsManager.saveApiKeys(newList)
            settingsManager.setActiveApiKeyId(provider, entry.id)
        }
    }
    fun deleteApiKey(id: String) {
        viewModelScope.launch {
            val entry = apiKeys.value.find { it.id == id } ?: return@launch
            val provider = entry.provider
            val newList = apiKeys.value.filter { it.id != id }
            settingsManager.saveApiKeys(newList)
            if (activeApiKeyIds.value[provider] == id) {
                settingsManager.setActiveApiKeyId(provider, null)
            }
        }
    }
    fun updateApiKey(id: String, name: String, key: String) {
        viewModelScope.launch {
            val newList = apiKeys.value.map { if (it.id == id) it.copy(name = name, key = key) else it }
            settingsManager.saveApiKeys(newList)
        }
    }
    fun setActiveApiKey(provider: String, id: String) { viewModelScope.launch { settingsManager.setActiveApiKeyId(provider, id) } }

    fun addSystemPrompt(title: String, content: String) {
        viewModelScope.launch {
            val newList = systemPrompts.value + SystemPromptEntry(title = title, content = content)
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == null) settingsManager.setActiveSystemPromptId(newList.last().id)
        }
    }
    fun deleteSystemPrompt(id: String) {
        viewModelScope.launch {
            val newList = systemPrompts.value.filter { it.id != id }
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == id) settingsManager.setActiveSystemPromptId(newList.firstOrNull()?.id)
        }
    }
    fun updateSystemPrompt(id: String, title: String, content: String) {
        viewModelScope.launch {
            val newList = systemPrompts.value.map { if (it.id == id) it.copy(title = title, content = content) else it }
            settingsManager.saveSystemPrompts(newList)
        }
    }
    fun setActiveSystemPrompt(id: String) { viewModelScope.launch { settingsManager.setActiveSystemPromptId(id) } }
    fun setMaxContextWindow(window: Int) { viewModelScope.launch { settingsManager.saveMaxContextWindow(window) } }
    fun setVisualizeContextRollout(enabled: Boolean) { viewModelScope.launch { settingsManager.saveVisualizeContextRollout(enabled) } }
    fun setCodeExecutionEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveCodeExecutionEnabled(enabled) } }
    fun setGoogleSearchEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveGoogleSearchEnabled(enabled) } }
    fun setThinkingEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveThinkingEnabled(enabled) } }
    fun setProviderBaseUrl(provider: String, url: String) { viewModelScope.launch { settingsManager.saveProviderBaseUrl(provider, url) } }
    fun setTitleGenerationEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveTitleGenerationEnabled(enabled) } }
    fun setTitleGenerationModel(model: String?) { viewModelScope.launch { settingsManager.saveTitleGenerationModel(model) } }
    fun setAccessPastConversations(enabled: Boolean) { viewModelScope.launch { settingsManager.saveAccessPastConversations(enabled) } }
    fun setAccessSavedMemories(enabled: Boolean) { viewModelScope.launch { settingsManager.saveAccessSavedMemories(enabled) } }
    fun setAccessActiveMemory(enabled: Boolean) { viewModelScope.launch { settingsManager.saveAccessActiveMemory(enabled) } }
    fun setRagSearchEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveRagSearchEnabled(enabled) } }
    fun setModelSearchMethod(method: String) { viewModelScope.launch { settingsManager.saveModelSearchMethod(method) } }
    fun setManualSearchMethod(method: String) { viewModelScope.launch { settingsManager.saveManualSearchMethod(method) } }
    fun addEmbeddingModel(config: EmbeddingModelConfig) {
        viewModelScope.launch {
            val models = embeddingModels.value.toMutableList()
            models.add(config)
            settingsManager.saveEmbeddingModels(models)
        }
    }
    fun deleteEmbeddingModel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.deleteEmbeddingsByModel(id)
            val models = embeddingModels.value.filter { it.id != id }
            settingsManager.saveEmbeddingModels(models)
            if (activeEmbeddingModelId.value == id && models.isNotEmpty()) {
                settingsManager.setActiveEmbeddingModelId(models.first().id)
            }
        }
    }
    fun setActiveEmbeddingModel(id: String) {
        viewModelScope.launch {
            settingsManager.setActiveEmbeddingModelId(id)
            val model = embeddingModels.value.find { it.id == id }
            if (model != null && !model.cached) {
                emitSnackbar("Embedding model \"${model.name}\" is not cached.", "Cache Now") {
                    cacheMessagesForModel(model.id)
                }
            }
        }
    }
    fun cacheMessagesForModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val model = embeddingModels.value.find { it.id == modelId } ?: return@launch
            val messages = chatDao.getAllMessagesForIndexing()
            for ((index, msg) in messages.withIndex()) {
                val text = msg.text.take(8000)
                if (text.isBlank()) continue
                val embedding: FloatArray? = if (model.type == EmbeddingModelType.LOCAL) {
                    if (com.newoether.agora.api.LocalEmbeddingEngine.isModelReady(model.localFilePath))
                        com.newoether.agora.api.LocalEmbeddingEngine.computeEmbedding(text, model.localFilePath)
                    else null
                } else {
                    val apiKey = resolveEmbeddingApiKey() ?: return@launch
                    val baseUrl = model.remoteBaseUrl.ifBlank { resolveEmbeddingBaseUrl() }
                    EmbeddingClient.computeEmbedding(text, apiKey, model.remoteModelName, baseUrl)
                }
                if (embedding != null) {
                    chatDao.upsertEmbedding(EmbeddingEntity(
                        messageId = msg.id,
                        modelId = modelId,
                        embedding = EmbeddingIndexer.floatsToBytes(embedding),
                        chunkText = text.take(500),
                        dimension = embedding.size
                    ))
                }
            }
            settingsManager.markModelCached(modelId)
        }
    }

    suspend fun semanticSearch(query: String, limit: Int = 20): List<MessageEntity> {
        val activeModel = activeEmbeddingModel.value ?: return emptyList()
        val queryEmbedding = resolveEmbedding(query) ?: return emptyList()

        val all = chatDao.getEmbeddingsByModel(activeModel.id)
        if (all.isEmpty()) return emptyList()

        val scored = all.map {
            val stored = EmbeddingIndexer.bytesToFloats(it.embedding)
            it to EmbeddingIndexer.cosineSimilarity(queryEmbedding, stored)
        }.filter { it.second > 0.3f }
         .sortedByDescending { it.second }
         .take(limit)

        if (scored.isEmpty()) return emptyList()
        return chatDao.getMessagesByIds(scored.map { it.first.messageId })
    }

    private suspend fun resolveEmbedding(text: String): FloatArray? {
        val model = activeEmbeddingModel.value ?: return null
        return if (model.type == EmbeddingModelType.LOCAL) {
            if (!LocalEmbeddingEngine.isModelReady(model.localFilePath)) return null
            LocalEmbeddingEngine.computeEmbedding(text, model.localFilePath)
        } else {
            val apiKey = resolveEmbeddingApiKey() ?: return null
            val baseUrl = model.remoteBaseUrl.ifBlank { resolveEmbeddingBaseUrl() }
            EmbeddingClient.computeEmbedding(text, apiKey, model.remoteModelName, baseUrl)
        }
    }

    private fun resolveEmbeddingApiKey(): String? {
        val keys = apiKeys.value
        for (entry in keys) {
            if (entry.provider == "OpenAI" || entry.provider == "DeepSeek" || entry.provider == "Qwen" || entry.provider == "Open Router") {
                return entry.key
            }
        }
        return keys.firstOrNull()?.key
    }

    private fun resolveEmbeddingBaseUrl(): String {
        return providerBaseUrls.value["OpenAI"] ?: "https://api.openai.com/v1"
    }

    fun indexMessageForRag(messageId: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val model = activeEmbeddingModel.value ?: return@launch
            val embedding: FloatArray? = if (model.type == EmbeddingModelType.LOCAL) {
                if (!LocalEmbeddingEngine.isModelReady(model.localFilePath)) return@launch
                LocalEmbeddingEngine.computeEmbedding(text, model.localFilePath)
            } else {
                val apiKey = resolveEmbeddingApiKey() ?: return@launch
                val baseUrl = model.remoteBaseUrl.ifBlank { resolveEmbeddingBaseUrl() }
                EmbeddingClient.computeEmbedding(text, apiKey, model.remoteModelName, baseUrl)
            }
            if (embedding != null) {
                chatDao.upsertEmbedding(EmbeddingEntity(
                    messageId = messageId,
                    modelId = model.id,
                    embedding = EmbeddingIndexer.floatsToBytes(embedding),
                    chunkText = text.take(500),
                    dimension = embedding.size
                ))
            }
        }
    }
    suspend fun searchMessages(query: String, limit: Int = 20) = chatDao.searchMessages(query, limit)
    fun setAppLanguage(language: String) { viewModelScope.launch { settingsManager.saveAppLanguage(language) } }
    fun setWebSearchEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveWebSearchEnabled(enabled) } }
    fun setWebSearchProvider(provider: String) { viewModelScope.launch { settingsManager.saveWebSearchProvider(provider) } }
    fun setWebSearchApiKey(apiKey: String) { viewModelScope.launch { settingsManager.saveWebSearchApiKey(apiKey) } }
    fun setWebSearchBaseUrl(url: String) { viewModelScope.launch { settingsManager.saveWebSearchBaseUrl(url) } }

    fun createNewChat() {
        switchingJob?.cancel()
        stopGeneration()
        if (!_isNewChatMode.value) {
            _pendingSystemPromptId.value = null
        }
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            clearMessageHeights()
            _currentConversationId.value = null
            _currentActiveModel.value = null
            _allMessages.value = emptyList()
            _selectedChildren.value = emptyMap()
            _branchSwitchTrigger.value = null
            _isSwitching.value = false
            _isTransitioningToNewChat.value = false
        }
    }

    fun selectConversation(id: String) {
        if (_currentConversationId.value == id && !_isNewChatMode.value) return

        switchingJob?.cancel()
        _isTransitioningToNewChat.value = false
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            _isNewChatMode.value = false
            clearMessageHeights()
            _branchSwitchTrigger.value = null
            _currentConversationId.value = id
            val conversation = chatDao.getConversation(id)
            _currentActiveModel.value = conversation?.modelId
            triggerScrollToMessage()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            val existing = chatDao.getConversation(id)
            if (existing != null) {
                chatDao.upsertConversation(existing.copy(title = newTitle))
            }
        }
    }

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            _snackbarMessage.emit(SnackbarEvent(appContext.getString(R.string.snackbar_generating_title)))
            val conversation = chatDao.getConversation(conversationId) ?: return@launch
            val path = messages.value
            val firstUserMsg = path.firstOrNull { it.participant == Participant.USER } ?: return@launch
            val firstModelMsg = path
                .filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .firstOrNull()

            val titleModelId = titleGenerationModel.value
            val modelIdWithPrefix = if (!titleModelId.isNullOrBlank()) titleModelId else (conversation.modelId ?: firstModelMsg?.modelName ?: selectedModel.value)
            val providerName = getProviderForModel(modelIdWithPrefix)
            val modelId = modelIdWithPrefix.substringAfter(":")
            val activeKeyId = activeApiKeyIds.value[providerName]
            val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
            if (activeKey.isBlank() && providerName != "Ollama") return@launch

            val summaryText = if (firstModelMsg != null) {
                "User: ${firstUserMsg.text}\nAssistant: ${firstModelMsg.text.take(500)}"
            } else {
                firstUserMsg.text
            }

            val titlePrompt = listOf(
                ChatMessage(
                    text = "Generate a short title (5 words maximum) for this conversation:\n\n$summaryText\n\nRespond with ONLY the title text, no quotes, no punctuation, no explanation.",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS
                )
            )

            val provider = getProviderInstance(providerName)
            val config = ProviderConfig(
                apiKey = activeKey,
                modelId = modelId,
                systemPrompt = "You are a title generator. Output only a short title in the same language as the conversation.",
                maxContextWindow = 1,
                thinkingEnabled = false,
                baseUrl = providerBaseUrls.value[providerName]
            )

            var title = ""
            try {
                provider.generateResponse(titlePrompt, config).collect { event ->
                    if (event is StreamEvent.TextChunk) title += event.text
                    else if (event is StreamEvent.Error) Log.e("AgoraVM", "Title generation error: ${event.message}")
                }
            } catch (e: Exception) {
                Log.e("AgoraVM", "Title generation failed for provider=$providerName model=$modelId", e)
                return@launch
            }

            title = title.trim().replace("\n", " ").take(60)
            if (title.isNotBlank()) {
                renameConversation(conversationId, title)
                _snackbarMessage.emit(SnackbarEvent(appContext.getString(R.string.snackbar_title_generated)))
            } else {
                _snackbarMessage.emit(SnackbarEvent(appContext.getString(R.string.snackbar_title_error)))
            }
        }
    }

    fun setConversationSystemPrompt(id: String, promptId: String?) {
        viewModelScope.launch {
            val existing = chatDao.getConversation(id)
            if (existing != null) {
                chatDao.upsertConversation(existing.copy(systemPromptId = promptId))
            }
        }
    }

    fun setActiveModel(model: String) {
        _currentActiveModel.value = model
        _currentConversationId.value?.let { id ->
            viewModelScope.launch {
                val existing = chatDao.getConversation(id)
                if (existing != null) {
                    chatDao.upsertConversation(existing.copy(modelId = model))
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            stopGeneration()
        }
        viewModelScope.launch {
            chatDao.deleteConversation(id)
            if (_currentConversationId.value == id) createNewChat()
        }
    }
    
    fun stopGeneration() {
        generationJob?.cancel()
        _isLoading.value = false
        val stoppedMsg = _streamingMessage.value?.copy(status = MessageStatus.STOPPED)
        _streamingMessage.value = stoppedMsg
        // Also update _allMessages entity immediately so UI reacts without flowOn delay
        if (stoppedMsg != null) {
            _allMessages.update { it.map { m ->
                if (m.id == stoppedMsg.id) stoppedMsg else m
            } }
        } else {
            // _streamingMessage was null — find the in-flight model message directly
            _allMessages.update { it.map { m ->
                if (m.participant == Participant.MODEL &&
                    (m.status == MessageStatus.SENDING || m.status == MessageStatus.THINKING)
                ) m.copy(status = MessageStatus.STOPPED) else m
            } }
        }
        _generatingInConversationId.value = null
        AgoraForegroundService.stop(getApplication())
    }

    fun regenerate(messageId: String) {
        val currentId = _currentConversationId.value ?: return
        val modelId = currentActiveModel.value
        val providerName = getProviderForModel(modelId)
        val activeKeyId = activeApiKeyIds.value[providerName]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        // Ollama doesn't need a key to be "ready"
        if (activeKey.isBlank() && providerName != "Ollama") return

        stopGeneration()

        // Compute IDs and set placeholder on the calling thread before launching IO work,
        // so the combine function never sees _streamingMessage=null while the error is in _allMessages.
        val messageToRegenerate = _allMessages.value.find { it.id == messageId } ?: return
        val parentId = messageToRegenerate.parentId ?: return
        val isErrorOrStopped = messageToRegenerate.status == MessageStatus.ERROR || messageToRegenerate.status == MessageStatus.STOPPED
        val hasChildren = _allMessages.value.any { it.parentId == messageId }
        val isLatest = !hasChildren
        val modelMessageId = if (isErrorOrStopped && isLatest) messageId else UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis() + 1

        // Insert placeholder into _allMessages and update _selectedChildren on the calling
        // thread BEFORE setting _streamingMessage. This ensures the combine function sees a
        // consistent state where the new ID is both present and selected, avoiding a frame
        // where two model messages appear in the path.
        val placeholder = ChatMessage(
            id = modelMessageId, parentId = parentId, text = "", participant = Participant.MODEL,
            status = MessageStatus.SENDING, timestamp = startTime
        )
        _allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder }
        val newMap = _selectedChildren.value.toMutableMap()
        newMap[parentId] = modelMessageId
        _selectedChildren.value = newMap

        _streamingMessage.value = placeholder
        _isLoading.value = true

        generationJob = generationScope.launch {
            val userMessage = _allMessages.value.find { it.id == parentId } ?: return@launch

            if (isErrorOrStopped && isLatest) {
                chatDao.upsertMessage(MessageEntity(
                    id = modelMessageId, conversationId = currentId, parentId = parentId,
                    text = "", thoughts = null, thoughtTitle = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                    modelName = currentActiveModel.value, toolCallJson = null
                ))
            } else {
                chatDao.upsertMessage(MessageEntity(
                    id = modelMessageId, conversationId = currentId, parentId = parentId,
                    text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                    modelName = currentActiveModel.value
                ))
            }
            val effectiveSystemPrompt = buildEffectiveSystemPrompt(currentId)
            val config = GenerationConfig(
                providerName = providerName,
                modelId = modelId.substringAfter(":"),
                apiKey = activeKey,
                effectiveSystemPrompt = effectiveSystemPrompt,
                maxContextWindow = maxContextWindow.value,
                codeExecutionEnabled = codeExecutionEnabled.value,
                googleSearchEnabled = googleSearchEnabled.value,
                thinkingEnabled = thinkingEnabled.value,
                baseUrl = providerBaseUrls.value[providerName]
            )

            generationManager.accessSavedMemories = accessSavedMemories.value
            generationManager.accessActiveMemory = accessActiveMemory.value
            generationManager.modelSearchMethod = modelSearchMethod.value
            generationManager.activeEmbeddingConfig = activeEmbeddingModel.value
            generationManager.embeddingApiKey = resolveEmbeddingApiKey() ?: ""
            generationManager.accessPastConversations = accessPastConversations.value
            generationManager.webSearchEnabled = webSearchEnabled.value
            generationManager.webSearchApiKey = webSearchApiKey.value
            generationManager.webSearchProvider = webSearchProvider.value
            generationManager.webSearchBaseUrl = webSearchBaseUrl.value
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = true,
                replaceMessageId = messageId,
                modelName = currentActiveModel.value,
                config = config,
                generationJob = generationJob,
                onStreamUpdate = { _streamingMessage.value = it },
                onLoadingChange = { _isLoading.value = it },
                onGeneratingIdChange = { _generatingInConversationId.value = it },
                onStreamClear = { _streamingMessage.value = null }
            )
        }
    }

    fun switchBranch(parentId: String?, direction: Int) {
        if (_isLoading.value) return
        val siblings = _allMessages.value.filter { it.parentId == parentId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }.sortedBy { it.timestamp }
        if (siblings.size < 2) return
        val currentId = _selectedChildren.value[parentId] ?: siblings.last().id
        val currentIndex = siblings.indexOfFirst { it.id == currentId }
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        
        switchingJob?.cancel()
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            val newMap = _selectedChildren.value.toMutableMap()
            val targetMessage = siblings[newIndex]
            newMap[parentId] = targetMessage.id
            _selectedChildren.value = newMap
            
            _branchSwitchTrigger.value = targetMessage.id
            triggerScrollToMessage(targetMessage.id)
        }
    }

    fun editMessage(messageId: String, newText: String) {
        val currentId = _currentConversationId.value ?: return
        val modelId = currentActiveModel.value
        val providerName = getProviderForModel(modelId)
        val activeKeyId = activeApiKeyIds.value[providerName]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        if (activeKey.isBlank() && providerName != "Ollama") return

        stopGeneration()
        generationJob = generationScope.launch {
            val messageToEdit = _allMessages.value.find { it.id == messageId } ?: return@launch
            val newUserMessageId = UUID.randomUUID().toString()
            chatDao.upsertMessage(MessageEntity(
                id = newUserMessageId, conversationId = currentId, parentId = messageToEdit.parentId,
                text = newText, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val newMap = _selectedChildren.value.toMutableMap()
            newMap[messageToEdit.parentId] = newUserMessageId
            _selectedChildren.value = newMap
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            chatDao.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = newUserMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            val effectiveSystemPrompt = buildEffectiveSystemPrompt(currentId)
            val config = GenerationConfig(
                providerName = providerName,
                modelId = modelId.substringAfter(":"),
                apiKey = activeKey,
                effectiveSystemPrompt = effectiveSystemPrompt,
                maxContextWindow = maxContextWindow.value,
                codeExecutionEnabled = codeExecutionEnabled.value,
                googleSearchEnabled = googleSearchEnabled.value,
                thinkingEnabled = thinkingEnabled.value,
                baseUrl = providerBaseUrls.value[providerName]
            )

            generationManager.accessSavedMemories = accessSavedMemories.value
            generationManager.accessActiveMemory = accessActiveMemory.value
            generationManager.modelSearchMethod = modelSearchMethod.value
            generationManager.activeEmbeddingConfig = activeEmbeddingModel.value
            generationManager.embeddingApiKey = resolveEmbeddingApiKey() ?: ""
            generationManager.accessPastConversations = accessPastConversations.value
            generationManager.webSearchEnabled = webSearchEnabled.value
            generationManager.webSearchApiKey = webSearchApiKey.value
            generationManager.webSearchProvider = webSearchProvider.value
            generationManager.webSearchBaseUrl = webSearchBaseUrl.value
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = currentActiveModel.value,
                config = config,
                generationJob = generationJob,
                onStreamUpdate = { _streamingMessage.value = it },
                onLoadingChange = { _isLoading.value = it },
                onGeneratingIdChange = { _generatingInConversationId.value = it },
                onStreamClear = { _streamingMessage.value = null }
            )
        }
    }

    private suspend fun buildEffectiveSystemPrompt(currentId: String): String? {
        val conversation = chatDao.getConversation(currentId)
        val targetPromptId = conversation?.systemPromptId ?: activeSystemPromptId.value
        val activePrompt = systemPrompts.value.find { it.id == targetPromptId }?.content
        val activeMemory = memoryManager.getActiveMemory()
        val includeActiveMemory = accessActiveMemory.value
        return buildString {
            if (includeActiveMemory && activeMemory.isNotBlank()) {
                append("[Active Memory]\n")
                append(activeMemory)
                append("\n\n")
            }
            if (!activePrompt.isNullOrBlank()) {
                append(activePrompt)
            }
        }.ifBlank { null }
    }

    private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) ?: uri.lastPathSegment ?: "unknown"
                    else uri.lastPathSegment ?: "unknown"
                } else uri.lastPathSegment ?: "unknown"
            } ?: (uri.lastPathSegment ?: "unknown")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }

    fun sendMessage(text: String, images: List<String> = emptyList()) {
        val modelId = currentActiveModel.value
        val providerName = getProviderForModel(modelId)
        val activeKeyId = activeApiKeyIds.value[providerName]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        stopGeneration()

        generationJob = generationScope.launch {
            val app = getApplication<Application>()
            val mediaUris = mutableListOf<String>()
            var fileContent = ""
            if (images.isNotEmpty()) {
                for (uri in images) {
                    val mimeType = try { app.contentResolver.getType(android.net.Uri.parse(uri)) } catch (_: Exception) { null }
                    if (mimeType != null && !mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
                        mediaUris.add(uri)
                        try {
                            val isText = mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml"
                            if (isText) {
                                app.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                                    val content = stream.bufferedReader().readText().take(500_000)
                                    if (content.isNotBlank()) {
                                        val fileName = getFileName(app, android.net.Uri.parse(uri))
                                        fileContent += "\n\n--- File: $fileName ---\n$content"
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    } else {
                        mediaUris.add(uri)
                    }
                }
            }
            val finalText = if (fileContent.isNotBlank()) text + fileContent else text
            val processedImages = if (mediaUris.isNotEmpty()) generationManager.processImages(mediaUris) else emptyList()
            var currentId = _currentConversationId.value
            val wasNewChat = _isNewChatMode.value
            if (wasNewChat) {
                val newId = UUID.randomUUID().toString()
                chatDao.upsertConversation(ChatEntity(id = newId, title = appContext.getString(R.string.new_chat), modelId = currentActiveModel.value, systemPromptId = _pendingSystemPromptId.value))
                _currentConversationId.value = newId
                _isNewChatMode.value = false
                currentId = newId
            }
            if (currentId == null) {
                val newId = UUID.randomUUID().toString()
                chatDao.upsertConversation(ChatEntity(id = newId, title = appContext.getString(R.string.new_chat), modelId = currentActiveModel.value, systemPromptId = _pendingSystemPromptId.value))
                _currentConversationId.value = newId
                _isNewChatMode.value = false
                currentId = newId
            }
            val currentPath = messages.value
            val lastMessageId = currentPath.lastOrNull()?.id
            val userMessageId = UUID.randomUUID().toString()
            chatDao.upsertMessage(MessageEntity(
                id = userMessageId, conversationId = currentId, parentId = lastMessageId,
                text = finalText, images = processedImages, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            chatDao.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = userMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            triggerScrollToMessage(userMessageId)

            val effectiveSystemPrompt = buildEffectiveSystemPrompt(currentId)
            val config = GenerationConfig(
                providerName = providerName,
                modelId = modelId.substringAfter(":"),
                apiKey = activeKey,
                effectiveSystemPrompt = effectiveSystemPrompt,
                maxContextWindow = maxContextWindow.value,
                codeExecutionEnabled = codeExecutionEnabled.value,
                googleSearchEnabled = googleSearchEnabled.value,
                thinkingEnabled = thinkingEnabled.value,
                baseUrl = providerBaseUrls.value[providerName]
            )

            generationManager.accessSavedMemories = accessSavedMemories.value
            generationManager.accessActiveMemory = accessActiveMemory.value
            generationManager.modelSearchMethod = modelSearchMethod.value
            generationManager.activeEmbeddingConfig = activeEmbeddingModel.value
            generationManager.embeddingApiKey = resolveEmbeddingApiKey() ?: ""
            generationManager.accessPastConversations = accessPastConversations.value
            generationManager.webSearchEnabled = webSearchEnabled.value
            generationManager.webSearchApiKey = webSearchApiKey.value
            generationManager.webSearchProvider = webSearchProvider.value
            generationManager.webSearchBaseUrl = webSearchBaseUrl.value
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = currentActiveModel.value,
                config = config,
                generationJob = generationJob,
                onStreamUpdate = { _streamingMessage.value = it },
                onLoadingChange = { _isLoading.value = it },
                onGeneratingIdChange = { _generatingInConversationId.value = it },
                onStreamClear = { _streamingMessage.value = null }
            )

            val lastMsg = _allMessages.value.find { it.id == modelMessageId }
            if (wasNewChat && titleGenerationEnabled.value && generationJob?.isActive == true && lastMsg?.status != MessageStatus.ERROR) {
                generateTitle(currentId)
            }
        }
    }

    fun fetchAvailableModels() {
        viewModelScope.launch {
            _isSyncingModels.value = true
            val successProviders = mutableListOf<String>()
            val failedProviders = mutableListOf<String>()
            var skippedCount = 0

            providers.forEach { (name, providerInstance) ->
                val activeKeyId = activeApiKeyIds.value[name]
                val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
                val currentBaseUrl = providerBaseUrls.value[name]
                
                val isConfigured = if (name == "Ollama") {
                    !currentBaseUrl.isNullOrBlank()
                } else {
                    activeKey.isNotBlank()
                }

                if (!isConfigured) {
                    skippedCount++
                    return@forEach
                }
                
                try {
                    val rawModels = providerInstance.fetchModels(activeKey, currentBaseUrl)
                    if (rawModels.isNotEmpty()) {
                        val prefixedModels = rawModels.map { "$name:${it.removePrefix("models/")}" }
                        settingsManager.saveAvailableModels(name, prefixedModels)
                        successProviders.add(name)
                    } else {
                        failedProviders.add(name)
                    }
                } catch (e: Exception) {
                    failedProviders.add(name)
                }
            }
            
            val allFetchedModels = settingsManager.availableModels.first().values.flatten().toSet()
            val newEnabled = enabledModels.value.intersect(allFetchedModels)
            settingsManager.saveEnabledModels(newEnabled)
            
            _isSyncingModels.value = false

            // Construct result message
            val app = getApplication<Application>()
            val message = when {
                successProviders.isNotEmpty() && failedProviders.isEmpty() ->
                    app.getString(R.string.sync_success_providers, successProviders.size)
                successProviders.isNotEmpty() && failedProviders.isNotEmpty() ->
                    app.getString(R.string.sync_partial, successProviders.joinToString(), failedProviders.joinToString())
                successProviders.isEmpty() && failedProviders.isNotEmpty() ->
                    app.getString(R.string.sync_failed_providers, failedProviders.joinToString())
                else -> if (skippedCount > 0) app.getString(R.string.sync_no_providers) else app.getString(R.string.sync_completed)
            }
            _snackbarMessage.emit(SnackbarEvent(message))
        }
    }
}
