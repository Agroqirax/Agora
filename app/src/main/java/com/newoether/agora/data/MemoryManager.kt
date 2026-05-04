package com.newoether.agora.data

import android.content.Context
import java.io.File

class MemoryManager(context: Context) {
    private val memoryDir: File =
        File(context.filesDir, "memory_db").also { it.mkdirs() }

    private val activeMemoryFile: File =
        File(context.filesDir, "active_memory.md")

    @Synchronized
    fun getActiveMemory(): String =
        if (activeMemoryFile.exists()) activeMemoryFile.readText() else ""

    @Synchronized
    fun updateActiveMemory(content: String, mode: String = "replace"): String =
        when (mode) {
            "append" -> {
                activeMemoryFile.appendText("\n$content")
                "Appended to active memory."
            }
            "prepend" -> {
                val existing = getActiveMemory()
                activeMemoryFile.writeText("$content\n$existing")
                "Prepended to active memory."
            }
            else -> {
                activeMemoryFile.writeText(content)
                "Active memory updated."
            }
        }

    @Synchronized
    fun listFiles(): List<String> =
        memoryDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { it.name }
            ?.sorted() ?: emptyList()

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String): String {
        val file = resolveFile(name)
        if (file.exists()) throw IllegalArgumentException("File already exists: ${file.name}")
        file.writeText(content)
        return "Created ${file.name}"
    }

    @Synchronized
    fun editFile(name: String, content: String? = null, newName: String? = null): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        if (content != null) file.writeText(content)
        if (newName != null && newName != name) {
            val newFile = resolveFile(newName)
            if (newFile.exists()) throw IllegalArgumentException("Target file already exists: ${newFile.name}")
            file.renameTo(newFile)
            if (content != null) return "Updated and renamed to ${newFile.name}"
            return "Renamed to ${newFile.name}"
        }
        if (content != null) return "Updated ${file.name}"
        return "No changes made."
    }

    @Synchronized
    fun deleteFile(name: String): String {
        val file = resolveFile(name)
        if (!file.exists()) throw IllegalArgumentException("File not found: $name")
        file.delete()
        return "Deleted ${file.name}"
    }

    private fun resolveFile(name: String): File {
        val sanitized = name.replace(Regex("""[/\\]"""), "_")
        val file = File(memoryDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
        val canonicalPath = file.canonicalPath
        val canonicalDir = memoryDir.canonicalPath
        if (!canonicalPath.startsWith(canonicalDir)) {
            throw IllegalArgumentException("Invalid file name: $name")
        }
        return file
    }
}
