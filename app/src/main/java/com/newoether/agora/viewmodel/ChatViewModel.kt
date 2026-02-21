package com.newoether.agora.viewmodel

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
data class ApiRequestPart(val text: String? = null)

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
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao
) : ViewModel() {
    
    val listState = LazyListState()

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
            entities.map { ChatConversation(id = it.id, title = it.title) }
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

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    init {
        viewModelScope.launch {
            _currentConversationId.collectLatest { id ->
                if (id != null) {
                    chatDao.getMessagesForConversation(id).collect { entities ->
                        _allMessages.value = entities.map { 
                            ChatMessage(
                                id = it.id, 
                                parentId = it.parentId,
                                text = it.text, 
                                thoughts = it.thoughts,
                                tokenCount = it.tokenCount,
                                status = it.status,
                                participant = it.participant, 
                                timestamp = it.timestamp
                            )
                        }
                    }
                } else {
                    _allMessages.value = emptyList()
                    _selectedChildren.value = emptyMap()
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
        _isNewChatMode.value = true
        _currentConversationId.value = null
        _allMessages.value = emptyList()
        _selectedChildren.value = emptyMap()
    }

    fun selectConversation(id: String) {
        _isNewChatMode.value = false
        _currentConversationId.value = id
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            chatDao.upsertConversation(ChatEntity(id = id, title = newTitle, lastUpdated = System.currentTimeMillis()))
        }
    }

    fun deleteConversation(id: String) {
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

    fun switchBranch(parentId: String?, direction: Int) {
        if (_isLoading.value) return
        val siblings = _allMessages.value.filter { it.parentId == parentId }.sortedBy { it.timestamp }
        if (siblings.size < 2) return
        val currentId = _selectedChildren.value[parentId] ?: siblings.last().id
        val currentIndex = siblings.indexOfFirst { it.id == currentId }
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        val newMap = _selectedChildren.value.toMutableMap()
        newMap[parentId] = siblings[newIndex].id
        _selectedChildren.value = newMap
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
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime
            ))
            generateResponse(currentId, newText, modelMessageId, startTime)
        }
    }

    fun sendMessage(text: String) {
        val activeKey = apiKeys.value.find { it.id == activeApiKeyId.value }?.key
        if (activeKey.isNullOrBlank()) {
            _allMessages.value = _allMessages.value + ChatMessage(text = "Please set API Key first!", participant = Participant.ERROR)
            return
        }
        stopGeneration()
        generationJob = viewModelScope.launch {
            var currentId = _currentConversationId.value
            if (_isNewChatMode.value) {
                val newId = UUID.randomUUID().toString()
                val title = if (text.length > 20) text.take(20) + "..." else text
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
                text = text, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            chatDao.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = userMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime
            ))
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
        var currentStatus = MessageStatus.SENDING
        val placeholder = chatDao.getMessagesForConversation(currentId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId

        try {
            if (provider.value == "Google") {
                val cleanModelName = selectedModel.value.removePrefix("models/")
                val currentPath = messages.value.filter { it.participant != Participant.ERROR }
                
                // Implement rolling context window
                val windowSize = maxContextWindow.value
                val limitedPath = if (currentPath.size > windowSize) {
                    currentPath.takeLast(windowSize)
                } else {
                    currentPath
                }

                val apiContents = limitedPath.map { msg ->
                    ApiRequestContent(role = if (msg.participant == Participant.USER) "user" else "model", parts = listOf(ApiRequestPart(text = msg.text)))
                }
                val activePrompt = systemPrompts.value.find { it.id == activeSystemPromptId.value }?.content
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
                                                    timestamp = startTime
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
            currentConnection?.disconnect()
            currentConnection = null
            _streamingMessage.value?.let { finalMsg ->
                chatDao.upsertMessage(MessageEntity(id = finalMsg.id, conversationId = currentId, parentId = finalMsg.parentId, text = finalMsg.text, thoughts = finalMsg.thoughts, tokenCount = finalMsg.tokenCount, status = currentStatus, participant = finalMsg.participant, timestamp = finalMsg.timestamp))
            } ?: run {
                chatDao.upsertMessage(MessageEntity(id = modelMessageId, conversationId = currentId, parentId = parentId, text = totalText, thoughts = totalThoughts.ifBlank { null }, tokenCount = totalTokenCount, status = currentStatus, participant = Participant.MODEL, timestamp = startTime))
            }
            _isLoading.value = false
            _streamingMessage.value = null
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
