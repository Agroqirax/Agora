package com.newoether.agora.automation

import android.app.Application
import android.content.Context
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.CompactResult
import com.newoether.agora.viewmodel.ContextCompactor
import com.newoether.agora.viewmodel.ConversationUiState
import com.newoether.agora.viewmodel.ConversationTitleGenerator
import com.newoether.agora.viewmodel.GenerationCallbacks
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.GenerationRequestBuilder
import com.newoether.agora.viewmodel.ProviderRegistry
import com.newoether.agora.viewmodel.RagManager
import com.newoether.agora.viewmodel.ShellConfirmationController
import com.newoether.agora.viewmodel.fallbackConversationTitle
import com.newoether.agora.tool.McpToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Headless single-shot generation engine (process-scoped).
 *
 * Drives one complete generation (including the agentic tool loop) for a conversation without
 * depending on a ViewModel or Compose state, while reusing the same [GenerationManager] pipeline
 * as foreground generation. Background Task/Loop runners call [runOnce]; when the conversation is
 * open, the engine attaches to its shared generation state so Stop and queued guidance retain the
 * same ownership semantics as the foreground path.
 *
 * Collaborators are the process-scoped singletons from `AppContainer`, so the
 * background engine shares the live provider map, the on-device llama engine, and
 * the conversation/settings repositories with the UI.
 */
class TaskExecutionEngine(
    private val application: Application,
    private val appContext: Context,
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val memoryManager: MemoryManager,
    private val providerRegistry: ProviderRegistry,
    localProvider: LocalProvider,
    sandboxFactory: SandboxManagerFactory?,
    private val appScope: CoroutineScope,
    private val executionCoordinator: ConversationExecutionCoordinator,
    shellConfirmation: ShellConfirmationController,
    mcpToolProvider: McpToolProvider,
    private val generationRegistry: ConversationStateRegistry? = null,
    private val automationExecutionGate: AutomationExecutionGate = AutomationExecutionGate(),
    private val pauseConversationLoop: suspend (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val modelMessageId: String, val text: String) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Optional bridge that redirects loop cycles on the foreground-open conversation through the
     * regular MessageGenerationController send path (with attached-only scroll) instead of the
     * headless engine path. Set by ChatViewModel when it is constructed; cleared on dispose.
     *
     * Contract: the bridge SUSPENDS until the delegated turn finishes and reports the durable
     * outcome. It must not return as soon as the send is accepted, otherwise the caller's
     * conversation lease would be released while the generation is still running, and the Loop
     * would record the cycle as complete before it produced anything.
     */
    private val foregroundBridgeLock = Any()
    private var foregroundBridgeOwner: Any? = null
    private var foregroundSendBridge: (suspend (conversationId: String, userText: String, modelId: String) -> BridgeOutcome)? = null

    /** Owner-token binding prevents an older ViewModel's late onCleared from erasing a newer one. */
    fun attachForegroundSendBridge(
        owner: Any,
        bridge: suspend (conversationId: String, userText: String, modelId: String) -> BridgeOutcome,
    ) = synchronized(foregroundBridgeLock) {
        foregroundBridgeOwner = owner
        foregroundSendBridge = bridge
    }

    fun detachForegroundSendBridge(owner: Any) = synchronized(foregroundBridgeLock) {
        if (foregroundBridgeOwner !== owner) return@synchronized
        foregroundBridgeOwner = null
        foregroundSendBridge = null
    }

    private fun currentForegroundSendBridge() = synchronized(foregroundBridgeLock) {
        foregroundSendBridge
    }

    /** Outcome of a delegated foreground send. [NotDelegated] means the caller must run headlessly. */
    sealed interface BridgeOutcome {
        data object NotDelegated : BridgeOutcome
        data class Completed(val modelMessageId: String, val text: String) : BridgeOutcome
        data class Failed(val reason: String) : BridgeOutcome
    }

    /** Embedding subsystem powering RAG/semantic-search context during generation.
     *  One per engine, mirrors `ChatViewModel.ragManager` but on the app scope. */
    private val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = appScope,
        emitSnackbar = {},
    )
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val contextCompactor = ContextCompactor(
        conversations = convRepo,
        settings = settings,
        providers = providerRegistry,
        pauseLoop = pauseConversationLoop,
    )

    /**
     * Task-only post-processing. Loop runs share this engine but never call this method, so a
     * conversation loop cannot repeatedly retitle itself after every cycle.
     */
    suspend fun updateTaskExecutionTitle(conversationId: String, response: String) {
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        if (settings.titleGenerationEnabled.value) {
            when (val result = titleGenerator.generateAndPersist(conversationId)) {
                is ConversationTitleGenerator.Result.Success -> return
                is ConversationTitleGenerator.Result.Failure ->
                    DebugLog.w(
                        "TaskExecutionEngine",
                        "Task title generation failed; using response fallback",
                    )
            }
        }
        val fallback = fallbackConversationTitle(response)
        if (fallback.isBlank()) return
        convRepo.getConversation(conversationId)?.let { conversation ->
            convRepo.updateConversationTitleIfUnchanged(
                id = conversationId,
                expectedTitle = conversation.title,
                newTitle = fallback,
            )
        }
    }

    private val generationManager = GenerationManager(
        app = application,
        conversations = convRepo,
        memoryManager = memoryManager,
        providers = providerRegistry.all,
        context = appContext,
        sandboxFactory = sandboxFactory,
        additionalToolProviders = listOf(mcpToolProvider),
    ).also {
        // Foreground Task/Loop executions share the exact same prompt and session trust state as
        // Chat. ShellConfirmationController itself fails fast when no Activity is visible.
        it.onConfirmShellCommand = shellConfirmation::confirm
    }

    /**
     * Injects [userText] as a new user turn at the leaf of [conversationId] and runs
     * one full generation, persisting the assistant reply. [modelId] is the prefixed
     * model id (e.g. "OpenAI:gpt-4o"); null/blank falls back to the app default model.
     *
     * [systemPromptOverride] bypasses the per-conversation / active-prompt resolution:
     * pass a task's own system prompt, or "" to run with no system prompt at all (the
     * default for task executions). Leave null to resolve the prompt the way the
     * foreground chat does (conversation's prompt id, falling back to the active one).
     */
    suspend fun runOnce(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
    ): Result = automationExecutionGate.withExecution {
        executionCoordinator.withAutomationConversationLock(conversationId) {
            runOnceLocked(
                conversationId = conversationId,
                userText = userText,
                modelId = modelId,
                systemPromptOverride = systemPromptOverride,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                precondition = precondition,
            )
        }
    }

    /**
     * LoopManager owns the conversation lock across its persistent cycle claim, generation, and
     * schedule update. Re-entering the non-reentrant coordinator from [runOnce] would self-deadlock,
     * so this entry point performs the same execution-gate work while trusting that outer owner.
     */
    internal suspend fun runOnceWithConversationLockHeld(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
    ): Result = automationExecutionGate.withExecution {
        runOnceLocked(
            conversationId = conversationId,
            userText = userText,
            modelId = modelId,
            systemPromptOverride = systemPromptOverride,
            foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            precondition = precondition,
        )
    }

    private suspend fun runOnceLocked(
        conversationId: String,
        userText: String,
        modelId: String?,
        systemPromptOverride: String?,
        foregroundServiceManagedExternally: Boolean,
        precondition: suspend () -> Boolean,
    ): Result {
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        convRepo.ensureRunRecovery()
        if (!precondition()) return Result.Failure("Execution cancelled")
        val conversation = convRepo.getConversation(conversationId)
            ?: return Result.Failure("Conversation not found: $conversationId")
        val effectiveModelId = modelId?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value

        // If the conversation is open in the foreground, delegate the send to the regular
        // controller path so the loop cycle gets bubble animation, scroll, and haptics.
        // The controller manages its own slot; do NOT acquire it here before the bridge check.
        // The bridge only returns once the delegated turn is durably finished, so the caller's
        // conversation lease still spans the whole generation and the Result reflects what
        // actually happened rather than merely "the send was accepted".
        val bridge = currentForegroundSendBridge()
        if (bridge != null) {
            when (val outcome = bridge(conversationId, userText, effectiveModelId)) {
                is BridgeOutcome.Completed ->
                    return Result.Success(outcome.modelMessageId, outcome.text)
                is BridgeOutcome.Failed -> return Result.Failure(outcome.reason)
                BridgeOutcome.NotDelegated -> Unit
            }
        }

        // Production always supplies the process registry. A busy conversation is an explicit
        // outcome; attempting a second headless Run would violate Room's unique live-Run slot.
        val generationState = generationRegistry?.getOrCreate(conversationId)
        val uiToken = generationState?.acquireForSend()
        if (generationState != null && uiToken == null) {
            return Result.Failure("Conversation is already generating")
        }
        val currentJob = currentCoroutineContext()[Job]
        if (generationState != null && uiToken != null) {
            if (currentJob == null || !generationState.attachGenerationJob(uiToken, currentJob)) {
                generationState.endGeneration(uiToken)
                return Result.Failure("Conversation generation slot was revoked")
            }
        }

        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val userMessageId = UUID.randomUUID().toString()
        val modelMessageId = UUID.randomUUID().toString()
        val startTime = now + 1
        var lastStreamed: ChatMessage? = null
        var runCreated = false
        var runBound = false
        val persistToken = if (generationState != null && uiToken != null) {
            generationState.nextPersistId()
        } else {
            0L
        }

        return try {
            if (effectiveModelId.isBlank()) return Result.Failure("No model selected")
            val providerName = providerRegistry.providerForModel(effectiveModelId)
            val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                ?: settings.resolveActiveKey(providerName) ?: ""
            if (!providerRegistry.isConfigured(providerName, activeKey)) {
                return Result.Failure("Provider not configured: $providerName")
            }

            val builder = GenerationRequestBuilder(
                settings = settings,
                convRepo = convRepo,
                memoryManager = memoryManager,
                providerRegistry = providerRegistry,
                ragManager = ragManager,
                appContext = appContext,
                pendingConversationSettings = MutableStateFlow(null),
                onSnackbar = {},
            )
            val resolved = if (systemPromptOverride != null) {
                GenerationRequestBuilder.ResolvedPrompt(
                    systemPromptOverride.ifBlank { null },
                    null,
                    null,
                )
            } else {
                builder.buildEffectiveSystemPrompt(conversationId, effectiveModelId)
            }
            val effectiveSettings = builder.buildEffectiveConversationSettings(conversationId)
            val contextLimit = effectiveSettings.contextWindow ?: settings.maxContextWindow.value
            suspend fun compactAtBoundary(): CompactResult {
                generationState?.compacting?.value = true
                return try {
                    contextCompactor.compactAutomatic(
                        conversationId = conversationId,
                        fallbackModel = effectiveModelId,
                        contextLimit = contextLimit,
                    )
                } finally {
                    generationState?.compacting?.value = false
                }
            }
            val snapshot = convRepo.getMessagesForConversationSnapshot(conversationId)
            val selections = convRepo.restoreBranchSelections(conversationId)
            val path = ConversationUiState.resolvePath(
                allMessages = snapshot.map {
                    ChatMessage(
                        id = it.id,
                        parentId = it.parentId,
                        text = it.text,
                        participant = it.participant,
                        timestamp = it.timestamp,
                        status = it.status,
                        runId = it.runId,
                        runSequence = it.runSequence,
                        consumedAtPass = it.consumedAtPass,
                    )
                },
                streamingMsg = null,
                selectedChildren = selections,
            )
            val leafId = path.lastOrNull()?.id
            val parentRunId = path.lastOrNull()?.runId
            val userMessage = MessageEntity(
                id = userMessageId,
                conversationId = conversationId,
                parentId = leafId,
                text = userText,
                thoughts = null,
                status = MessageStatus.SUCCESS,
                participant = Participant.USER,
                timestamp = now,
                runId = runId,
                runSequence = 0,
                consumedAtPass = 0,
            )
            val modelMessage = MessageEntity(
                id = modelMessageId,
                conversationId = conversationId,
                parentId = userMessageId,
                text = "",
                thoughts = null,
                status = MessageStatus.SENDING,
                participant = Participant.MODEL,
                timestamp = startTime,
                modelName = effectiveModelId,
                runId = runId,
                runSequence = 1,
            )
            convRepo.createRunWithMessages(
                RunEntity(
                    id = runId,
                    conversationId = conversationId,
                    parentRunId = parentRunId,
                    status = RunStatus.ACTIVE,
                    activeSlot = 1,
                    startedAt = now,
                    lastCheckpointAt = startTime,
                ),
                listOf(userMessage, modelMessage),
                messageSelectionUpdates = mapOf(
                    leafId to userMessageId,
                    userMessageId to modelMessageId,
                ),
            )
            runCreated = true
            if (generationState != null && uiToken != null) {
                runBound = generationState.tryBindRun(uiToken, runId)
                if (!runBound) {
                    withContext(NonCancellable) {
                        convRepo.finishStoppedGeneration(emptyList(), runId)
                    }
                    currentCoroutineContext().ensureActive()
                    return Result.Failure("Execution cancelled")
                }
                val placeholder = ChatMessage(
                    id = modelMessageId,
                    parentId = userMessageId,
                    text = "",
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    status = MessageStatus.SENDING,
                    modelName = effectiveModelId,
                    runId = runId,
                    runSequence = 1,
                )
                generationState.loadingChange(uiToken, true)
                generationState.streamUpdate(uiToken, placeholder)
            }

            // The current user boundary must be durable before eligibility is evaluated. The
            // compactor excludes the empty SENDING placeholder from token accounting while
            // retaining it as the graph suffix below a newly inserted Compact boundary.
            when (
                val compactResult = compactAtBoundary()
            ) {
                is CompactResult.Failed -> error(
                    "Automatic context compact failed: ${compactResult.message}"
                )
                is CompactResult.Created,
                CompactResult.NotNeeded -> Unit
            }

            val (config, baseGenCtx) = builder.buildGenerationPair(
                providerName, effectiveModelId, activeKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, conversationId,
            )
            val genCtx = baseGenCtx.copy(
                // Automation tools are intentionally foreground-only: a scheduled run must
                // not recursively create more tasks/loops without a user in the loop.
                automationToolsEnabled = false,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            )

            val baseCallbacks = if (generationState != null && uiToken != null) {
                generationState.callbacksFor(uiToken, persistToken)
            } else {
                GenerationCallbacks(
                    onStreamUpdate = {},
                    onLoadingChange = {},
                    onStreamClear = {},
                    isLatestPersist = { true },
                )
            }
            generationManager.generate(
                conversationId = conversationId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = effectiveModelId,
                runId = runId,
                pass = 0,
                config = config,
                ctx = genCtx,
                generationJob = currentJob,
                callbacks = baseCallbacks.copy(
                    onStreamUpdate = { message ->
                        lastStreamed = message
                        baseCallbacks.onStreamUpdate(message)
                    },
                    onToolRoundPersisted = {
                        when (
                            val compactResult = compactAtBoundary()
                        ) {
                            is CompactResult.Failed -> error(
                                "Automatic context compact failed: ${compactResult.message}"
                            )
                            is CompactResult.Created,
                            CompactResult.NotNeeded -> Unit
                        }
                    },
                ),
                streamScope = generationState?.streamScope,
            )
            val finalMsg = convRepo.getMessagesForConversationSnapshot(conversationId)
                .find { it.id == modelMessageId }
            if (finalMsg != null && finalMsg.status == MessageStatus.SUCCESS) {
                Result.Success(modelMessageId, finalMsg.text)
            } else {
                Result.Failure(finalMsg?.text?.takeIf { it.isNotBlank() } ?: "Generation failed")
            }
        } catch (e: CancellationException) {
            // User Stop has a dedicated finalizer once the Run is bound. Other cancellation owners
            // (Worker/Task teardown or the pre-bind commit edge) must not strand a live Run.
            if (runCreated && (!runBound || generationState?.stopping?.value != true)) {
                withContext(NonCancellable) {
                    val stopped = lastStreamed?.copy(status = MessageStatus.STOPPED)
                    convRepo.finishStoppedGeneration(stopped?.let(::listOf).orEmpty(), runId)
                }
            }
            throw e
        } catch (e: Exception) {
            DebugLog.e("TaskExecutionEngine", "runOnce failed for conversation=$conversationId", e)
            val reason = e.localizedMessage ?: "Unexpected error"
            if (runCreated) {
                convRepo.updateStreamingMessageCheckpoint(
                    ChatMessage(
                        id = modelMessageId,
                        parentId = userMessageId,
                        text = reason,
                        thoughts = null,
                        status = MessageStatus.ERROR,
                        participant = Participant.MODEL,
                        timestamp = startTime,
                        modelName = effectiveModelId.takeIf { it.isNotBlank() },
                        runId = runId,
                        runSequence = 1,
                    )
                )
                convRepo.failRun(runId)
            }
            Result.Failure(reason)
        } finally {
            if (generationState != null && uiToken != null && generationState.endGeneration(uiToken)) {
                generationState.onQueueDrainRequested?.invoke(generationState)
            }
        }
    }
}
