package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSettingsArchiveTest {
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
        assertEquals(mapOf("Relay X" to id), result.modelReferenceRemap)
        assertEquals(mapOf("Relay X" to "Relay X"), result.providerNameRemap)
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
