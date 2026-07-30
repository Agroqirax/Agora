package com.newoether.agora.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SkillManager(context: Context) {
    private val skillsDir: File =
        File(context.filesDir, "skills_db").also { it.mkdirs() }

    private val metaFile: File =
        File(skillsDir, "skills_meta.json")

    private val seededFile: File =
        File(skillsDir, "skills_seeded.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    data class SkillMeta(
        val description: String = "",
        val builtin: Boolean = false
    )

    data class SkillFileInfo(
        val name: String,
        val description: String = "",
        val isBuiltin: Boolean = false
    )

    init {
        seedDefaults()
    }

    @Synchronized
    private fun loadMeta(): MutableMap<String, SkillMeta> =
        if (metaFile.exists()) {
            try { json.decodeFromString<MutableMap<String, SkillMeta>>(metaFile.readText()) }
            catch (_: Exception) { mutableMapOf() }
        } else mutableMapOf()

    @Synchronized
    private fun saveMeta(meta: Map<String, SkillMeta>) {
        metaFile.writeText(json.encodeToString(meta))
    }

    @Synchronized
    private fun loadSeeded(): MutableSet<String> =
        if (seededFile.exists()) {
            try { json.decodeFromString<MutableSet<String>>(seededFile.readText()) }
            catch (_: Exception) { mutableSetOf() }
        } else mutableSetOf()

    @Synchronized
    private fun saveSeeded(seeded: Set<String>) {
        seededFile.writeText(json.encodeToString(seeded))
    }

    @Synchronized
    private fun seedDefaults() {
        if (DefaultSkills.BUILTINS.isEmpty()) return
        val seeded = loadSeeded()
        val meta = loadMeta()
        var changed = false
        for (builtin in DefaultSkills.BUILTINS) {
            val file = resolveFile(builtin.name)
            if (seeded.contains(file.name)) continue
            if (!file.exists()) {
                file.writeText(builtin.content)
                meta[file.name] = SkillMeta(builtin.description, builtin = true)
            }
            seeded.add(file.name)
            changed = true
        }
        if (changed) {
            saveMeta(meta)
            saveSeeded(seeded)
        }
    }

    @Synchronized
    fun listFiles(): List<SkillFileInfo> {
        val meta = loadMeta()
        return skillsDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { f ->
                val m = meta[f.name] ?: SkillMeta()
                SkillFileInfo(f.name, m.description, m.builtin)
            }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun getMetaJson(): String =
        if (metaFile.exists()) metaFile.readText() else "{}"

    fun saveMetaJson(jsonStr: String) {
        metaFile.writeText(jsonStr)
    }

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String, description: String = ""): String {
        val file = resolveFile(name)
        if (file.exists()) throw IllegalArgumentException("File already exists: ${file.name}")
        file.writeText(content)
        if (description.isNotBlank()) {
            val meta = loadMeta()
            meta[file.name] = SkillMeta(description, builtin = false)
            saveMeta(meta)
        }
        return "Created ${file.name}"
    }

    @Synchronized
    fun editFile(
        name: String,
        content: String? = null,
        newName: String? = null,
        description: String? = null,
        oldString: String? = null,
        newString: String? = null
    ): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        val meta = loadMeta()
        val existingMeta = meta[file.name] ?: SkillMeta()
        var renamedFile: File? = null
        var contentChanged = false
        if (oldString != null) {
            val fileText = file.readText()
            val count = fileText.countOccurrences(oldString)
            if (count == 0)
                throw IllegalArgumentException("old_string not found in ${file.name}")
            if (count > 1)
                throw IllegalArgumentException("old_string matches $count times in ${file.name} — must be unique")
            file.writeText(fileText.replace(oldString, newString ?: ""))
            contentChanged = true
        } else if (content != null) {
            file.writeText(content)
            contentChanged = true
        }
        var currentMeta = if (contentChanged && existingMeta.builtin) existingMeta.copy(builtin = false) else existingMeta
        if (newName != null && newName != name) {
            renamedFile = resolveFile(newName)
            if (renamedFile.exists()) throw IllegalArgumentException("Target file already exists: ${renamedFile.name}")
            file.renameTo(renamedFile)
            meta.remove(file.name)
        }
        if (description != null) {
            currentMeta = currentMeta.copy(description = description)
        }
        val targetName = renamedFile?.name ?: file.name
        if (currentMeta != SkillMeta()) meta[targetName] = currentMeta else meta.remove(targetName)
        saveMeta(meta)
        if (oldString != null && newName != null) return "Replaced in and renamed to $targetName"
        if (oldString != null) return "Replaced in $targetName"
        if (content != null && newName != null) return "Updated and renamed to $targetName"
        if (content != null) return "Updated $targetName"
        if (newName != null) return "Renamed to $targetName"
        if (description != null) return "Updated description of $targetName"
        return "No changes made."
    }

    @Synchronized
    fun deleteFile(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        file.delete()
        val meta = loadMeta()
        meta.remove(file.name)
        saveMeta(meta)
        return "Deleted ${file.name}"
    }

    @Synchronized
    fun resetToDefault(name: String): String {
        val file = resolveFile(name)
        val builtin = DefaultSkills.BUILTINS.find { resolveFile(it.name).name == file.name }
            ?: throw IllegalArgumentException("No builtin skill named: $name")
        file.writeText(builtin.content)
        val meta = loadMeta()
        meta[file.name] = SkillMeta(builtin.description, builtin = true)
        saveMeta(meta)
        return "Reset ${file.name} to default"
    }

    private fun String.countOccurrences(substring: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = indexOf(substring, idx)
            if (idx < 0) break
            count++
            idx += substring.length
        }
        return count
    }

    private fun resolveFile(name: String): File {
        val sanitized = name.replace(Regex("""[/\\]"""), "_")
        val file = File(skillsDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
        val canonicalPath = file.canonicalPath
        val canonicalDir = skillsDir.canonicalPath
        if (!canonicalPath.startsWith(canonicalDir)) {
            throw IllegalArgumentException("Invalid file name: $name")
        }
        return file
    }
}
