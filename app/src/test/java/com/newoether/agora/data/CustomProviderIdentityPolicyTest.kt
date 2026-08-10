package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderIdentityPolicyTest {
    @Test
    fun legacyProvidersReceiveDistinctStableIdsAndCrashSafeMarkers() {
        val ids = ArrayDeque(
            listOf(
                "custom-provider-00000000-0000-4000-8000-000000000001",
                "custom-provider-00000000-0000-4000-8000-000000000002",
            ),
        )

        val result = CustomProviderIdentityPolicy.normalize(
            rawProviders = listOf(CustomProviderConfig("Relay A"), CustomProviderConfig("Relay B")),
            newId = { ids.removeFirst() },
        )

        assertNotEquals(result.providers[0].id, result.providers[1].id)
        assertEquals(setOf("Relay A"), result.providers[0].legacyNames)
        assertEquals(
            listOf(
                CustomProviderIdentityMigration("Relay A", result.providers[0].id),
                CustomProviderIdentityMigration("Relay B", result.providers[1].id),
            ),
            result.migrations,
        )
    }

    @Test
    fun stableProviderIdentitySurvivesDisplayRenameAndOwnsDisplayResolution() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val renamed = CustomProviderConfig(
            name = "Relay X",
            id = id,
            legacyNames = setOf("Old Relay"),
        )

        assertTrue(renamed.ownsIdentity(id))
        assertTrue(renamed.ownsIdentity("Old Relay"))
        assertEquals("Relay X", providerDisplayName(id, listOf(renamed)))
        assertEquals("Relay X", providerDisplayName("Old Relay", listOf(renamed)))
        assertEquals("Built In", providerDisplayName("Built In", listOf(renamed)))
    }

    @Test
    fun modelReferenceRemapChangesOnlyTheExactProviderComponent() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val migrations = mapOf("Relay" to id)

        assertEquals("$id:model:variant", "Relay:model:variant".remapProviderReference(migrations))
        assertEquals("Relay Two:model", "Relay Two:model".remapProviderReference(migrations))
        assertEquals("unprefixed", "unprefixed".remapProviderReference(migrations))

        val colonNameId = "custom-provider-00000000-0000-4000-8000-000000000002"
        assertEquals(
            "$colonNameId:model",
            "Relay:China:model".remapProviderReference(mapOf("Relay:China" to colonNameId)),
        )
    }

    @Test
    fun canonicalAliasWinsWhenLegacyAndStableKeysCollide() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"

        assertEquals(
            mapOf("$id:model" to "Current alias"),
            remapModelAliases(
                aliases = linkedMapOf(
                    "Relay:model" to "Legacy alias",
                    "$id:model" to "Current alias",
                ),
                migrations = mapOf("Relay" to id),
            ),
        )
    }

    @Test
    fun canonicalModelIdAcceptsCurrentAndHistoricalDisplayNamesIncludingColon() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val providers = listOf(
            CustomProviderConfig(
                name = "Relay X",
                id = id,
                legacyNames = setOf("Relay:Old"),
            ),
        )

        assertEquals("$id:model", canonicalCustomModelId("Relay X:model", providers))
        assertEquals("$id:model", canonicalCustomModelId("Relay:Old:model", providers))
        assertEquals("$id:model", canonicalCustomModelId("$id:model", providers))
    }

    @Test
    fun customModelDisplayUsesCurrentProviderNameUnlessAliasOverridesIt() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val model = "$id:gemini-3.1-pro"
        val providers = listOf(CustomProviderConfig(name = "Relay X", id = id))

        assertEquals("gemini-3.1-pro (Relay X)", modelDisplayName(model, emptyMap(), providers))
        assertEquals("Fast", modelDisplayName(model, mapOf(model to "Fast"), providers))
    }

    @Test
    fun unresolvedStableIdentityNeverLeaksIntoDisplayText() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"

        assertEquals("Custom", providerDisplayName(id, emptyList()))
        assertEquals("model (Custom)", modelDisplayName("$id:model", emptyMap(), emptyList()))
    }

    @Test
    fun orphanedAliasIsNotCrossBoundWhenMultipleProvidersExposeTheSameModel() {
        val orphan = "custom-provider-00000000-0000-4000-8000-000000000001"
        val first = "custom-provider-00000000-0000-4000-8000-000000000002"
        val second = "custom-provider-00000000-0000-4000-8000-000000000003"
        val aliases = mapOf("$orphan:model" to "My Alias")

        assertEquals(
            aliases,
            repairOrphanedCustomProviderAliases(
                aliases = aliases,
                knownModelReferences = listOf("$first:model", "$second:model"),
                activeProviderIds = setOf(first, second),
            ),
        )
    }
}
