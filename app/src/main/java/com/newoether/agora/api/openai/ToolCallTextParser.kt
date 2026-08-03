package com.newoether.agora.api.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Recovers tool calls that an OpenAI-compatible server emitted as **content text** rather than as
 * structured `delta.tool_calls` (issue #33, path B). llama.cpp and other self-hosted servers
 * frequently finish with `finish_reason == "stop"` while placing the tool call inside the message
 * `content` — the model's chat template renders it as a tagged ``{json}`` block. The structured
 * path in [BaseOpenAiProvider] only fires on `finish_reason == "tool_calls"`, so without this
 * fallback such servers never enter the tool-call phase (the JSON just shows up as answer text).
 * This parser brings them to parity with Ollama, which reads the structured field.
 *
 * Recognized forms:
 *  - One or more tagged blocks anywhere in the content (the standard form emitted by
 *    Hermes / Qwen / llama.cpp tool-aware templates). The inner JSON may use
 *    `{"name":...,"arguments":...}` or `{"name":...,"parameters":...}`, or nest them under
 *    `"function"`.
 *  - As a last resort, the *entire* trimmed content being a single JSON object or array of the
 *    same tool-call shape (some templates emit the JSON with no surrounding tags). Only attempted
 *    when the whole content is JSON, so prose answers are never misread as tool calls.
 *
 * The inner `arguments`/`parameters` value is preserved verbatim as a JSON string for the
 * downstream tool executor, matching how structured tool calls carry arguments.
 */
internal object ToolCallTextParser {

    data class ParsedCall(val name: String, val arguments: String)

    // Split so the bare tag literals never appear as a contiguous substring in source tooling.
    private const val OPEN_TAG = "<tool_" + "call>"
    private const val CLOSE_TAG = "</tool_" + "call>"

    /** Extract tool calls from [content]; empty if none are recognized. */
    fun parse(content: String): List<ParsedCall> {
        val results = mutableListOf<ParsedCall>()
        var idx = 0
        while (true) {
            val start = content.indexOf(OPEN_TAG, idx)
            if (start < 0) break
            val innerStart = start + OPEN_TAG.length
            val end = content.indexOf(CLOSE_TAG, innerStart)
            if (end < 0) break
            val inner = content.substring(innerStart, end).trim()
            parseCallJson(inner)?.let { results.add(it) }
            idx = end + CLOSE_TAG.length
        }
        if (results.isNotEmpty()) return results

        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        // Only treat the whole content as a tool call when it is pure JSON — never parse tool
        // calls out of prose that merely happens to contain a JSON fragment.
        parseCallJson(trimmed)?.let { return listOf(it) }
        if (trimmed.startsWith("[")) {
            val array = try { Json.parseToJsonElement(trimmed).jsonArray } catch (_: Exception) { return emptyList() }
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                parseCallJson(obj.toString())?.let { results.add(it) }
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun parseCallJson(jsonStr: String): ParsedCall? {
        val obj = try { Json.parseToJsonElement(jsonStr).jsonObject } catch (_: Exception) { return null }
        val name = stringField(obj, "name")
            ?: (obj["function"] as? JsonObject)?.let { stringField(it, "name") }
            ?: return null
        if (name.isBlank()) return null
        val args = obj["arguments"] ?: obj["parameters"]
        val arguments = args?.let { normalizeArguments(it) } ?: "{}"
        return ParsedCall(name, arguments)
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    /** The tool executor expects a JSON string for arguments; keep objects/arrays as-is and
     *  stringify primitives so the downstream parser still sees valid JSON. */
    private fun normalizeArguments(element: JsonElement): String =
        when (element) {
            is JsonObject, is JsonArray -> element.toString()
            is JsonPrimitive -> if (element.isString) element.content else element.toString()
            else -> element.toString()
        }

}

/**
 * Streaming companion to [ToolCallTextParser].
 *
 * Compatible servers may write tool calls in ordinary content. This parser withholds only a
 * possible tag/pure-tool prefix, emits surrounding prose normally, and exposes accumulated
 * name/arguments snapshots as soon as the model has written enough to identify a call.
 */
internal class StreamingTextToolCallParser {
    data class Snapshot(
        val streamKey: String,
        val name: String,
        val arguments: String,
    )

    data class CompletedCall(
        val streamKey: String,
        val name: String,
        val arguments: String,
    )

    private enum class Mode { NORMAL, TAGGED_CALL, BARE_JSON_CALL }

    private val buffer = StringBuilder()
    private var mode = Mode.NORMAL
    private var canProbeBareJson = true
    private var streamKey: String? = null
    private var announcedName: String? = null
    private var announcedArguments = ""
    private var lastSnapshot: Snapshot? = null

    suspend fun feed(
        content: String,
        onText: suspend (String) -> Unit,
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        if (content.isEmpty()) return
        buffer.append(content)
        drain(onText, onUpdate, onComplete, onMalformed)
    }

    suspend fun flush(
        onText: suspend (String) -> Unit,
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        when (mode) {
            Mode.NORMAL -> emitBufferedText(onText)
            Mode.TAGGED_CALL -> {
                announcePartial(buffer.toString(), onUpdate)
                onMalformed("Provider ended before the tagged tool call was complete")
            }
            Mode.BARE_JSON_CALL -> completeBareJson(onUpdate, onComplete, onMalformed)
        }
        resetAfterCall()
        buffer.clear()
        canProbeBareJson = false
    }

    private suspend fun drain(
        onText: suspend (String) -> Unit,
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        while (true) {
            when (mode) {
                Mode.NORMAL -> {
                    if (canProbeBareJson) {
                        val candidate = buffer.toString().trimStart()
                        if (candidate.isEmpty()) return
                        if (BARE_JSON_PREFIXES.any(candidate::startsWith)) {
                            beginCall(Mode.BARE_JSON_CALL, onUpdate)
                            announcePartial(buffer.toString(), onUpdate)
                            return
                        }
                        if (BARE_JSON_PREFIXES.any { prefix -> prefix.startsWith(candidate) }) {
                            return
                        }
                        canProbeBareJson = false
                    }

                    val openAt = buffer.indexOf(OPEN_TAG)
                    if (openAt >= 0) {
                        if (openAt > 0) onText(buffer.substring(0, openAt))
                        buffer.delete(0, openAt + OPEN_TAG.length)
                        beginCall(Mode.TAGGED_CALL, onUpdate)
                        continue
                    }

                    val retained = longestTagPrefixSuffix(buffer)
                    val safeLength = buffer.length - retained
                    if (safeLength > 0) {
                        onText(buffer.substring(0, safeLength))
                        buffer.delete(0, safeLength)
                    }
                    return
                }

                Mode.TAGGED_CALL -> {
                    val closeAt = buffer.indexOf(CLOSE_TAG)
                    if (closeAt < 0) {
                        announcePartial(buffer.toString(), onUpdate)
                        return
                    }

                    val body = buffer.substring(0, closeAt)
                    val parsed = ToolCallTextParser.parse(
                        OPEN_TAG + body + CLOSE_TAG
                    ).singleOrNull()
                    if (parsed != null) {
                        val key = checkNotNull(streamKey)
                        emitSnapshot(key, parsed.name, parsed.arguments, onUpdate)
                        onComplete(CompletedCall(key, parsed.name, parsed.arguments))
                    } else {
                        announcePartial(body, onUpdate)
                        onMalformed("Tagged tool call was not valid complete JSON")
                    }
                    buffer.delete(0, closeAt + CLOSE_TAG.length)
                    resetAfterCall()
                    mode = Mode.NORMAL
                    canProbeBareJson = false
                }

                Mode.BARE_JSON_CALL -> {
                    announcePartial(buffer.toString(), onUpdate)
                    return
                }
            }
        }
    }

    private suspend fun completeBareJson(
        onUpdate: suspend (Snapshot) -> Unit,
        onComplete: suspend (CompletedCall) -> Unit,
        onMalformed: suspend (String) -> Unit,
    ) {
        val parsed = ToolCallTextParser.parse(buffer.toString())
        if (parsed.isNotEmpty()) {
            parsed.forEachIndexed { index, call ->
                val key = if (index == 0) {
                    streamKey ?: newStreamKey()
                } else {
                    newStreamKey()
                }
                emitSnapshot(key, call.name, call.arguments, onUpdate)
                onComplete(CompletedCall(key, call.name, call.arguments))
            }
            return
        }

        announcePartial(buffer.toString(), onUpdate)
        onMalformed("Provider ended before the JSON tool call was complete")
    }

    private suspend fun announcePartial(
        body: String,
        onUpdate: suspend (Snapshot) -> Unit,
    ) {
        val name = extractName(body) ?: announcedName ?: return
        val argumentsStart = ARGUMENTS_KEY.find(body)?.range?.last?.plus(1) ?: return
        val arguments = partialArguments(body.substring(argumentsStart))
        announcedName = name
        announcedArguments = arguments
        emitSnapshot(checkNotNull(streamKey), name, arguments, onUpdate)
    }

    private suspend fun emitSnapshot(
        key: String,
        name: String,
        arguments: String,
        onUpdate: suspend (Snapshot) -> Unit,
    ) {
        val snapshot = Snapshot(key, name, arguments)
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            onUpdate(snapshot)
        }
    }

    private suspend fun beginCall(
        nextMode: Mode,
        onUpdate: suspend (Snapshot) -> Unit,
    ) {
        mode = nextMode
        streamKey = newStreamKey()
        announcedName = null
        announcedArguments = ""
        lastSnapshot = null
        emitSnapshot(checkNotNull(streamKey), "", "", onUpdate)
    }

    private fun resetAfterCall() {
        streamKey = null
        announcedName = null
        announcedArguments = ""
        lastSnapshot = null
    }

    private suspend fun emitBufferedText(onText: suspend (String) -> Unit) {
        if (buffer.isNotEmpty()) onText(buffer.toString())
    }

    private fun extractName(body: String): String? {
        val encoded = NAME_FIELD.find(body)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            Json.decodeFromString<String>("\"$encoded\"")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun partialArguments(source: String): String {
        val trimmed = source.trimStart()
        if (!trimmed.startsWith('"')) return trimmed
        return trimmed
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun longestTagPrefixSuffix(source: StringBuilder): Int {
        val maxLength = minOf(source.length, OPEN_TAG.length - 1)
        for (length in maxLength downTo 1) {
            if (source.substring(source.length - length) == OPEN_TAG.take(length)) return length
        }
        return 0
    }

    private fun newStreamKey(): String = "text_tool_${UUID.randomUUID()}"

    private companion object {
        const val OPEN_TAG = "<tool_" + "call>"
        const val CLOSE_TAG = "</tool_" + "call>"
        val BARE_JSON_PREFIXES = listOf(
            "{\"name\"",
            "{\"function\"",
            "[{\"name\"",
            "[{\"function\"",
        )
        val NAME_FIELD = Regex(""""name"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val ARGUMENTS_KEY = Regex(""""(?:arguments|parameters)"\s*:\s*""")
    }
}
