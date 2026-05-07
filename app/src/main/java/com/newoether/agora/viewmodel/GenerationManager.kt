package com.newoether.agora.viewmodel

import android.app.Application
import android.util.Log
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.service.AgoraForegroundService
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
    val baseUrl: String?
)

class GenerationManager(
    private val app: Application,
    private val chatDao: ChatDao,
    private val memoryManager: MemoryManager,
    private val providers: Map<String, LlmProvider>,
    private val context: android.content.Context
) {
    private var generationId = 0
    var accessSavedMemories: Boolean = true
    var accessPastConversations: Boolean = true
    var webSearchEnabled: Boolean = false
    var webSearchApiKey: String = ""
    var webSearchProvider: String = "brave"
    var webSearchBaseUrl: String = ""

    private fun getProviderInstance(name: String): LlmProvider =
        providers[name] ?: providers.values.first()

    suspend fun processImages(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uriString ->
            try {
                val uri = android.net.Uri.parse(uriString)
                val mimeType = app.contentResolver.getType(uri)

                when {
                    mimeType?.startsWith("video/") == true -> {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(app, uri)
                        val bitmap = retriever.frameAtTime
                        retriever.release()
                        if (bitmap != null) {
                            val file = File(app.filesDir, "vid_${UUID.randomUUID()}.jpg")
                            file.outputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            bitmap.recycle()
                            file.absolutePath
                        } else null
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
                                    file.absolutePath
                                } else null
                            }
                        }
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun buildMemoryTools(): List<ToolDefinition> {
        if (!accessSavedMemories) return emptyList()
        return listOf(
        ToolDefinition(function = ToolFunction(
            name = "list_memory_files",
            description = "List all files in the memory database.",
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
            description = "Edit or rename a file in the memory database. At least one of 'content' or 'new_name' must be provided.",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty("string", "The current file name to edit."),
                    "content" to ToolProperty("string", "The new markdown content. Omit to keep existing content."),
                    "new_name" to ToolProperty("string", "New file name to rename to. Omit to keep existing name.")
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
    }

    fun buildWebSearchTool(): List<ToolDefinition> {
        if (!webSearchEnabled) return emptyList()
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
            ))
        )
    }

    fun buildRagTool(): List<ToolDefinition> {
        if (!accessPastConversations) return emptyList()
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
            ))
        )
    }

    private suspend fun executeSearchConversations(arguments: String): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "search_conversations"); put("error", "no_query") }.toString()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 10).coerceIn(1, 20)

        return try {
            val results = chatDao.searchMessages(query, limit)
            if (results.isEmpty())
                return buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "no_results") }.toString()

            val grouped = results.groupBy { it.conversationId }
            val titles = mutableMapOf<String, String>()
            for (convId in grouped.keys.take(5)) {
                titles[convId] = chatDao.getConversation(convId)?.title ?: ""
            }

            val resultArray = buildJsonArray {
                for ((convId, messages) in grouped.entries.take(5)) {
                    val title = titles[convId] ?: ""
                    add(buildJsonObject {
                        put("title", title)
                        putJsonArray("messages") {
                            for (msg in messages.take(3)) {
                                add(buildJsonObject {
                                    put("participant", msg.participant.name)
                                    put("text", msg.text)
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

    private fun executeWebSearch(arguments: String): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_search"); put("error", "no_query") }.toString()
        val numResults = ((args["num_results"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 5).coerceIn(1, 10)

        return try {
            val (url, requestHeaders) = when (webSearchProvider) {
                "searxng" -> {
                    val baseUrl = webSearchBaseUrl.ifBlank { "https://searx.be" }
                    ("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&engines=google,brave") to emptyMap<String, String>()
                }
                else -> {
                    val apiKey = webSearchApiKey.ifBlank {
                        return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_api_key") }.toString()
                    }
                    ("https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults") to
                        mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey)
                }
            }

            val body = com.newoether.agora.api.HttpClient.fetchModels(url, requestHeaders)
                ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_response") }.toString()
            val json: Map<String, kotlinx.serialization.json.JsonElement> = Json.decodeFromString(body)
            val resultsArray = when {
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
                        put("url", (obj["url"] as? JsonPrimitive)?.content ?: "")
                        put("description", (obj["description"] as? JsonPrimitive)?.content ?: "")
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

    private suspend fun executeTool(name: String, arguments: String): String {
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
                "create_memory_file" -> memoryManager.createFile(arg("name"), arg("content"))
                "edit_memory_file" -> {
                    val editContent = arg("content").ifBlank { null }
                    val newName = arg("new_name").ifBlank { null }
                    if (editContent == null && newName == null) "Error: At least 'content' or 'new_name' must be provided."
                    else memoryManager.editFile(arg("name"), editContent, newName)
                }
                "delete_memory_file" -> memoryManager.deleteFile(arg("name"))
                "update_active_memory" -> {
                    val mode = arg("mode").ifBlank { "replace" }
                    memoryManager.updateActiveMemory(arg("content"), mode)
                }
                "web_search" -> executeWebSearch(arguments)
                "search_conversations" -> executeSearchConversations(arguments)
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

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        config: GenerationConfig,
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
        var currentStatus = MessageStatus.SENDING
        val segments = mutableListOf<MessageSegment>()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        val placeholder = chatDao.getMessagesForConversation(conversationId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId
        var toolPath = emptyList<ChatMessage>()

        try {
            val dbMessages = chatDao.getMessagesForConversation(conversationId).first()
            val pathEntities = mutableListOf<MessageEntity>()
            var currId: String? = parentId
            while (currId != null) {
                val msg = dbMessages.find { it.id == currId } ?: break
                pathEntities.add(0, msg)
                currId = msg.parentId
            }
            val currentPath = pathEntities.map {
                val segs = it.toolCallJson?.let { json -> try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null } }
                val toolCall = segs?.lastOrNull { s -> s.type == "tool" }?.let { s ->
                    ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", s.toolResult ?: "")
                }
                ChatMessage(id = it.id, parentId = it.parentId, text = it.text, images = it.images, thoughts = it.thoughts, thoughtTitle = it.thoughtTitle, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, thoughtTimeMs = it.thoughtTimeMs, segments = segs, toolCall = toolCall)
            }.filter { it.participant != Participant.ERROR }
                .let { path ->
                    if (isRegenerate) {
                        path.filterNot { it.id.startsWith(Constants.TOOL_MSG_PREFIX) || it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
                            .let { filtered ->
                                if (replaceMessageId != null) {
                                    val oldIdx = filtered.indexOfFirst { it.id == replaceMessageId }
                                    if (oldIdx >= 0) filtered.take(oldIdx) else filtered
                                } else filtered
                            }
                    } else path
                }

            val memoryTools = buildMemoryTools()
            val webSearchTool = buildWebSearchTool()
            val ragTool = buildRagTool()
            val allTools = memoryTools + webSearchTool + ragTool
            val providerConfig = ProviderConfig(
                apiKey = config.apiKey,
                modelId = config.modelId,
                systemPrompt = config.effectiveSystemPrompt,
                maxContextWindow = config.maxContextWindow,
                codeExecutionEnabled = config.codeExecutionEnabled,
                googleSearchEnabled = config.googleSearchEnabled,
                thinkingEnabled = config.thinkingEnabled,
                baseUrl = config.baseUrl,
                tools = allTools
            )

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()

            var lastEmitMs = 0L

            provider.generateResponse(currentPath, providerConfig).collect { event ->
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
                        val result = executeTool(event.name, event.arguments)
                        val tcd = ToolCallData(event.name, event.arguments, result, event.signature)
                        if (toolCallData == null) toolCallData = tcd
                        toolCallDataList = toolCallDataList + tcd
                        val ts = MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = result, signature = event.signature)
                        segments.add(ts)
                        roundToolSegments.add(ts)
                        currentStatus = MessageStatus.SENDING
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        if (currentThoughtBuf.isNotEmpty()) {
                            segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                            currentThoughtBuf = StringBuilder()
                            currentThoughtSignature = null
                        }
                        val tcds = event.calls.map { call ->
                            val result = executeTool(call.name, call.arguments)
                            val ts = MessageSegment(type = "tool", toolName = call.name, toolArgs = call.arguments, toolResult = result, signature = call.signature)
                            segments.add(ts)
                            roundToolSegments.add(ts)
                            ToolCallData(call.name, call.arguments, result, call.signature)
                        }
                        toolCallData = tcds.firstOrNull()
                        toolCallDataList = tcds
                        currentStatus = MessageStatus.SENDING
                    }
                }

                val now = System.currentTimeMillis()
                val isSignificant = event is StreamEvent.Error || event is StreamEvent.ToolCallRequest || event is StreamEvent.ToolCallsRequest
                if (now - lastEmitMs >= 500 || isSignificant) {
                    onStreamUpdate(ChatMessage(
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
                        modelName = modelName,
                        toolCall = toolCallData,
                        segments = if (segments.isNotEmpty()) buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature) else null
                    ))
                    lastEmitMs = now
                }
            }
            // Always emit final state after collection completes
            onStreamUpdate(ChatMessage(
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
                modelName = modelName,
                toolCall = toolCallData,
                segments = if (segments.isNotEmpty()) buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature) else null
            ))

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath
            val chainRootId = if (isRegenerate) parentId else null

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = segments.filter { it.type == "thought" }
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                val prevLastId = if (toolRound == 1 && chainRootId != null) chainRootId else toolPath.lastOrNull()?.id
                val toolMsgId = "${Constants.TOOL_MSG_PREFIX}${UUID.randomUUID()}"
                val toolMsgSegs = txedSegments.ifEmpty { null }
                val tcds = toolCallDataList
                val allSegmentsJson = Json.encodeToString(toolMsgSegs ?: tcds.map { tc ->
                    MessageSegment(type = "tool", toolName = tc.toolName, toolArgs = tc.arguments, toolResult = tc.result, signature = tc.signature)
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
                            MessageSegment(type = "tool", toolName = tcds[index].toolName, toolArgs = tcds[index].arguments, toolResult = tcds[index].result, signature = tcds[index].signature)
                        ))
                    ))
                }

                toolCallData = null
                toolCallDataList = emptyList()

                lastEmitMs = 0L

                provider.generateResponse(toolPath, providerConfig).collect { event ->
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
                            val result = executeTool(event.name, event.arguments)
                            val tcd = ToolCallData(event.name, event.arguments, result, event.signature)
                            if (toolCallData == null) toolCallData = tcd
                            toolCallDataList = toolCallDataList + tcd
                            val ts = MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = result, signature = event.signature)
                            segments.add(ts)
                            roundToolSegments.add(ts)
                            currentStatus = MessageStatus.SENDING
                        }
                        is StreamEvent.ToolCallsRequest -> {
                            if (currentThoughtBuf.isNotEmpty()) {
                                segments.add(MessageSegment(type = "thought", content = currentThoughtBuf.toString(), signature = currentThoughtSignature))
                                currentThoughtBuf = StringBuilder()
                                currentThoughtSignature = null
                            }
                            val tcds = event.calls.map { call ->
                                val result = executeTool(call.name, call.arguments)
                                val ts = MessageSegment(type = "tool", toolName = call.name, toolArgs = call.arguments, toolResult = result, signature = call.signature)
                                segments.add(ts)
                                roundToolSegments.add(ts)
                                ToolCallData(call.name, call.arguments, result, call.signature)
                            }
                            toolCallData = tcds.firstOrNull()
                            toolCallDataList = tcds
                            currentStatus = MessageStatus.SENDING
                        }
                    }

                    val now = System.currentTimeMillis()
                    val isSignificant = event is StreamEvent.Error || event is StreamEvent.ToolCallRequest || event is StreamEvent.ToolCallsRequest
                    if (now - lastEmitMs >= 500 || isSignificant) {
                        onStreamUpdate(ChatMessage(
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
                            modelName = modelName,
                            toolCall = toolCallData,
                            segments = if (segments.isNotEmpty()) buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature) else null
                        ))
                        lastEmitMs = now
                    }
                }
                // Always emit final state after tool round completes
                onStreamUpdate(ChatMessage(
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
                    modelName = modelName,
                    toolCall = toolCallData,
                    segments = if (segments.isNotEmpty()) buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature) else null
                ))
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
                                    MessageSegment(type = "tool", toolName = it.toolName, toolArgs = it.arguments, toolResult = it.result, signature = it.signature)
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
                        val conversationExists = chatDao.getConversation(conversationId) != null
                        if (conversationExists) {
                            val finalSegments = buildLiveSegments(segments, currentThoughtBuf, currentThoughtSignature)
                                ?: segments.toList().ifEmpty { null }
                            val segmentsJson = finalSegments?.let { Json.encodeToString(it) }
                            val effectiveParentId = if (isRegenerate) parentId else (toolPath.lastOrNull()?.id ?: parentId)
                            chatDao.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = totalText, thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName, toolCallJson = segmentsJson
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AgoraVM", "Failed to persist message to DB", e)
                }
                if (generationId == myGenerationId) {
                    onStreamClear()
                    onLoadingChange(false)
                    onGeneratingIdChange(null)
                }
                AgoraForegroundService.stop(app)
            }
        }
    }
}
