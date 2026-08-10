package com.newoether.agora.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeveloperOptionsPersistenceContractTest {
    @Test
    fun `developer options key remains stable`() {
        assertEquals("developer_options_enabled", DEVELOPER_OPTIONS_ENABLED.name)
    }

    @Test
    fun `developer options remain device local during portable archive and replace`() {
        val sourceRoot = locateMainSourceRoot()
        val portableSource = File(
            sourceRoot,
            "com/newoether/agora/data/PortableSettingsArchive.kt",
        ).readText()
        assertFalse(
            "Developer Options must not enter portable settings archives",
            portableSource.contains("developerOptionsEnabled"),
        )

        val managerSource = File(
            sourceRoot,
            "com/newoether/agora/data/SettingsManager.kt",
        ).readText()
        val resetBody = managerSource
            .substringAfter("suspend fun resetPortableSettingsForImport()")
            .substringBefore("suspend fun invalidatePortableModelCaches")
        assertFalse(
            "Replacing portable settings must preserve this installation's Developer Options gate",
            resetBody.contains("DEVELOPER_OPTIONS_ENABLED"),
        )
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
