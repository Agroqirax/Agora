package com.newoether.agora.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomProviderConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConfigWithoutProtocolDefaultsToOpenAi() {
        val config = json.decodeFromString<CustomProviderConfig>("""{"name":"Legacy"}""")

        assertEquals("Legacy", config.name)
        assertEquals(CustomEndpointProtocol.OPENAI, config.protocol)
        assertEquals("", config.id)
        assertEquals(emptySet<String>(), config.legacyNames)
        assertEquals(false, config.responsesApiEnabled)
    }

    @Test
    fun responsesApiSettingRoundTripsWithStableProviderIdentity() {
        val original = CustomProviderConfig(
            name = "Relay X",
            id = "custom-provider-00000000-0000-4000-8000-000000000001",
            responsesApiEnabled = true,
        )

        assertEquals(
            original,
            json.decodeFromString<CustomProviderConfig>(json.encodeToString(original)),
        )
    }

    @Test
    fun responsesApiRuntimeGateRequiresBuiltInOrCustomOpenAiProtocol() {
        val providers = listOf(
            CustomProviderConfig(
                name = "OpenAI relay",
                protocol = CustomEndpointProtocol.OPENAI,
                responsesApiEnabled = true,
            ),
            CustomProviderConfig(
                name = "Google relay",
                protocol = CustomEndpointProtocol.GOOGLE,
                responsesApiEnabled = true,
            ),
            CustomProviderConfig(
                name = "Unknown relay",
                protocol = CustomEndpointProtocol.UNKNOWN,
                responsesApiEnabled = true,
            ),
        )

        assertEquals(true, isResponsesApiEnabledForProvider("OpenAI", true, providers))
        assertEquals(false, isResponsesApiEnabledForProvider("OpenAI", false, providers))
        assertEquals(true, isResponsesApiEnabledForProvider("OpenAI relay", false, providers))
        assertEquals(
            true,
            isResponsesApiEnabledForProvider(providers[0].providerId, false, providers),
        )
        assertEquals(false, isResponsesApiEnabledForProvider("Google relay", true, providers))
        assertEquals(false, isResponsesApiEnabledForProvider("Unknown relay", true, providers))
        assertEquals(false, isResponsesApiEnabledForProvider("missing", true, providers))
    }

    @Test
    fun supportedProtocolsRoundTrip() {
        CustomEndpointProtocol.selectable.forEach { protocol ->
            val encoded = json.encodeToString(
                CustomProviderConfig(name = "Endpoint", protocol = protocol),
            )
            val decoded = json.decodeFromString<CustomProviderConfig>(encoded)

            assertEquals(protocol, decoded.protocol)
        }
    }

    @Test
    fun stableIdentityAndMigrationMarkersRoundTrip() {
        val original = CustomProviderConfig(
            name = "Relay X",
            protocol = CustomEndpointProtocol.ANTHROPIC,
            id = "custom-provider-00000000-0000-4000-8000-000000000001",
            legacyNames = setOf("Old Relay"),
        )

        assertEquals(
            original,
            json.decodeFromString<CustomProviderConfig>(json.encodeToString(original)),
        )
    }

    @Test
    fun unknownProtocolDecodesFailClosedWithoutDroppingProviderList() {
        val configs = json.decodeFromString<List<CustomProviderConfig>>(
            """[{"name":"Future","protocol":"future-api"},{"name":"Legacy"}]""",
        )

        assertEquals(CustomEndpointProtocol.UNKNOWN, configs[0].protocol)
        assertEquals(CustomEndpointProtocol.OPENAI, configs[1].protocol)
    }
}
