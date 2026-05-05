package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

@Serializable
internal data class ApiGenerateContentRequest(
    val contents: List<ApiRequestContent>,
    @SerialName("system_instruction") val systemInstruction: ApiRequestContent? = null,
    val tools: List<ApiTool>? = null,
    @SerialName("toolConfig") val toolConfig: ApiToolConfig? = null,
    @SerialName("generationConfig") val generationConfig: ApiGenerationConfig? = null
)

@Serializable
internal data class ApiToolConfig(
    @SerialName("includeServerSideToolInvocations") val includeServerSideToolInvocations: Boolean = false
)

@Serializable
internal data class ApiGenerationConfig(
    @SerialName("thinkingConfig") val thinkingConfig: ApiThinkingConfig? = null
)

@Serializable
internal data class ApiThinkingConfig(
    @SerialName("includeThoughts") val includeThoughts: Boolean,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    @SerialName("thinkingBudget") val thinkingBudget: Int? = null
)

@Serializable
internal data class ApiTool(
    @SerialName("code_execution") val codeExecution: JsonObject? = null,
    @SerialName("google_search") val googleSearch: JsonObject? = null,
    @SerialName("function_declarations") val functionDeclarations: List<GeminiFunctionDeclaration>? = null
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null
)

@Serializable
internal data class ApiRequestContent(val role: String? = null, val parts: List<ApiRequestPart>)

@Serializable
internal data class ApiInlineData(val mimeType: String, val data: String)

@Serializable
internal data class ApiRequestPart(
    val text: String? = null,
    val inlineData: ApiInlineData? = null,
    val thought: String? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
internal data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject
)

@Serializable
internal data class ApiResponseContent(val role: String? = null, val parts: List<ApiResponsePart>)

@Serializable
internal data class ApiResponsePart(
    val text: String? = null,
    val thought: JsonElement? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("executable_code") val executableCode: ApiExecutableCode? = null,
    @SerialName("code_execution_result") val codeExecutionResult: ApiCodeExecutionResult? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null
)

@Serializable
internal data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject? = null,
    @SerialName("thought_signature") val thoughtSignature: String? = null
)

@Serializable
internal data class ApiExecutableCode(val language: String, val code: String)

@Serializable
internal data class ApiCodeExecutionResult(val outcome: String, val output: String)

@Serializable
internal data class ApiStreamResponse(
    val candidates: List<ApiCandidate>? = null,
    @SerialName("usageMetadata") val usageMetadata: ApiUsageMetadata? = null
)

@Serializable
internal data class ApiCandidate(val content: ApiResponseContent? = null)

@Serializable
internal data class ApiUsageMetadata(
    val totalTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null
)

@Serializable
internal data class ApiErrorResponse(val error: ApiError)

@Serializable
internal data class ApiError(val code: Int? = null, val message: String? = null, val status: String? = null)

@Serializable
internal data class ModelListResponse(val models: List<ModelInfo>)

@Serializable
internal data class ModelInfo(val name: String, val displayName: String, val supportedGenerationMethods: List<String>)

class GeminiProvider : LlmProvider {
    override val name: String = "Google"
    override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl
        val cleanModelName = config.modelId.removePrefix("models/")
        
        // Context windowing
        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }


        val apiContents = limitedPath.flatMap { msg ->
            val entries = mutableListOf<ApiRequestContent>()

            // tool_ messages: model turn with functionCall(s)
            // Note: Gemini 3 requires thought to be boolean in requests, so we omit thought strings
            // and only include thoughtSignature on the functionCall part
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val sig = msg.segments?.lastOrNull { !it.signature.isNullOrBlank() }?.signature
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val args = try {
                            json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject
                        } catch (_: Exception) { JsonObject(emptyMap()) }
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                name = seg.toolName ?: "", args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = sig
                        )
                    }
                    entries.add(ApiRequestContent(role = "model", parts = parts))
                } else if (msg.toolCall != null) {
                    val args = try {
                        json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject
                    } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(ApiRequestContent(
                        role = "model",
                        parts = listOf(ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                name = msg.toolCall!!.toolName, args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = sig
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the function response(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val response = buildGeminiFunctionResponse(seg.toolResult ?: "{}")
                        ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            name = seg.toolName ?: "",
                            response = response
                        ))
                    }
                    entries.add(ApiRequestContent(role = "user", parts = parts))
                } else if (msg.toolCall != null) {
                    val response = buildGeminiFunctionResponse(msg.toolCall!!.result)
                    entries.add(ApiRequestContent(
                        role = "user",
                        parts = listOf(ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            name = msg.toolCall!!.toolName,
                            response = response
                        )))
                    ))
                }
                return@flatMap entries
            }

            // Normal message: text + images only
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
                    Log.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                }
            }
            if (parts.isEmpty()) parts.add(ApiRequestPart(text = ""))
            entries.add(ApiRequestContent(
                role = if (msg.participant == Participant.USER) "user" else "model",
                parts = parts
            ))

            entries
        }

        val sdf = SimpleDateFormat("MMMM d, yyyy, HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }
        val timeInfo = "Current Time: ${sdf.format(Date())} (UTC+8)\n\n"
        val systemInstruction = ApiRequestContent(parts = listOf(ApiRequestPart(text = timeInfo + (config.systemPrompt ?: ""))))

        val tools = mutableListOf<ApiTool>()
        if (config.codeExecutionEnabled) tools.add(ApiTool(codeExecution = JsonObject(emptyMap())))
        if (config.googleSearchEnabled) tools.add(ApiTool(googleSearch = JsonObject(emptyMap())))

        // Add memory function declarations as a separate tool entry
        val functionDeclarations = config.tools?.map { td ->
            GeminiFunctionDeclaration(
                name = td.function.name,
                description = td.function.description,
                parameters = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(td.function.parameters.type),
                        "properties" to JsonObject(
                            td.function.parameters.properties.mapValues { (_, prop) ->
                                val propMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                                    "type" to JsonPrimitive(prop.type),
                                    "description" to JsonPrimitive(prop.description)
                                )
                                if (prop.items != null) {
                                    propMap["items"] = JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive(prop.items.type),
                                            "description" to JsonPrimitive(prop.items.description)
                                        )
                                    )
                                }
                                JsonObject(propMap)
                            }
                        ),
                        "required" to kotlinx.serialization.json.JsonArray(
                            td.function.parameters.required.map { JsonPrimitive(it) }
                        )
                    )
                )
            )
        }
        if (!functionDeclarations.isNullOrEmpty()) {
            tools.add(ApiTool(functionDeclarations = functionDeclarations))
        }

        val thinkingConfig = when {
            cleanModelName.contains("gemini-3", ignoreCase = true) -> 
                ApiThinkingConfig(includeThoughts = config.thinkingEnabled, thinkingLevel = "HIGH")
            cleanModelName.contains("gemini-2.5", ignoreCase = true) -> 
                ApiThinkingConfig(includeThoughts = config.thinkingEnabled, thinkingBudget = -1)
            cleanModelName.contains("thinking-exp", ignoreCase = true) -> 
                ApiThinkingConfig(includeThoughts = config.thinkingEnabled)
            else -> null
        }

        val hasBuiltInTools = tools.any { it.codeExecution != null || it.googleSearch != null }
        val hasFunctionDeclarations = tools.any { it.functionDeclarations != null }
        val toolConfig = if (hasBuiltInTools && hasFunctionDeclarations) {
            ApiToolConfig(includeServerSideToolInvocations = true)
        } else null

        val requestBody = ApiGenerateContentRequest(
            contents = apiContents,
            systemInstruction = systemInstruction,
            tools = if (tools.isNotEmpty()) tools else null,
            toolConfig = toolConfig,
            generationConfig = if (thinkingConfig != null) ApiGenerationConfig(thinkingConfig = thinkingConfig) else null
        )

        var connection: HttpURLConnection? = null
        try {
            // Determine if baseUrl already includes versioning
            val finalUrlString = if (baseUrl.contains("/v1") || baseUrl.contains("/v1beta")) {
                "$baseUrl/models/$cleanModelName:streamGenerateContent?alt=sse&key=${config.apiKey}"
            } else {
                "$baseUrl/v1beta/models/$cleanModelName:streamGenerateContent?alt=sse&key=${config.apiKey}"
            }
            
            val url = URL(finalUrlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val requestJson = json.encodeToString(ApiGenerateContentRequest.serializer(), requestBody)
            Log.d("AgoraAPI", "[Gemini] REQ → $finalUrlString | model=$cleanModelName | msgs=${apiContents.size} | thinking=${config.thinkingEnabled} | tools=${tools.size}")
            Log.d("AgoraAPI", "[Gemini] BODY: ${requestJson.take(4000)}")
            connection.outputStream.bufferedWriter().use { it.write(requestJson) }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                connection.readTimeout = 200
                val reader = connection.inputStream.bufferedReader()
                var line: String? = null
                var currentThoughtSignature: String? = null
                var inThoughtBlock = false
                while (currentCoroutineContext().isActive) {
                    try {
                        line = reader.readLine()
                        if (line == null) break
                    } catch (e: java.net.SocketTimeoutException) {
                        if (!currentCoroutineContext().isActive) break
                        continue
                    }
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        if (jsonStr != "[DONE]") {
                            try {
                                val response = json.decodeFromString<ApiStreamResponse>(jsonStr)

                                inThoughtBlock = false
                                response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                                    var isPartOfThought = false

                                    part.thought?.let { thoughtElement ->
                                        if (thoughtElement is JsonPrimitive) {
                                            if (thoughtElement.isString) {
                                                val content = thoughtElement.content
                                                val title = Regex("\\*\\*(.*?)\\*\\*").find(content)?.groupValues?.get(1)
                                                          ?: Regex("(?m)^#+\\s*(.*)$").find(content)?.groupValues?.get(1)
                                                emit(StreamEvent.ThoughtChunk(content, title, currentThoughtSignature))
                                                isPartOfThought = true
                                                inThoughtBlock = true
                                            } else if (thoughtElement.content == "true") {
                                                isPartOfThought = true
                                                inThoughtBlock = true
                                            }
                                        }
                                    }

                                    part.reasoningContent?.let {
                                        val title = Regex("\\*\\*(.*?)\\*\\*").find(it)?.groupValues?.get(1)
                                                  ?: Regex("(?m)^#+\\s*(.*)$").find(it)?.groupValues?.get(1)
                                        emit(StreamEvent.ThoughtChunk(it, title, currentThoughtSignature))
                                        isPartOfThought = true
                                        inThoughtBlock = true
                                    }

                                    part.thoughtSignature?.let { sig ->
                                        currentThoughtSignature = sig
                                        isPartOfThought = true
                                        inThoughtBlock = true
                                    }

                                    part.text?.let {
                                        // isPartOfThought: explicit marker on this part (thought, thoughtSignature, reasoningContent)
                                        // inThoughtBlock: carry-over from a preceding boolean {thought: true} part in an earlier event
                                        val inThought = isPartOfThought || inThoughtBlock
                                        if (inThought) {
                                            val title = Regex("\\*\\*(.*?)\\*\\*").find(it)?.groupValues?.get(1)
                                                      ?: Regex("(?m)^#+\\s*(.*)$").find(it)?.groupValues?.get(1)
                                            emit(StreamEvent.ThoughtChunk(it, title, currentThoughtSignature))
                                            inThoughtBlock = false
                                        } else {
                                            emit(StreamEvent.TextChunk(it))
                                        }
                                    }

                                    part.executableCode?.let {
                                        emit(StreamEvent.TextChunk("\n```${it.language}\n${it.code}\n```\n"))
                                    }

                                    part.codeExecutionResult?.let {
                                        emit(StreamEvent.TextChunk("\n> Output: ${it.output}\n"))
                                    }

                                    part.functionCall?.let { fc ->
                                        val argsJson = fc.args?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: "{}"
                                        val sig = fc.thoughtSignature ?: currentThoughtSignature
                                        emit(StreamEvent.ToolCallRequest("gemini_call", fc.name, argsJson, sig))
                                        inThoughtBlock = false
                                    }
                                }
                                response.usageMetadata?.let { metadata ->
                                    emit(StreamEvent.UsageUpdate(metadata.totalTokenCount ?: 0, metadata.thoughtsTokenCount ?: 0))
                                }
                            } catch (e: Exception) {
                                Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                            }
                        }
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    throw kotlinx.coroutines.CancellationException("Stream cancelled")
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error (Code: $responseCode)"
                Log.e("AgoraAPI", "[Gemini] ERR $responseCode: $errorRaw")
                val errorMessage = try {
                    val errorJson = json.decodeFromString<ApiErrorResponse>(errorRaw)
                    val code = errorJson.error.code ?: responseCode
                    val status = errorJson.error.status ?: "UNKNOWN"
                    val message = errorJson.error.message ?: "No error message provided"
                    "Error $code ($status): $message"
                } catch (e: Exception) {
                    "Error (Code $responseCode): $errorRaw"
                }
                emit(StreamEvent.Error(errorMessage))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error("Request timed out. The server took too long to respond."))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error("Connection refused. Please check your internet connection or if the service is available."))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error("Network error: Unable to reach the server. Please check your internet connection."))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error("Error: ${e.localizedMessage ?: "An unexpected error occurred."}"))
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
            val finalUrlString = if (effectiveBaseUrl.contains("/v1") || effectiveBaseUrl.contains("/v1beta")) {
                "$effectiveBaseUrl/models?key=$apiKey"
            } else {
                "$effectiveBaseUrl/v1beta/models?key=$apiKey"
            }
            
            val url = URL(finalUrlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<ModelListResponse>(responseText).models
                .filter { it.supportedGenerationMethods.contains("generateContent") }
                .map { it.name.removePrefix("models/") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildGeminiFunctionResponse(result: String): JsonObject {
        return try {
            val parsed = json.parseToJsonElement(result)
            when (parsed) {
                is JsonObject -> parsed
                else -> JsonObject(mapOf("result" to parsed))
            }
        } catch (_: Exception) {
            JsonObject(mapOf("result" to JsonPrimitive(result)))
        }
    }
}
