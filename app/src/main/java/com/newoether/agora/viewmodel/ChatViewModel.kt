package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import com.newoether.agora.util.DebugLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.api.*
import com.newoether.agora.api.anthropic.*
import com.newoether.agora.api.gemini.*
import com.newoether.agora.api.local.*
import com.newoether.agora.api.ollama.*
import com.newoether.agora.api.openai.*
import com.newoether.agora.data.ClaudeChatImporter
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SearchResultFormatter
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.service.AgoraForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
    private val appContext: Context,
    private val sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null,
    // All injected via AppContainer/ChatViewModelFactory — the single construction site.
    val autoBackupManager: com.newoether.agora.data.AutoBackupManager,
    conversationRepository: com.newoether.agora.data.repository.ConversationRepository,
    settingsRepository: com.newoether.agora.data.repository.SettingsRepository
) : AndroidViewModel(application) {

    /**
     * Shared settings state holder (eagerly-shared StateFlows + setters).
     * Revived from the previously-unused [SettingsRepository]; both ChatViewModel's
     * internal logic and the settings pages read settings from here, so each setting
     * is owned in exactly one place.
     */
    val settings: com.newoether.agora.data.repository.SettingsRepository = settingsRepository

    /**
     * Conversation/message persistence behind the repository layer. Revived from the
     * previously-unused [ConversationRepository]; CRUD, cascade-delete, branch-selection
     * and stuck-message logic live there instead of being hand-rolled against [chatDao].
     * (Embedding writes / bulk import-export still go direct until later phases.)
     */
    private val convRepo: com.newoether.agora.data.repository.ConversationRepository = conversationRepository

    private val localProvider = LocalProvider(appContext, settingsManager)

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = com.newoether.agora.viewmodel.RagManager(
        chatDao = chatDao,
        settings = settings,
        settingsManager = settingsManager,
        localProvider = localProvider,
        appContext = appContext,
        scope = viewModelScope,
    ) { _snackbarMessage.emit(it) }

    /** Data export/import orchestration (native backup + Claude + GPT formats). */
    val importExport = ImportExportManager(
        app = getApplication(),
        chatDao = chatDao,
        conversations = convRepo,
        settingsManager = settingsManager,
        memoryManager = memoryManager,
        scope = viewModelScope,
        emitSnackbar = { _snackbarMessage.emit(it) },
        onDataChanged = { refreshDataCounts() },
    )

    /** Local (on-device) chat-model configuration CRUD. */
    val modelManager = ModelManager(settings, settingsManager, viewModelScope)

    /** Built-in + custom provider instances, resolution, and model discovery (see [ProviderRegistry]). */
    private val providerRegistry = ProviderRegistry(settings, localProvider, viewModelScope)

    /**
     * Startup jobs deferred until all StateFlow/property backing fields are
     * initialized — avoids the constructor this-escape where a Dispatchers.IO
     * coroutine accesses a field whose JVM backing field is still null.
     */
    private fun startInitJobs() {
        // Auto-check for updates on launch (at most once per day)
        viewModelScope.launch(Dispatchers.IO) {
            if (settings.getAutoUpdateCheck()) {
                val lastCheck = settings.getLastUpdateCheckTime()
                val now = System.currentTimeMillis()
                if (now - lastCheck > 24 * 60 * 60 * 1000L) {
                    settings.saveLastUpdateCheckTime(now)
                    val info = com.newoether.agora.util.UpdateChecker.check(getCurrentVersion())
                    if (info != null) {
                        _updateDialogData.value = info
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val models = settings.getEmbeddingModels()
            val activeId = settings.getActiveEmbeddingModelId()
            val active = models.find { it.id == activeId } ?: return@launch
            val total = convRepo.getAllMessagesForIndexing().count { it.text.isNotBlank() }
            val cached = convRepo.getEmbeddingCountByModel(active.id)
            val notCached = (total - cached).coerceAtLeast(0)
            if (notCached > 0 && !ragManager.cachingProgress.value.containsKey(active.id)) {
                _snackbarMessage.emit(SnackbarEvent(
                    getApplication<Application>().getString(R.string.messages_not_cached, notCached, total),
                    getApplication<Application>().getString(R.string.cache_now)
                ) { cacheMessagesForModel(active.id) })
            }
        }
        // Clean up orphaned embeddings (messages that no longer exist)
        viewModelScope.launch(Dispatchers.IO) {
            convRepo.deleteOrphanedEmbeddings()
        }
        // Sweep orphaned PDF render files (pdf_* / pdf_preview_*) left in filesDir by a
        // process death while the page-select dialog was open. At startup nothing is
        // rendering and no dialog is open, so any pdf_*.jpg not referenced by a stored
        // message's images is junk and gets deleted.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val referenced = convRepo.getAllMessagesList()
                    .asSequence()
                    .flatMap { it.images.asSequence() }
                    .map { it.removePrefix("file://") }
                    .toHashSet()
                getApplication<Application>().filesDir.listFiles { f ->
                    f.isFile && f.name.startsWith("pdf_") && f.name.endsWith(".jpg")
                }?.forEach { f ->
                    if (f.absolutePath !in referenced) runCatching { f.delete() }
                }
            } catch (_: Exception) {}
        }
        // ── Auto Backup ──────────────────────────────────────────
        try { com.newoether.agora.service.AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
        viewModelScope.launch(Dispatchers.IO) {
            try { autoBackupManager.checkAndBackup() } catch (_: Exception) {}
        }
        // Sync local chat models into available models
        viewModelScope.launch {
            var lastLocalIds: List<String>? = null
            var lastAliases: Map<String, String>? = null
            settings.localChatModels.collect { models ->
                val localIds = models.map { "Local:${it.modelId}" }
                val currentAliases = settings.getModelAliases()
                val aliases = currentAliases.toMutableMap()
                models.forEach { aliases["Local:${it.modelId}"] = it.alias }
                if (localIds != lastLocalIds) {
                    settings.saveAvailableModels("Local", localIds)
                    lastLocalIds = localIds
                }
                if (aliases != lastAliases) {
                    settings.saveModelAliases(aliases)
                    lastAliases = aliases
                }
            }
        }
        // Keep the provider map and cached model lists consistent with settings.
        providerRegistry.launchSyncJobs()
    }

    // Generation lifecycle (IO scope, current job, send gate, race-free stop/persist
    // ownership tokens) lives in [GenerationSession]; declared below once the
    // generation StateFlows it shares are initialized.

    private val generationManager by lazy {
        GenerationManager(
            app = application,
            chatDao = chatDao,
            memoryManager = memoryManager,
            providers = providerRegistry.all,
            context = appContext,
            sandboxFactory = sandboxFactory
        ).also { gm ->
            gm.onMessagePersisted = { messageId, text ->
                if (settings.autoCacheEnabled.value && (settings.modelSearchMethod.value == "rag" || settings.manualSearchMethod.value == "rag")) {
                    indexMessageForRag(messageId, text)
                }
            }
            gm.onConfirmShellCommand = { server, summary -> shellConfirmation.confirm(server, summary) }
        }
    }

    val sandboxManager: com.newoether.agora.sandbox.SandboxManager? by lazy {
        sandboxFactory?.create()
    }
    val isSandboxFlavor: Boolean = sandboxFactory?.isAvailable() == true

    override fun onCleared() {
        super.onCleared()
        sandboxManager?.close()
        localProvider.close()
        session.cancelScope()
        autoBackupManager.destroy()
    }

    val listState = LazyListState()
    val messageHeights = androidx.compose.runtime.mutableStateMapOf<String, Int>()


    fun getProviderInstance(name: String): LlmProvider = providerRegistry.getInstance(name)



    private val _scrollToMessage = MutableSharedFlow<String?>(replay = 0)
    val scrollToMessage = _scrollToMessage.asSharedFlow()

    fun triggerScrollToMessage(messageId: String? = null) {
        viewModelScope.launch {
            _scrollToMessage.emit(messageId)
        }
    }

    private val _currentActiveModel = MutableStateFlow<String?>(null)
    val currentActiveModel = kotlinx.coroutines.flow.combine(_currentActiveModel, settings.selectedModel) { active, default ->
        active ?: default
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "gemini-1.5-flash")

    fun getProviderForModel(modelId: String): String = providerRegistry.providerForModel(modelId)
    

        
    // Embedding subsystem state lives in [ragManager]; exposed here for the UI.
    val activeEmbeddingModel get() = ragManager.activeEmbeddingModel
    val cachingProgress get() = ragManager.cachingProgress
    val cacheCounts get() = ragManager.cacheCounts
    fun loadCacheCounts() = ragManager.loadCacheCounts()

    // ── Remote shell command confirmation gate ───────────────────────────
    /** Shell-command confirmation policy + pending-prompt handshake (see [ShellConfirmationController]). */
    private val shellConfirmation = ShellConfirmationController(settings)
    val pendingShellCommand: StateFlow<ShellConfirmationController.PendingShellCommand?>
        get() = shellConfirmation.pendingShellCommand

    /** Called by the UI to resolve a pending confirmation. */
    fun resolveShellConfirmation(allow: Boolean, alwaysAllowServer: Boolean = false) =
        shellConfirmation.resolve(allow, alwaysAllowServer)

    fun setShellConfirmEnabled(enabled: Boolean) = shellConfirmation.setEnabled(enabled)

    // ── Auto Backup ───────────────────────────────────────────

        val conversations: StateFlow<List<ChatConversation>> = convRepo.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()

    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(replay = 1)
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }

    private val _updateDialogData = MutableStateFlow<com.newoether.agora.util.UpdateInfo?>(null)
    val updateDialogData: StateFlow<com.newoether.agora.util.UpdateInfo?> = _updateDialogData.asStateFlow()
    fun dismissUpdateDialog() { _updateDialogData.value = null }
    fun showUpdateDialog(info: com.newoether.agora.util.UpdateInfo) { _updateDialogData.value = info }

    /** PDF / text-file preview state (see [MediaPreviewState]). */
    private val mediaPreview = MediaPreviewState()
    val previewPdfPages: StateFlow<List<String>> get() = mediaPreview.pdfPages
    val previewPdfIndex: StateFlow<Int> get() = mediaPreview.pdfIndex
    val previewFileContent: StateFlow<String?> get() = mediaPreview.fileContent
    val previewFileName: StateFlow<String?> get() = mediaPreview.fileName

    fun showPdfPreview(pages: List<String>, startIndex: Int) = mediaPreview.showPdf(pages, startIndex)
    fun showFilePreview(fileName: String, content: String) = mediaPreview.showFile(fileName, content)
    fun clearPreviews() = mediaPreview.clear()

    // Legacy state — to be replaced by _conversationUiState
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

    /** Race-free generation lifecycle: IO scope, current job, send gate, stop/persist tokens. */
    private val session = GenerationSession(
        app = application,
        convRepo = convRepo,
        settings = settings,
        isLoading = _isLoading,
        streamingMessage = _streamingMessage,
        generatingInConversationId = _generatingInConversationId,
        allMessages = _allMessages,
        currentConversationId = _currentConversationId,
        onIndexMessageForRag = ::indexMessageForRag,
        onCacheMessages = { cacheMessagesForModel(it, silent = true) },
    )

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

    private val _pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null)
    val pendingConversationSettings: StateFlow<ConversationSettings?> = _pendingConversationSettings.asStateFlow()

    fun setPendingConversationSettings(settings: ConversationSettings?) {
        _pendingConversationSettings.value = settings
    }

    fun updateConversationSetting(convId: String?, update: (ConversationSettings) -> ConversationSettings) {
        if (convId != null) {
            val current = settings.conversationSettings.value[convId] ?: ConversationSettings()
            settings.setConversationSettings(convId, update(current))
        } else {
            val current = _pendingConversationSettings.value ?: ConversationSettings()
            _pendingConversationSettings.value = update(current)
        }
    }

    private val _branchSwitchTrigger = MutableStateFlow<String?>(null)
    val branchSwitchTrigger: StateFlow<String?> = _branchSwitchTrigger.asStateFlow()

    fun clearBranchSwitchTrigger() {
        _branchSwitchTrigger.value = null
    }

    // Export/Import state lives in [importExport]; exposed here for the UI.
    val exportProgress get() = importExport.exportProgress
    val importProgress get() = importExport.importProgress
    val importManifest get() = importExport.importManifest
    val importPreview get() = importExport.importPreview
    val claudeImportPreview get() = importExport.claudeImportPreview
    val claudeImportProgress get() = importExport.claudeImportProgress
    val claudeImportResult get() = importExport.claudeImportResult
    val gptImportPreview get() = importExport.gptImportPreview
    val gptImportProgress get() = importExport.gptImportProgress
    val gptImportResult get() = importExport.gptImportResult


    private val _conversationCount = MutableStateFlow(0)
    val conversationCount: StateFlow<Int> = _conversationCount.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _systemPromptCount = MutableStateFlow(0)
    val systemPromptCount: StateFlow<Int> = _systemPromptCount.asStateFlow()

    init { startInitJobs() }

    init {
        viewModelScope.launch {
            _currentConversationId.collectLatest { id ->
                if (id != null) {
                    // Fix stuck sending states when loading conversation — skip if currently generating
                    if (!_isLoading.value) {
                        val stuckMessages = convRepo.getMessagesForConversation(id).first()
                            .filter { it.status == MessageStatus.SENDING || it.status == MessageStatus.THINKING || it.status == MessageStatus.TOOL_CALLING || it.status == MessageStatus.TRANSCRIBING }

                        stuckMessages.forEach { msg ->
                            convRepo.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
                        }
                    }

                    // Restore selected branches
                    val conversation = convRepo.getConversation(id)
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

                    convRepo.getMessagesForConversation(id).collect { entities ->
                        val mapped = entities.map {
                            ChatMessage(
                                id = it.id,
                                parentId = it.parentId,
                                text = SearchResultFormatter.format(it.text, appContext),
                                images = it.images,
                                thoughts = it.thoughts,
                                thoughtTitle = it.thoughtTitle,
                                tokenCount = it.tokenCount,
                                status = it.status,
                                participant = it.participant,
                                timestamp = it.timestamp,
                                thoughtTimeMs = it.thoughtTimeMs,
                                modelName = it.modelName,
                                segments = it.toolCallJson?.let { json ->
                                    try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null }
                                } ?: it.thoughts?.takeIf { t -> t.isNotBlank() }?.let { listOf(MessageSegment(type = "thought", content = it)) },
                                toolCall = it.toolCallJson?.let { json ->
                                    try {
                                        val segs = Json.decodeFromString<List<MessageSegment>>(json)
                                        segs.lastOrNull { s -> s.type == "tool" }?.let { s ->
                                            val rawResult = s.toolResult ?: ""
                                            ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", SearchResultFormatter.format(rawResult, appContext))
                                        }
                                    } catch (_: Exception) { null }
                                },
                                attachmentMeta = it.attachmentMeta?.let { json ->
                                    try { Json.decodeFromString<AttachmentMeta>(json) } catch (_: Exception) { null }
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
                    persistSelectedChildren(id, childrenMap)
                }
            }
        }
    }

    private suspend fun persistSelectedChildren(conversationId: String, childrenMap: Map<String?, String>) {
        convRepo.saveBranchSelections(conversationId, childrenMap)
    }

    // ── Custom providers ──────────────────────────────────────
    // Settings persistence lives in SettingsRepository; ChatViewModel only maintains
    // the live in-memory provider instances (the `providers` map) via callbacks.
    fun addCustomProvider(name: String, baseUrl: String) = providerRegistry.addCustom(name, baseUrl)
    fun renameCustomProvider(oldName: String, newName: String) = providerRegistry.renameCustom(oldName, newName)
    fun deleteCustomProvider(name: String) = providerRegistry.deleteCustom(name)

    private fun resolveTranscriptionProviderName(): String =
        settings.imageTranscriptionModel.value?.let { getProviderForModel(it) } ?: ""

    private fun resolveTranscriptionModelId(): String =
        settings.imageTranscriptionModel.value?.let { com.newoether.agora.model.ModelId.parse(it).modelName } ?: ""

    private fun resolveTranscriptionApiKey(): String {
        val model = settings.imageTranscriptionModel.value ?: return ""
        val providerName = getProviderForModel(model)
        if (providerName == "Local") return ""
        val activeKeyId = settings.activeApiKeyIds.value[providerName]
        return settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
    }

    private fun resolveTranscriptionBaseUrl(): String? {
        val model = settings.imageTranscriptionModel.value ?: return null
        return providerRegistry.getEffectiveBaseUrl(getProviderForModel(model))
    }

    // Image generation reuses the selected model's provider credentials (mirrors transcription).
    private fun resolveImageGenModelId(): String =
        settings.imageGenModel.value?.let { com.newoether.agora.model.ModelId.parse(it).modelName.removePrefix("models/") } ?: ""

    private fun resolveImageGenApiKey(): String {
        val model = settings.imageGenModel.value ?: return ""
        val providerName = getProviderForModel(model)
        if (providerName == "Local") return ""
        val activeKeyId = settings.activeApiKeyIds.value[providerName]
        return settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
    }

    private fun resolveImageGenBaseUrl(): String {
        val model = settings.imageGenModel.value ?: return ""
        return providerRegistry.getEffectiveBaseUrl(getProviderForModel(model)) ?: ""
    }

    fun getCurrentVersion(): String {
        return try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }
    suspend fun checkForUpdates(): com.newoether.agora.util.UpdateInfo? {
        val current = getCurrentVersion()
        return com.newoether.agora.util.UpdateChecker.check(current)
    }
    fun addEmbeddingModel(config: EmbeddingModelConfig) = ragManager.addEmbeddingModel(config)
    fun deleteEmbeddingModel(id: String) = ragManager.deleteEmbeddingModel(id)
    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int? = null) =
        ragManager.renameEmbeddingModel(id, newName, batchSize)
    fun setActiveEmbeddingModel(id: String) = ragManager.setActiveEmbeddingModel(id)
    fun cacheMessagesForModel(modelId: String, recache: Boolean = false, silent: Boolean = false) =
        ragManager.cacheMessagesForModel(modelId, recache, silent)

    fun isLocalModelIdTaken(modelId: String, excludeId: String? = null) =
        modelManager.isLocalModelIdTaken(modelId, excludeId)
    fun addLocalChatModel(config: LocalChatModelConfig) = modelManager.addLocalChatModel(config)
    fun deleteLocalChatModel(uuid: String) = modelManager.deleteLocalChatModel(uuid)
    fun updateLocalChatModel(
        uuid: String, newModelId: String, newAlias: String, nCtx: Int, temperature: Float, topP: Float, maxTokens: Int,
        mmprojPath: String = ""
    ) = modelManager.updateLocalChatModel(uuid, newModelId, newAlias, nCtx, temperature, topP, maxTokens, mmprojPath)

    suspend fun semanticSearch(query: String, limit: Int = 20): List<Pair<MessageEntity, Float>> {
        val ctx = com.newoether.agora.viewmodel.GenerationContext(
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = activeEmbeddingModel.value,
            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value
        )
        return generationManager.semanticSearch(query, limit, ctx)
    }

    fun resolveEmbeddingKeyForProviderExact(targetProvider: String) =
        ragManager.resolveEmbeddingKeyForProviderExact(targetProvider)

    fun indexMessageForRag(messageId: String, text: String) = ragManager.indexMessageForRag(messageId, text)
    suspend fun searchMessages(query: String, limit: Int = 20) = convRepo.searchMessages(query, limit)
    // ── Auto Backup ───────────────────────────────────────────
    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupEnabled(enabled)
            if (enabled) {
                try { com.newoether.agora.service.AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
            } else {
                try { com.newoether.agora.service.AutoBackupWorker.cancel(getApplication()) } catch (_: Exception) {}
            }
        }
    }
    fun setAutoBackupPeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupPeriodHours(hours)
            // Enforce: auto-delete period must be strictly greater than backup period
            val deleteTiers = listOf(168, 720, 8760)
            val deleteHours = settings.autoDeletePeriodHours.value
            if (deleteHours <= hours) {
                val nextDelete = deleteTiers.firstOrNull { it > hours } ?: 8760
                settings.saveAutoDeletePeriodHours(nextDelete)
            }
        }
    }
    fun setAutoBackupCategories(categories: String) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoBackupCategories(categories) }
    }
    fun setAutoBackupDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoBackupDirectory(path) }
    }
    fun setAutoDeleteEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoDeleteEnabled(enabled) }
    }
    fun setAutoDeletePeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val backupHours = settings.autoBackupPeriodHours.value
            val deleteTiers = listOf(168, 720, 8760)
            // Find the smallest valid delete tier that is > backupHours, and >= the requested hours
            val minValid = deleteTiers.firstOrNull { it > backupHours } ?: 8760
            settings.saveAutoDeletePeriodHours(maxOf(hours, minValid))
        }
    }
    fun buildEffectiveConversationSettings(conversationId: String): ConversationSettings {
        val overrides = settings.conversationSettings.value[conversationId]
            ?: _pendingConversationSettings.value  // new chat: may not be saved to map yet
            ?: ConversationSettings()
        return ConversationSettings(
            contextWindow = overrides.contextWindow ?: settings.maxContextWindow.value,
            temperature = overrides.temperature ?: settings.defaultTemperature.value,
            maxTokens = overrides.maxTokens ?: settings.defaultMaxTokens.value,
            topP = overrides.topP ?: settings.defaultTopP.value,
            frequencyPenalty = overrides.frequencyPenalty ?: settings.defaultFrequencyPenalty.value,
            presencePenalty = overrides.presencePenalty ?: settings.defaultPresencePenalty.value,
            codeExecutionEnabled = overrides.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,
            googleSearchEnabled = overrides.googleSearchEnabled ?: settings.googleSearchEnabled.value,
            thinkingEnabled = overrides.thinkingEnabled ?: settings.thinkingEnabled.value,
            thinkingLevel = overrides.thinkingLevel ?: settings.thinkingLevel.value,
            thinkingBudgetEnabled = overrides.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = overrides.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,
            webSearchEnabled = if (settings.webSearchEnabled.value) (overrides.webSearchEnabled ?: true) else false,
            shellEnabled = if (settings.shellEnabled.value) (overrides.shellEnabled ?: true) else false
        )
    }

    private fun buildGenerationPair(
        providerName: String,
        modelId: String,
        activeKey: String,
        resolvedSystemPrompt: String?,
        resolvedUserPrepend: String?,
        resolvedUserPostpend: String?,
        effectiveSettings: ConversationSettings,
        currentId: String
    ): Pair<GenerationConfig, GenerationContext> {
        val config = GenerationConfig(
            providerName = providerName,
            modelId = com.newoether.agora.model.ModelId.parse(modelId).modelName,
            apiKey = activeKey,
            effectiveSystemPrompt = resolvedSystemPrompt,
            maxContextWindow = effectiveSettings.contextWindow ?: settings.maxContextWindow.value,
            codeExecutionEnabled = effectiveSettings.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,
            googleSearchEnabled = effectiveSettings.googleSearchEnabled ?: settings.googleSearchEnabled.value,
            thinkingEnabled = effectiveSettings.thinkingEnabled ?: settings.thinkingEnabled.value,
            thinkingLevel = effectiveSettings.thinkingLevel ?: settings.thinkingLevel.value,
            thinkingBudgetEnabled = effectiveSettings.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = effectiveSettings.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,
            baseUrl = providerRegistry.getEffectiveBaseUrl(providerName),
            userPrepend = resolvedUserPrepend,
            userPostpend = resolvedUserPostpend,
            temperature = effectiveSettings.temperature,
            maxTokens = effectiveSettings.maxTokens,
            topP = effectiveSettings.topP,
            frequencyPenalty = effectiveSettings.frequencyPenalty,
            presencePenalty = effectiveSettings.presencePenalty
        )
        val genCtx = com.newoether.agora.viewmodel.GenerationContext(
            conversationId = currentId,
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = activeEmbeddingModel.value,
            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = effectiveSettings.webSearchEnabled ?: settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value,
            imageGenEnabled = settings.imageGenEnabled.value && settings.imageGenModel.value?.contains(":") == true,
            imageGenApiKey = resolveImageGenApiKey(),
            imageGenBaseUrl = resolveImageGenBaseUrl(),
            imageGenModel = resolveImageGenModelId(),
            imageGenSize = settings.imageGenSize.value,
            shellEnabled = effectiveSettings.shellEnabled ?: settings.shellEnabled.value,
            shellDevices = settings.shellDevices.value,
            sandboxEnabled = settings.sandboxEnabled.value,
            imageTranscriptionEnabled = settings.imageTranscriptionEnabledModels.value.contains(currentActiveModel.value),
            imageTranscriptionModel = settings.imageTranscriptionModel.value,
            imageTranscriptionBatchSize = settings.imageTranscriptionBatchSize.value,
            imageTranscriptionPrompt = settings.imageTranscriptionPrompt.value,
            transcriptionProviderName = resolveTranscriptionProviderName(),
            transcriptionModelId = resolveTranscriptionModelId(),
            transcriptionApiKey = resolveTranscriptionApiKey(),
            transcriptionBaseUrl = resolveTranscriptionBaseUrl()
        )
        return Pair(config, genCtx)
    }

    fun addShellDevice(device: com.newoether.agora.data.ShellDeviceConfig) {
        settings.addShellDevice(device)
    }
    fun updateShellDevice(device: com.newoether.agora.data.ShellDeviceConfig) {
        settings.updateShellDevice(device)
    }

    /**
     * Connects to an SSH host in capture mode and returns the server host key
     * (base64) together with its SHA-256 fingerprint, for the user to review and
     * pin. The host key is exchanged before authentication, so this succeeds even
     * if the password is wrong — letting the user pin the key first.
     */
    suspend fun verifySshHostKey(
        host: String, port: Int, user: String, password: String, timeoutSec: Int
    ): Result<Pair<String, String>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext Result.failure(Exception("Host is empty"))
        val client = com.newoether.agora.util.SshClient(
            host, port, user.ifBlank { "root" }, password, timeoutSec * 1000,
            pinnedHostKey = "", allowUnknownHostKey = true
        )
        try {
            client.executeCommand("true")
        } catch (_: Exception) {
            // Ignore — the host key is captured during the handshake regardless of auth result.
        } finally {
            client.close()
        }
        val key = client.capturedHostKey
        if (key.isNullOrBlank()) Result.failure(Exception("Could not reach host or no host key presented"))
        else Result.success(key to com.newoether.agora.util.SshClient.fingerprintSha256(key))
    }
    suspend fun testRemoteEmbedding(modelName: String, baseUrl: String, apiKey: String = ""): String? {
        val effectiveKey = apiKey.ifBlank { ragManager.resolveEmbeddingApiKey() ?: "" }
        val url = baseUrl.ifBlank { ragManager.resolveEmbeddingBaseUrl() }
        return withContext(Dispatchers.IO) {
            try {
                val result = EmbeddingClient.computeEmbedding("test connection", effectiveKey, modelName, url)
                if (result != null) "OK (dim=${result.size})" else "Request failed. Check API key, URL, and model name."
            } catch (e: Exception) {
                e.message ?: "Error"
            }
        }
    }

    fun createNewChat() {
        switchingJob?.cancel()
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
            _pendingConversationSettings.value = null
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
            val conversation = convRepo.getConversation(id)
            _currentActiveModel.value = conversation?.modelId
            triggerScrollToMessage()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            val existing = convRepo.getConversation(id)
            if (existing != null) {
                convRepo.upsertConversation(existing.copy(title = newTitle))
            }
        }
    }

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            _snackbarMessage.emit(SnackbarEvent(appContext.getString(R.string.snackbar_generating_title)))
            val conversation = convRepo.getConversation(conversationId) ?: return@launch
            val path = messages.value
            val firstUserMsg = path.firstOrNull { it.participant == Participant.USER } ?: return@launch
            val firstModelMsg = path
                .filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .firstOrNull()

            val titleModelId = settings.titleGenerationModel.value
            val modelIdWithPrefix = if (!titleModelId.isNullOrBlank()) titleModelId else (conversation.modelId ?: firstModelMsg?.modelName ?: settings.selectedModel.value)
            val providerName = getProviderForModel(modelIdWithPrefix)
            val modelId = com.newoether.agora.model.ModelId.parse(modelIdWithPrefix).modelName
            val activeKeyId = settings.activeApiKeyIds.value[providerName]
            val activeKey = settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
            if (!providerRegistry.isConfigured(providerName, activeKey)) {
                emitSnackbar(getApplication<Application>().getString(R.string.no_api_key_for_provider, providerName))
                return@launch
            }

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
                systemPrompt = settings.titleGenerationPrompt.value.ifBlank { BuiltInPrompts.TITLE_GENERATION_SYSTEM },
                maxContextWindow = 1,
                thinkingEnabled = false,
                baseUrl = providerRegistry.getEffectiveBaseUrl(providerName)
            )

            var title = ""
            try {
                // Serialize with embedding to avoid dual model load OOM
                if (providerName == "Local") {
                    LlamaEngine.modelMutex.withLock {
                        withContext(Dispatchers.IO) {
                            provider.generateResponse(titlePrompt, config).collect { event ->
                                if (event is StreamEvent.TextChunk) title += event.text
                                else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                            }
                        }
                        localProvider.releaseEngine()
                    }
                } else {
                    provider.generateResponse(titlePrompt, config).collect { event ->
                        if (event is StreamEvent.TextChunk) title += event.text
                        else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Title generation failed for provider=$providerName model=$modelId", e)
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
            val existing = convRepo.getConversation(id)
            if (existing != null) {
                convRepo.upsertConversation(existing.copy(systemPromptId = promptId))
            }
        }
    }

    fun setActiveModel(model: String) {
        _currentActiveModel.value = model
        _currentConversationId.value?.let { id ->
            viewModelScope.launch {
                val existing = convRepo.getConversation(id)
                if (existing != null) {
                    convRepo.upsertConversation(existing.copy(modelId = model))
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            stopGeneration()
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Delete attachment files for all messages in this conversation
            val allMsgs = convRepo.getMessagesForConversation(id).first()
            for (msg in allMsgs) {
                for (imagePath in msg.images) {
                    try { java.io.File(imagePath).delete() } catch (_: Exception) {}
                }
                // Delete video files referenced in attachmentMeta
                if (msg.attachmentMeta != null) {
                    try {
                        val meta = kotlinx.serialization.json.Json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(msg.attachmentMeta!!)
                        for (item in meta.items) {
                            if (item.type == "video" && item.originalUri?.startsWith("file://") == true) {
                                try {
                                    java.io.File(item.originalUri.removePrefix("file://")).delete()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            convRepo.deleteConversation(id)
            if (_currentConversationId.value == id) createNewChat()
        }
    }

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    fun deleteMessage(messageId: String): Int {
        val currentId = _currentConversationId.value ?: return 0
        stopGeneration()

        val allMsgs = _allMessages.value
        val targetMsg = allMsgs.find { it.id == messageId } ?: return 0

        // BFS walk to collect all descendant IDs (including hidden tool_/result_ messages)
        val staleIds = linkedSetOf(messageId)
        val queue = mutableListOf(messageId)
        while (queue.isNotEmpty()) {
            val pid = queue.removeAt(0)
            allMsgs.filter { it.parentId == pid }.forEach {
                if (staleIds.add(it.id)) queue.add(it.id)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Delete attachment files for all cascaded messages
            val staleList = allMsgs.filter { it.id in staleIds }
            for (msg in staleList) {
                for (imagePath in msg.images) {
                    try { java.io.File(imagePath).delete() } catch (_: Exception) {}
                }
                if (msg.attachmentMeta != null) {
                    for (item in msg.attachmentMeta.items) {
                        if ((item.type == "video" || item.type == "image" || item.type == "file") && item.originalUri?.startsWith("file://") == true) {
                            try { java.io.File(item.originalUri.removePrefix("file://")).delete() } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Delete embeddings for all cascaded messages
            for (id in staleIds) {
                convRepo.deleteEmbedding(id)
            }

            // DB delete
            convRepo.deleteMessagesByIds(staleIds.toList())

            // Update _allMessages
            _allMessages.update { it.filter { m -> m.id !in staleIds } }

            // Fix _selectedChildren — remove entries where key or value is deleted.
            // If a deleted message was the selected branch, switch to the next available sibling.
            val remainingMsgs = _allMessages.value
            val newSelected = _selectedChildren.value.toMutableMap()
            var changed = false
            for ((parentId, childId) in _selectedChildren.value) {
                // Remove entry if the parent itself was deleted
                if (parentId != null && parentId in staleIds) {
                    newSelected.remove(parentId)
                    changed = true
                    continue
                }
                if (childId in staleIds) {
                    val siblings = remainingMsgs.filter {
                        it.parentId == parentId &&
                            !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                            !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
                    }.sortedBy { it.timestamp }
                    if (siblings.isNotEmpty()) {
                        newSelected[parentId] = siblings.last().id
                    } else {
                        newSelected.remove(parentId)
                    }
                    changed = true
                }
            }
            if (changed) _selectedChildren.value = newSelected
        }

        return staleIds.size
    }

    fun stopGeneration() = session.stop()

    fun regenerate(messageId: String) {
        val currentId = _currentConversationId.value ?: return
        val modelId = currentActiveModel.value
        val providerName = getProviderForModel(modelId)
        val activeKeyId = settings.activeApiKeyIds.value[providerName]
        val activeKey = settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        if (!providerRegistry.isConfigured(providerName, activeKey)) {
            emitSnackbar(getApplication<Application>().getString(R.string.no_api_key_for_provider, providerName))
            return
        }

        val stopFinalization = session.stopForReplacement()
        // Capture ownership on the UI thread, immediately after stopGeneration advanced
        // the token, so no concurrent stop can slip in before we record it.
        val myUiToken = session.captureUiToken()

        // Compute IDs and set placeholder on the calling thread before launching IO work,
        // so the combine function never sees _streamingMessage=null while the error is in _allMessages.
        val messageToRegenerate = _allMessages.value.find { it.id == messageId } ?: return
        val parentId = messageToRegenerate.parentId ?: return
        val isErrorOrStopped = messageToRegenerate.status == MessageStatus.ERROR || messageToRegenerate.status == MessageStatus.STOPPED
        val isLatest = _allMessages.value.none { it.parentId == messageId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
        // Error/stopped: purge and replace in-place. Normal: create new branch.
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
        val selectedAfterRegenerate = newMap.toMap()
        _selectedChildren.value = selectedAfterRegenerate

        _streamingMessage.value = placeholder
        _isLoading.value = true

        session.generationJob = session.scope.launch {
            // Wait only for the short STOPPED DB finalization. The cancelled provider
            // may still be unwinding, but it no longer owns the next generation path.
            stopFinalization?.join()
            val myPersistId = session.nextPersistId()
            try {
                val userMessage = _allMessages.value.find { it.id == parentId } ?: return@launch

            if (isErrorOrStopped && isLatest) {
                // Purge stale tool call children, thinking content, and embeddings
                val allMsgs = _allMessages.value
                val staleIds = mutableListOf<String>()
                val queue = mutableListOf(modelMessageId)
                while (queue.isNotEmpty()) {
                    val pid = queue.removeAt(0)
                    allMsgs.filter { it.parentId == pid && (it.id.startsWith(Constants.TOOL_MSG_PREFIX) || it.id.startsWith(Constants.RESULT_MSG_PREFIX)) }
                        .forEach { staleIds.add(it.id); queue.add(it.id) }
                }
                if (staleIds.isNotEmpty()) {
                    convRepo.deleteMessagesByIds(staleIds)
                    _allMessages.update { it.filter { m -> m.id !in staleIds } }
                }
                convRepo.deleteEmbedding(modelMessageId)
                convRepo.upsertMessage(MessageEntity(
                    id = modelMessageId, conversationId = currentId, parentId = parentId,
                    text = "", thoughts = null, thoughtTitle = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                    modelName = currentActiveModel.value, toolCallJson = null
                ))
                } else {
                    // New branch — old message and its tool calls stay as a selectable branch
                    convRepo.upsertMessage(MessageEntity(
                        id = modelMessageId, conversationId = currentId, parentId = parentId,
                        text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                        modelName = currentActiveModel.value
                    ))
                }
                persistSelectedChildren(currentId, selectedAfterRegenerate)
                convRepo.getConversation(currentId)?.let { conv ->
                    convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                }
            val resolved = buildEffectiveSystemPrompt(currentId)
            val effectiveSettings = buildEffectiveConversationSettings(currentId)
            val (config, genCtx) = buildGenerationPair(
                providerName, modelId, activeKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, currentId
            )
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = true,
                replaceMessageId = messageId,
                modelName = currentActiveModel.value,
                config = config,
                ctx = genCtx,
                generationJob = session.generationJob,
                onStreamUpdate = { session.streamUpdate(myUiToken, it) },
                onLoadingChange = { session.loadingChange(myUiToken, it) },
                onGeneratingIdChange = { session.generatingIdChange(myUiToken, it) },
                onStreamClear = { session.streamClear(myUiToken) },
                isLatestPersist = { session.isLatestPersist(myPersistId) }
            )
            } finally {
                session.loadingChange(myUiToken, false)
            }
        }
    }

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) {
        if (_isLoading.value && _generatingInConversationId.value == _currentConversationId.value) return
        val siblings = _allMessages.value.filter { it.parentId == parentId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }.sortedBy { it.timestamp }
        if (siblings.size < 2) return
        var currentIndex = siblings.indexOfFirst { it.id == currentMessageId }
        if (currentIndex == -1) {
            val selectedId = _selectedChildren.value[parentId]
            currentIndex = siblings.indexOfFirst { it.id == selectedId }
        }
        if (currentIndex == -1) return
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        if (newIndex == currentIndex) return
        
        switchingJob?.cancel()
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            val newMap = _selectedChildren.value.toMutableMap()
            val targetMessage = siblings[newIndex]
            newMap[parentId] = targetMessage.id
            _selectedChildren.value = newMap
            
            _branchSwitchTrigger.value = null
            _branchSwitchTrigger.value = targetMessage.id
        }
    }

    fun editMessage(messageId: String, newText: String) {
        val currentId = _currentConversationId.value ?: return
        val modelId = currentActiveModel.value
        val providerName = getProviderForModel(modelId)
        val activeKeyId = settings.activeApiKeyIds.value[providerName]
        val activeKey = settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        if (!providerRegistry.isConfigured(providerName, activeKey)) {
            emitSnackbar(getApplication<Application>().getString(R.string.no_api_key_for_provider, providerName))
            return
        }

        val stopFinalization = session.stopForReplacement()
        val myUiToken = session.captureUiToken()
        session.generationJob = session.scope.launch {
            stopFinalization?.join()
            val myPersistId = session.nextPersistId()
            try {
            val messageToEdit = _allMessages.value.find { it.id == messageId } ?: return@launch
            val newUserMessageId = UUID.randomUUID().toString()
            convRepo.upsertMessage(MessageEntity(
                id = newUserMessageId, conversationId = currentId, parentId = messageToEdit.parentId,
                text = newText, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val newMap = _selectedChildren.value.toMutableMap()
            newMap[messageToEdit.parentId] = newUserMessageId
            val selectedAfterUserEdit = newMap.toMap()
            _selectedChildren.value = selectedAfterUserEdit
            persistSelectedChildren(currentId, selectedAfterUserEdit)
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            convRepo.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = newUserMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            convRepo.getConversation(currentId)?.let { conv ->
                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
            }
            // Set _streamingMessage BEFORE _allMessages so the combine never
            // evaluates with stale _allMessages data but no streaming overlay.
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = newUserMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = currentActiveModel.value
            )
            session.streamUpdate(myUiToken, placeholder)
            _allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder }
            val editChildren = selectedAfterUserEdit.toMutableMap()
            editChildren[newUserMessageId] = modelMessageId
            val selectedAfterModelEdit = editChildren.toMap()
            _selectedChildren.value = selectedAfterModelEdit
            persistSelectedChildren(currentId, selectedAfterModelEdit)
            val resolved = buildEffectiveSystemPrompt(currentId)
            val effectiveSettings = buildEffectiveConversationSettings(currentId)
            val (config, genCtx) = buildGenerationPair(
                providerName, modelId, activeKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, currentId
            )
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = currentActiveModel.value,
                config = config,
                ctx = genCtx,
                generationJob = session.generationJob,
                onStreamUpdate = { session.streamUpdate(myUiToken, it) },
                onLoadingChange = { session.loadingChange(myUiToken, it) },
                onGeneratingIdChange = { session.generatingIdChange(myUiToken, it) },
                onStreamClear = { session.streamClear(myUiToken) },
                isLatestPersist = { session.isLatestPersist(myPersistId) }
            )
            } finally {
                session.loadingChange(myUiToken, false)
            }
        }
    }

    private data class ResolvedPrompt(
        val systemPrompt: String?,
        val userPrepend: String?,
        val userPostpend: String?
    )

    private suspend fun buildEffectiveSystemPrompt(currentId: String): ResolvedPrompt {
        val conversation = convRepo.getConversation(currentId)
        val targetPromptId = conversation?.systemPromptId ?: settings.activeSystemPromptId.value
        val entry = settings.systemPrompts.value.find { it.id == targetPromptId }
        val activeMemory = memoryManager.getActiveMemory()
        val includeActiveMemory = settings.accessActiveMemory.value
        val modelId = com.newoether.agora.model.ModelId.parse(currentActiveModel.value).modelName

        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val now = java.util.Date()

        val runtimeValues = mapOf(
            PredefinedVariables.TIME to sdf.format(now),
            PredefinedVariables.DATE to dateSdf.format(now),
            PredefinedVariables.SENT_TIME to sdf.format(now),
            PredefinedVariables.SENT_DATE to dateSdf.format(now),
            PredefinedVariables.MODEL_ID to modelId,
            PredefinedVariables.ACTIVE_MEMORY to if (includeActiveMemory && activeMemory.isNotBlank()) activeMemory else ""
        )

        if (entry != null) {
            val systemItems = entry.resolvedSystemItems
            // Prepend/postpend: {sent_time}/{sent_date} stay as placeholders resolved per-message in applyUserTemplate
            val perMsgValues = runtimeValues.filterKeys { it !in PredefinedVariables.PER_MESSAGE_VARS }
            return ResolvedPrompt(
                systemPrompt = PredefinedVariables.compile(systemItems, runtimeValues).ifBlank { null },
                userPrepend = PredefinedVariables.compile(entry.userPrependItems, perMsgValues, emptyMap()).ifBlank { null },
                userPostpend = PredefinedVariables.compile(entry.userPostpendItems, perMsgValues, emptyMap()).ifBlank { null }
            )
        }

        return ResolvedPrompt(null, null, null)
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

    /** The persisted user-message media (final image paths) plus structured attachment metadata. */
    private data class MessagePayload(
        val allImages: List<String>,
        val attachmentMeta: com.newoether.agora.model.AttachmentMeta?
    )

    /**
     * Resolves the outgoing message's attachments into concrete image paths and
     * structured [com.newoether.agora.model.AttachmentMeta]. Handles legacy images,
     * images, videos (frame extraction / slicing), text files, and rendered PDF pages,
     * then reconciles each metadata item's `imageIndex` against the actual processed
     * image positions. Extracted from [sendMessage] so the send path reads as a sequence
     * of steps rather than one ~320-line method.
     */
    private suspend fun buildMessagePayload(
        app: Application,
        images: List<String>,
        attachments: List<SelectedAttachment>
    ): MessagePayload {
        // mediaUris: URIs that need processImages (images, video content:// URIs)
        // directPaths: paths that skip processImages (pre-extracted frames, PDF copies, rendered pages)
        val mediaUris = mutableListOf<String>()
        val directPaths = mutableListOf<String>()
        val sliceConfigs = mutableMapOf<String, VideoSliceConfig>()
        val metaItems = mutableListOf<com.newoether.agora.model.AttachmentItem>()
        var nextImageIndex = 0

        // Process legacy images list (backward compatibility)
        for (uri in images) {
            val mimeType = try { app.contentResolver.getType(android.net.Uri.parse(uri)) } catch (_: Exception) { null }
            if (mimeType != null && !mimeType.startsWith("image/") && !mimeType.startsWith("video/")) {
                mediaUris.add(uri)
                try {
                    val isText = mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml"
                    if (isText) {
                        app.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                            val content = stream.bufferedReader().readText().take(Constants.MAX_FILE_CONTENT_READ_LENGTH)
                            if (content.isNotBlank()) {
                                val fileName = getFileName(app, android.net.Uri.parse(uri))
                                // Legacy file content stored in text (pre-attachmentMeta)
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else {
                mediaUris.add(uri)
            }
        }

        // Process new SelectedAttachment list
        for (att in attachments) {
            when (att.type) {
                "image" -> {
                    mediaUris.add(att.uri)
                    metaItems.add(com.newoether.agora.model.AttachmentItem(
                        originalUri = att.uri, type = "image", mimeType = att.mimeType,
                        imageIndex = nextImageIndex
                    ))
                    nextImageIndex++
                }
                "video" -> {
                    // Copy video to local storage for export/playback survival
                    val videoExt = when {
                        att.mimeType?.contains("mp4") == true -> "mp4"
                        att.mimeType?.contains("webm") == true -> "webm"
                        att.mimeType?.contains("quicktime") == true -> "mov"
                        else -> "mp4"
                    }
                    val videoFile = java.io.File(app.filesDir, "vid_original_${java.util.UUID.randomUUID()}.$videoExt")
                    var localVideoUri: String? = null
                    try {
                        app.contentResolver.openInputStream(android.net.Uri.parse(att.uri))?.use { input ->
                            videoFile.outputStream().use { input.copyTo(it) }
                        }
                        localVideoUri = "file://${videoFile.absolutePath}"
                    } catch (_: Exception) {
                        // Fallback: keep original content URI (may expire)
                        localVideoUri = att.uri
                    }

                    if (att.processedFrames != null && att.processedFrames.isNotEmpty()) {
                        metaItems.add(com.newoether.agora.model.AttachmentItem(
                            originalUri = localVideoUri, type = "video",
                            fileName = att.fileName, mimeType = att.mimeType,
                            imageIndex = nextImageIndex, pageCount = att.frameCount
                        ))
                        directPaths.addAll(att.processedFrames)
                        nextImageIndex += att.processedFrames.size
                    } else {
                        val frameCount = att.frameCount ?: 1
                        metaItems.add(com.newoether.agora.model.AttachmentItem(
                            originalUri = localVideoUri, type = "video",
                            fileName = att.fileName, mimeType = att.mimeType,
                            imageIndex = nextImageIndex, pageCount = att.frameCount
                        ))
                        mediaUris.add(att.uri)
                        if (att.frameCount != null && att.frameCount > 1 && att.sliceIntervalMs != null) {
                            sliceConfigs[att.uri] = VideoSliceConfig(
                                intervalMicros = att.sliceIntervalMs * 1000L,
                                frameCount = att.frameCount
                            )
                        }
                        nextImageIndex += frameCount
                    }
                }
                "file" -> {
                    var textContent: String? = null
                    try {
                        app.contentResolver.openInputStream(android.net.Uri.parse(att.uri))?.use { stream ->
                            val content = stream.bufferedReader().readText().take(Constants.MAX_FILE_CONTENT_READ_LENGTH)
                            if (content.isNotBlank()) {
                                val fileName = att.fileName ?: getFileName(app, android.net.Uri.parse(att.uri))
                                textContent = content
                            }
                        }
                    } catch (_: Exception) {}
                    metaItems.add(com.newoether.agora.model.AttachmentItem(
                        originalUri = att.uri, type = "file",
                        fileName = att.fileName, mimeType = att.mimeType,
                        textContent = textContent
                    ))
                }
                "pdf" -> {
                    val pagePaths = if (att.preRenderedPaths != null && att.preRenderedPaths.isNotEmpty()) {
                        val sel = att.selectedPages ?: att.preRenderedPaths.indices.toSet()
                        att.preRenderedPaths.filterIndexed { i, _ -> i in sel }
                    } else {
                        com.newoether.agora.util.PdfPageRenderer.renderAsImages(app, android.net.Uri.parse(att.uri), att.selectedPages)
                    }
                    if (pagePaths.isEmpty()) {
                        _snackbarMessage.emit(SnackbarEvent(app.getString(R.string.pdf_render_failed)))
                        continue
                    }
                    metaItems.add(com.newoether.agora.model.AttachmentItem(
                        originalUri = att.uri, type = "pdf",
                        fileName = att.fileName, mimeType = "application/pdf",
                        imageIndex = nextImageIndex, pageCount = pagePaths.size
                    ))
                    directPaths.addAll(pagePaths)
                    nextImageIndex += pagePaths.size
                }
            }
        }

        val processedImages = if (mediaUris.isNotEmpty()) generationManager.processImages(mediaUris, sliceConfigs) else emptyList()
        val allImages = processedImages + directPaths

        // Recalculate imageIndex for all meta items based on final allImages positions.
        // nextImageIndex tracked the expected order:
        //   First N items correspond to mediaUris entries (→ processedImages)
        //   Remaining items correspond to directPaths entries
        // After processing, processedImages may differ in size from mediaUris.
        // We build a position map: for each metaItem that has imageIndex < mediaUris.size,
        // it was tracking an offset within mediaUris. We need the actual offset within processedImages.
        val uriToResultMap = mutableListOf<IntRange>() // for each mediaUris entry, the range in processedImages
        var pos = 0
        for (uri in mediaUris) {
            val start = pos
            // Count consecutive results belonging to this URI by scanning forward until
            // we find files that don't correspond. Since we can't distinguish, use a simple
            // heuristic: each URI produces either 0 or 1+ results. The slice configs tell us
            // how many frames per video.
            val config = sliceConfigs[uri]
            val expectedCount = config?.frameCount ?: 1
            val end = minOf(pos + expectedCount, processedImages.size)
            uriToResultMap.add(start until end)
            pos = end
        }
        // Cap at processedImages size
        val adjustedMetaItems = metaItems.map { item ->
            val idx = item.imageIndex
            if (idx == null) {
                item
            } else if (idx < mediaUris.size && idx < uriToResultMap.size) {
                val range = uriToResultMap[idx]
                item.copy(imageIndex = range.first)
            } else if (idx in mediaUris.size until (mediaUris.size + directPaths.size)) {
                // This item's imageIndex is relative to directPaths start
                item.copy(imageIndex = processedImages.size + (idx - mediaUris.size))
            } else {
                // Fallback: keep original index (shouldn't happen for well-formed input)
                item
            }
        }
        val attachmentMeta = if (adjustedMetaItems.isNotEmpty()) {
            com.newoether.agora.model.AttachmentMeta(items = adjustedMetaItems)
        } else null
        return MessagePayload(allImages, attachmentMeta)
    }

    fun sendMessage(text: String, images: List<String> = emptyList(), attachments: List<SelectedAttachment> = emptyList()): Boolean {
        if (!session.sendGate.compareAndSet(false, true)) return false
        var committed = false
        try {
        val modelId = currentActiveModel.value
        if (modelId.isBlank()) {
            emitSnackbar(getApplication<Application>().getString(R.string.no_model_selected))
            return false
        }
        val providerName = getProviderForModel(modelId)
        val activeKeyId = settings.activeApiKeyIds.value[providerName]
        val activeKey = settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
        if (!providerRegistry.isConfigured(providerName, activeKey)) {
            emitSnackbar(getApplication<Application>().getString(R.string.no_api_key_for_provider, providerName))
            return false
        }
        if (providerName == "Local") {
            val localModelId = modelId.substringAfter("Local:")
            val config = settings.localChatModels.value.find { it.modelId == localModelId }
            if (config == null || !java.io.File(config.localFilePath).exists()) {
                emitSnackbar(getApplication<Application>().getString(R.string.local_model_not_found))
                return false
            }
        }
        val stopFinalization = session.stopForReplacement()
        // Set loading immediately so UI shows sending state during attachment processing
        _isLoading.value = true
        // Capture ownership on the UI thread right after stopGeneration advanced the token.
        val myUiToken = session.captureUiToken()

        committed = true
        session.generationJob = session.scope.launch {
            try {
            // Wait only for the short STOPPED DB finalization. The cancelled provider
            // may still be unwinding, but it no longer owns the next generation path.
            stopFinalization?.join()
            val myPersistId = session.nextPersistId()
            val app = getApplication<Application>()
            val finalText = text
            val (allImages, attachmentMeta) = buildMessagePayload(app, images, attachments)
            var currentId = _currentConversationId.value
            val wasNewChat = _isNewChatMode.value
            if (wasNewChat) {
                val newId = UUID.randomUUID().toString()
                convRepo.upsertConversation(ChatEntity(id = newId, title = appContext.getString(R.string.new_chat), modelId = currentActiveModel.value, systemPromptId = _pendingSystemPromptId.value))
                _currentConversationId.value = newId
                _isNewChatMode.value = false
                currentId = newId
            }
            if (currentId == null) {
                val newId = UUID.randomUUID().toString()
                convRepo.upsertConversation(ChatEntity(id = newId, title = appContext.getString(R.string.new_chat), modelId = currentActiveModel.value, systemPromptId = _pendingSystemPromptId.value))
                _currentConversationId.value = newId
                _isNewChatMode.value = false
                currentId = newId
            }
            // Apply pending per-conversation settings if any (from Advanced dialog in new chat)
            val pendingSettings = _pendingConversationSettings.value
            if (pendingSettings != null) {
                settings.setConversationSettings(currentId, pendingSettings)
                _pendingConversationSettings.value = null
            }
            val currentPath = messages.value
            val lastMessageId = currentPath.lastOrNull()?.id
            val userMessageId = UUID.randomUUID().toString()
            convRepo.upsertMessage(MessageEntity(
                id = userMessageId, conversationId = currentId, parentId = lastMessageId,
                text = finalText, images = allImages, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis(),
                attachmentMeta = attachmentMeta?.let { kotlinx.serialization.json.Json.encodeToString(it) }
            ))
            settings.incrementMessagesSent()
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            convRepo.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = userMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            convRepo.getConversation(currentId)?.let { conv ->
                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
            }
            // Set _streamingMessage BEFORE _allMessages, so when the combine
            // re-evaluates on the _allMessages change, _streamingMessage is already
            // visible — eliminating the single-frame gap.
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = userMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = currentActiveModel.value
            )
            session.streamUpdate(myUiToken, placeholder)
            _allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder }
            val newChildren = _selectedChildren.value.toMutableMap()
            newChildren[userMessageId] = modelMessageId
            _selectedChildren.value = newChildren
            triggerScrollToMessage(userMessageId)

            val resolved = buildEffectiveSystemPrompt(currentId)
            val effectiveSettings = buildEffectiveConversationSettings(currentId)
            val (config, genCtx) = buildGenerationPair(
                providerName, modelId, activeKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, currentId
            )
            try {
                generationManager.generate(
                    conversationId = currentId,
                    modelMessageId = modelMessageId,
                    startTime = startTime,
                    isRegenerate = false,
                    replaceMessageId = null,
                    modelName = currentActiveModel.value,
                    config = config,
                    ctx = genCtx,
                    generationJob = session.generationJob,
                    onStreamUpdate = { session.streamUpdate(myUiToken, it) },
                    onLoadingChange = { session.loadingChange(myUiToken, it) },
                    onGeneratingIdChange = { session.generatingIdChange(myUiToken, it) },
                    onStreamClear = { session.streamClear(myUiToken) },
                    isLatestPersist = { session.isLatestPersist(myPersistId) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            val lastMsg = _allMessages.value.find { it.id == modelMessageId }
            if (wasNewChat && settings.titleGenerationEnabled.value && session.generationJob?.isActive == true && lastMsg?.status != MessageStatus.ERROR) {
                generateTitle(currentId)
            }
        } finally {
            // Token-gated: only the still-current generation clears the button, so a
            // cancelled/superseded coroutine can't revert the icon mid-generation.
            session.loadingChange(myUiToken, false)
            // sendGate must ALWAYS be freed, even when this coroutine was cancelled
            // by a subsequent regenerate(). Otherwise the send button stays locked.
            session.sendGate.set(false)
        }
        } // end launch
    } finally {
        if (!committed) session.sendGate.set(false)
    }
        return true
    }

    /**
     * Onboarding-focused model fetch for a single provider.
     *
     * Unlike [fetchAvailableModels] this carries no global side effects: no
     * `_isSyncingModels` guard (so re-entry always refetches the latest key),
     * no enabled-set intersection, and no snackbar. It is a plain suspend
     * function so the caller's coroutine owns its lifecycle — cancelling that
     * coroutine cooperatively aborts the in-flight network request, which keeps
     * the welcome flow seamless (no stale result can land after the user edits
     * their key and returns). Results are persisted so the [availableModels]
     * flow updates the list. Returns the prefixed model ids, or empty on
     * failure / unconfigured provider.
     */
    suspend fun fetchModelsForProvider(name: String): List<String> = providerRegistry.fetchModelsForProvider(name)

    fun computeProviderFingerprint(): String = providerRegistry.computeFingerprint()

    fun fetchAvailableModels() {
        viewModelScope.launch {
            if (_isSyncingModels.value) return@launch
            _isSyncingModels.value = true
            val successProviders = mutableListOf<String>()
            val failedProviders = mutableListOf<String>()
            var skippedCount = 0

            // Ensure custom providers are loaded into the providers map before iterating
            providerRegistry.ensureCustomProvidersRegistered()

            val message = try {
                providerRegistry.all.forEach { (name, providerInstance) ->
                    if (name == "Local") return@forEach

                    try {
                        val activeKeyId = settings.activeApiKeyIds.value[name]
                        val activeKey = settings.apiKeys.value.find { it.id == activeKeyId }?.key ?: ""
                        val isCustomProvider = !providerRegistry.isBuiltIn(name)
                        val currentBaseUrl = if (isCustomProvider) {
                            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() } ?: providerInstance.defaultBaseUrl
                        } else {
                            settings.providerBaseUrls.value[name]
                        }

                        if (!providerRegistry.isConfigured(name, activeKey)) {
                            skippedCount++
                            settings.saveAvailableModels(name, emptyList())
                            return@forEach
                        }

                        val rawModels = withTimeout(10_000L) {
                            providerInstance.fetchModels(activeKey, currentBaseUrl)
                        }
                        if (rawModels.isNotEmpty()) {
                            val prefixedModels = rawModels.map { "$name:${it.removePrefix("models/")}" }
                            settings.saveAvailableModels(name, prefixedModels)
                            successProviders.add(name)
                        } else {
                            failedProviders.add(name)
                        }
                    } catch (e: Exception) {
                        failedProviders.add(name)
                    }
                }

                val allFetchedModels = settings.getAvailableModels().values.flatten().toSet()
                val newEnabled = settings.enabledModels.value.intersect(allFetchedModels)
                settings.setEnabledModels(newEnabled)

                // Save fingerprint on any successful fetch so we don't re-fetch on next visit
                settings.saveLastModelsFetchFingerprint(computeProviderFingerprint())

                when {
                    successProviders.isNotEmpty() && failedProviders.isEmpty() ->
                        appContext.getString(R.string.sync_success_providers, successProviders.size)
                    successProviders.isNotEmpty() && failedProviders.isNotEmpty() ->
                        appContext.getString(R.string.sync_partial, successProviders.joinToString(), failedProviders.joinToString())
                    successProviders.isEmpty() && failedProviders.isNotEmpty() ->
                        appContext.getString(R.string.sync_failed_providers, failedProviders.joinToString())
                    else -> if (skippedCount > 0) appContext.getString(R.string.sync_no_providers) else appContext.getString(R.string.sync_completed)
                }
            } catch (e: Exception) {
                appContext.getString(R.string.sync_failed_providers, e.message ?: appContext.getString(R.string.unknown_error))
            } finally {
                _isSyncingModels.value = false
            }

            _snackbarMessage.tryEmit(SnackbarEvent(message))
        }
    }

    // ---- Data Control: Export / Import ----

    fun refreshDataCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            _conversationCount.value = convRepo.getAllConversationsList().size
            _memoryCount.value = memoryManager.listFiles().size +
                (if (memoryManager.getActiveMemory().isNotEmpty()) 1 else 0)
            _systemPromptCount.value = settings.getSystemPrompts().size
        }
    }

    fun exportData(uri: Uri, categories: Set<DataExporter.ExportCategory>, includeApiKeys: Boolean) =
        importExport.exportData(uri, categories, includeApiKeys)
    fun previewImport(uri: Uri) = importExport.previewImport(uri)
    fun clearImportState() = importExport.clearImportState()
    fun setClaudeImportPreview(preview: ClaudeChatImporter.ImportPreview) = importExport.setClaudeImportPreview(preview)
    fun previewClaudeChat(uri: Uri) = importExport.previewClaudeChat(uri)
    fun setClaudeImportError(error: String) = importExport.setClaudeImportError(error)
    fun clearClaudeImportState() = importExport.clearClaudeImportState()
    fun importClaudeChat(uri: Uri, strategy: com.newoether.agora.ui.settings.ImportStrategy, selectedIds: Set<String>) =
        importExport.importClaudeChat(uri, strategy, selectedIds)
    fun previewGptChat(uri: Uri) = importExport.previewGptChat(uri)
    fun setGptImportError(error: String) = importExport.setGptImportError(error)
    fun clearGptImportState() = importExport.clearGptImportState()
    fun importGptChat(uri: Uri, strategy: com.newoether.agora.ui.settings.ImportStrategy, selectedIds: Set<String>) =
        importExport.importGptChat(uri, strategy, selectedIds)
    fun importData(uri: Uri, decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) =
        importExport.importData(uri, decisions)
}
