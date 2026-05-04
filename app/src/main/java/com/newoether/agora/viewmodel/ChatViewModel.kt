package com.newoether.agora.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.api.*
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatConversation
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
    val memoryManager: MemoryManager
) : AndroidViewModel(application) {

    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generationJob: Job? = null
    private var generationId = 0

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

    private fun getActiveProvider(): LlmProvider {
        val modelId = currentActiveModel.value
        val providerName = getProviderForModel(modelId)
        return providers[providerName] ?: GeminiProvider()
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

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

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
            var selectedMessage = siblings.find { it.id == selectedId } ?: siblings.last()
            
            if (streaming != null && selectedMessage.id == streaming.id) {
                selectedMessage = streaming
            }
            
            // Skip synthetic tool call/result messages (hidden from UI, API context only)
            // Identified by ID prefix: "tool_" for tool calls, "result_" for tool results
            val isSynthetic = selectedMessage.id.startsWith("tool_") || selectedMessage.id.startsWith("result_")
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
                        _allMessages.value = entities.map { 
                            ChatMessage(
                                id = it.id, 
                                parentId = it.parentId,
                                text = it.text, 
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
                                toolCall = null
                            )
                        }
                    }
                } else {
                    _allMessages.value = emptyList()
                    _selectedChildren.value = emptyMap()
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
                chatDao.upsertConversation(existing.copy(title = newTitle, lastUpdated = System.currentTimeMillis()))
            }
        }
    }

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            val conversation = chatDao.getConversation(conversationId) ?: return@launch
            val msgs = chatDao.getMessagesForConversation(conversationId).first()
            val firstUserMsg = msgs.firstOrNull { it.participant == Participant.USER } ?: return@launch
            // Find the first successful model response after the user message
            val firstModelMsg = msgs
                .filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .minByOrNull { it.timestamp }

            val titleModelId = titleGenerationModel.value
            val modelIdWithPrefix = if (titleModelId != null) titleModelId else (conversation.modelId ?: selectedModel.value)
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
                }
            } catch (_: Exception) { return@launch }

            title = title.trim().replace("\n", " ").take(60)
            if (title.isNotBlank()) {
                renameConversation(conversationId, title)
            }
        }
    }

    fun setConversationSystemPrompt(id: String, promptId: String?) {
        viewModelScope.launch {
            val existing = chatDao.getConversation(id)
            if (existing != null) {
                chatDao.upsertConversation(existing.copy(systemPromptId = promptId, lastUpdated = System.currentTimeMillis()))
            }
        }
    }

    fun setActiveModel(model: String) {
        _currentActiveModel.value = model
        _currentConversationId.value?.let { id ->
            viewModelScope.launch {
                val existing = chatDao.getConversation(id)
                if (existing != null) {
                    chatDao.upsertConversation(existing.copy(modelId = model, lastUpdated = System.currentTimeMillis()))
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
            _allMessages.value = _allMessages.value.map {
                if (it.id == stoppedMsg.id) stoppedMsg else it
            }
        } else {
            // _streamingMessage was null — find the in-flight model message directly
            _allMessages.value = _allMessages.value.map {
                if (it.participant == Participant.MODEL &&
                    (it.status == MessageStatus.SENDING || it.status == MessageStatus.THINKING)
                ) it.copy(status = MessageStatus.STOPPED) else it
            }
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
        val hasChildren = _allMessages.value.any { it.parentId == messageId && !it.id.startsWith("tool_") && !it.id.startsWith("result_") }
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
        _allMessages.value = _allMessages.value.filter { it.id != modelMessageId } + placeholder
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
            generateResponse(currentId, userMessage.text, modelMessageId, startTime, isRegenerate = true, replaceMessageId = messageId)
        }
    }

    fun switchBranch(parentId: String?, direction: Int) {
        if (_isLoading.value) return
        val siblings = _allMessages.value.filter { it.parentId == parentId && !it.id.startsWith("tool_") && !it.id.startsWith("result_") }.sortedBy { it.timestamp }
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
            generateResponse(currentId, newText, modelMessageId, startTime)
        }
    }

    private suspend fun processImages(uris: List<String>): List<String> {
        return withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            uris.mapNotNull { uriString ->
                try {
                    val uri = android.net.Uri.parse(uriString)
                    app.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                        
                        var scale = 1
                        while (options.outWidth / scale / 2 >= 1024 && options.outHeight / scale / 2 >= 1024) {
                            scale *= 2
                        }
                        
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                        app.contentResolver.openInputStream(uri)?.use { stream2 ->
                            val bitmap = android.graphics.BitmapFactory.decodeStream(stream2, null, decodeOptions)
                            if (bitmap != null) {
                                val file = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                                file.outputStream().use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                file.absolutePath
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

            fun sendMessage(text: String, images: List<String> = emptyList()) {
                val modelId = currentActiveModel.value
                val providerName = getProviderForModel(modelId)
                val activeKeyId = activeApiKeyIds.value[providerName]
                val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
                    stopGeneration()
    
        generationJob = generationScope.launch {
            val processedImages = if (images.isNotEmpty()) processImages(images) else emptyList()
            var currentId = _currentConversationId.value
            val wasNewChat = _isNewChatMode.value
            if (wasNewChat) {
                val newId = UUID.randomUUID().toString()
                chatDao.upsertConversation(ChatEntity(id = newId, title = "New Chat", modelId = currentActiveModel.value, systemPromptId = _pendingSystemPromptId.value))
                _currentConversationId.value = newId
                _isNewChatMode.value = false
                currentId = newId
            }
            val currentPath = messages.value
            val lastMessageId = currentPath.lastOrNull()?.id
            val userMessageId = UUID.randomUUID().toString()
            chatDao.upsertMessage(MessageEntity(
                id = userMessageId, conversationId = currentId!!, parentId = lastMessageId,
                text = text, images = processedImages, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            chatDao.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = userMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            triggerScrollToMessage(userMessageId)
            generateResponse(currentId, text, modelMessageId, startTime)
            // Auto-generate title after first successful response
            val lastMsg = _allMessages.value.find { it.id == modelMessageId }
            if (wasNewChat && titleGenerationEnabled.value && generationJob?.isActive == true && lastMsg?.status != MessageStatus.ERROR) {
                generateTitle(currentId)
            }
        }
    }

    private fun buildMemoryTools(): List<ToolDefinition> = listOf(
        ToolDefinition(function = ToolFunction(
            name = "list_memory_files",
            description = "List all files in the memory database.",
            parameters = ToolParameters(
                properties = emptyMap()
            )
        )),
        ToolDefinition(function = ToolFunction(
            name = "read_memory_file",
            description = "Read the content of a file from the memory database.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "The file name to read.")
                ),
                required = listOf("name")
            )
        )),
        ToolDefinition(function = ToolFunction(
            name = "create_memory_file",
            description = "Create a new file in the memory database with the given content.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "The file name to create (e.g., 'notes.md')."),
                    "content" to ToolProperty("string", "The markdown content for the file.")
                ),
                required = listOf("name", "content")
            )
        )),
        ToolDefinition(function = ToolFunction(
            name = "edit_memory_file",
            description = "Overwrite an existing file in the memory database.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "The file name to edit."),
                    "content" to ToolProperty("string", "The new markdown content.")
                ),
                required = listOf("name", "content")
            )
        )),
        ToolDefinition(function = ToolFunction(
            name = "delete_memory_file",
            description = "Delete a file from the memory database.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "The file name to delete.")
                ),
                required = listOf("name")
            )
        )),
        ToolDefinition(function = ToolFunction(
            name = "update_active_memory",
            description = "Update the active memory context. Use 'replace' to overwrite, 'append' to add to the end, or 'prepend' to add to the beginning.",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty("string", "The content to write."),
                    "mode" to ToolProperty("string", "One of: replace, append, prepend. Default is replace.")
                ),
                required = listOf("content")
            )
        ))
    )

    private fun executeTool(name: String, arguments: String): String {
        return try {
            val argsStr = arguments.ifBlank { "{}" }
            val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
            fun arg(key: String): String = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            when (name) {
                "list_memory_files" -> {
                    val files = memoryManager.listFiles()
                    if (files.isEmpty()) "No memory files found."
                    else "Memory files:\n${files.joinToString("\n") { "- $it" }}"
                }
                "read_memory_file" -> memoryManager.readFile(arg("name"))
                "create_memory_file" -> memoryManager.createFile(arg("name"), arg("content"))
                "edit_memory_file" -> memoryManager.editFile(arg("name"), arg("content"))
                "delete_memory_file" -> memoryManager.deleteFile(arg("name"))
                "update_active_memory" -> {
                    val mode = arg("mode").ifBlank { "replace" }
                    memoryManager.updateActiveMemory(arg("content"), mode)
                }
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    private fun buildLiveSegments(flushed: List<MessageSegment>, buf: StringBuilder, signature: String? = null): List<MessageSegment>? {
        val result = flushed.toMutableList()
        if (buf.isNotEmpty()) {
            result.add(MessageSegment(type = "thought", content = buf.toString(), signature = signature))
        }
        return result.ifEmpty { null }
    }

    private suspend fun generateResponse(currentId: String, text: String, modelMessageId: String, startTime: Long, isRegenerate: Boolean = false, replaceMessageId: String? = null) {
        val myGenerationId = ++generationId
        val modelIdWithPrefix = currentActiveModel.value
        val providerName = getProviderForModel(modelIdWithPrefix)
        val modelId = modelIdWithPrefix.substringAfter(":")
        
        val activeKeyId = activeApiKeyIds.value[providerName]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        _isLoading.value = true
        _generatingInConversationId.value = currentId
        if (_streamingMessage.value?.id != modelMessageId) {
            _streamingMessage.value = null
        }
        val app = getApplication<Application>()
        withContext(Dispatchers.Main) { AgoraForegroundService.start(app) }
        var totalText = ""
        var totalThoughts = ""
        var totalThoughtTitle: String? = null
        var totalTokenCount = 0
        var totalThoughtTimeMs: Long? = null
        var currentStatus = MessageStatus.SENDING
        val segments = mutableListOf<MessageSegment>()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        val placeholder = chatDao.getMessagesForConversation(currentId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId
        var toolPath = emptyList<ChatMessage>()

        try {
            val dbMessages = chatDao.getMessagesForConversation(currentId).first()
            val pathEntities = mutableListOf<MessageEntity>()
            var currId: String? = parentId
            while (currId != null) {
                val msg = dbMessages.find { it.id == currId } ?: break
                pathEntities.add(0, msg)
                currId = msg.parentId
            }
            val currentPath = pathEntities.map {
                val segs = it.toolCallJson?.let { json -> try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null } }
                val toolCall = segs?.find { s -> s.type == "tool" }?.let { s ->
                    ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", s.toolResult ?: "")
                }
                ChatMessage(id = it.id, parentId = it.parentId, text = it.text, images = it.images, thoughts = it.thoughts, thoughtTitle = it.thoughtTitle, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, thoughtTimeMs = it.thoughtTimeMs, segments = segs, toolCall = toolCall)
            }.filter { it.participant != Participant.ERROR }
                .let { path ->
                    if (isRegenerate) {
                        // Exclude old model response and all synthetic tool_/result_ messages
                        path.filterNot { it.id.startsWith("tool_") || it.id.startsWith("result_") }
                            .let { filtered ->
                                if (replaceMessageId != null) {
                                    val oldIdx = filtered.indexOfFirst { it.id == replaceMessageId }
                                    if (oldIdx >= 0) filtered.take(oldIdx) else filtered
                                } else filtered
                            }
                    } else path
                }
            
            val conversation = chatDao.getConversation(currentId)
            val targetPromptId = conversation?.systemPromptId ?: activeSystemPromptId.value
            val activePrompt = systemPrompts.value.find { it.id == targetPromptId }?.content
            
            // Merge active memory into system prompt
            val activeMemory = memoryManager.getActiveMemory()
            val effectiveSystemPrompt = buildString {
                if (activeMemory.isNotBlank()) {
                    append("[Active Memory]\n")
                    append(activeMemory)
                    append("\n\n")
                }
                if (!activePrompt.isNullOrBlank()) {
                    append(activePrompt)
                }
            }.ifBlank { null }

            val memoryTools = buildMemoryTools()
            val config = ProviderConfig(
                apiKey = activeKey,
                modelId = modelId,
                systemPrompt = effectiveSystemPrompt,
                maxContextWindow = maxContextWindow.value,
                codeExecutionEnabled = codeExecutionEnabled.value,
                googleSearchEnabled = googleSearchEnabled.value,
                thinkingEnabled = thinkingEnabled.value,
                baseUrl = providerBaseUrls.value[providerName],
                tools = memoryTools
            )

            var toolCallData: ToolCallData? = null

            getActiveProvider().generateResponse(currentPath, config).collect { event ->
                        when (event) {
                            is StreamEvent.TextChunk -> {
                                if (currentStatus == MessageStatus.THINKING) {
                                    totalThoughtTimeMs = System.currentTimeMillis() - startTime
                                    if (currentThoughtBuf.isNotEmpty()) {
                                        segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                                        currentThoughtBuf = StringBuilder()
                                        currentThoughtSignature = null
                                    }
                                    totalText += event.text.trimStart()
                                } else {
                                    totalText += event.text
                                }
                                currentStatus = MessageStatus.SENDING
                            }
                            is StreamEvent.ThoughtChunk -> {
                                if (totalText.isEmpty()) {
                                    currentStatus = MessageStatus.THINKING
                                    if (totalThoughtTimeMs == null) totalThoughtTimeMs = System.currentTimeMillis() - startTime
                                    if (totalThoughts.isEmpty()) totalThoughts = "Thinking..."
                                }
                                if (event.thought.isNotEmpty()) {
                                    currentThoughtBuf.append(event.thought)
                                    if (totalThoughts == "Thinking...") totalThoughts = event.thought
                                    else totalThoughts += event.thought
                                }
                                if (event.title != null) totalThoughtTitle = event.title
                                if (event.signature != null) currentThoughtSignature = event.signature
                            }
                            is StreamEvent.UsageUpdate -> {
                                if (event.tokenCount > 0) totalTokenCount = event.tokenCount
                                if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                                    currentStatus = MessageStatus.THINKING
                                    if (totalThoughtTimeMs == null) totalThoughtTimeMs = System.currentTimeMillis() - startTime
                                    if (totalThoughts.isEmpty()) totalThoughts = "Thinking..."
                                }
                            }
                            is StreamEvent.Error -> {
                                if (toolCallData == null) {
                                    totalText = event.message
                                    currentStatus = MessageStatus.ERROR
                                }
                            }
                            is StreamEvent.ToolCallRequest -> {
                                if (currentThoughtBuf.isNotEmpty()) {
                                    segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                                    currentThoughtBuf = StringBuilder()
                                    currentThoughtSignature = null
                                }
                                val result = executeTool(event.name, event.arguments)
                                toolCallData = ToolCallData(event.name, event.arguments, result)
                                segments.add(MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = result, signature = event.signature))
                                currentStatus = MessageStatus.SENDING
                            }
                        }

                        _streamingMessage.value = ChatMessage(
                            id = modelMessageId,
                            parentId = parentId,
                            text = totalText,
                            thoughts = totalThoughts.ifBlank { null },
                            thoughtTitle = totalThoughtTitle,
                            tokenCount = totalTokenCount,
                            status = currentStatus,
                            participant = Participant.MODEL,
                            timestamp = startTime,
                            thoughtTimeMs = totalThoughtTimeMs,
                            modelName = currentActiveModel.value,
                            toolCall = toolCallData,
                            segments = if (segments.isNotEmpty()) buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature) else null
                        )
            }

            // Multi-tool loop: keep calling tools until the model responds with text
            var toolRound = 0
            val maxToolRounds = 5
            toolPath = currentPath
            // For regenerate, chain new tool messages from the original model's parent
            // so model_new stays a sibling of model_original
            val chainRootId = if (isRegenerate) parentId else null

            while (toolCallData != null && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive && toolRound < maxToolRounds) {
                toolRound++
                val prevLastId = if (toolRound == 1 && chainRootId != null) chainRootId else toolPath.lastOrNull()?.id
                val toolMsgId = "tool_${UUID.randomUUID()}"
                val resultMsgId = "result_${UUID.randomUUID()}"
                val toolMsgSegs = segments.ifEmpty { null }
                val tcData = toolCallData!!
                val allSegmentsJson = Json.encodeToString(toolMsgSegs ?: listOf(
                    MessageSegment(type = "tool", toolName = tcData.toolName, toolArgs = tcData.arguments, toolResult = tcData.result)
                ))
                toolPath = toolPath.toMutableList().apply {
                    add(ChatMessage(
                        id = toolMsgId, parentId = prevLastId,
                        text = "", participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS, toolCall = tcData,
                        segments = toolMsgSegs
                    ))
                    add(ChatMessage(
                        id = resultMsgId, parentId = toolMsgId,
                        text = tcData.result,
                        participant = Participant.USER, status = MessageStatus.SUCCESS,
                        toolCall = tcData
                    ))
                }
                // Persist tool call messages to DB so history survives reload
                chatDao.upsertMessage(MessageEntity(
                    id = toolMsgId, conversationId = currentId, parentId = prevLastId,
                    text = "", thoughts = null, status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL, timestamp = System.currentTimeMillis(),
                    toolCallJson = allSegmentsJson
                ))
                chatDao.upsertMessage(MessageEntity(
                    id = resultMsgId, conversationId = currentId, parentId = toolMsgId,
                    text = tcData.result, thoughts = null, status = MessageStatus.SUCCESS,
                    participant = Participant.USER, timestamp = System.currentTimeMillis(),
                    toolCallJson = null
                ))

                toolCallData = null

                getActiveProvider().generateResponse(toolPath, config).collect { event ->
                            when (event) {
                                is StreamEvent.TextChunk -> {
                                    if (currentStatus == MessageStatus.THINKING) {
                                        totalThoughtTimeMs = System.currentTimeMillis() - startTime
                                        if (currentThoughtBuf.isNotEmpty()) {
                                            segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                                            currentThoughtBuf = StringBuilder()
                                            currentThoughtSignature = null
                                        }
                                        totalText += event.text.trimStart()
                                    } else {
                                        totalText += event.text
                                    }
                                    currentStatus = MessageStatus.SENDING
                                }
                                is StreamEvent.ThoughtChunk -> {
                                    if (totalText.isEmpty()) {
                                        currentStatus = MessageStatus.THINKING
                                        if (totalThoughtTimeMs == null) totalThoughtTimeMs = System.currentTimeMillis() - startTime
                                        if (totalThoughts.isEmpty()) totalThoughts = "Thinking..."
                                    }
                                    if (event.thought.isNotEmpty()) {
                                        currentThoughtBuf.append(event.thought)
                                        if (totalThoughts == "Thinking...") totalThoughts = event.thought
                                        else totalThoughts += event.thought
                                    }
                                    if (event.title != null) totalThoughtTitle = event.title
                                    if (event.signature != null) currentThoughtSignature = event.signature
                                }
                                is StreamEvent.UsageUpdate -> {
                                    if (event.tokenCount > 0) totalTokenCount += event.tokenCount
                                }
                                is StreamEvent.Error -> {
                                    totalText = event.message
                                    currentStatus = MessageStatus.ERROR
                                }
                                is StreamEvent.ToolCallRequest -> {
                                    if (currentThoughtBuf.isNotEmpty()) {
                                        segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                                        currentThoughtBuf = StringBuilder()
                                        currentThoughtSignature = null
                                    }
                                    val result = executeTool(event.name, event.arguments)
                                    toolCallData = ToolCallData(event.name, event.arguments, result)
                                    segments.add(MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = result))
                                    currentStatus = MessageStatus.SENDING
                                }
                            }

                            _streamingMessage.value = ChatMessage(
                                id = modelMessageId,
                                parentId = parentId,
                                text = totalText,
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle,
                                tokenCount = totalTokenCount,
                                status = currentStatus,
                                participant = Participant.MODEL,
                                timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs,
                                modelName = currentActiveModel.value,
                                toolCall = toolCallData,
                                segments = if (segments.isNotEmpty()) buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature) else null
                            )
                }
            }

            // Persist synthetic tool messages from toolPath to DB (batch, not during streaming)
            // Skip for regenerate — the model response must stay a sibling of the original
            if (!isRegenerate && generationId == myGenerationId) for (msg in toolPath) {
                if (msg.id.startsWith("tool_") || msg.id.startsWith("result_")) {
                    val exists = chatDao.getMessagesForConversation(currentId).first().any { it.id == msg.id }
                    if (!exists) {
                        chatDao.upsertMessage(MessageEntity(
                            id = msg.id, conversationId = currentId, parentId = msg.parentId,
                            text = msg.text, thoughts = null, status = msg.status,
                            participant = msg.participant, timestamp = System.currentTimeMillis(),
                            toolCallJson = msg.segments?.let { Json.encodeToString(it) }
                                ?: msg.toolCall?.let { Json.encodeToString(listOf(
                                    MessageSegment(type = "tool", toolName = it.toolName, toolArgs = it.arguments, toolResult = it.result)
                                )) }
                        ))
                    }
                }
            }

            if (currentStatus != MessageStatus.ERROR) {
                currentStatus = if (totalText.isNotEmpty() || totalThoughts.isNotEmpty()) MessageStatus.SUCCESS else MessageStatus.ERROR
            }
        } catch (e: CancellationException) {
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                totalText = "Error: ${e.localizedMessage ?: "An unexpected error occurred."}"
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    if (generationId == myGenerationId) {
                        val conversationExists = chatDao.getConversation(currentId) != null
                        if (conversationExists) {
                            val finalStreamingMsg = _streamingMessage.value
                            val finalSegments = finalStreamingMsg?.segments ?: segments.toList().ifEmpty { null }
                            val segmentsJson = finalSegments?.let { Json.encodeToString(it) }
                            val effectiveParentId = if (isRegenerate) parentId else (toolPath.lastOrNull()?.id ?: parentId)
                            if (finalStreamingMsg != null) {
                                chatDao.upsertMessage(MessageEntity(id = finalStreamingMsg.id, conversationId = currentId, parentId = effectiveParentId, text = finalStreamingMsg.text, thoughts = finalStreamingMsg.thoughts, thoughtTitle = finalStreamingMsg.thoughtTitle, tokenCount = finalStreamingMsg.tokenCount, status = currentStatus, participant = finalStreamingMsg.participant, timestamp = finalStreamingMsg.timestamp, thoughtTimeMs = finalStreamingMsg.thoughtTimeMs, modelName = finalStreamingMsg.modelName, toolCallJson = segmentsJson))
                            } else {
                                chatDao.upsertMessage(MessageEntity(id = modelMessageId, conversationId = currentId, parentId = effectiveParentId, text = totalText, thoughts = totalThoughts.ifBlank { null }, thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount, status = currentStatus, participant = Participant.MODEL, timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs, modelName = currentActiveModel.value, toolCallJson = segmentsJson))
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (generationId == myGenerationId) {
                    _isLoading.value = false
                    _streamingMessage.value = null
                    _generatingInConversationId.value = null
                }
                AgoraForegroundService.stop(getApplication())
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
            val message = when {
                successProviders.isNotEmpty() && failedProviders.isEmpty() -> 
                    "Successfully synced ${successProviders.size} provider(s)"
                successProviders.isNotEmpty() && failedProviders.isNotEmpty() -> 
                    "Synced: ${successProviders.joinToString()}; Failed: ${failedProviders.joinToString()}"
                successProviders.isEmpty() && failedProviders.isNotEmpty() -> 
                    "Failed to sync: ${failedProviders.joinToString()}"
                else -> if (skippedCount > 0) "No providers configured to sync" else "Sync completed"
            }
            _snackbarMessage.emit(message)
        }
    }
}
