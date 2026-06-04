package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SearchResultFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

data class GenerationConfig(
    val providerName: String,
    val modelId: String,
    val apiKey: String,
    val effectiveSystemPrompt: String?,
    val maxContextWindow: Int,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String = "medium",
    val baseUrl: String?,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
)

data class GenerationContext(
    val conversationId: String? = null,
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.newoether.agora.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val searchMatchLimit: Int = 10,
    val searchContextWindow: Int = 8,
    val webSearchEnabled: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "brave",
    val webSearchNumResults: Int = 5,
    val webSearchBaseUrl: String = "",
    val shellEnabled: Boolean = false,
    val shellDevices: List<com.newoether.agora.data.ShellDeviceConfig> = emptyList()
)

class GenerationManager(
    private val app: Application,
    private val chatDao: ChatDao,
    private val memoryManager: MemoryManager,
    private val providers: Map<String, LlmProvider>,
    private val context: android.content.Context
) {
    private var generationId = 0
    var onMessagePersisted: ((messageId: String, text: String) -> Unit)? = null

    private fun getProviderInstance(name: String): LlmProvider =
        providers[name] ?: providers.values.first()

    data class VideoSliceConfig(
        val intervalMicros: Long,
        val frameCount: Int
    )

    suspend fun processImages(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = withContext(Dispatchers.IO) {
        uris.flatMap { uriString ->
            try {
                val uri = android.net.Uri.parse(uriString)
                val mimeType = app.contentResolver.getType(uri)

                when {
                    mimeType?.startsWith("video/") == true -> {
                        val config = sliceConfigs[uriString]
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(app, uri)
                        val paths = mutableListOf<String>()

                        if (config != null && config.frameCount > 1) {
                            var timeUs = 0L
                            for (i in 0 until config.frameCount) {
                                val bitmap = retriever.getFrameAtTime(
                                    timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                                )
                                if (bitmap != null) {
                                    val file = File(app.filesDir, "vid_${UUID.randomUUID()}_$i.jpg")
                                    file.outputStream().use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                    }
                                    bitmap.recycle()
                                    paths.add(file.absolutePath)
                                }
                                timeUs += config.intervalMicros
                            }
                        } else {
                            // Single frame (default behavior)
                            val bitmap = retriever.frameAtTime
                            if (bitmap != null) {
                                val file = File(app.filesDir, "vid_${UUID.randomUUID()}.jpg")
                                file.outputStream().use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                bitmap.recycle()
                                paths.add(file.absolutePath)
                            }
                        }
                        retriever.release()
                        paths
                    }
                    mimeType?.startsWith("image/") == true || mimeType == null -> {
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
                                    bitmap.recycle()
                                    listOf(file.absolutePath)
                                } else emptyList()
                            } ?: emptyList()
                        } ?: emptyList()
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun buildMemoryTools(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSavedMemories && !ctx.accessActiveMemory) return emptyList()
        val tools = mutableListOf<ToolDefinition>()
        if (ctx.accessSavedMemories) {
            tools.addAll(listOf(
                ToolDefinition(function = ToolFunction(
                    name = "list_memory_files",
                    description = "List all files in the memory database with their names and descriptions.",
                    parameters = ToolParameters(properties = emptyMap())
                )),
                ToolDefinition(function = ToolFunction(
                    name = "read_memory_file",
                    description = "Read the content of one or more files from the memory database.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "The file name to read."),
                            "names" to ToolProperty("array", "Multiple file names to read in one call.", items = ToolProperty("string", "A file name."))
                        ),
                        required = emptyList()
                    )
                )),
                ToolDefinition(function = ToolFunction(
                    name = "create_memory_file",
                    description = "Create a new file in the memory database with the given content and optional description.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "The file name to create (e.g., 'notes.md')."),
                            "content" to ToolProperty("string", "The markdown content for the file."),
                            "description" to ToolProperty("string", "A short description of what this file contains (optional).")
                        ),
                        required = listOf("name", "content")
                    )
                )),
                ToolDefinition(function = ToolFunction(
                    name = "edit_memory_file",
                    description = "Edit, rename, or update the description of a file in the memory database. Use 'old_string' + 'new_string' for precise string replacement — the old_string must match exactly once in the file. Use 'content' for full rewrites (mutually exclusive with old_string). At least one of 'content', 'old_string', 'new_name', or 'description' must be provided.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "The current file name to edit."),
                            "content" to ToolProperty("string", "The new markdown content (full rewrite). Omit to keep existing content. Mutually exclusive with 'old_string'."),
                            "old_string" to ToolProperty("string", "Exact string to find and replace. Must match exactly once in the file. Mutually exclusive with 'content'."),
                            "new_string" to ToolProperty("string", "Replacement string for old_string. Pass empty string to delete the matched text. Required when old_string is provided."),
                            "new_name" to ToolProperty("string", "New file name to rename to. Omit to keep existing name."),
                            "description" to ToolProperty("string", "A short description of the file contents. Omit to keep existing description. Pass empty string to remove.")
                        ),
                        required = listOf("name")
                    )
                )),
                ToolDefinition(function = ToolFunction(
                    name = "delete_memory_file",
                    description = "Delete a file from the memory database.",
                    parameters = ToolParameters(
                        properties = mapOf("name" to ToolProperty("string", "The file name to delete.")),
                        required = listOf("name")
                    )
                ))
            ))
        }
        if (ctx.accessActiveMemory) {
            tools.add(ToolDefinition(function = ToolFunction(
                name = "update_active_memory",
                description = "Update the active memory context. Use 'replace' to overwrite, 'append' to add to the end, or 'prepend' to add to the beginning.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "content" to ToolProperty("string", "The content to write."),
                        "mode" to ToolProperty("string", "One of: replace, append, prepend. Default is replace.")
                    ),
                    required = listOf("content")
                )
            )))
        }
        return tools
    }

    fun buildWebSearchTool(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.webSearchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "web_search",
                description = "Search the web for current information. Use this to find facts, news, or data not in your training set.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to execute."),
                        "num_results" to ToolProperty("integer", "Number of results to return (1-10, default 5).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "web_fetch",
                description = "Fetch and read the full text content of a web page. Use this after web_search when you need more detail from a specific page.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The URL of the page to fetch.")
                    ),
                    required = listOf("url")
                )
            ))
        )
    }

    fun buildRagTool(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessPastConversations) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "search_conversations",
                description = "Search past conversations for relevant information. Use this to recall facts, decisions, or context from previous discussions.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to find relevant past conversations."),
                        "limit" to ToolProperty("integer", "Maximum number of results (1-20, default 10).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "list_conversations",
                description = "List all past conversations. Use this to browse conversation history and find conversations to read. Returns conversation IDs, titles, and last-updated timestamps.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "order" to ToolProperty("string", "Sort order by last updated time: 'asc' (oldest first) or 'desc' (newest first). Default: 'desc'."),
                        "limit" to ToolProperty("integer", "Maximum conversations per page (1-50, default 20)."),
                        "offset" to ToolProperty("integer", "Number of conversations to skip for pagination (default 0).")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "read_conversation",
                description = "Read a specific conversation by its ID. Shows the selected message branch as a linear list with page controls. Use this after list_conversations or search_conversations to read a conversation of interest. Each message includes participant, text, and timestamp.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversation_id" to ToolProperty("string", "The conversation ID to read (from list_conversations or search_conversations results)."),
                        "offset" to ToolProperty("integer", "Number of messages to skip for pagination (default 0)."),
                        "limit" to ToolProperty("integer", "Maximum messages per page (1-100, default 50).")
                    ),
                    required = listOf("conversation_id")
                )
            ))
        )
    }

    fun buildShellTool(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled || ctx.shellDevices.isEmpty()) return emptyList()
        val deviceNames = ctx.shellDevices.joinToString(", ") { d -> "\"${d.name}\"" }
        val serverProperty = if (ctx.shellDevices.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only configured server: \"${ctx.shellDevices[0].name}\").")
        } else {
            ToolProperty("string", "The shell server name. Use list_shells to see available servers: $deviceNames.")
        }
        val requiredParams = if (ctx.shellDevices.size == 1) listOf("command") else listOf("command", "server")
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "list_shells",
                description = "List all configured shell servers with their names and descriptions.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )),
            ToolDefinition(function = ToolFunction(
                name = "execute_shell_command",
                description = "Execute a shell command on a remote server and return the combined stdout and stderr output. Use this to run system commands, scripts, or interact with the command line. The command is sent to a configured remote shell server, not executed locally on the device.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "command" to ToolProperty("string", "The shell command to execute."),
                        "server" to serverProperty,
                        "timeout_ms" to ToolProperty("integer", "Timeout in milliseconds (optional, defaults to the device's configured timeout)."),
                        "workdir" to ToolProperty("string", "Working directory for the command (optional).")
                    ),
                    required = requiredParams
                )
            ))
        )
    }

    fun buildFileTool(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled || ctx.shellDevices.isEmpty()) return emptyList()
        val deviceNames = ctx.shellDevices.joinToString(", ") { d -> "\"${d.name}\"" }
        val serverProperty = if (ctx.shellDevices.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only configured server).")
        } else {
            ToolProperty("string", "The shell server name. Available: $deviceNames.")
        }
        val fileRequired = if (ctx.shellDevices.size == 1) emptyList<String>() else listOf("server")

        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "file_read",
                description = "Read a file from a remote shell server. Returns the file content as text.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file on the remote server."),
                        "server" to serverProperty,
                        "offset" to ToolProperty("integer", "Byte offset to start reading from (optional, default 0)."),
                        "limit" to ToolProperty("integer", "Maximum bytes to read (optional, default 1MB).")
                    ),
                    required = listOf("path") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_write",
                description = "Write content to a file on a remote shell server. Creates parent directories as needed and overwrites existing files.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file on the remote server."),
                        "content" to ToolProperty("string", "Content to write to the file."),
                        "server" to serverProperty
                    ),
                    required = listOf("path", "content") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_edit",
                description = "Edit a file on a remote shell server by replacing old_string with new_string. The old_string must match exactly once in the file (or set replace_all to replace all occurrences).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file on the remote server."),
                        "old_string" to ToolProperty("string", "The exact text to find and replace."),
                        "new_string" to ToolProperty("string", "The replacement text."),
                        "server" to serverProperty,
                        "replace_all" to ToolProperty("boolean", "Replace all occurrences instead of requiring a unique match (optional, default false).")
                    ),
                    required = listOf("path", "old_string", "new_string") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_glob",
                description = "List files on a remote shell server matching a glob pattern. Supports * and ** wildcards.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Glob pattern (e.g. '*.go', '**/*.md')."),
                        "server" to serverProperty,
                        "path" to ToolProperty("string", "Base directory for the search (optional, defaults to current directory).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_grep",
                description = "Search for a regex pattern in files on a remote shell server. Returns matching lines with file paths and line numbers.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Regular expression pattern to search for."),
                        "server" to serverProperty,
                        "path" to ToolProperty("string", "File or directory to search in (optional, defaults to current directory)."),
                        "glob" to ToolProperty("string", "Filter files by glob pattern, e.g. '*.go' (optional).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            ))
        )
    }

    private data class SearchWindow(
        val conversationId: String,
        val conversationTitle: String,
        val messages: List<MessageEntity>,
        val topScore: Float,
        val matchCount: Int
    )

    private suspend fun executeSearchConversations(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "search_conversations"); put("error", "no_query") }.toString()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.searchMatchLimit).coerceIn(1, 30)
        val n = ((args["context_window"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.searchContextWindow).coerceIn(4, 32)
        val halfN = n / 2
        val maxWindowSize = n * 3
        val totalCap = 200

        return try {
            // Step 1: Search — normalize to List<Pair<MessageEntity, Float>>
            val scoredResults: List<Pair<MessageEntity, Float>> = if (ctx.modelSearchMethod == "rag" && ctx.activeEmbeddingConfig != null) {
                semanticSearch(query, limit, ctx)
                    .filter { it.second >= ctx.ragThreshold }
            } else {
                chatDao.searchMessages(query, limit).map { it to 1.0f }
            }
            if (scoredResults.isEmpty())
                return buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "no_results") }.toString()

            // Exclude current conversation
            val currentConvId = ctx.conversationId
            val scoreByMessageId = scoredResults.associate { it.first.id to it.second }
            val matchesByConv = scoredResults.filter { it.first.conversationId != currentConvId }
                .groupBy({ it.first.conversationId }, { it.first.id })
            if (matchesByConv.isEmpty())
                return buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "no_results") }.toString()

            // Step 2-4: For each conversation, build branch, expand windows, merge
            val allWindows = mutableListOf<SearchWindow>()

            for ((convId, matchIds) in matchesByConv) {
                val conversation = chatDao.getConversation(convId) ?: continue
                val allMsgs = chatDao.getMessagesForConversation(convId).first()
                    .filter { it.participant in listOf(Participant.USER, Participant.MODEL) && it.text.isNotEmpty() }

                // Build selected branch as indexed list
                val branch = buildSelectedBranch(allMsgs, conversation.selectedBranchesJson)
                val indexMap = branch.withIndex().associate { (i, m) -> m.id to i }
                val branchMatchIds = matchIds.filter { it in indexMap }.toSet()

                // For each match, expand window N/2 before and N/2 after
                val windows = mutableListOf<Pair<IntRange, Float>>() // (range, score)
                for (matchId in matchIds) {
                    val centerIdx = indexMap[matchId] ?: continue
                    val score = scoreByMessageId[matchId] ?: 1.0f
                    val before = halfN.coerceAtMost(centerIdx)
                    val after = halfN.coerceAtMost(branch.size - 1 - centerIdx)
                    // Asymmetric fill: compensate short sides with extra from the other side
                    val extraBefore = (halfN - before).coerceAtMost(branch.size - 1 - centerIdx - after)
                    val extraAfter = (halfN - after - extraBefore).coerceAtLeast(0).coerceAtMost(centerIdx - before)
                    val start = (centerIdx - before - extraAfter).coerceAtLeast(0)
                    val end = (centerIdx + after + extraBefore).coerceAtMost(branch.size - 1)
                    windows.add((start..end) to score)
                }

                // Merge overlapping windows within this conversation
                val sorted = windows.sortedByDescending { it.second }
                val merged = mutableListOf<Pair<IntRange, Float>>()
                for ((range, score) in sorted) {
                    var mergedRange = range
                    val overlapIdx = merged.indexOfFirst { (existing, _) ->
                        mergedRange.first <= existing.last + 1 && existing.first <= mergedRange.last + 1
                    }
                    if (overlapIdx >= 0) {
                        val (existing, existingScore) = merged[overlapIdx]
                        mergedRange = (minOf(mergedRange.first, existing.first)..maxOf(mergedRange.last, existing.last))
                        merged[overlapIdx] = mergedRange to maxOf(score, existingScore)
                    } else {
                        merged.add(mergedRange to score)
                    }
                }
                // Convert to SearchWindow, apply cap
                for ((range, score) in merged) {
                    var cappedRange = range
                    if (range.last - range.first + 1 > maxWindowSize) {
                        val centerId = branchMatchIds.maxByOrNull { scoreByMessageId[it] ?: 0f }
                        val centerIdx = if (centerId != null) indexMap[centerId]!! else (range.first + range.last) / 2
                        cappedRange = ((centerIdx - halfN).coerceAtLeast(range.first)..(centerIdx + halfN).coerceAtMost(range.last))
                    }
                    val windowMsgIds = branch.subList(cappedRange.first, cappedRange.last + 1).map { it.id }.toSet()
                    val matchedInWindow = branchMatchIds.count { it in windowMsgIds }
                    allWindows.add(SearchWindow(
                        conversationId = convId,
                        conversationTitle = conversation.title,
                        messages = cappedRange.map { branch[it] },
                        topScore = score,
                        matchCount = matchedInWindow
                    ))
                }
            }

            // Step 5: Sort by topScore desc, cap total messages
            val finalWindows = mutableListOf<SearchWindow>()
            var totalMessages = 0
            for (window in allWindows.sortedByDescending { it.topScore }) {
                if (totalMessages >= totalCap) break
                val available = totalCap - totalMessages
                if (window.messages.size > available) {
                    finalWindows.add(window.copy(messages = window.messages.take(available)))
                    totalMessages = totalCap
                } else {
                    finalWindows.add(window)
                    totalMessages += window.messages.size
                }
            }

            // Step 6: Format output
            val resultArray = buildJsonArray {
                for (window in finalWindows) {
                    add(buildJsonObject {
                        put("title", window.conversationTitle)
                        put("conversation_id", window.conversationId)
                        put("top_score", window.topScore)
                        put("match_count", window.matchCount)
                        putJsonArray("messages") {
                            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            for (msg in window.messages) {
                                add(buildJsonObject {
                                    put("participant", msg.participant.name)
                                    put("text", msg.text)
                                    put("timestamp", dateFormat.format(java.util.Date(msg.timestamp)))
                                })
                            }
                        }
                    })
                }
            }
            buildJsonObject {
                put("type", "search_conversations")
                put("query", query)
                put("results", resultArray)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "search_conversations")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeListConversations(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val order = ((args["order"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "desc").lowercase()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = ((args["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)

        return try {
            val allConversations = chatDao.getAllConversationsList()
            val sorted = if (order == "desc") allConversations.reversed() else allConversations
            val total = sorted.size
            val page = if (offset < total) {
                sorted.subList(offset, (offset + limit).coerceAtMost(total))
            } else {
                emptyList()
            }
            val hasMore = offset + limit < total

            buildJsonObject {
                put("type", "list_conversations")
                put("total", total)
                put("offset", offset)
                put("limit", limit)
                put("has_more", hasMore)
                putJsonArray("conversations") {
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    for (conv in page) {
                        add(buildJsonObject {
                            put("id", conv.id)
                            put("title", conv.title)
                            put("timestamp", dateFormat.format(java.util.Date(conv.lastUpdated)))
                        })
                    }
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "list_conversations")
                put("error", "list_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeReadConversation(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val conversationId = ((args["conversation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "").trim()
        if (conversationId.isEmpty()) {
            return buildJsonObject {
                put("type", "read_conversation")
                put("error", "missing_conversation_id")
            }.toString()
        }
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 50).coerceIn(1, 100)
        val offset = ((args["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)

        return try {
            val conversation = chatDao.getConversation(conversationId)
                ?: return buildJsonObject {
                    put("type", "read_conversation")
                    put("conversation_id", conversationId)
                    put("error", "not_found")
                }.toString()

            val allMessages = chatDao.getMessagesForConversation(conversationId).first()
                .filter { it.participant in listOf(Participant.USER, Participant.MODEL) }
            // buildSelectedBranch needs all intermediate nodes to walk the tree without gaps;
            // text emptiness check is deferred: tool-only MODEL msgs must stay as parent-chain links.
            val branch = buildSelectedBranch(allMessages, conversation.selectedBranchesJson)
                .filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            val totalMessages = branch.size
            val page = if (offset < totalMessages) {
                branch.subList(offset, (offset + limit).coerceAtMost(totalMessages))
            } else {
                emptyList()
            }
            val hasMore = offset + limit < totalMessages

            buildJsonObject {
                put("type", "read_conversation")
                put("conversation_id", conversationId)
                put("title", conversation.title)
                put("total_messages", totalMessages)
                put("offset", offset)
                put("limit", limit)
                put("has_more", hasMore)
                putJsonArray("messages") {
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    for (msg in page) {
                        add(buildJsonObject {
                            put("participant", msg.participant.name)
                            put("text", msg.text)
                            put("timestamp", dateFormat.format(java.util.Date(msg.timestamp)))
                        })
                    }
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "read_conversation")
                put("conversation_id", conversationId)
                put("error", "read_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Reconstruct the user-selected message branch for a conversation.
     * Uses selectedBranchesJson (Map<parentId → childId>) to walk from root to leaf.
     */
    private fun buildSelectedBranch(
        allMessages: List<MessageEntity>,
        selectedBranchesJson: String?
    ): List<MessageEntity> {
        val selections: Map<String?, String> = try {
            val raw = Json.decodeFromString<Map<String, String>>(selectedBranchesJson ?: "{}")
            raw.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) { emptyMap() }

        val byParent = allMessages.groupBy { it.parentId }
        val path = mutableListOf<MessageEntity>()
        var parentId: String? = null
        while (true) {
            val siblings = byParent[parentId] ?: break
            if (siblings.isEmpty()) break
            val selectedId = selections[parentId]
            val visible = siblings.filter {
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            val chosen = if (visible.isNotEmpty()) {
                visible.find { it.id == selectedId } ?: visible.last()
            } else {
                siblings.find { it.id == selectedId } ?: siblings.last()
            }
            path.add(chosen)
            parentId = chosen.id
        }
        return path
    }

    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<com.newoether.agora.data.local.MessageEntity, Float>> = withContext(Dispatchers.IO) {
        val config = ctx.activeEmbeddingConfig
        if (config == null) {
            DebugLog.w("AgoraVM", "GM RAG: no active embedding config")
            return@withContext emptyList()
        }
        val queryEmbedding = if (config.type == com.newoether.agora.data.EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(config.localFilePath)) {
                DebugLog.w("AgoraVM", "GM RAG: local model not ready")
                return@withContext emptyList()
            }
            LlamaEngine.computeEmbedding(query, config.localFilePath)
        } else {
            val apiKey = resolveEmbeddingApiKey(ctx)
            if (apiKey == null) {
                DebugLog.w("AgoraVM", "GM RAG: no API key")
                return@withContext emptyList()
            }
            EmbeddingClient.computeEmbedding(
                text = query,
                apiKey = apiKey,
                model = config.remoteModelName,
                baseUrl = config.remoteBaseUrl.ifBlank { "https://api.openai.com/v1" }
            )
        }
        if (queryEmbedding == null) {
            DebugLog.w("AgoraVM", "GM RAG: failed to compute query embedding")
            return@withContext emptyList()
        }

        val all = chatDao.getEmbeddingsByModel(config.id)
        DebugLog.d("AgoraVM", "GM RAG: ${all.size} stored embeddings, query dim=${queryEmbedding.size}")
        if (all.isEmpty()) return@withContext emptyList()

        val scored = all.map {
            val stored = EmbeddingIndexer.bytesToFloats(it.embedding)
            it to EmbeddingIndexer.cosineSimilarity(queryEmbedding, stored)
        }
        val best = scored.maxOfOrNull { it.second } ?: 0f
        DebugLog.d("AgoraVM", "GM RAG: best cosine = ${"%.4f".format(best)}")
        val aboveThreshold = scored.filter { it.second > ctx.ragThreshold }
        val messagesById = chatDao.getMessagesByIds(aboveThreshold.map { it.first.messageId }).associateBy { it.id }
        val filtered = aboveThreshold
            .filter { (messagesById[it.first.messageId]?.text?.length ?: 0) >= 10 }
            .sortedByDescending { it.second }
            .take(limit)
        filtered.mapNotNull { (embedding, score) -> messagesById[embedding.messageId]?.let { it to score } }
    }

    private fun resolveEmbeddingApiKey(ctx: GenerationContext): String? {
        return ctx.embeddingApiKey.ifBlank { null }
    }

    private fun executeWebSearch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_search"); put("error", "no_query") }.toString()
        val numResults = ((args["num_results"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.webSearchNumResults).coerceIn(1, 10)

        return try {
            val apiKey = ctx.webSearchApiKeys[ctx.webSearchProvider].orEmpty()
            if (ctx.webSearchProvider != "searxng" && apiKey.isBlank()) {
                return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_api_key") }.toString()
            }
            val body = when (ctx.webSearchProvider) {
                "serper" -> com.newoether.agora.api.HttpClient.post(
                    "https://google.serper.dev/search",
                    Json.encodeToString(buildJsonObject { put("q", query); put("num", numResults) }),
                    mapOf("X-API-KEY" to apiKey)
                )
                "tavily" -> com.newoether.agora.api.HttpClient.post(
                    "https://api.tavily.com/search",
                    Json.encodeToString(buildJsonObject {
                        put("api_key", apiKey)
                        put("query", query)
                        put("max_results", numResults)
                        put("search_depth", "advanced")
                        put("include_answer", true)
                    }),
                    emptyMap()
                )
                "searxng" -> {
                    val baseUrl = ctx.webSearchBaseUrl.ifBlank { "https://searx.be" }
                    com.newoether.agora.api.HttpClient.fetchModels(
                        "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&engines=google,brave"
                    )
                }
                else -> com.newoether.agora.api.HttpClient.fetchModels(
                    "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults",
                    mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey)
                )
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_response") }.toString()

            val json: Map<String, kotlinx.serialization.json.JsonElement> = Json.decodeFromString(body)

            // Tavily: rich response with answer, full content, and scores
            if (ctx.webSearchProvider == "tavily") {
                val resultsArray = json["results"]?.jsonArray
                    ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                if (resultsArray.isEmpty())
                    return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                val answer = (json["answer"] as? JsonPrimitive)?.content
                val rawResults = buildJsonArray {
                    for (element in resultsArray) {
                        val obj = element.jsonObject
                        add(buildJsonObject {
                            put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                            put("url", (obj["url"] as? JsonPrimitive)?.content ?: "")
                            put("content", (obj["content"] as? JsonPrimitive)?.content ?: "")
                            val score = (obj["score"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            if (score != null) put("score", score)
                        })
                    }
                }
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    if (!answer.isNullOrBlank()) put("answer", answer)
                    put("results", rawResults)
                }.toString()
            }

            // Serper, Brave, SearXNG: normalized {title, url, description}
            val resultsArray = when {
                json.containsKey("organic") -> json["organic"]?.jsonArray
                json.containsKey("web") -> {
                    val web = json["web"]?.jsonObject
                    web?.get("results")?.jsonArray
                }
                json.containsKey("results") -> json["results"]?.jsonArray
                else -> null
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            if (resultsArray.isEmpty())
                return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            val rawResults = buildJsonArray {
                for (element in resultsArray) {
                    val obj = element.jsonObject
                    add(buildJsonObject {
                        put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                        put("url", (obj["link"] as? JsonPrimitive)?.content ?: (obj["url"] as? JsonPrimitive)?.content ?: "")
                        put("description", (obj["snippet"] as? JsonPrimitive)?.content ?: (obj["content"] as? JsonPrimitive)?.content ?: (obj["description"] as? JsonPrimitive)?.content ?: "")
                    })
                }
            }
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("results", rawResults)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeWebFetch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val url = (args["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()

        return try {
            val html = com.newoether.agora.api.HttpClient.fetchModels(url)
                ?: return buildJsonObject { put("type", "web_fetch"); put("url", url); put("error", "no_response") }.toString()
            val text = html
                .take(80000)
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&[a-z]+;|&#\\d+;")) { match ->
                    when (match.value) {
                        "&amp;" -> "&"; "&lt;" -> "<"; "&gt;" -> ">"; "&quot;" -> "\""
                        "&apos;" -> "'"; "&nbsp;" -> " "; "&#39;" -> "'"
                        else -> " "
                    }
                }
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(4000)
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("error", "fetch_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun listShells(ctx: GenerationContext): String {
        val devices = ctx.shellDevices.map { d ->
            buildJsonObject {
                put("name", d.name.ifBlank { "Untitled" })
                put("description", d.description)
            }
        }
        return buildJsonObject {
            put("type", "list_shells")
            putJsonArray("devices") { devices.forEach { add(it) } }
        }.toString()
    }

    private suspend fun executeShellCommand(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = try {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        } catch (e: Exception) {
            return buildJsonObject {
                put("type", "execute_shell_command")
                put("error", "parse_error")
                put("message", "Failed to parse arguments: ${e.message}")
            }.toString()
        }
        fun arg(key: String): String = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val command = arg("command")
        if (command.isBlank()) {
            return buildJsonObject {
                put("type", "execute_shell_command")
                put("error", "no_command")
            }.toString()
        }
        val serverName = arg("server")
        val device = if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else {
            null
        }
        if (device == null) {
            return if (ctx.shellDevices.size == 1) {
                buildJsonObject {
                    put("type", "execute_shell_command")
                    put("error", "server_not_found")
                    put("message", "Unknown server: $serverName. Use \"${ctx.shellDevices[0].name}\" or omit the server parameter.")
                }.toString()
            } else {
                val names = ctx.shellDevices.joinToString(", ") { "\"${it.name}\"" }
                buildJsonObject {
                    put("type", "execute_shell_command")
                    put("error", "server_not_found")
                    put("message", if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names." else "Unknown server: $serverName. Available: $names.")
                }.toString()
            }
        }
        val timeoutMs = (arg("timeout_ms").toIntOrNull() ?: device.timeout * 1000).coerceIn(1000, 120000)
        val workdir = arg("workdir")
        val serverUrl = device.serverUrl.trimEnd('/')
        if (serverUrl.isBlank()) {
            return buildJsonObject {
                put("type", "execute_shell_command")
                put("error", "no_server_url")
                put("message", "Server \"${device.name}\" has no URL configured.")
            }.toString()
        }
        return try {
            val shellClient = com.newoether.agora.util.ShellClient(
                serverUrl = serverUrl,
                apiKey = device.apiKey,
                cachedPublicKey = device.conchPublicKey
            )
            // Fetch and verify server public key for E2E encryption
            if (!shellClient.fetchPublicKey() && device.apiKey.isNotBlank()) {
                return buildJsonObject {
                    put("type", "execute_shell_command")
                    put("error", "encryption_failed")
                    put("message", shellClient.lastError ?: "Failed to establish encrypted channel.")
                }.toString()
            }

            val prepared = shellClient.prepareRequest(command, timeoutMs, workdir)
            val handle = com.newoether.agora.api.HttpClient.streamPost(
                "${prepared.serverUrl}/execute", prepared.body, prepared.headers
            )
            try {
                val output = StringBuilder()
                var exitCode: Int? = null
                var errorMessage: String? = null
                var currentEvent: String? = null
                val aesKey = shellClient.getSessionKey()
                while (true) {
                    val line = handle.readLine() ?: break
                    when {
                        line.startsWith("event: ") -> { currentEvent = line.substring(7).trim() }
                        line.startsWith("data: ") -> {
                            var dataStr = line.substring(6).trim()
                            if (aesKey != null) {
                                try {
                                    dataStr = shellClient.decryptSseData(dataStr)
                                } catch (e: Exception) {
                                    DebugLog.e("AgoraAPI", "SSE decryption failed: ${e.message}", e)
                                    continue
                                }
                            }
                            val dataJson = try { Json.parseToJsonElement(dataStr).jsonObject } catch (_: Exception) { null }
                            if (dataJson == null) continue
                            when (currentEvent) {
                                "line" -> {
                                    val text = (dataJson["line"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    if (text != null) output.append(text).append('\n')
                                }
                                "result" -> {
                                    exitCode = (dataJson["exit_code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                                }
                                "error" -> {
                                    errorMessage = (dataJson["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                }
                            }
                        }
                    }
                }
                if (errorMessage != null) {
                    buildJsonObject {
                        put("type", "execute_shell_command")
                        put("server", device.name)
                        put("command", command)
                        put("error", "execution_error")
                        put("message", errorMessage)
                        put("output", output.toString().trimEnd())
                    }.toString()
                } else {
                    buildJsonObject {
                        put("type", "execute_shell_command")
                        put("server", device.name)
                        put("command", command)
                        put("exit_code", exitCode ?: -1)
                        put("output", output.toString().trimEnd())
                    }.toString()
                }
            } finally { handle.close() }
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "execute_shell_command")
                put("server", device.name)
                put("command", command)
                put("error", "request_failed")
                put("message", e.message ?: "Unknown error")
            }.toString()
        }
    }

    // --- File tool helpers ---

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        return if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else null
    }

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        return if (ctx.shellDevices.size == 1) {
            "Unknown server: $serverName. Use \"${ctx.shellDevices[0].name}\" or omit the server parameter."
        } else {
            val names = ctx.shellDevices.joinToString(", ") { "\"${it.name}\"" }
            if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names."
            else "Unknown server: $serverName. Available: $names."
        }
    }

    private suspend fun withShellClient(
        device: ShellDeviceConfig,
        ctx: GenerationContext,
        block: suspend (com.newoether.agora.util.ShellClient) -> String
    ): String {
        val serverUrl = device.serverUrl.trimEnd('/')
        if (serverUrl.isBlank()) {
            return buildJsonObject {
                put("error", "no_server_url")
                put("message", "Server \"${device.name}\" has no URL configured.")
            }.toString()
        }
        return try {
            val shellClient = com.newoether.agora.util.ShellClient(
                serverUrl = serverUrl,
                apiKey = device.apiKey,
                cachedPublicKey = device.conchPublicKey
            )
            if (!shellClient.fetchPublicKey() && device.apiKey.isNotBlank()) {
                buildJsonObject {
                    put("error", "encryption_failed")
                    put("message", shellClient.lastError ?: "Failed to establish encrypted channel.")
                }.toString()
            } else {
                block(shellClient)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "request_failed")
                put("message", e.message ?: "Unknown error")
            }.toString()
        }
    }

    private suspend fun executeFileRead(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return toolError("file_read", "path is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx)
            ?: return toolError("file_read", serverNotFoundMessage(serverName, ctx), serverName)
        val offset = arg(args, "offset").toLongOrNull() ?: 0L
        val limit = arg(args, "limit").toLongOrNull() ?: 0L

        return withShellClient(device, ctx) { client ->
            val result = client.fileRead(path, offset, limit)
            if (result.error != null) {
                toolError("file_read", result.error, device.name)
            } else {
                buildJsonObject {
                    put("type", "file_read")
                    put("server", device.name)
                    put("path", path)
                    put("content", result.content)
                    put("lines", result.lines)
                }.toString()
            }
        }
    }

    private suspend fun executeFileWrite(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return toolError("file_write", "path is required")
        val content = arg(args, "content")
        if (content.isBlank()) return toolError("file_write", "content is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx)
            ?: return toolError("file_write", serverNotFoundMessage(serverName, ctx), serverName)

        return withShellClient(device, ctx) { client ->
            val error = client.fileWrite(path, content)
            if (error != null) {
                toolError("file_write", error, device.name)
            } else {
                buildJsonObject {
                    put("type", "file_write")
                    put("server", device.name)
                    put("path", path)
                    put("ok", true)
                }.toString()
            }
        }
    }

    private suspend fun executeFileEdit(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return toolError("file_edit", "path is required")
        val oldStr = arg(args, "old_string")
        if (oldStr.isBlank()) return toolError("file_edit", "old_string is required")
        val newStr = arg(args, "new_string")
        val replaceAll = arg(args, "replace_all").equals("true", ignoreCase = true)
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx)
            ?: return toolError("file_edit", serverNotFoundMessage(serverName, ctx), serverName)

        return withShellClient(device, ctx) { client ->
            val result = client.fileRead(path, 0, 0)
            if (result.error != null) return@withShellClient toolError("file_edit", "read error: ${result.error}", device.name)

            val content = result.content
            val count = content.split(oldStr).size - 1
            if (count == 0) {
                toolError("file_edit", "old_string not found in file", device.name)
            } else if (count > 1 && !replaceAll) {
                toolError("file_edit", "Found $count matches of old_string. Use replace_all=true to replace all, or provide more context to make it unique.", device.name)
            } else {
                val replaced = content.replace(oldStr, newStr)
                val writeError = client.fileWrite(path, replaced)
                if (writeError != null) {
                    toolError("file_edit", "write error: $writeError", device.name)
                } else {
                    buildJsonObject {
                        put("type", "file_edit")
                        put("server", device.name)
                        put("path", path)
                        put("replaced", count)
                    }.toString()
                }
            }
        }
    }

    private suspend fun executeFileGlob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return toolError("file_glob", "pattern is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx)
            ?: return toolError("file_glob", serverNotFoundMessage(serverName, ctx), serverName)
        val basePath = arg(args, "path")

        return withShellClient(device, ctx) { client ->
            val result = client.fileGlob(pattern, basePath)
            result.fold(
                onSuccess = { files ->
                    buildJsonObject {
                        put("type", "file_glob")
                        put("server", device.name)
                        put("pattern", pattern)
                        putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
                    }.toString()
                },
                onFailure = { e -> toolError("file_glob", e.message ?: "Unknown error", device.name) }
            )
        }
    }

    private suspend fun executeFileGrep(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return toolError("file_grep", "pattern is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx)
            ?: return toolError("file_grep", serverNotFoundMessage(serverName, ctx), serverName)
        val basePath = arg(args, "path")
        val fileGlob = arg(args, "glob")

        return withShellClient(device, ctx) { client ->
            val result = client.fileGrep(pattern, basePath, fileGlob)
            result.fold(
                onSuccess = { matches ->
                    buildJsonObject {
                        put("type", "file_grep")
                        put("server", device.name)
                        put("pattern", pattern)
                        putJsonArray("matches") {
                            matches.forEach { m ->
                                add(buildJsonObject {
                                    put("path", m.path)
                                    put("line", m.line)
                                    put("content", m.content)
                                })
                            }
                        }
                    }.toString()
                },
                onFailure = { e -> toolError("file_grep", e.message ?: "Unknown error", device.name) }
            )
        }
    }

    private fun parseToolArgs(arguments: String): Map<String, kotlinx.serialization.json.JsonElement> {
        return try {
            val argsStr = arguments.ifBlank { "{}" }
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun toolError(type: String, message: String, server: String? = null): String {
        return buildJsonObject {
            put("type", type)
            put("error", "error")
            put("message", message)
            if (server != null) put("server", server)
        }.toString()
    }

    private fun arg(args: Map<String, kotlinx.serialization.json.JsonElement>, key: String): String {
        return (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    }

    private suspend fun executeTool(name: String, arguments: String, ctx: GenerationContext): String {
        return try {
            val argsStr = arguments.ifBlank { "{}" }
            val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
            fun arg(key: String): String = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            when (name) {
                "list_memory_files" -> {
                    val files = memoryManager.listFiles()
                    if (files.isEmpty()) {
                        buildJsonObject {
                            put("type", "list_memory_files")
                            putJsonArray("files") {}
                        }.toString()
                    } else {
                        buildJsonObject {
                            put("type", "list_memory_files")
                            putJsonArray("files") {
                                files.forEach { f ->
                                    add(buildJsonObject {
                                        put("name", f.name)
                                        put("description", f.description)
                                    })
                                }
                            }
                        }.toString()
                    }
                }
                "read_memory_file" -> {
                    val singleName = arg("name")
                    val namesArray = args["names"] as? kotlinx.serialization.json.JsonArray
                    if (namesArray != null && namesArray.isNotEmpty()) {
                        val names = namesArray.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }.filter { it.isNotEmpty() }
                        names.joinToString("\n\n") { name ->
                            "--- $name ---\n${memoryManager.readFile(name)}"
                        }
                    } else if (singleName.isNotEmpty()) {
                        memoryManager.readFile(singleName)
                    } else {
                        "Error: No file name provided. Use 'name' for a single file or 'names' for multiple files."
                    }
                }
                "create_memory_file" -> memoryManager.createFile(arg("name"), arg("content"), arg("description"))
                "edit_memory_file" -> {
                    val editContent = arg("content").ifBlank { null }
                    val oldStr = arg("old_string").ifBlank { null }
                    val newStr = arg("new_string") // empty is valid (means delete)
                    val newName = arg("new_name").ifBlank { null }
                    val descArg = arg("description")
                    val desc = if (args.containsKey("description")) descArg else null
                    if (editContent != null && oldStr != null) {
                        "Error: 'content' and 'old_string' are mutually exclusive. Use one or the other."
                    } else if (oldStr != null && !args.containsKey("new_string")) {
                        "Error: 'old_string' requires 'new_string' (pass empty string to delete)."
                    } else if (editContent == null && oldStr == null && newName == null && desc == null) {
                        "Error: At least 'content', 'old_string', 'new_name', or 'description' must be provided."
                    } else {
                        memoryManager.editFile(arg("name"), editContent, newName, desc, oldStr, newStr)
                    }
                }
                "delete_memory_file" -> memoryManager.deleteFile(arg("name"))
                "update_active_memory" -> {
                    val mode = arg("mode").ifBlank { "replace" }
                    memoryManager.updateActiveMemory(arg("content"), mode)
                }
                "web_search" -> executeWebSearch(arguments, ctx)
                "web_fetch" -> executeWebFetch(arguments, ctx)
                "search_conversations" -> executeSearchConversations(arguments, ctx)
                "list_conversations" -> executeListConversations(arguments, ctx)
                "read_conversation" -> executeReadConversation(arguments, ctx)
                "list_shells" -> listShells(ctx)
                "execute_shell_command" -> executeShellCommand(arguments, ctx)
                "file_read" -> executeFileRead(arguments, ctx)
                "file_write" -> executeFileWrite(arguments, ctx)
                "file_edit" -> executeFileEdit(arguments, ctx)
                "file_glob" -> executeFileGlob(arguments, ctx)
                "file_grep" -> executeFileGrep(arguments, ctx)
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    private fun applyUserTemplate(messages: List<ChatMessage>, prepend: String?, postpend: String?): List<ChatMessage> {
        if (prepend == null && postpend == null) return messages
        val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return messages.map { msg ->
            if (msg.participant == Participant.USER && msg.text.isNotEmpty()) {
                val ts = java.util.Date(msg.timestamp)
                val rp = prepend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
                val ra = postpend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
                if (rp.isEmpty() && ra.isEmpty()) msg
                else msg.copy(text = rp + msg.text + ra)
            } else msg
        }
    }

    private fun buildLiveSegments(flushed: List<MessageSegment>, buf: StringBuilder, signature: String? = null): List<MessageSegment>? {
        val result = flushed.toMutableList()
        if (buf.isNotEmpty()) {
            result.add(MessageSegment(type = "thought", content = buf.toString(), signature = signature))
        }
        return result.ifEmpty { null }
    }

    private suspend fun buildApiPath(
        parentId: String?,
        conversationId: String,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        config: GenerationConfig,
        ctx: GenerationContext
    ): Pair<List<ChatMessage>, ProviderConfig> {
        val dbMessages = chatDao.getMessagesForConversation(conversationId).first()
        val pathEntities = mutableListOf<MessageEntity>()
        var currId: String? = parentId
        while (currId != null) {
            val msg = dbMessages.find { it.id == currId } ?: break
            pathEntities.add(0, msg)
            currId = msg.parentId
        }
        // Inject tool call chains that are children of messages in the ancestor path.
        val expanded = mutableListOf<MessageEntity>()
        for (entity in pathEntities) {
            val toolChildren = dbMessages
                .filter { it.parentId == entity.id && it.id.startsWith(Constants.TOOL_MSG_PREFIX) }
                .sortedBy { it.timestamp }
            if (toolChildren.isEmpty()) {
                expanded.add(entity)
            } else {
                for (toolMsg in toolChildren) {
                    expanded.add(toolMsg)
                    val pending = mutableListOf(toolMsg)
                    var safety = 0
                    while (pending.isNotEmpty() && safety < 100) {
                        val current = pending.removeAt(0)
                        val children = dbMessages
                            .filter { it.parentId == current.id && (it.id.startsWith(Constants.RESULT_MSG_PREFIX) || it.id.startsWith(Constants.TOOL_MSG_PREFIX)) }
                            .sortedBy { it.timestamp }
                        for (child in children) {
                            val isResult = child.id.startsWith(Constants.RESULT_MSG_PREFIX)
                            if (isResult) {
                                // Include result_ messages so providers can emit
                                // correct tool_use/tool_result pairs. The result
                                // data lives in TOOL_MSG segments too, but Anthropic
                                // requires separate tool_result blocks in the next
                                // user-role message.
                                if (child !in expanded) {
                                    expanded.add(child)
                                }
                                pending.add(child)
                            } else if (child !in expanded) {
                                expanded.add(child)
                                pending.add(child)
                            }
                        }
                        safety++
                    }
                }
                expanded.add(entity.copy(toolCallJson = null))
            }
        }
        val currentPath = expanded.map {
            val segs = it.toolCallJson?.let { json -> try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null } }
            val toolCall = segs?.lastOrNull { s -> s.type == "tool" }?.let { s ->
                ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", s.toolResult ?: "", s.toolCallId)
            }
            val meta = it.attachmentMeta?.let { json -> try { Json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(json) } catch (_: Exception) { null } }
            val combinedText = if (meta != null && it.participant == Participant.USER) {
                val fileContent = meta.items.mapNotNull { item ->
                    val content = item.textContent
                    if (content != null) {
                        val label = item.fileName ?: "file"
                        "\n\n--- File: $label ---\n$content"
                    } else null
                }.joinToString("")
                it.text + fileContent
            } else it.text
            ChatMessage(id = it.id, parentId = it.parentId, text = combinedText, images = it.images, thoughts = it.thoughts, thoughtTitle = it.thoughtTitle, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, thoughtTimeMs = it.thoughtTimeMs, segments = segs, toolCall = toolCall)
        }.filter { it.participant != Participant.ERROR }
            .let { path ->
                if (isRegenerate && replaceMessageId != null) {
                    val oldIdx = path.indexOfFirst { it.id == replaceMessageId }
                    if (oldIdx >= 0) path.take(oldIdx) else path
                } else path
            }

        val memoryTools = buildMemoryTools(ctx)
        val webSearchTool = buildWebSearchTool(ctx)
        val ragTool = buildRagTool(ctx)
        val shellTool = buildShellTool(ctx)
        val fileTool = buildFileTool(ctx)
        val allTools = memoryTools + webSearchTool + ragTool + shellTool + fileTool
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = config.effectiveSystemPrompt,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            thinkingEnabled = config.thinkingEnabled,
            thinkingLevel = config.thinkingLevel,
            baseUrl = config.baseUrl,
            tools = allTools,
            userPrepend = config.userPrepend,
            userPostpend = config.userPostpend,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        return Pair(currentPath, providerConfig)
    }

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        config: GenerationConfig,
        ctx: GenerationContext,
        generationJob: kotlinx.coroutines.Job?,
        onStreamUpdate: (ChatMessage) -> Unit,
        onLoadingChange: (Boolean) -> Unit,
        onGeneratingIdChange: (String?) -> Unit,
        onStreamClear: () -> Unit
    ) {
        generationId++
        val myGenerationId = generationId

        val provider = getProviderInstance(config.providerName)

        onLoadingChange(true)
        onGeneratingIdChange(conversationId)
        withContext(Dispatchers.Main) { AgoraForegroundService.start(app) }

        var totalText = ""
        var totalThoughts = ""
        var totalThoughtTitle: String? = null
        var totalTokenCount = 0
        var totalThoughtTimeMs: Long? = null
        var cumulativeThoughtMs: Long = 0
        var currentThoughtStartMs: Long? = null
        var currentStatus = MessageStatus.SENDING
        val segments = mutableListOf<MessageSegment>()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        val placeholder = chatDao.getMessagesForConversation(conversationId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId
        var toolPath = emptyList<ChatMessage>()

        try {
            val (currentPath, providerConfig) = buildApiPath(parentId, conversationId, isRegenerate, replaceMessageId, config, ctx)

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()

            var lastEmitMs = 0L

            fun modelMessage() = ChatMessage(
                id = modelMessageId, parentId = parentId,
                text = totalText, thoughts = totalThoughts.ifBlank { null },
                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                status = currentStatus, participant = Participant.MODEL,
                timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs,
                modelName = modelName, toolCall = toolCallData,
                segments = buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature)
            )

            suspend fun handleStreamEvent(event: StreamEvent) {
                when (event) {
                    is StreamEvent.TextChunk -> {
                        if (currentStatus == MessageStatus.THINKING) {
                            if (currentThoughtStartMs != null) {
                                cumulativeThoughtMs += System.currentTimeMillis() - currentThoughtStartMs!!
                                currentThoughtStartMs = null
                            }
                            totalThoughtTimeMs = cumulativeThoughtMs
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
                        currentStatus = MessageStatus.THINKING
                        if (currentThoughtStartMs == null) {
                            currentThoughtStartMs = System.currentTimeMillis()
                        }
                        if (totalThoughts.isEmpty()) totalThoughts = "Thinking..."
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
                            if (currentThoughtStartMs == null) {
                                currentThoughtStartMs = System.currentTimeMillis()
                            }
                            if (totalThoughts.isEmpty()) totalThoughts = "Thinking..."
                        }
                    }
                    is StreamEvent.Error -> {
                        if (toolCallData == null && toolCallDataList.isEmpty()) {
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
                        if (currentThoughtStartMs != null) {
                            cumulativeThoughtMs += System.currentTimeMillis() - currentThoughtStartMs!!
                            currentThoughtStartMs = null
                        }
                        totalThoughtTimeMs = cumulativeThoughtMs
                        val ts = MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = null, toolCallId = event.id, signature = event.signature)
                        segments.add(ts)
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                        val result = executeTool(event.name, event.arguments, ctx)
                        val idx = segments.indexOfLast { it.toolCallId == event.id }
                        if (idx >= 0) {
                            segments[idx] = segments[idx].copy(toolResult = result)
                            roundToolSegments.add(segments[idx])
                        }
                        val tcd = ToolCallData(event.name, event.arguments, result, event.signature, event.id)
                        if (toolCallData == null) toolCallData = tcd
                        toolCallDataList = toolCallDataList + tcd
                        currentStatus = MessageStatus.SENDING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        if (currentThoughtBuf.isNotEmpty()) {
                            segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                            currentThoughtBuf = StringBuilder()
                            currentThoughtSignature = null
                        }
                        if (currentThoughtStartMs != null) {
                            cumulativeThoughtMs += System.currentTimeMillis() - currentThoughtStartMs!!
                            currentThoughtStartMs = null
                        }
                        totalThoughtTimeMs = cumulativeThoughtMs
                        event.calls.forEach { call ->
                            segments.add(MessageSegment(type = "tool", toolName = call.name, toolArgs = call.arguments, toolResult = null, toolCallId = call.id, signature = call.signature))
                        }
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                        val tcds = event.calls.map { call ->
                            val result = executeTool(call.name, call.arguments, ctx)
                            val idx = segments.indexOfLast { it.toolCallId == call.id }
                            if (idx >= 0) {
                                segments[idx] = segments[idx].copy(toolResult = result)
                                roundToolSegments.add(segments[idx])
                            }
                            ToolCallData(call.name, call.arguments, result, call.signature, call.id)
                        }
                        toolCallData = tcds.firstOrNull()
                        toolCallDataList = tcds
                        currentStatus = MessageStatus.SENDING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                    }
                }

                val now = System.currentTimeMillis()
                val isSignificant = event is StreamEvent.Error
                if (now - lastEmitMs >= 500 || isSignificant) {
                    onStreamUpdate(modelMessage())
                    lastEmitMs = now
                }
            }

            val apiPath = applyUserTemplate(currentPath, config.userPrepend, config.userPostpend)
            provider.generateResponse(apiPath, providerConfig).collect { event ->
                handleStreamEvent(event)
            }
            // Always emit final state after collection completes
            onStreamUpdate(modelMessage())

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = segments.filter { it.type == "thought" }
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                val prevLastId = if (toolRound == 1) modelMessageId else toolPath.lastOrNull()?.id
                val toolMsgId = "${Constants.TOOL_MSG_PREFIX}${UUID.randomUUID()}"
                val toolMsgSegs = txedSegments.ifEmpty { null }
                val tcds = toolCallDataList
                val allSegmentsJson = Json.encodeToString(toolMsgSegs ?: tcds.map { tc ->
                    MessageSegment(type = "tool", toolName = tc.toolName, toolArgs = tc.arguments, toolResult = tc.result, signature = tc.signature, toolCallId = tc.toolCallId)
                })
                val resultMsgs = tcds.map { tcData ->
                    val rid = "${Constants.RESULT_MSG_PREFIX}${UUID.randomUUID()}"
                    val displayText = SearchResultFormatter.format(tcData.result, context)
                    rid to ChatMessage(
                        id = rid, parentId = toolMsgId,
                        text = displayText,
                        participant = Participant.USER, status = MessageStatus.SUCCESS,
                        toolCall = tcData
                    )
                }
                toolPath = toolPath.toMutableList().apply {
                    add(ChatMessage(
                        id = toolMsgId, parentId = prevLastId,
                        text = "", participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS, toolCall = tcds.first(),
                        segments = toolMsgSegs
                    ))
                    for ((_, msg) in resultMsgs) add(msg)
                }
                chatDao.upsertMessage(MessageEntity(
                    id = toolMsgId, conversationId = conversationId, parentId = prevLastId,
                    text = "", thoughts = null, status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL, timestamp = System.currentTimeMillis(),
                    toolCallJson = allSegmentsJson
                ))
                for ((index, entry) in resultMsgs.withIndex()) {
                    val (rid, _) = entry
                    chatDao.upsertMessage(MessageEntity(
                        id = rid, conversationId = conversationId, parentId = toolMsgId,
                        text = tcds[index].result, thoughts = null, status = MessageStatus.SUCCESS,
                        participant = Participant.USER, timestamp = System.currentTimeMillis(),
                        toolCallJson = Json.encodeToString(listOf(
                            MessageSegment(type = "tool", toolName = tcds[index].toolName, toolArgs = tcds[index].arguments, toolResult = tcds[index].result, signature = tcds[index].signature, toolCallId = tcds[index].toolCallId)
                        ))
                    ))
                }

                toolCallData = null
                toolCallDataList = emptyList()

                lastEmitMs = 0L

                val apiToolPath = applyUserTemplate(toolPath, config.userPrepend, config.userPostpend)
                provider.generateResponse(apiToolPath, providerConfig).collect { event ->
                    handleStreamEvent(event)
                }
                // Always emit final state after tool round completes
                onStreamUpdate(modelMessage())
            }

            if (!currentCoroutineContext().isActive) {
                currentStatus = MessageStatus.STOPPED
            }

            if (!isRegenerate && generationId == myGenerationId) for (msg in toolPath) {
                if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX) || msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                    val exists = chatDao.getMessagesForConversation(conversationId).first().any { it.id == msg.id }
                    if (!exists) {
                        chatDao.upsertMessage(MessageEntity(
                            id = msg.id, conversationId = conversationId, parentId = msg.parentId,
                            text = msg.text, thoughts = null, status = msg.status,
                            participant = msg.participant, timestamp = System.currentTimeMillis(),
                            toolCallJson = msg.segments?.let { Json.encodeToString(it) }
                                ?: msg.toolCall?.let { Json.encodeToString(listOf(
                                    MessageSegment(type = "tool", toolName = it.toolName, toolArgs = it.arguments, toolResult = it.result, signature = it.signature, toolCallId = it.toolCallId)
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
            val cancelledExternally = generationJob?.isCancelled == true
            withContext(NonCancellable) {
                try {
                    if (generationId == myGenerationId) {
                        val conversationExists = chatDao.getConversation(conversationId) != null
                        if (conversationExists) {
                            val finalSegments = buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature)
                                ?: segments.toList().ifEmpty { null }
                            val segmentsJson = finalSegments?.let { Json.encodeToString(it) }
                            val effectiveParentId = parentId
                            chatDao.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = totalText, thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName, toolCallJson = segmentsJson
                            ))
                            if (totalText.isNotBlank()) {
                                onMessagePersisted?.invoke(modelMessageId, totalText)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraVM", "Failed to persist message to DB", e)
                }
                if (generationId == myGenerationId && !cancelledExternally) {
                    onStreamClear()
                    onLoadingChange(false)
                    onGeneratingIdChange(null)
                }
                AgoraForegroundService.stop(app)
                if (!AppForegroundTracker.isInForeground && currentStatus == MessageStatus.SUCCESS && totalText.isNotBlank()) {
                    AgoraForegroundService.showCompletionNotification(app, totalText)
                }
            }
        }
    }
}
