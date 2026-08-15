package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsArchiveTest {
    @Test
    fun compactThresholdImportAcceptsOnlyThePortableRange() {
        assertEquals(50, importedContextCompactThresholdPercent(50))
        assertEquals(90, importedContextCompactThresholdPercent(90))
        assertEquals(100, importedContextCompactThresholdPercent(100))
        assertEquals(null, importedContextCompactThresholdPercent(null))
        assertEquals(null, importedContextCompactThresholdPercent(49))
        assertEquals(null, importedContextCompactThresholdPercent(101))
    }

    @Test
    fun legacyArchiveProviderReusesExistingIdentityAndMarksRoomReferences() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X")),
            existing = listOf(CustomProviderConfig(name = "Relay X", id = id)),
            replace = false,
        )

        assertEquals(id, result.providers.single().id)
        assertEquals(setOf("Relay X"), result.providers.single().legacyNames)
        assertEquals(false, result.providers.single().responsesApiEnabled)
        assertEquals(mapOf("Relay X" to id), result.modelReferenceRemap)
        assertEquals(mapOf("Relay X" to "Relay X"), result.providerNameRemap)
    }

    @Test
    fun importedProviderResponsesSettingReplacesExistingStableRecord() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X", responsesApiEnabled = true)),
            existing = listOf(CustomProviderConfig(name = "Relay X", id = id)),
            replace = false,
        )
        assertEquals(id, result.providers.single().id)
        assertEquals(true, result.providers.single().responsesApiEnabled)
    }
    @Test
    fun replacingFromLegacyArchiveAllocatesStableIdentity() {
        val result = PortableSettingsArchive.prepareImportedCustomProviders(
            raw = listOf(CustomProviderConfig(name = "Relay X")),
            existing = emptyList(),
            replace = true,
        )

        val provider = result.providers.single()
        assertTrue(CustomProviderIdentityPolicy.isStableId(provider.id))
        assertEquals(provider.id, result.modelReferenceRemap["Relay X"])
        assertEquals(setOf("Relay X"), provider.legacyNames)
    }
}
