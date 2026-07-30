package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets the model pause generation and ask the human user a question — free text, or a
 * short list of tappable multiple-choice [options] with an always-available free-text
 * fallback (see AskUserDialog) — then resumes with the answer as the tool result.
 *
 * No confirm/permission gate: asking a question has no side effect on the device, so —
 * like [CalculatorToolProvider] and [DeviceInfoToolProvider] — this only has a single
 * enable toggle. The actual pause/resume plumbing is [ask], wired by GenerationManager/
 * ChatViewModel to a [com.newoether.agora.viewmodel.AskUserController].
 */
class AskUserToolProvider : ToolProvider {

    /** Set by GenerationManager; returns null if the user dismissed without answering. */
    var ask: (suspend (question: String, options: List<String>) -> String?)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.askUserEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = ASK_USER,
                description = "Pause and ask the human user a question when you need information " +
                    "only they can provide, need them to make a choice, or want their confirmation " +
                    "before proceeding down an ambiguous path. Use sparingly — don't ask about things " +
                    "you can figure out yourself. If `options` is given, the user is shown those as " +
                    "quick-tap choices but can still type a free-text answer instead, so don't assume " +
                    "the answer will exactly match one of the options.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "question" to ToolProperty(
                            type = "string",
                            description = "The question to show the user. Keep it short and specific."
                        ),
                        "options" to ToolProperty(
                            type = "array",
                            description = "Optional list of 2-5 short suggested answers shown as " +
                                "tappable buttons. Omit for a free-text-only question.",
                            items = ToolProperty(type = "string", description = "A single suggested answer.")
                        )
                    ),
                    required = listOf("question")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == ASK_USER

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.askUserEnabled) return err(null, "disabled", "The ask_user tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        val question = args["question"]?.let { (it as? JsonPrimitive)?.content }
            ?: return err(null, "invalid_argument", "question is required.")
        val options = (args["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        val answer = try {
            ask?.invoke(question, options)
        } catch (e: Exception) {
            return err(question, "ask_user_error", e.message)
        }

        return if (answer != null) {
            buildJsonObject {
                put("type", ASK_USER)
                put("question", question)
                put("answer", answer)
            }.toString()
        } else {
            err(question, "user_cancelled", "The user dismissed the question without answering.")
        }
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun err(question: String?, code: String, message: String?): String = buildJsonObject {
        put("type", ASK_USER)
        if (question != null) put("question", question)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val ASK_USER = "ask_user"
    }
}
