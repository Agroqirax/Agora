package com.newoether.agora.util

import android.content.Context
import com.newoether.agora.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object SearchResultFormatter {

    fun isRawSearchResult(text: String): Boolean = try {
        val type = Json.parseToJsonElement(text).jsonObject["type"]?.let { (it as? JsonPrimitive)?.content }
        type == "web_search" || type == "search_conversations"
    } catch (_: Exception) { false }

    fun format(text: String, context: Context): String {
        if (!isRawSearchResult(text)) return text
        return try {
            val json = Json.parseToJsonElement(text).jsonObject
            val error = json["error"]?.let { (it as? JsonPrimitive)?.content }
            if (error != null) return formatError(json, error, context)
            when (json["type"]?.let { (it as? JsonPrimitive)?.content }) {
                "web_search" -> formatWebSearch(json, context)
                "search_conversations" -> formatConversationSearch(json, context)
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }

    fun getFirstLine(text: String, context: Context): String {
        val formatted = format(text, context)
        return formatted.lines().first().take(100)
    }

    private fun formatError(json: JsonObject, error: String, context: Context): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        return when (error) {
            "no_query" -> context.getString(R.string.search_no_query)
            "no_results" -> context.getString(R.string.search_no_results)
            "no_response" -> context.getString(R.string.search_no_response)
            "no_api_key" -> context.getString(R.string.provider_no_keys, "Brave Search")
            "search_error" -> {
                val msg = json["message"]?.let { (it as? JsonPrimitive)?.content } ?: context.getString(R.string.unknown)
                context.getString(R.string.search_error_format, msg)
            }
            else -> context.getString(R.string.search_error_format, error)
        }
    }

    private fun formatWebSearch(json: JsonObject, context: Context): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val results = json["results"]?.jsonArray ?: return context.getString(R.string.search_no_results)
        if (results.isEmpty()) return context.getString(R.string.search_no_results)

        val untitled = context.getString(R.string.search_untitled)
        val body = results.take(10).mapIndexed { i, element ->
            val obj = element.jsonObject
            val title = (obj["title"] as? JsonPrimitive)?.content ?: untitled
            val url = (obj["url"] as? JsonPrimitive)?.content ?: ""
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: ""
            "${i + 1}. $title\n   $url\n   $desc"
        }.joinToString("\n\n")

        val total = results.size
        val prefix = if (query.isNotBlank())
            context.getString(R.string.search_found_results, total, query)
        else
            context.getString(R.string.search_found_results_no_query, total)
        return "$prefix\n\n$body"
    }

    private fun formatConversationSearch(json: JsonObject, context: Context): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val results = json["results"]?.jsonArray ?: return context.getString(R.string.search_no_matches, query)
        if (results.isEmpty()) return context.getString(R.string.search_no_matches, query)

        val userRole = context.getString(R.string.search_role_user)
        val modelRole = context.getString(R.string.search_role_model)
        val untitled = context.getString(R.string.search_untitled)

        val body = results.take(5).joinToString("\n\n") { element ->
            val obj = element.jsonObject
            val title = (obj["title"] as? JsonPrimitive)?.content ?: untitled
            val messages = obj["messages"]?.jsonArray
            val previews = messages?.take(3)?.joinToString("\n") { msg ->
                val msgObj = msg.jsonObject
                val participant = (msgObj["participant"] as? JsonPrimitive)?.content ?: ""
                val role = if (participant == "USER") userRole else modelRole
                val text = (msgObj["text"] as? JsonPrimitive)?.content?.lines()?.first()?.take(200) ?: ""
                "$role: $text"
            } ?: ""
            "## $title\n$previews"
        }

        val total = results.sumOf { (it.jsonObject["messages"]?.jsonArray?.size ?: 0) }
        return context.getString(R.string.search_found_matches, total, query) + "\n\n$body"
    }
}
