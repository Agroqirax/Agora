package com.newoether.agora.viewmodel

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderFactoryTest {
    @Test
    fun factoryReusesExistingProtocolImplementations() {
        val url = "https://example.test/api"

        val openAi = createCustomProvider(
            CustomProviderConfig("OpenAI proxy", CustomEndpointProtocol.OPENAI),
            url,
        )
        val google = createCustomProvider(
            CustomProviderConfig("Google proxy", CustomEndpointProtocol.GOOGLE),
            url,
        )
        val anthropic = createCustomProvider(
            CustomProviderConfig("Anthropic proxy", CustomEndpointProtocol.ANTHROPIC),
            url,
        )

        assertTrue(openAi is CustomOpenAiProvider)
        assertTrue(google is GeminiProvider)
        assertTrue(anthropic is AnthropicProvider)
        assertEquals("Google proxy", google?.name)
        assertEquals("Anthropic proxy", anthropic?.name)
        assertEquals(url, google?.defaultBaseUrl)
        assertEquals(url, anthropic?.defaultBaseUrl)
    }

    @Test
    fun unknownProtocolIsNotRegistered() {
        assertNull(
            createCustomProvider(
                CustomProviderConfig("Unknown", CustomEndpointProtocol.UNKNOWN),
                "https://example.test",
            ),
        )
    }

    @Test
    fun baseUrlCandidatesAreProtocolSpecific() {
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.OPENAI,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.GOOGLE,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.ANTHROPIC,
                "https://example.test",
            ),
        )
        assertEquals(
            emptyList<String?>(),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.UNKNOWN,
                "https://example.test",
            ),
        )
    }

    @Test
    fun explicitVersionedBaseUrlIsNeverRewritten() {
        val url = "https://example.test/v1beta"

        CustomEndpointProtocol.selectable.forEach { protocol ->
            assertEquals(
                listOf(url),
                customEndpointBaseUrlCandidates(protocol, url),
            )
        }
    }
}
