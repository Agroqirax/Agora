package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearxngSearchUrlTest {
    @Test
    fun searxngUsesInstanceDefaultsAndCanonicalizesTrailingSlash() {
        val url = searxngSearchUrl(
            configuredBaseUrl = "https://search.example.test/",
            query = "hello world/中文",
        )

        assertEquals(
            "https://search.example.test/search?q=hello+world%2F%E4%B8%AD%E6%96%87&format=json",
            url,
        )
        assertFalse(url.contains("engines="))
        assertFalse(url.contains(".test//search"))
    }

    @Test
    fun openAiRequestUsesHostedWebSearchTool() {
        val body = Json.parseToJsonElement(openAiWebSearchRequestBody("latest news")).jsonObject
        assertEquals(OPENAI_WEB_SEARCH_MODEL, body["model"]?.jsonPrimitive?.content)
        assertEquals("latest news", body["input"]?.jsonPrimitive?.content)
        assertEquals(
            "web_search",
            body["tools"]?.jsonArray?.single()?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun openAiResponseNormalizesAnswerAndUniqueCitations() {
        val response = """{"output":[{"type":"web_search_call","id":"ws_1"},{"type":"message","content":[{"type":"output_text","text":"Answer [1]","annotations":[{"type":"url_citation","url":"https://a.test","title":"A"},{"type":"url_citation","url":"https://a.test","title":"A duplicate"},{"type":"url_citation","url":"https://b.test","title":"B"}]}]}]}"""
        val normalized = Json.parseToJsonElement(
            normalizeOpenAiWebSearchResponse(response, "q", 2),
        ).jsonObject
        assertEquals("Answer [1]", normalized["answer"]?.jsonPrimitive?.content)
        val results = normalized["results"]?.jsonArray.orEmpty()
        assertEquals(2, results.size)
        assertEquals(
            listOf("https://a.test", "https://b.test"),
            results.map { it.jsonObject["url"]?.jsonPrimitive?.content },
        )
        assertTrue(normalized["error"] == null)
    }
}
