package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.R
import com.newoether.agora.api.*
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.anthropic.*
import com.newoether.agora.api.gemini.*
import com.newoether.agora.api.local.*
import com.newoether.agora.api.ollama.*
import com.newoether.agora.api.openai.*
import com.newoether.agora.automation.TaskExecutionEngine.BridgeOutcome
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ClaudeChatImporter
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.PredefinedVariables

import com.newoether.agora.data.ShellDeviceConfig

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AutoBackupWorker
import com.newoether.agora.ui.settings.ImportStrategy
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.PdfPageRenderer
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.util.SshClient
import com.newoether.agora.util.UpdateChecker
import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class AnimatedScrollDestination {
    MESSAGE,
    ABSOLUTE_BOTTOM,
}

data class AnimatedScrollRequest(
    val id: Long,
    val conversationId: String,
    val targetMessageId: String?,
    val destination: AnimatedScrollDestination = AnimatedScrollDestination.MESSAGE,
    val attachedOnly: Boolean = false,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    // [chatDao] and [settingsManager] are retained ONLY to pass to ImportExportManager,
    // which threads them into DataExporter/DataImporter (bulk data-layer utilities that
    // genuinely need raw DAO/DataStore). All other managers use repositories uniformly.
    private val database: com.newoether.agora.data.local.ChatDatabase,
    private val chatDao: com.newoether.agora.data.local.ChatDao,
    private val settingsManager: com.newoether.agora.data.SettingsManager,
    val memoryManager: MemoryManager,
    private val appContext: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    // All injected via AppContainer/ChatViewModelFactory — the single construction site.
    val autoBackupManager: AutoBackupManager,
    conversationRepository: ConversationRepository,
    settingsRepository: SettingsRepository,
    // Process-scoped generation singletons, shared with background task execution.
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    // App-scoped automation orchestrator (task CRUD + run-now).
    private val taskManager: com.newoether.agora.automation.TaskManager,
    private val loopManager: com.newoether.agora.automation.LoopManager,
    private val automationToolProvider: com.newoether.agora.tool.AutomationToolProvider,
    private val conversationExecutionCoordinator: com.newoether.agora.automation.ConversationExecutionCoordinator,
    private val automationExecutionGate: com.newoether.agora.automation.AutomationExecutionGate,
    private val generationRegistry: ConversationStateRegistry,
    private val shellConfirmation: ShellConfirmationController,
    private val mcpRegistry: com.newoether.agora.mcp.McpRegistry,
    private val mcpToolProvider: com.newoether.agora.tool.McpToolProvider,
    private val taskExecutionEngine: com.newoether.agora.automation.TaskExecutionEngine,
) : AndroidViewModel(application) {

    companion object {
        /** Overlay fade duration for conversation-switch transitions. */
        private const val SWITCH_OVERLAY_FADE_MS = 200L
        /** Auto-delete period tiers in hours: 7 days, 30 days, 365 days. */
        private val AUTO_DELETE_TIERS_HOURS = listOf(168, 720, 8760)
    }

    val settings: SettingsRepository = settingsRepository

    /**
     * Conversation/message persistence behind the repository layer. CRUD, cascade-delete,
     * branch-selection and stuck-message logic live in [ConversationRepository]; managers
     * receive the repository (not raw DAO) for a uniform boundary.
     */
    private val convRepo: ConversationRepository = conversationRepository
    private val composerDrafts = ComposerDraftController(conversationRepository)
    private val attachmentOrphanSweeper = AttachmentOrphanSweeper(
        conversations = conversationRepository,
        filesDirectory = application.filesDir,
    )
    private val conversationForkShare =
        ConversationForkShareService(
            conversationRepository,
            settingsRepository,
            File(application.filesDir, "fork-attachments"),
        )

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = viewModelScope,
    ) { _snackbarMessage.emit(it) }

    /**
     * Data export/import orchestration (native backup + Claude + GPT formats).
     * [chatDao] and [settingsManager] are passed through to [DataExporter]/[DataImporter]
     * which need raw DAO/DataStore for bulk cross-table operations.
     */
    val importExport = ImportExportManager(
        app = getApplication(),
        conversations = convRepo,
        database = database,
        chatDao = chatDao,
        settingsManager = settingsManager,
        memoryManager = memoryManager,
        scope = viewModelScope,
        emitSnackbar = { _snackbarMessage.emit(it) },
        onDataChanged = { refreshDataCounts() },
        automationExecutionGate = automationExecutionGate,
        quiesceAutomation = {
            taskManager.cancelAllExecutionsForImport()
            loopManager.cancelAllExecutionsForImport()
        },
        resumeAutomationScheduling = taskManager::refreshSchedulingAfterImport,
    )

    /** Local (on-device) chat-model configuration CRUD. */
    val modelManager = ModelManager(settings, viewModelScope)
    private val customModelMutationMutex = Mutex()

    // [providerRegistry] and [localProvider] are now constructor-injected, process-scoped
    // singletons (see AppContainer) so background task execution shares the same instances.

    /**
     * Startup jobs deferred until all StateFlow/property backing fields are
     * initialized — avoids the constructor this-escape where a Dispatchers.IO
     * coroutine accesses a field whose JVM backing field is still null.
     */
    /** Build the proxy config from settings and push it into the shared HttpClient. */
    private fun applyProxy() {
        val host = settings.proxyHost.value.trim()
        val cfg = if (settings.proxyEnabled.value && host.isNotEmpty()) {
            com.newoether.agora.api.HttpClient.ProxyConfig(
                type = if (settings.proxyType.value.equals("socks5", ignoreCase = true))
                    com.newoether.agora.api.HttpClient.ProxyType.SOCKS
                else com.newoether.agora.api.HttpClient.ProxyType.HTTP,
                host = host,
                port = settings.proxyPort.value.trim().toIntOrNull() ?: 0,
                username = settings.proxyUsername.value,
                password = settings.proxyPassword.value,
                bypass = settings.proxyBypass.value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        } else null
        com.newoether.agora.api.HttpClient.setProxy(cfg)
    }

    private fun startInitJobs() {
        // Apply the network proxy at startup and whenever its settings change.
        viewModelScope.launch {
            val proxyFlows = listOf(
                settings.proxyEnabled.map { it.toString() },
                settings.proxyType, settings.proxyHost, settings.proxyPort,
                settings.proxyUsername, settings.proxyPassword, settings.proxyBypass
            )
            kotlinx.coroutines.flow.combine(proxyFlows) { it }.collect { applyProxy() }
        }
        // Auto-check for updates on launch (at most once per day)
        viewModelScope.launch(Dispatchers.IO) {
            if (settings.getAutoUpdateCheck()) {
                val lastCheck = settings.getLastUpdateCheckTime()
                val now = System.currentTimeMillis()
                if (now - lastCheck > 24 * 60 * 60 * 1000L) {
                    settings.saveLastUpdateCheckTime(now)
                    val info = UpdateChecker.check(getCurrentVersion())
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
            val total = convRepo.getIndexableMessageCount()
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
        // Sweep orphaned attachment files left in filesDir or run-inputs by a process death,
        // interrupted Edit, or the v18 removal of v17's cloned Regenerate inputs. A file is junk
        // only when nothing references it: a stored message's images, its attachmentMeta
        // originalUri (the video-playback / file-open source), or any conversation draft's
        // private copies. The 1h age guard means a copy racing this sweep is never deleted.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                attachmentOrphanSweeper.sweep()
            } catch (e: Exception) { DebugLog.d("ChatViewModel", "Attachment orphan sweep error", e) }
        }
        // ── Auto Backup ──────────────────────────────────────────
        try {
            AutoBackupWorker.schedule(getApplication())
        } catch (e: Exception) {
            // Losing periodic backups silently would be data-loss-adjacent — log it.
            DebugLog.e("ChatViewModel", "AutoBackupWorker.schedule failed", e)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { autoBackupManager.checkAndBackup() } catch (e: Exception) { DebugLog.e("ChatViewModel", "Auto backup check failed", e) }
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
                    settings.saveAvailableModels(Constants.PROVIDER_LOCAL, localIds)
                    lastLocalIds = localIds
                }
                if (aliases != lastAliases) {
                    settings.saveModelAliases(aliases)
                    lastAliases = aliases
                }
            }
        }
        // Provider map / model-list sync jobs now run on the process-scoped registry
        // (launched once in AppContainer), so they survive ViewModel recreation.
    }

    // Per-conversation generation lifecycle (IO scope, job, slot, race-free stop/persist tokens)
    // lives in [ConversationGenerationState], one per conversation via [generationRegistry].

    private val generationManager by lazy {
        GenerationManager(
            app = application,
            conversations = convRepo,
            memoryManager = memoryManager,
            providers = providerRegistry.all,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOf(automationToolProvider, mcpToolProvider),
        ).also { gm ->
            // Gate lives in RagManager.indexMessageForRag (autoCacheEnabled + active model).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = { server, summary -> shellConfirmation.confirm(server, summary) }
        }
    }

    val sandboxManager: SandboxManager? by lazy {
        sandboxFactory?.create()
    }
    val isSandboxFlavor: Boolean = sandboxFactory?.isAvailable() == true
    val mcpServerSnapshots: StateFlow<Map<String, com.newoether.agora.mcp.McpServerSnapshot>>
        get() = mcpRegistry.snapshots

    fun refreshMcpServer(serverId: String) = mcpRegistry.refresh(serverId)

    override fun onCleared() {
        super.onCleared()
        // The engine and the registry are process-scoped while this ViewModel is not, so every
        // reference either of them holds must be released here or the whole graph leaks.
        taskExecutionEngine.detachForegroundSendBridge(generationCallbackOwner)
        sandboxManager?.close()
        generationRegistry.detachUiCallbacks(generationCallbackOwner)
        autoBackupManager.destroy()
    }

    /** Nullable on purpose: the provider settings page recomposes one frame after a custom
     *  provider is deleted and must render gracefully instead of crashing. */
    fun getProviderInstanceOrNull(name: String): LlmProvider? = providerRegistry.getInstanceOrNull(name)



    private val animatedScrollIds = AtomicLong(0L)

    /** Callback invoked when any send path (manual/queue/loop) accepts a message.
     *  ChatApp wires this to trigger a single haptics.confirm() for all three paths. */
    @Volatile var onSendAccepted: ((conversationId: String, messageId: String) -> Unit)? = null
    private val _animatedScrollRequest = MutableStateFlow<AnimatedScrollRequest?>(null)
    val animatedScrollRequest: StateFlow<AnimatedScrollRequest?> =
        _animatedScrollRequest.asStateFlow()

    /** One-shot: set when sendMessage creates a new conversation so the conversation-open
     *  auto-scroll skips once (the send's scroll-to-message already handles it), preventing
     *  a double scroll on the first message of a new chat. Consumed by ChatApp. */
    @Volatile
    var suppressNextOpenScroll: Boolean = false

    /** When true, draft write-backs are suppressed to prevent feedback loops while
     *  programmatically loading a stored draft into the composer field. */
    @Volatile
    var loadingDraft: Boolean = false

    fun triggerScrollToMessage(messageId: String? = null) {
        val conversationId = _currentConversationId.value ?: return
        _animatedScrollRequest.value = AnimatedScrollRequest(
            id = animatedScrollIds.incrementAndGet(),
            conversationId = conversationId,
            targetMessageId = messageId,
        )
    }

    fun triggerScrollToAbsoluteBottomAfter(conversationId: String, messageId: String) {
        _animatedScrollRequest.value = AnimatedScrollRequest(
            id = animatedScrollIds.incrementAndGet(),
            conversationId = conversationId,
            targetMessageId = messageId,
            destination = AnimatedScrollDestination.ABSOLUTE_BOTTOM,
        )
    }

    fun triggerScrollToAttachedBottomAfter(conversationId: String, messageId: String) {
        _animatedScrollRequest.value = AnimatedScrollRequest(
            id = animatedScrollIds.incrementAndGet(),
            conversationId = conversationId,
            targetMessageId = messageId,
            destination = AnimatedScrollDestination.ABSOLUTE_BOTTOM,
            attachedOnly = true,
        )
    }

    fun completeAnimatedScroll(requestId: Long) {
        if (_animatedScrollRequest.value?.id == requestId) {
            _animatedScrollRequest.value = null
        }
    }

    private val _currentActiveModel = MutableStateFlow<String?>(null)
    val currentActiveModel = kotlinx.coroutines.flow.combine(_currentActiveModel, settings.selectedModel) { active, default ->
        active ?: default
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Constants.EXAMPLE_MODEL_ID)

    fun getProviderForModel(modelId: String): String = providerRegistry.providerForModel(modelId)
    

        
    // Embedding subsystem state lives in [ragManager]; exposed here for the UI.
    val activeEmbeddingModel get() = ragManager.activeEmbeddingModel
    val cachingProgress get() = ragManager.cachingProgress
    val cacheCounts get() = ragManager.cacheCounts
    fun loadCacheCounts() = ragManager.loadCacheCounts()

    // ── Remote shell command confirmation gate ───────────────────────────
    /** Shell-command confirmation policy + pending-prompt handshake (see [ShellConfirmationController]). */
    val pendingShellCommand: StateFlow<ShellConfirmationController.PendingShellCommand?>
        get() = shellConfirmation.pendingShellCommand

    /** Called by the UI to resolve a pending confirmation. */
    fun resolveShellConfirmation(allow: Boolean, alwaysAllowServer: Boolean = false) =
        shellConfirmation.resolve(allow, alwaysAllowServer)

    fun setShellConfirmEnabled(enabled: Boolean) = shellConfirmation.setEnabled(enabled)

    // ── Tasks (automation) ────────────────────────────────────
    /** Saved automation tasks; CRUD + run-now delegate to the app-scoped [taskManager]. */
    val tasks: StateFlow<List<com.newoether.agora.data.local.TaskEntity>> get() = taskManager.tasks
    val runningTaskIds: StateFlow<Set<String>> get() = taskManager.runningTaskIds

    fun executionsForTask(taskId: String) = taskManager.executionsForTask(taskId)
    fun executionSummariesForTask(taskId: String) = taskManager.executionSummariesForTask(taskId)
    suspend fun getTask(taskId: String) = taskManager.getTask(taskId)

    fun saveTask(task: com.newoether.agora.data.local.TaskEntity) {
        viewModelScope.launch { taskManager.saveTask(task) }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch { taskManager.deleteTask(taskId) }
    }

    fun runTaskNow(task: com.newoether.agora.data.local.TaskEntity) = taskManager.runNow(task)

    // ── Auto Backup ───────────────────────────────────────────

    val conversations: StateFlow<List<ChatConversation>> = convRepo.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentConversation: StateFlow<ChatConversation?> = _currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else convRepo.observeConversation(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentLoop: StateFlow<com.newoether.agora.data.local.LoopEntity?> = _currentConversationId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(
                    loopManager.loopForConversation(id),
                    loopManager.runningConversationIds,
                ) { loop, _ ->
                    // Visibility tracks the TIMER only. The card is a schedule indicator, so once
                    // the schedule is inactive it must disappear at once, even mid-cycle.
                    //
                    // It deliberately does not stay up for a running worker: an in-flight
                    // generation is already stoppable through the composer's Stop button, so
                    // keeping the card alive for that would make one control appear to own two
                    // unrelated lifetimes.
                    loop?.takeIf { it.active }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val runningLoopConversationIds: StateFlow<Set<String>> get() = loopManager.runningConversationIds

    fun stopCurrentLoop() {
        val id = _currentConversationId.value ?: return
        viewModelScope.launch { loopManager.stopLoop(id) }
    }

    private val conversationUi = ConversationUiStateAssembler(
        conversations = convRepo,
        registry = generationRegistry,
        executionCoordinator = conversationExecutionCoordinator,
        currentConversationId = _currentConversationId,
        appContext = appContext,
        scope = viewModelScope,
    )
    private val renderStore: ConversationRenderStore get() = conversationUi.renderStore
    val allMessages: StateFlow<List<ChatMessage>> = conversationUi.allMessages
    val loadedMessagesConversationId: StateFlow<String?> =
        conversationUi.loadedMessagesConversationId

    private val providerModelSync = ProviderModelSyncController(
        providers = providerRegistry,
        settings = settings,
        scope = viewModelScope,
    )
    val isSyncingModels: StateFlow<Boolean> = providerModelSync.isSyncing

    // replay=0: with replay=1 an Activity recreation (rotation) re-collected the flow and
    // re-showed the last snackbar. The 1-slot buffer keeps tryEmit lossless for slow collectors;
    // events emitted during the brief recreation gap are dropped rather than replayed stale.
    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }
    private val _conversationShareText = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val conversationShareText = _conversationShareText.asSharedFlow()

    private val _firstMessageCommitted = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val firstMessageCommitted = _firstMessageCommitted.asSharedFlow()

    private val _updateDialogData = MutableStateFlow<UpdateInfo?>(null)
    val updateDialogData: StateFlow<UpdateInfo?> = _updateDialogData.asStateFlow()
    fun dismissUpdateDialog() { _updateDialogData.value = null }
    fun showUpdateDialog(info: UpdateInfo) { _updateDialogData.value = info }

    /** PDF / text-file preview state (see [MediaPreviewState]). */
    private val mediaPreview = MediaPreviewState()
    val previewPdfPages: StateFlow<List<String>> get() = mediaPreview.pdfPages
    val previewPdfIndex: StateFlow<Int> get() = mediaPreview.pdfIndex
    val previewFileContent: StateFlow<String?> get() = mediaPreview.fileContent
    val previewFileName: StateFlow<String?> get() = mediaPreview.fileName

    fun showPdfPreview(pages: List<String>, startIndex: Int) = mediaPreview.showPdf(pages, startIndex)
    fun showFilePreview(fileName: String, content: String) = mediaPreview.showFile(fileName, content)
    fun clearPreviews() = mediaPreview.clear()

    val messages: StateFlow<List<ChatMessage>> = conversationUi.messages
    val totalTokens: StateFlow<Int> = conversationUi.totalTokens
    val isLoading: StateFlow<Boolean> = conversationUi.isLoading
    val generatingInConversationId: StateFlow<String?> =
        conversationUi.generatingInConversationId

    /** Per-conversation generation state registry. Each conversation owns an independent
     *  ConversationGenerationState; the global loading/render mirrors
     *  below are now a MIRROR of whichever conversation is currently open (see init collectors). */
    private val generationCallbackOwner = Any()
    private val generationCallbacksAttached = Unit.also {
        generationRegistry.attachUiCallbacks(generationCallbackOwner) { state ->
            state.onActive = { conversationId ->
                // Publish synchronously with the slot claim so Stop and edit closure are immediate.
                conversationUi.markActive(conversationId)
            }
            state.onIdle = { conversationId ->
                conversationUi.markIdle(conversationId)
            }
            state.onStreamCommit = { conversationId, message ->
                conversationUi.commitTerminalStreamingMessage(conversationId, message)
            }
            state.onQueueDrainRequested = { settledState ->
                settledState.scope.launch {
                    generationController.drainQueuedAfterGeneration(settledState)
                }
            }
            state.onStopSettled = { settledState ->
                // After a Stop cleanly settles (STOPPED row persisted, slot released), drain
                // any queued sends into a fresh Run so accepted interventions are never dropped.
                settledState.scope.launch {
                    generationController.drainQueuedAfterStop(settledState)
                }
            }
        }
    }

    /** Every conversation currently mutating its message tree through foreground generation or
     * headless Task/Loop execution. Drawer rows use this per-id set instead of the open
     * conversation's open UI loading mirror. */
    val generatingConversationIds: StateFlow<Set<String>> = combine(
        generationRegistry.activeConversationIds,
        conversationExecutionCoordinator.activeAutomationConversationIds,
    ) { foreground, automation ->
        foreground + automation
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Stop-finalization helper shared by the controller and the ViewModel's stop path. */
    private val generationFinalizer by lazy {
        GenerationFinalizer(convRepo, ragManager::indexMessageForRag)
    }

    private val switchingCoordinator = SwitchingCoordinator()
    val isSwitching: StateFlow<Boolean> = switchingCoordinator.isSwitching

    private val regenerationTransitions = RegenerationTransitionCoordinator()
    internal val regenerationTransition: StateFlow<RegenerationTransitionRequest?> =
        regenerationTransitions.request

    fun acknowledgeRegenerationFade(requestId: Long) {
        regenerationTransitions.acknowledgeFade(requestId)
    }

    fun acknowledgeRegenerationScroll(requestId: Long, success: Boolean) {
        regenerationTransitions.acknowledgeScroll(requestId, success)
    }

    fun completeRegenerationTransition(requestId: Long) {
        regenerationTransitions.complete(requestId)
    }

    private var switchingJob: Job? = null

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    // A monotonic page-entry identity, distinct from recomposition/configuration changes.
    // The initial value owns the app-launch welcome; each real conversation→New Chat
    // transition increments it exactly once.
    private val _newChatEntryId = MutableStateFlow(1L)
    val newChatEntryId: StateFlow<Long> = _newChatEntryId.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> = _isTransitioningToNewChat.asStateFlow()

    private val _pendingSystemPromptId = MutableStateFlow<String?>(null)
    val pendingSystemPromptId: StateFlow<String?> = _pendingSystemPromptId.asStateFlow()

    fun setPendingSystemPrompt(promptId: String?) {
        _pendingSystemPromptId.value = promptId
    }

    private val _pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCompacting: StateFlow<Boolean> = _currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) flowOf(false)
            else generationRegistry.getOrCreate(conversationId).compacting
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val pendingConversationSettings: StateFlow<ConversationSettings?> = _pendingConversationSettings.asStateFlow()

    fun setPendingConversationSettings(settings: ConversationSettings?) {
        _pendingConversationSettings.value = settings
    }

    private val payloadBuilder by lazy {
        MessagePayloadBuilder(
            generationManager = generationManager,
            onSnackbar = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
        )
    }

    private val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = convRepo,
        memoryManager = memoryManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        appContext = appContext,
        pendingConversationSettings = _pendingConversationSettings,
        onSnackbar = { msg -> emitSnackbar(msg) },
    )

    private val generationController by lazy {
        MessageGenerationController(
            viewModelScope = viewModelScope,
            application = getApplication(),
            appContext = appContext,
            convRepo = convRepo,
            settings = settings,
            registry = generationRegistry,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = payloadBuilder,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = conversationExecutionCoordinator,
            renderStore = renderStore,
            currentConversationId = _currentConversationId,
            isNewChatMode = _isNewChatMode,
            pendingConversationSettings = _pendingConversationSettings,
            pendingSystemPromptId = _pendingSystemPromptId,
            currentActiveModel = currentActiveModel,
            messages = messages,
            onScrollToMessage = { id -> triggerScrollToMessage(id) },
            onScrollToAbsoluteBottomAfter = ::triggerScrollToAbsoluteBottomAfter,
            onScrollToAttachedBottomAfter = ::triggerScrollToAttachedBottomAfter,
            onSendAcceptedEvent = { convId, msgId ->
                // Feedback belongs to the conversation on screen. A send from the new-chat page
                // qualifies because that page becomes this very conversation, but its id is only
                // published after acceptance, so it is matched via isNewChatMode rather than by id.
                // Background automation on another conversation stays silent: from the user's point
                // of view nothing happened on screen.
                val currentId = _currentConversationId.value
                val targetsOpenConversation = currentId == convId ||
                    (currentId == null && isNewChatMode.value)
                if (targetsOpenConversation) onSendAccepted?.invoke(convId, msgId)
            },
            onSnackbar = { msg -> emitSnackbar(msg) },
            onSnackbarSuspend = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
            onConversationCreatedBySend = { conversationId ->
                suppressNextOpenScroll = true
                _firstMessageCommitted.tryEmit(conversationId)
            },
            onUserMessagePersisted = ragManager::indexMessageForRag,
            onTreeMutationStart = {
                val request = _currentConversationId.value?.let {
                    switchingCoordinator.beginTreeMutation(it)
                }
                delay(SWITCH_OVERLAY_FADE_MS)
                request?.id
            },
            onTreeMutationSettling = { requestId, targetMessageId ->
                requestId?.let {
                    switchingCoordinator.markTreeMutationReady(it, targetMessageId)
                }
            },
            onTreeMutationFailed = { requestId ->
                requestId?.let { switchingCoordinator.complete(it) }
            },
            regenerationTransitions = regenerationTransitions,
            pauseConversationTasks = { conversationId -> loopManager.stopLoop(conversationId) },
        )
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

    val switchingScrollRequest: StateFlow<SwitchingScrollRequest?> =
        switchingCoordinator.request

    fun completeSwitchingScroll(requestId: Long): Boolean =
        switchingCoordinator.complete(requestId)

    fun failSwitchingScroll(requestId: Long, reason: String) {
        if (!switchingCoordinator.isCurrent(requestId)) return
        DebugLog.e("AgoraVM", "Switching scroll did not settle: $reason")
        switchingCoordinator.complete(requestId)
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

    init {
        startInitJobs()
        viewModelScope.launch(Dispatchers.IO) {
            // A completed generation marks its conversation unread in the same transaction as
            // the terminal message. Selecting that conversation is the read boundary: observing
            // its row here also covers completion while the conversation is already open.
            currentConversation
                .filterNotNull()
                .filter { it.hasUnreadGeneration }
                .collect { conversation ->
                    convRepo.setConversationUnreadGeneration(
                        id = conversation.id,
                        unread = false,
                    )
                }
        }
        conversationUi.start()

        // Register the foreground-send bridge so loop cycles on this open conversation
        // go through the controller's regular send path (bubble animation + scroll + haptics).
        taskExecutionEngine.attachForegroundSendBridge(generationCallbackOwner) bridge@{ convId, text, modelId ->
            if (_currentConversationId.value != convId) return@bridge BridgeOutcome.NotDelegated
            // The Loop already holds this conversation's automation lease. Falling back to the
            // headless path while a manual send owns the generation slot recreates the lock/slot
            // deadlock this direct-only bridge exists to prevent. Busy therefore ends this cycle as
            // an explicit failure without persisting another user turn.
            val delivered = when (
                val outcome = generationController.sendMessageFromAutomationAwaitingCompletion(
                    convId, text, modelId,
                )
            ) {
                AutomationSendOutcome.SlotBusy ->
                    return@bridge BridgeOutcome.Busy()
                is AutomationSendOutcome.Delivered -> outcome
            }
            // Read back the exact row this send created, never the conversation tail: a branch
            // switch or a queue drain could otherwise make an unrelated turn look like this result.
            val modelMsg = convRepo.getMessagesForConversationSnapshot(convId)
                .find { it.id == delivered.modelMessageId }
            when {
                modelMsg == null -> BridgeOutcome.Failed("Generation row disappeared")
                modelMsg.status == MessageStatus.SUCCESS ->
                    BridgeOutcome.Completed(modelMsg.id, modelMsg.text)
                else -> BridgeOutcome.Failed(
                    modelMsg.text.takeIf { it.isNotBlank() } ?: "Generation failed",
                )
            }
        }
    }

    // ── Custom providers ──────────────────────────────────────
    // Settings persistence lives in SettingsRepository; ChatViewModel only maintains
    // the live in-memory provider instances (the `providers` map) via callbacks.
    fun addCustomProvider(
        name: String,
        baseUrl: String,
        protocol: com.newoether.agora.data.CustomEndpointProtocol =
            com.newoether.agora.data.CustomEndpointProtocol.OPENAI,
    ) = providerRegistry.addCustom(name, baseUrl, protocol)
    fun renameCustomProvider(oldName: String, newName: String) {
        if (!providerRegistry.renameCustom(oldName, newName)) return
        viewModelScope.launch(Dispatchers.IO) {
            convRepo.renameConfiguredProviderModelReferences(oldName, newName.trim())
        }
    }
    fun updateCustomProviderProtocol(
        name: String,
        protocol: com.newoether.agora.data.CustomEndpointProtocol,
    ) = providerRegistry.updateCustomProtocol(name, protocol)
    fun deleteCustomProvider(name: String) = providerRegistry.deleteCustom(name)

    fun updateCustomModel(
        oldModelId: String,
        provider: String,
        modelId: String,
        alias: String,
    ) {
        val normalizedProvider = provider.trim()
        val normalizedModelId = modelId.trim()
        if (normalizedProvider.isEmpty() || normalizedModelId.isEmpty()) return
        val newModelId = ModelId(normalizedProvider, normalizedModelId).prefixed

        viewModelScope.launch(Dispatchers.IO) {
            customModelMutationMutex.withLock {
                val customModels = settings.customModels.value
                if (oldModelId !in customModels) return@withLock
                if (newModelId != oldModelId && newModelId in customModels) return@withLock

                settings.replaceCustomModel(oldModelId, newModelId, alias)
                convRepo.replaceConfiguredModelReferences(oldModelId, newModelId)
                if (_currentActiveModel.value == oldModelId) {
                    _currentActiveModel.value = newModelId
                }
            }
        }
    }

    fun deleteCustomModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            customModelMutationMutex.withLock {
                if (modelId !in settings.customModels.value) return@withLock

                settings.replaceCustomModel(modelId, null, "")
                convRepo.replaceConfiguredModelReferences(modelId, null)
                if (_currentActiveModel.value == modelId) {
                    _currentActiveModel.value = null
                }
            }
        }
    }

    fun getCurrentVersion(): String {
        return try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }
    suspend fun checkForUpdates(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            UpdateChecker.check(getCurrentVersion())
        }
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
        val ctx = GenerationContext(
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
                try { AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
            } else {
                try { AutoBackupWorker.cancel(getApplication()) } catch (_: Exception) {}
            }
        }
    }
    fun setAutoBackupPeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupPeriodHours(hours)
            // Enforce: auto-delete period must be strictly greater than backup period
            val deleteTiers = AUTO_DELETE_TIERS_HOURS
            val deleteHours = settings.autoDeletePeriodHours.value
            if (deleteHours <= hours) {
                val nextDelete = deleteTiers.firstOrNull { it > hours } ?: AUTO_DELETE_TIERS_HOURS.last()
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
            val deleteTiers = AUTO_DELETE_TIERS_HOURS
            // Find the smallest valid delete tier that is > backupHours, and >= the requested hours
            val minValid = deleteTiers.firstOrNull { it > backupHours } ?: AUTO_DELETE_TIERS_HOURS.last()
            settings.saveAutoDeletePeriodHours(maxOf(hours, minValid))
        }
    }
    fun addShellDevice(device: ShellDeviceConfig) {
        settings.addShellDevice(device)
    }
    fun updateShellDevice(device: ShellDeviceConfig) {
        settings.updateShellDevice(device)
    }

    /**
     * Connects to an SSH host in capture mode and returns the server host key
     * (base64) together with its SHA-256 fingerprint, for the user to review and
     * pin. The host key is exchanged before authentication, so this succeeds even
     * if the password is wrong — letting the user pin the key first.
     */
    suspend fun verifySshHostKey(
        host: String, port: Int, user: String, password: String
    ): Result<Pair<String, String>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext Result.failure(Exception("Host is empty"))
        val client = SshClient(
            host, port, user.ifBlank { "root" }, password,
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
        else Result.success(key to SshClient.fingerprintSha256(key))
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
        // Already on the new-chat screen: ignore (both the drawer and the top-bar capsule route
        // here; behaviour must be identical and a no-op when there's nothing to reset).
        if (_isNewChatMode.value) return
        regenerationTransitions.abortCurrent()
        val previousJob = switchingJob
        val request = switchingCoordinator.beginNewChat()
        previousJob?.cancel()
        if (!_isNewChatMode.value) {
            _pendingSystemPromptId.value = null
        }
        _newChatEntryId.value += 1L
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        _animatedScrollRequest.value = null
        switchingJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
                if (!switchingCoordinator.isCurrent(request.id)) return@launch
                _currentConversationId.value = null
                _currentActiveModel.value = null
                _pendingConversationSettings.value = null
                conversationUi.clearConversationGraph()
            } finally {
                if (switchingCoordinator.complete(request.id)) {
                    _isTransitioningToNewChat.value = false
                }
            }
        }
    }

    fun selectConversation(
        id: String,
        hapticOnCompletion: Boolean = true,
    ) {
        if (_currentConversationId.value == id && !_isNewChatMode.value) return
        regenerationTransitions.abortCurrent()

        val previousJob = switchingJob
        val request = switchingCoordinator.beginConversation(
            conversationId = id,
            hapticOnCompletion = hapticOnCompletion,
        )
        previousJob?.cancel()
        _isTransitioningToNewChat.value = false
        _animatedScrollRequest.value = null
        switchingJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
                if (!switchingCoordinator.isCurrent(request.id)) return@launch
                _isNewChatMode.value = false
                _currentConversationId.value = id
                val conversation = convRepo.getConversation(id)
                if (switchingCoordinator.isCurrent(request.id)) {
                    _currentActiveModel.value = conversation?.modelId
                    // Publish UI readiness only after this owned job has committed every
                    // synchronous target state. In particular, a same-id selection must not let
                    // the UI settle the request during the overlay delay and cancel this job's
                    // transition out of New Chat mode.
                    switchingCoordinator.markConversationReady(request.id)
                }
            } catch (e: CancellationException) {
                if (switchingCoordinator.isCurrent(request.id)) {
                    failSwitchingScroll(request.id, "conversation switch cancelled")
                }
                throw e
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to select conversation $id", e)
                failSwitchingScroll(request.id, "conversation load failed")
            }
        }
    }

    fun forkConversationFrom(messageId: String? = null) {
        val conversationId = _currentConversationId.value ?: return
        viewModelScope.launch {
            when (val result = conversationForkShare.fork(conversationId, messageId)) {
                is ConversationForkShareService.ForkResult.Success ->
                    selectConversation(result.conversationId)
                is ConversationForkShareService.ForkResult.Failure ->
                    _snackbarMessage.emit(
                        SnackbarEvent(
                            appContext.getString(R.string.conversation_fork_failed, result.reason)
                        )
                    )
            }
        }
    }

    fun shareConversation() {
        val conversationId = _currentConversationId.value ?: return
        viewModelScope.launch {
            emitShareResult(conversationForkShare.shareAll(conversationId))
        }
    }

    fun shareGeneration(assistantMessageId: String) {
        val conversationId = _currentConversationId.value ?: return
        viewModelScope.launch {
            emitShareResult(
                conversationForkShare.shareRun(conversationId, assistantMessageId)
            )
        }
    }

    fun shareMessages(messageIds: Set<String>) {
        val conversationId = _currentConversationId.value ?: return
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            emitShareResult(
                conversationForkShare.shareMessages(conversationId, messageIds)
            )
        }
    }

    private suspend fun emitShareResult(result: ConversationForkShareService.ShareResult) {
        when (result) {
            is ConversationForkShareService.ShareResult.Success ->
                _conversationShareText.emit(result.text)
            is ConversationForkShareService.ShareResult.Failure ->
                _snackbarMessage.emit(
                    SnackbarEvent(
                        appContext.getString(R.string.conversation_share_failed, result.reason)
                    )
                )
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            convRepo.updateConversationTitle(id, newTitle)
        }
    }

    fun generateTitle(conversationId: String) = generationController.generateTitle(conversationId)

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
            loopManager.stopLoop(id)
            conversationExecutionCoordinator.withConversationLock(id) {
                convRepo.deleteConversation(id)
            }
            generationRegistry.remove(id)
            if (_currentConversationId.value == id) {
                withContext(Dispatchers.Main) { createNewChat() }
            }
        }
    }

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    suspend fun compactContextManual(
        model: String,
        prompt: String,
        retainLogicalMessages: Int,
    ): CompactResult = generationController.compactManual(
        CompactRequest(model, prompt, retainLogicalMessages),
    )

    fun deleteMessage(messageId: String): Int {
        if (isSwitching.value) return 0
        return generationController.deleteMessage(messageId)
    }

    /** Queued sends for the currently-open conversation (drives the queue banner above the input). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queuedSends: StateFlow<List<QueuedSend>> = _currentConversationId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else generationRegistry.getOrCreate(id).queuedSends
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** True while the open conversation's Stop is still winding down (slot held until the
     *  cancelled coroutine fully unwinds). Drives the composer's gray stopping spinner. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isStopping: StateFlow<Boolean> = _currentConversationId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(false)
            else generationRegistry.getOrCreate(id).stopping
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun removeQueuedSend(id: String) {
        val conversationId = _currentConversationId.value ?: return
        val state = generationRegistry.getOrCreate(conversationId)
        viewModelScope.launch(Dispatchers.IO) {
            state.queueMutationMutex.withLock {
                val queued = state.removeQueuedSend(id) ?: return@withLock
                // Guidance has not entered Room or the message tree yet. Removing it therefore
                // only releases the prepared files owned by this in-memory pending input.
                queued.deleteOwnedFiles()
            }
        }
    }

    fun stopGeneration() {
        // Stop the CURRENTLY-OPEN conversation's generation only. A background conversation's
        // generation is intentionally not killed here — the user is asking to stop what they
        // see. the state mailbox cancels that conversation's job + streamScope (not other
        // conversations'), and finalizer persists STOPPED to the correct conversation id.
        val id = _currentConversationId.value ?: return
        val state = generationRegistry.get(id) ?: return
        state.requestStop { result ->
            val stoppedMsg = result.stoppedMessage
            val messages = if (stoppedMsg != null) {
                listOf(stoppedMsg)
            } else if (_currentConversationId.value == result.conversationId) {
                // streamingMessage was null — mark any in-flight model message in the open list directly.
                runCatching {
                    renderStore.allMessages.mapNotNull { m ->
                        if (m.participant == Participant.MODEL &&
                            (m.status == MessageStatus.SENDING || m.status == MessageStatus.THINKING ||
                                m.status == MessageStatus.TOOL_CALLING || m.status == MessageStatus.TRANSCRIBING)
                        ) {
                            val stopped = m.copy(status = MessageStatus.STOPPED)
                            renderStore.updateAllMessages { list ->
                                list.map { if (it.id == m.id) stopped else it }
                            }
                            stopped
                        } else null
                    }
                }.getOrElse { error ->
                    DebugLog.e("AgoraVM", "Failed to snapshot stopped render rows", error)
                    emptyList()
                }
            } else emptyList()
            val finalizationEffect = result.finalizationEffect
            if (finalizationEffect != null) {
                // Release the STOPPED overlay once the terminal row is in Room — otherwise the
                // stale snapshot lives on in state and resolvePath resurrects it as a ghost bubble.
                generationFinalizer.launchStopFinalization(
                    state.scope, finalizationEffect.identity, messages,
                    onFinalized = { completion ->
                        val outcome = state.finishStopFinalization(completion)
                        // A delayed/duplicate callback is a no-op for process state and UI.
                        if (!outcome.accepted) return@launchStopFinalization
                        if (completion.success) {
                            // Room invalidation and the state mirror are asynchronous. Commit the
                            // exact STOPPED overlay before releasing the private state copy.
                            if (
                                stoppedMsg != null &&
                                _currentConversationId.value == result.conversationId
                            ) {
                                renderStore.commitTerminalStreamingMessage(stoppedMsg)
                            }
                            state.clearStoppedOverlay()
                        } else {
                            emitSnackbar(
                                getApplication<Application>().getString(R.string.failed_to_generate),
                            )
                        }
                    },
                )
            }
        }
    }

    fun regenerate(messageId: String): Boolean = generationController.regenerate(messageId)

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) {
        if (isSwitching.value) return
        val conversationId = _currentConversationId.value ?: return
        val state = generationRegistry.getOrCreate(conversationId)
        if (state.generating.value) return
        val currentAnchor = renderStore.allMessages.firstOrNull { it.id == currentMessageId }
            ?: return
        // Edit branches are USER siblings; Regenerate branches are MODEL siblings. Never mix
        // another structural edge that happens to share the same parent into this selector.
        val siblings = renderStore.allMessages.filter {
            it.parentId == parentId &&
                it.participant == currentAnchor.participant &&
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
        }.sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        if (siblings.size < 2) return
        var currentIndex = siblings.indexOfFirst { it.id == currentMessageId }
        if (currentIndex == -1) {
            val selectedId = renderStore.selectedChildren[parentId]
            currentIndex = siblings.indexOfFirst { it.id == selectedId }
        }
        if (currentIndex == -1) return
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        if (newIndex == currentIndex) return
        val parentRunId = parentId?.let { pid ->
            renderStore.allMessages.firstOrNull { it.id == pid }?.runId
        }
        
        val previousJob = switchingJob
        val request = switchingCoordinator.beginTreeMutation(conversationId)
        previousJob?.cancel()
        switchingJob = viewModelScope.launch {
            try {
                delay(SWITCH_OVERLAY_FADE_MS)
                if (!switchingCoordinator.isCurrent(request.id)) return@launch
                state.queueMutationMutex.withLock {
                    if (
                        state.generating.value ||
                        _currentConversationId.value != conversationId
                    ) {
                        switchingCoordinator.complete(request.id)
                        return@withLock
                    }
                    val newMap = renderStore.selectedChildren.toMutableMap()
                    val targetMessage = siblings[newIndex]
                    val targetRunId = targetMessage.runId ?: run {
                        switchingCoordinator.complete(request.id)
                        return@withLock
                    }
                    newMap[parentId] = targetMessage.id
                    convRepo.selectRunBranch(
                        conversationId = conversationId,
                        parentRunId = parentRunId,
                        runId = targetRunId,
                        messageSelections = newMap,
                    )
                    renderStore.setSelectedChildren(newMap)
                    switchingCoordinator.markTreeMutationReady(request.id, targetMessage.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to switch Run branch", e)
                switchingCoordinator.complete(request.id)
            }
        }
    }

    suspend fun editMessage(messageId: String, newText: String): Boolean =
        generationController.editMessage(messageId, newText)

    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
        onAccepted: suspend () -> Unit = {},
    ): SendAcceptance? =
        generationController.sendMessage(text, images, attachments) { acceptance ->
            // Acceptance transfers attachment ownership before the composer clears. Direct sends
            // are already Room-owned; queued guidance remains memory-owned until its later drain.
            // Invalidate older draft revisions, clear the exact submitted UI, and only then let
            // the Controller publish the bubble/banner and its scroll request.
            val attachmentsToReclaim = withContext(NonCancellable) {
                clearAcceptedComposerDraft(acceptance.conversationId)
            }
            withContext(Dispatchers.Main.immediate + NonCancellable) {
                onAccepted()
            }
            if (attachmentsToReclaim.isNotEmpty() && acceptance.hasDurableAttachmentOwner()) {
                // Reclamation is no longer part of the visible Send handshake. The durable
                // MessageEntity already owns these paths, and repository cleanup rechecks every
                // remaining message/draft reference before deleting anything.
                viewModelScope.launch(Dispatchers.IO) {
                    composerDrafts.reclaimAttachments(attachmentsToReclaim)
                }
            }
        }

    /**
     * Onboarding-focused model fetch for a single provider.
     *
     * Unlike [fetchAvailableModels] this carries no global side effects: no
     * full-sync admission guard (so re-entry always refetches the latest key),
     * no enabled-set intersection, and no snackbar. It is a plain suspend
     * function so the caller's coroutine owns its lifecycle — cancelling that
     * coroutine cooperatively aborts the in-flight network request, which keeps
     * the welcome flow seamless (no stale result can land after the user edits
     * their key and returns). Results are persisted so the [availableModels]
     * flow updates the list. Returns the prefixed model ids, or empty on
     * failure / unconfigured provider.
     */
    suspend fun fetchModelsForProvider(name: String): List<String> =
        providerModelSync.fetchModelsForProvider(name)

    fun computeProviderFingerprint(): String = providerModelSync.computeFingerprint()

    fun fetchAvailableModels() {
        providerModelSync.start(
            request = ProviderModelSyncRequest(
                failureLabels = ModelSyncFailureLabels(
                    noModels = appContext.getString(R.string.sync_error_no_models),
                    timeout = appContext.getString(R.string.sync_error_timeout),
                    invalidResponse = appContext.getString(R.string.sync_error_invalid_response),
                    unknown = appContext.getString(R.string.unknown_error),
                ),
                globalProviderName = appContext.getString(R.string.models_title),
            ),
        ) { outcome ->
            val message = providerModelSyncFailureMessage(outcome.failures) ?: when {
                outcome.successfulProviderCount > 0 -> appContext.getString(
                    R.string.sync_success_providers,
                    outcome.successfulProviderCount,
                )
                outcome.skippedProviderCount > 0 ->
                    appContext.getString(R.string.sync_no_providers)
                else -> appContext.getString(R.string.sync_completed)
            }
            _snackbarMessage.emit(SnackbarEvent(message))
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
    fun importClaudeChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) =
        importExport.importClaudeChat(uri, strategy, selectedIds)
    fun previewGptChat(uri: Uri) = importExport.previewGptChat(uri)
    fun setGptImportError(error: String) = importExport.setGptImportError(error)
    fun clearGptImportState() = importExport.clearGptImportState()
    fun importGptChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) =
        importExport.importGptChat(uri, strategy, selectedIds)
    fun importData(uri: Uri, decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) =
        importExport.importData(uri, decisions)

    // ── Per-conversation draft persistence ─────────────────────

    suspend fun persistDraft(
        conversationId: String,
        expectedRevision: Long,
        text: String,
        attachments: List<SelectedAttachment>,
        explicitlyRemovedAttachments: List<SelectedAttachment> = emptyList(),
    ): DraftPersistResult = composerDrafts.persist(
        conversationId = conversationId,
        expectedRevision = expectedRevision,
        text = text,
        attachments = attachments,
        explicitlyRemovedAttachments = explicitlyRemovedAttachments,
    )

    private suspend fun clearAcceptedComposerDraft(
        conversationId: String,
    ): List<SelectedAttachment> = composerDrafts.clearAccepted(conversationId)

    suspend fun loadDraft(
        conversationId: String,
    ): LoadedComposerDraft = composerDrafts.load(conversationId)
}
