package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.DefaultSkills
import com.newoether.agora.data.SkillManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class SkillToolProvider(
    private val skillManager: SkillManager
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.skillsEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "list_skills",
                    description = "List all available skills with their names and descriptions. Skills are curated instructions for how to approach specific tasks.",
                    parameters = ToolParameters(properties = emptyMap())
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "read_skill",
                    description = "Read the full content of one or more skills.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "The skill name to read."),
                            "names" to ToolProperty(
                                "array",
                                "Multiple skill names to read in one call.",
                                items = ToolProperty("string", "A skill name.")
                            )
                        ),
                        required = emptyList()
                    )
                )
            )
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args =
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        fun arg(key: String): String =
            (args[key] as? JsonPrimitive)?.content ?: ""

        return when (name) {
            "list_skills" -> {
                val skills = skillManager.listFiles().filter { isSkillAvailable(it.name, ctx) }
                buildJsonObject {
                    put("type", "list_skills")
                    putJsonArray("skills") {
                        skills.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("name", s.name)
                                    put("description", s.description)
                                }
                            )
                        }
                    }
                }.toString()
            }

            "read_skill" -> {
                val singleName = arg("name")
                val namesArray = args["names"] as? JsonArray
                if (namesArray != null && namesArray.isNotEmpty()) {
                    val names = namesArray.map {
                        (it as? JsonPrimitive)?.content ?: ""
                    }.filter { it.isNotEmpty() }
                    names.joinToString("\n\n") { n -> "--- $n ---\n${readSkillGated(n, ctx)}" }
                } else if (singleName.isNotEmpty()) {
                    readSkillGated(singleName, ctx)
                } else {
                    "Error: No skill name provided. Use 'name' for a single skill or 'names' for multiple skills."
                }
            }

            else -> "Unknown tool: $name"
        }
    }

    /** Some built-in skills only make sense while their backing feature is enabled — e.g. the
     *  html-widgets skill describes a rendering path (the `html-render` fence) that the chat UI
     *  won't act on when HTML widgets are disabled, so hide it rather than have the model follow
     *  instructions that render as an inert code block. */
    private fun isSkillAvailable(skillFileName: String, ctx: GenerationContext): Boolean =
        when (skillFileName.removeSuffix(".md")) {
            DefaultSkills.HTML_WIDGETS_SKILL_NAME -> ctx.htmlWidgetsEnabled
            DefaultSkills.MERMAID_WIDGETS_SKILL_NAME -> ctx.mermaidWidgetsEnabled
            else -> true
        }

    private fun readSkillGated(name: String, ctx: GenerationContext): String =
        if (!isSkillAvailable(name, ctx)) "Error: No skill named '$name' is currently available."
        else skillManager.readFile(name)

    override fun handles(name: String): Boolean = name in setOf(
        "list_skills",
        "read_skill"
    )
}
