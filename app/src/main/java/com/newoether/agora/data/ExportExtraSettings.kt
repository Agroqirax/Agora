package com.newoether.agora.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.float
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Extra settings serialized as JsonObject to avoid D8 field-count crash on large @Serializable classes.
 * All serialization is done at runtime (no compile-time serializer codegen).
 */
object ExportExtraSettings {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun toJsonObject(sm: SettingsManager): JsonObject = buildJsonObject {
        val prompts = sm.systemPrompts.first()
        if (prompts.isNotEmpty()) {
            putJsonArray("systemPrompts") {
                prompts.forEach { p -> add(json.encodeToJsonElement(SystemPromptEntry.serializer(), p)) }
            }
        }

        val imgTransEnabled = sm.imageTranscriptionEnabledModels.first()
        put("imageTranscriptionEnabledModels", JsonPrimitive(imgTransEnabled.joinToString(",")))
        sm.imageTranscriptionModel.first()?.let { put("imageTranscriptionModel", JsonPrimitive(it)) }
        put("imageTranscriptionBatchSize", JsonPrimitive(sm.imageTranscriptionBatchSize.first()))

        put("webSearchNumResults", JsonPrimitive(sm.webSearchNumResults.first()))
        put("searchContextWindow", JsonPrimitive(sm.searchContextWindow.first()))
        put("searchMatchLimit", JsonPrimitive(sm.searchMatchLimit.first()))

        sm.defaultTemperature.first()?.let { put("defaultTemperature", JsonPrimitive(it)) }
        sm.defaultMaxTokens.first()?.let { put("defaultMaxTokens", JsonPrimitive(it)) }
        sm.defaultTopP.first()?.let { put("defaultTopP", JsonPrimitive(it)) }
        sm.defaultFrequencyPenalty.first()?.let { put("defaultFrequencyPenalty", JsonPrimitive(it)) }
        sm.defaultPresencePenalty.first()?.let { put("defaultPresencePenalty", JsonPrimitive(it)) }

        val conv = sm.conversationSettings.first()
        if (conv.isNotEmpty()) {
            putJsonObject("conversationSettings") {
                conv.forEach { (convId, cs) ->
                    putJsonObject(convId) {
                        cs.contextWindow?.let { put("contextWindow", JsonPrimitive(it)) }
                        cs.temperature?.let { put("temperature", JsonPrimitive(it.toDouble())) }
                        cs.maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
                        cs.topP?.let { put("topP", JsonPrimitive(it.toDouble())) }
                        cs.frequencyPenalty?.let { put("frequencyPenalty", JsonPrimitive(it.toDouble())) }
                        cs.presencePenalty?.let { put("presencePenalty", JsonPrimitive(it.toDouble())) }
                        cs.codeExecutionEnabled?.let { put("codeExecutionEnabled", JsonPrimitive(it)) }
                        cs.googleSearchEnabled?.let { put("googleSearchEnabled", JsonPrimitive(it)) }
                        cs.thinkingEnabled?.let { put("thinkingEnabled", JsonPrimitive(it)) }
                        cs.thinkingLevel?.let { put("thinkingLevel", JsonPrimitive(it)) }
                        cs.webSearchEnabled?.let { put("webSearchEnabled", JsonPrimitive(it)) }
                        cs.shellEnabled?.let { put("shellEnabled", JsonPrimitive(it)) }
                    }
                }
            }
        }

        put("showDocumentationFab", JsonPrimitive(sm.showDocumentationFab.first()))
        put("themeMode", JsonPrimitive(sm.themeMode.first()))
        put("colorScheme", JsonPrimitive(sm.colorScheme.first()))
        put("dynamicColor", JsonPrimitive(sm.dynamicColor.first()))
        put("schemeStyle", JsonPrimitive(sm.schemeStyle.first()))
        put("autoUpdateCheck", JsonPrimitive(sm.autoUpdateCheck.first()))
    }

    suspend fun restoreFromJsonObject(obj: JsonObject, sm: SettingsManager) {
        obj["systemPrompts"]?.jsonArray?.let { arr ->
            val prompts = arr.mapNotNull { el ->
                try { json.decodeFromJsonElement(SystemPromptEntry.serializer(), el) } catch (_: Exception) { null }
            }
            if (prompts.isNotEmpty()) sm.saveSystemPrompts(prompts)
        }
        obj["imageTranscriptionEnabledModels"]?.jsonPrimitive?.contentOrNull?.let {
            val set = it.split(",").filter { s -> s.isNotBlank() }.toSet()
            sm.saveImageTranscriptionEnabledModels(set)
        }
        obj["imageTranscriptionModel"]?.jsonPrimitive?.contentOrNull?.let { sm.saveImageTranscriptionModel(it) }
        obj["imageTranscriptionBatchSize"]?.jsonPrimitive?.int?.let { sm.saveImageTranscriptionBatchSize(it) }
        obj["webSearchNumResults"]?.jsonPrimitive?.int?.let { sm.saveWebSearchNumResults(it) }
        obj["searchContextWindow"]?.jsonPrimitive?.int?.let { sm.saveSearchContextWindow(it) }
        obj["searchMatchLimit"]?.jsonPrimitive?.int?.let { sm.saveSearchMatchLimit(it) }
        obj["defaultTemperature"]?.jsonPrimitive?.float?.let { sm.saveDefaultTemperature(it) }
        obj["defaultMaxTokens"]?.jsonPrimitive?.int?.let { sm.saveDefaultMaxTokens(it) }
        obj["defaultTopP"]?.jsonPrimitive?.float?.let { sm.saveDefaultTopP(it) }
        obj["defaultFrequencyPenalty"]?.jsonPrimitive?.float?.let { sm.saveDefaultFrequencyPenalty(it) }
        obj["defaultPresencePenalty"]?.jsonPrimitive?.float?.let { sm.saveDefaultPresencePenalty(it) }
        obj["conversationSettings"]?.jsonObject?.forEach { (convId, settingsJson) ->
            val s = settingsJson.jsonObject
            val cs = ConversationSettings(
                contextWindow = s["contextWindow"]?.jsonPrimitive?.int,
                temperature = s["temperature"]?.jsonPrimitive?.float,
                maxTokens = s["maxTokens"]?.jsonPrimitive?.int,
                topP = s["topP"]?.jsonPrimitive?.float,
                frequencyPenalty = s["frequencyPenalty"]?.jsonPrimitive?.float,
                presencePenalty = s["presencePenalty"]?.jsonPrimitive?.float,
                codeExecutionEnabled = s["codeExecutionEnabled"]?.jsonPrimitive?.boolean,
                googleSearchEnabled = s["googleSearchEnabled"]?.jsonPrimitive?.boolean,
                thinkingEnabled = s["thinkingEnabled"]?.jsonPrimitive?.boolean,
                thinkingLevel = s["thinkingLevel"]?.jsonPrimitive?.contentOrNull,
                webSearchEnabled = s["webSearchEnabled"]?.jsonPrimitive?.boolean,
                shellEnabled = s["shellEnabled"]?.jsonPrimitive?.boolean
            )
            if (!cs.isAllNull()) sm.saveConversationSettings(convId, cs)
        }
        obj["showDocumentationFab"]?.jsonPrimitive?.boolean?.let { sm.saveShowDocumentationFab(it) }
        obj["themeMode"]?.jsonPrimitive?.contentOrNull?.let { sm.saveThemeMode(it) }
        obj["colorScheme"]?.jsonPrimitive?.contentOrNull?.let { sm.saveColorScheme(it) }
        obj["dynamicColor"]?.jsonPrimitive?.boolean?.let { sm.saveDynamicColor(it) }
        obj["schemeStyle"]?.jsonPrimitive?.contentOrNull?.let { sm.saveSchemeStyle(it) }
        obj["autoUpdateCheck"]?.jsonPrimitive?.boolean?.let { sm.saveAutoUpdateCheck(it) }
    }
}
