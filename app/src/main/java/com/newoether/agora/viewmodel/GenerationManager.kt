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
    val userPostpend: String? = null
)

data class GenerationContext(
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.newoether.agora.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val webSearchEnabled: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "brave",
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
                    description = "Edit, rename, or update the description of a file in the memory database. At least one of 'content', 'new_name', or 'description' must be provided.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "The current file name to edit."),
                            "content" to ToolProperty("string", "The new markdown content. Omit to keep existing content."),
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

    private suspend fun executeSearchConversations(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "search_conversations"); put("error", "no_query") }.toString()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 10).coerceIn(1, 20)

        return try {
            val results: List<com.newoether.agora.data.local.MessageEntity> = if (ctx.modelSearchMethod == "rag" && ctx.activeEmbeddingConfig != null) {
                semanticSearch(query, limit, ctx).map { it.first }
            } else {
                chatDao.searchMessages(query, limit)
            }
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
        val filtered = scored.filter { it.second > ctx.ragThreshold }
         .sortedByDescending { it.second }
         .take(limit)
        val messagesById = chatDao.getMessagesByIds(filtered.map { it.first.messageId }).associateBy { it.id }
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
        val numResults = ((args["num_results"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 5).coerceIn(1, 10)

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
            val body = buildJsonObject {
                put("command", command)
                put("timeout_ms", timeoutMs)
                if (workdir.isNotBlank()) put("workdir", workdir)
            }.toString()
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (device.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${device.apiKey}"
            val handle = com.newoether.agora.api.HttpClient.streamPost("$serverUrl/execute", body, headers)
            try {
                val output = StringBuilder()
                var exitCode: Int? = null
                var errorMessage: String? = null
                var currentEvent: String? = null
                while (true) {
                    val line = handle.readLine() ?: break
                    when {
                        line.startsWith("event: ") -> { currentEvent = line.substring(7).trim() }
                        line.startsWith("data: ") -> {
                            val dataStr = line.substring(6).trim()
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
                    val newName = arg("new_name").ifBlank { null }
                    val descArg = arg("description")
                    val desc = if (args.containsKey("description")) descArg else null
                    if (editContent == null && newName == null && desc == null) "Error: At least 'content', 'new_name', or 'description' must be provided."
                    else memoryManager.editFile(arg("name"), editContent, newName, desc)
                }
                "delete_memory_file" -> memoryManager.deleteFile(arg("name"))
                "update_active_memory" -> {
                    val mode = arg("mode").ifBlank { "replace" }
                    memoryManager.updateActiveMemory(arg("content"), mode)
                }
                "web_search" -> executeWebSearch(arguments, ctx)
                "web_fetch" -> executeWebFetch(arguments, ctx)
                "search_conversations" -> executeSearchConversations(arguments, ctx)
                "list_shells" -> listShells(ctx)
                "execute_shell_command" -> executeShellCommand(arguments, ctx)
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
            // Inject tool call chains that are children of messages in the ancestor path.
            // Tool messages are children of model messages; reorder so the API sees
            // assistant(tool_use) → user(tool_result) → assistant(text) instead of
            // invalid consecutive assistant messages.
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
                                if (child !in expanded) {
                                    expanded.add(child)
                                    pending.add(child)
                                }
                            }
                            safety++
                        }
                    }
                    // Add entity without tool data so it serializes as plain text,
                    // avoiding a second assistant message with tool_use.
                    expanded.add(entity.copy(toolCallJson = null))
                }
            }
            val currentPath = expanded.map {
                val segs = it.toolCallJson?.let { json -> try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null } }
                val toolCall = segs?.lastOrNull { s -> s.type == "tool" }?.let { s ->
                    ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", s.toolResult ?: "")
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
            val allTools = memoryTools + webSearchTool + ragTool + shellTool
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
                userPostpend = config.userPostpend
            )

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()

            var lastEmitMs = 0L

            val apiPath = applyUserTemplate(currentPath, config.userPrepend, config.userPostpend)
            provider.generateResponse(apiPath, providerConfig).collect { event ->
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
                        val result = executeTool(event.name, event.arguments, ctx)
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
                            val result = executeTool(call.name, call.arguments, ctx)
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

                val apiToolPath = applyUserTemplate(toolPath, config.userPrepend, config.userPostpend)
                provider.generateResponse(apiToolPath, providerConfig).collect { event ->
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
                            val result = executeTool(event.name, event.arguments, ctx)
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
                                val result = executeTool(call.name, call.arguments, ctx)
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
                if (generationId == myGenerationId) {
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
