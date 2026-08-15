package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.isOpenAiProtocolProvider
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiServiceTierTest {
    private val wireJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun enabledTierIsSerializedForAnOpenAiProtocolRequest() {
        val customized = request().copy(
            serviceTier = OpenAiServiceTiers.requestValue(
                enabled = true,
                value = OpenAiServiceTiers.FAST,
                responsesApiEnabled = true,
            ),
        )

        assertEquals(OpenAiServiceTiers.FAST, customized.serviceTier)
        assertTrue(
            wireJson.encodeToString(customized)
                .contains("\"service_tier\":\"fast\""),
        )
    }

    @Test
    fun disabledTierDoesNotLeakIntoTheCompatibleRequest() {
        val baseRequest = request()

        assertNull(
            OpenAiServiceTiers.requestValue(
                enabled = false,
                value = OpenAiServiceTiers.FAST,
                responsesApiEnabled = true,
            ),
        )
        assertNull(
            OpenAiServiceTiers.requestValue(
                enabled = true,
                value = OpenAiServiceTiers.FAST,
                responsesApiEnabled = false,
            ),
        )
        assertNull(baseRequest.serviceTier)
        assertFalse(
            wireJson.encodeToString(baseRequest)
                .contains("\"service_tier\""),
        )
    }

    @Test
    fun invalidOrMissingTiersNormalizeToAuto() {
        assertEquals(OpenAiServiceTiers.AUTO, OpenAiServiceTiers.normalize(null))
        assertEquals(OpenAiServiceTiers.AUTO, OpenAiServiceTiers.normalize("unknown"))
        assertEquals(OpenAiServiceTiers.FLEX, OpenAiServiceTiers.normalize(" FLEX "))
    }

    @Test
    fun capabilityIncludesBuiltInAndCustomOpenAiProtocolOnly() {
        val customProviders = listOf(
            CustomProviderConfig("Sub2", CustomEndpointProtocol.OPENAI),
            CustomProviderConfig("Claude relay", CustomEndpointProtocol.ANTHROPIC),
        )

        assertTrue(isOpenAiProtocolProvider(Constants.PROVIDER_OPENAI, customProviders))
        assertTrue(isOpenAiProtocolProvider("Sub2", customProviders))
        assertFalse(isOpenAiProtocolProvider("Claude relay", customProviders))
        assertFalse(isOpenAiProtocolProvider(Constants.PROVIDER_DEEPSEEK, customProviders))
    }

    private fun request() = OpenAiChatRequest(
        model = "gpt-5",
        messages = listOf(OpenAiMessage(role = "user")),
    )
}
