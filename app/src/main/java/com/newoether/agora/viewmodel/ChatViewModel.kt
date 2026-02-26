package com.newoether.agora.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import androidx.compose.foundation.lazy.LazyListState

@Serializable
data class ModelListResponse(val models: List<ModelInfo>)

@Serializable
data class ModelInfo(val name: String, val displayName: String, val supportedGenerationMethods: List<String>)

@Serializable
data class ApiGenerateContentRequest(
    val contents: List<ApiRequestContent>,
    @SerialName("system_instruction") val systemInstruction: ApiRequestContent? = null,
    val tools: List<ApiTool>? = null,
    @SerialName("generationConfig") val generationConfig: ApiGenerationConfig? = null
)

@Serializable
data class ApiGenerationConfig(
    @SerialName("thinkingConfig") val thinkingConfig: ApiThinkingConfig? = null
)

@Serializable
data class ApiThinkingConfig(
    @SerialName("includeThoughts") val includeThoughts: Boolean,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    @SerialName("thinkingBudget") val thinkingBudget: Int? = null
)

@Serializable
data class ApiTool(
    @SerialName("code_execution") val codeExecution: JsonObject? = null,
    @SerialName("google_search") val googleSearch: JsonObject? = null
)

@Serializable
data class ApiRequestContent(val role: String? = null, val parts: List<ApiRequestPart>)

@Serializable
data class ApiInlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class ApiRequestPart(
    val text: String? = null,
    val inlineData: ApiInlineData? = null
)

@Serializable
data class ApiResponseContent(val role: String? = null, val parts: List<ApiResponsePart>)

@Serializable
data class ApiResponsePart(
    val text: String? = null,
    val thought: JsonElement? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("executable_code") val executableCode: ApiExecutableCode? = null,
    @SerialName("code_execution_result") val codeExecutionResult: ApiCodeExecutionResult? = null
)

@Serializable
data class ApiExecutableCode(val language: String, val code: String)

@Serializable
data class ApiCodeExecutionResult(val outcome: String, val output: String)

@Serializable
data class ApiStreamResponse(
    val candidates: List<ApiCandidate>? = null,
    @SerialName("usageMetadata") val usageMetadata: ApiUsageMetadata? = null
)

@Serializable
data class ApiCandidate(val content: ApiResponseContent? = null)

@Serializable
data class ApiUsageMetadata(
    val totalTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null
)

@Serializable
data class ApiErrorResponse(val error: ApiError)

@Serializable
data class ApiError(val code: Int? = null, val message: String? = null, val status: String? = null)

class ChatViewModel(
    application: Application,
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao
) : AndroidViewModel(application) {
    
    val listState = LazyListState()
    val messageHeights = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    private val _scrollToMessage = MutableSharedFlow<String?>(replay = 0)
    val scrollToMessage = _scrollToMessage.asSharedFlow()

    fun triggerScrollToMessage(messageId: String? = null) {
        viewModelScope.launch {
            _scrollToMessage.emit(messageId)
        }
    }

    val provider = settingsManager.provider.stateIn(viewModelScope, SharingStarted.Eagerly, "Google")
    val selectedModel = settingsManager.selectedModel.stateIn(viewModelScope, SharingStarted.Eagerly, "models/gemini-1.5-flash")
    val availableModels = settingsManager.availableModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val enabledModels = settingsManager.enabledModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val modelAliases = settingsManager.modelAliases.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val apiKeys = settingsManager.apiKeys.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeApiKeyId = settingsManager.activeApiKeyId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    val systemPrompts = settingsManager.systemPrompts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeSystemPromptId = settingsManager.activeSystemPromptId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val maxContextWindow = settingsManager.maxContextWindow.stateIn(viewModelScope, SharingStarted.Eagerly, 20)
    val visualizeContextRollout = settingsManager.visualizeContextRollout.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val codeExecutionEnabled = settingsManager.codeExecutionEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val googleSearchEnabled = settingsManager.googleSearchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val conversations: StateFlow<List<ChatConversation>> = chatDao.getAllConversations()
        .map { entities -> 
            entities.map { ChatConversation(id = it.id, title = it.title, systemPromptId = it.systemPromptId) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _selectedChildren = MutableStateFlow<Map<String?, String>>(emptyMap())

    private var generationJob: Job? = null
    private var currentConnection: HttpURLConnection? = null

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
            
            path.add(selectedMessage)
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
                                modelName = it.modelName
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
    fun setProvider(p: String) { viewModelScope.launch { settingsManager.saveProvider(p) } }
    fun setSelectedModel(model: String) { viewModelScope.launch { settingsManager.saveSelectedModel(model) } }
    fun setEnabledModels(models: Set<String>) { viewModelScope.launch { settingsManager.saveEnabledModels(models) } }

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

    fun addApiKey(name: String, key: String) {
        viewModelScope.launch {
            val newList = apiKeys.value + ApiKeyEntry(name = name, key = key)
            settingsManager.saveApiKeys(newList)
            if (activeApiKeyId.value == null) settingsManager.setActiveApiKeyId(newList.last().id)
        }
    }
    fun deleteApiKey(id: String) {
        viewModelScope.launch {
            val newList = apiKeys.value.filter { it.id != id }
            settingsManager.saveApiKeys(newList)
            if (activeApiKeyId.value == id) settingsManager.setActiveApiKeyId(newList.firstOrNull()?.id)
        }
    }
    fun updateApiKey(id: String, name: String, key: String) {
        viewModelScope.launch {
            val newList = apiKeys.value.map { if (it.id == id) it.copy(name = name, key = key) else it }
            settingsManager.saveApiKeys(newList)
        }
    }
    fun setActiveApiKey(id: String) { viewModelScope.launch { settingsManager.setActiveApiKeyId(id) } }

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

    fun createNewChat() {
        switchingJob?.cancel()
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            clearMessageHeights()
            _currentConversationId.value = null
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

    fun setConversationSystemPrompt(id: String, promptId: String?) {
        viewModelScope.launch {
            val existing = chatDao.getConversation(id)
            if (existing != null) {
                chatDao.upsertConversation(existing.copy(systemPromptId = promptId, lastUpdated = System.currentTimeMillis()))
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
        viewModelScope.launch(Dispatchers.IO) {
            currentConnection?.disconnect()
            currentConnection = null
        }
        _isLoading.value = false
        _streamingMessage.value = null
    }

    fun regenerate(messageId: String) {
        val currentId = _currentConversationId.value ?: return
        val activeKey = apiKeys.value.find { it.id == activeApiKeyId.value }?.key
        if (activeKey.isNullOrBlank()) return

        stopGeneration()
        generationJob = viewModelScope.launch {
            val messageToRegenerate = _allMessages.value.find { it.id == messageId } ?: return@launch
            val parentId = messageToRegenerate.parentId ?: return@launch
            val userMessage = _allMessages.value.find { it.id == parentId } ?: return@launch

            val isErrorOrStopped = messageToRegenerate.status == MessageStatus.ERROR || messageToRegenerate.status == MessageStatus.STOPPED
            val hasChildren = _allMessages.value.any { it.parentId == messageId }
            val isLatest = !hasChildren

            val modelMessageId: String
            val startTime = System.currentTimeMillis() + 1
            
            if (isErrorOrStopped && isLatest) {
                modelMessageId = messageId
                chatDao.upsertMessage(MessageEntity(
                    id = modelMessageId, conversationId = currentId, parentId = parentId,
                    text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                    modelName = selectedModel.value
                ))
                val newMap = _selectedChildren.value.toMutableMap()
                newMap[parentId] = modelMessageId
                _selectedChildren.value = newMap
            } else {
                modelMessageId = UUID.randomUUID().toString()
                chatDao.upsertMessage(MessageEntity(
                    id = modelMessageId, conversationId = currentId, parentId = parentId,
                    text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                    modelName = selectedModel.value
                ))
                
                val newMap = _selectedChildren.value.toMutableMap()
                newMap[parentId] = modelMessageId
                _selectedChildren.value = newMap
            }
            
            generateResponse(currentId, userMessage.text, modelMessageId, startTime)
        }
    }

    fun switchBranch(parentId: String?, direction: Int) {
        if (_isLoading.value) return
        val siblings = _allMessages.value.filter { it.parentId == parentId }.sortedBy { it.timestamp }
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
        val activeKey = apiKeys.value.find { it.id == activeApiKeyId.value }?.key
        if (activeKey.isNullOrBlank()) return

        stopGeneration()
        generationJob = viewModelScope.launch {
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
                modelName = selectedModel.value
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
        val activeKey = apiKeys.value.find { it.id == activeApiKeyId.value }?.key
        if (activeKey.isNullOrBlank()) {
            _allMessages.value = _allMessages.value + ChatMessage(text = "Please set API Key first!", participant = Participant.ERROR)
            return
        }
        stopGeneration()
        generationJob = viewModelScope.launch {
            val processedImages = if (images.isNotEmpty()) processImages(images) else emptyList()
            var currentId = _currentConversationId.value
            if (_isNewChatMode.value) {
                val newId = UUID.randomUUID().toString()
                val title = if (text.length > 20) text.take(20) + "..." else text.ifBlank { "New Chat" }
                chatDao.upsertConversation(ChatEntity(id = newId, title = title))
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
                modelName = selectedModel.value
            ))
            triggerScrollToMessage(userMessageId)
            generateResponse(currentId, text, modelMessageId, startTime)
        }
    }

    private suspend fun generateResponse(currentId: String, text: String, modelMessageId: String, startTime: Long) {
        val activeKey = apiKeys.value.find { it.id == activeApiKeyId.value }?.key ?: return
        _isLoading.value = true
        _streamingMessage.value = null
        var totalText = ""
        var totalThoughts = ""
        var totalTokenCount = 0
        var totalThoughtTimeMs: Long? = null
        var currentStatus = MessageStatus.SENDING
        val placeholder = chatDao.getMessagesForConversation(currentId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId

        try {
            if (provider.value == "Google") {
                val cleanModelName = selectedModel.value.removePrefix("models/")
                val dbMessages = chatDao.getMessagesForConversation(currentId).first()
                val pathEntities = mutableListOf<MessageEntity>()
                var currId: String? = parentId
                while (currId != null) {
                    val msg = dbMessages.find { it.id == currId } ?: break
                    pathEntities.add(0, msg)
                    currId = msg.parentId
                }
                val currentPath = pathEntities.map { 
                    ChatMessage(id = it.id, parentId = it.parentId, text = it.text, images = it.images, thoughts = it.thoughts, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, thoughtTimeMs = it.thoughtTimeMs) 
                }.filter { it.participant != Participant.ERROR }
                
                // Implement rolling context window
                val windowSize = maxContextWindow.value
                val limitedPath = if (currentPath.size > windowSize) {
                    currentPath.takeLast(windowSize)
                } else {
                    currentPath
                }

                val apiContents = limitedPath.map { msg ->
                    val parts = mutableListOf<ApiRequestPart>()
                    if (msg.text.isNotEmpty()) {
                        parts.add(ApiRequestPart(text = msg.text))
                    }
                    for (imagePath in msg.images) {
                        try {
                            val file = File(imagePath)
                            if (file.exists()) {
                                val bytes = file.readBytes()
                                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                parts.add(ApiRequestPart(inlineData = ApiInlineData(mimeType = "image/jpeg", data = base64)))
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                        }
                    }
                    if (parts.isEmpty()) parts.add(ApiRequestPart(text = ""))
                    ApiRequestContent(role = if (msg.participant == Participant.USER) "user" else "model", parts = parts)
                }
                
                val conversation = chatDao.getConversation(currentId)
                val targetPromptId = conversation?.systemPromptId ?: activeSystemPromptId.value
                val activePrompt = systemPrompts.value.find { it.id == targetPromptId }?.content
                
                val sdf = SimpleDateFormat("MMMM d, yyyy, HH:mm", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT+8")
                }
                val timeInfo = "Current Time: ${sdf.format(Date())} (UTC+8)\n\n"
                val systemInstruction = ApiRequestContent(parts = listOf(ApiRequestPart(text = timeInfo + (activePrompt ?: ""))))
                
                val tools = mutableListOf<ApiTool>()
                if (codeExecutionEnabled.value) tools.add(ApiTool(codeExecution = JsonObject(emptyMap())))
                if (googleSearchEnabled.value) {
                    tools.add(ApiTool(googleSearch = JsonObject(emptyMap())))
                }
                
                val thinkingConfig = when {
                    cleanModelName.contains("gemini-3", ignoreCase = true) -> 
                        ApiThinkingConfig(includeThoughts = true, thinkingLevel = "HIGH")
                    cleanModelName.contains("gemini-2.5", ignoreCase = true) -> 
                        ApiThinkingConfig(includeThoughts = true, thinkingBudget = -1)
                    cleanModelName.contains("thinking-exp", ignoreCase = true) -> 
                        ApiThinkingConfig(includeThoughts = true)
                    else -> null
                }
                
                val requestBody = ApiGenerateContentRequest(
                    contents = apiContents, 
                    systemInstruction = systemInstruction,
                    tools = if (tools.isNotEmpty()) tools else null,
                    generationConfig = if (thinkingConfig != null) ApiGenerationConfig(thinkingConfig = thinkingConfig) else null
                )

                withContext(Dispatchers.IO) {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$cleanModelName:streamGenerateContent?alt=sse&key=$activeKey")
                    val connection = url.openConnection() as HttpURLConnection
                    currentConnection = connection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                    connection.outputStream.bufferedWriter().use { it.write(json.encodeToString(ApiGenerateContentRequest.serializer(), requestBody)) }
                    
                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        val reader = connection.inputStream.bufferedReader()
                        var line = reader.readLine()
                        while (line != null && isActive) {
                            if (line.startsWith("data: ")) {
                                val jsonStr = line.substring(6).trim()
                                if (jsonStr != "[DONE]") {
                                    try {
                                        val response = json.decodeFromString<ApiStreamResponse>(jsonStr)
                                        // Log raw part for diagnosis
                                        android.util.Log.d("AgoraAPI", "JSON Part: $jsonStr")
                                        
                                        response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                                            var hasNewContent = false
                                            
                                            // 1. Determine if this part is a "Thought" part
                                            var isPartOfThought = false
                                            
                                            // Handle polymorphic thought field (JsonElement)
                                            part.thought?.let { thoughtElement ->
                                                if (thoughtElement is kotlinx.serialization.json.JsonPrimitive) {
                                                    if (thoughtElement.isString) {
                                                        totalThoughts += thoughtElement.content
                                                        isPartOfThought = true
                                                        hasNewContent = true
                                                    } else if (thoughtElement.content == "true") {
                                                        // "thought": true signal -> this part's text is reasoning
                                                        isPartOfThought = true
                                                    }
                                                }
                                            }
                                            
                                            // Handle explicit reasoning_content
                                            part.reasoningContent?.let { 
                                                totalThoughts += it 
                                                isPartOfThought = true
                                                hasNewContent = true
                                            }

                                            // Handle Gemini 3 encrypted thoughtSignature
                                            part.thoughtSignature?.let {
                                                isPartOfThought = true
                                                if (totalThoughts.isEmpty()) {
                                                    totalThoughts = "Model is reasoning..."
                                                }
                                                hasNewContent = true
                                            }

                                            // 2. Process Text based on phase
                                            part.text?.let { 
                                                if (isPartOfThought) {
                                                    totalThoughts += it
                                                } else {
                                                    totalText += it 
                                                    currentStatus = MessageStatus.SENDING
                                                }
                                                hasNewContent = true
                                            }
                                            
                                            // 3. Status Transition
                                            if (isPartOfThought && totalText.isEmpty()) {
                                                currentStatus = MessageStatus.THINKING
                                                totalThoughtTimeMs = System.currentTimeMillis() - startTime
                                            }
                                            
                                            // 4. Other content types (always treated as main output)
                                            part.executableCode?.let { 
                                                totalText += "\n```${it.language}\n${it.code}\n```\n" 
                                                hasNewContent = true
                                                currentStatus = MessageStatus.SENDING
                                            }
                                            
                                            part.codeExecutionResult?.let { 
                                                totalText += "\n> Output: ${it.output}\n" 
                                                hasNewContent = true
                                                currentStatus = MessageStatus.SENDING
                                            }
                                            
                                            if (hasNewContent) {
                                                _streamingMessage.value = ChatMessage(
                                                    id = modelMessageId, 
                                                    parentId = parentId, 
                                                    text = totalText, 
                                                    thoughts = totalThoughts.ifBlank { null }, 
                                                    tokenCount = totalTokenCount, 
                                                    status = currentStatus, 
                                                    participant = Participant.MODEL, 
                                                    timestamp = startTime,
                                                    thoughtTimeMs = totalThoughtTimeMs,
                                                    modelName = selectedModel.value
                                                )
                                            }
                                        }
                                            response.usageMetadata?.let { metadata ->
                                                metadata.totalTokenCount?.let { totalTokenCount = it }
                                                if (totalText.isEmpty() && (metadata.thoughtsTokenCount ?: 0) > 0) {
                                                    currentStatus = MessageStatus.THINKING
                                                    if (totalThoughts.isEmpty()) {
                                                        totalThoughts = "Model is reasoning..."
                                                    }
                                                }
                                            }
                                    } catch (e: Exception) { 
                                        android.util.Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                                    }
                                }
                            }
                            line = reader.readLine()
                        }
                        currentStatus = if (totalText.isNotEmpty() || totalThoughts.isNotEmpty()) MessageStatus.SUCCESS else MessageStatus.ERROR
                    } else {
                        val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error (Code: $responseCode)"
                        totalText = try {
                            val errorJson = json.decodeFromString<ApiErrorResponse>(errorRaw)
                            val code = errorJson.error.code ?: responseCode
                            val status = errorJson.error.status ?: "UNKNOWN"
                            val message = errorJson.error.message ?: "No error message provided"
                            "Error $code ($status): $message"
                        } catch (e: Exception) {
                            "Error (Code $responseCode): $errorRaw"
                        }
                        currentStatus = MessageStatus.ERROR
                    }
                }
            }
        } catch (e: CancellationException) {
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            currentStatus = MessageStatus.ERROR
            totalText = "Request timed out. The server took too long to respond."
        } catch (e: java.net.ConnectException) {
            currentStatus = MessageStatus.ERROR
            totalText = "Connection refused. Please check your internet connection or if the service is available."
        } catch (e: java.net.UnknownHostException) {
            currentStatus = MessageStatus.ERROR
            totalText = "Network error: Unable to reach the server. Please check your internet connection."
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                totalText = "Error: ${e.localizedMessage ?: "An unexpected error occurred."}"
            }
        } finally {
            withContext(NonCancellable) {
                currentConnection?.disconnect()
                currentConnection = null
                // Only persist if the conversation still exists to avoid FK constraint crashes on deletion
                val conversationExists = chatDao.getConversation(currentId) != null
                if (conversationExists) {
                    val finalStreamingMsg = _streamingMessage.value
                    if (finalStreamingMsg != null) {
                        chatDao.upsertMessage(MessageEntity(id = finalStreamingMsg.id, conversationId = currentId, parentId = finalStreamingMsg.parentId, text = finalStreamingMsg.text, thoughts = finalStreamingMsg.thoughts, tokenCount = finalStreamingMsg.tokenCount, status = currentStatus, participant = finalStreamingMsg.participant, timestamp = finalStreamingMsg.timestamp, thoughtTimeMs = finalStreamingMsg.thoughtTimeMs, modelName = finalStreamingMsg.modelName))
                    } else {
                        chatDao.upsertMessage(MessageEntity(id = modelMessageId, conversationId = currentId, parentId = parentId, text = totalText, thoughts = totalThoughts.ifBlank { null }, tokenCount = totalTokenCount, status = currentStatus, participant = Participant.MODEL, timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs, modelName = selectedModel.value))
                    }
                }
                _isLoading.value = false
                _streamingMessage.value = null
            }
        }
    }

    fun fetchAvailableModels() {
        val activeKey = apiKeys.value.find { it.id == activeApiKeyId.value }?.key
        if (activeKey.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                val newModels = withContext(Dispatchers.IO) {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$activeKey")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = Json { ignoreUnknownKeys = true }
                    json.decodeFromString<ModelListResponse>(responseText).models.filter { it.supportedGenerationMethods.contains("generateContent") }.map { it.name }
                }
                if (newModels.isNotEmpty()) {
                    settingsManager.saveAvailableModels(newModels)
                    val newEnabled = enabledModels.value.intersect(newModels.toSet())
                    settingsManager.saveEnabledModels(newEnabled)
                    if (!newEnabled.contains(selectedModel.value)) settingsManager.saveSelectedModel(newEnabled.firstOrNull() ?: "")
                }
            } catch (e: Exception) { }
        }
    }
}
