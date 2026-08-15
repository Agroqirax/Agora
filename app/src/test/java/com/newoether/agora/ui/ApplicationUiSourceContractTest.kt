package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationUiSourceContractTest {
    @Test
    fun `onboarding primary action reuses the documentation press spring geometry`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/onboarding/WelcomeScreen.kt")

        assertTrue(source.contains("MutableInteractionSource()"))
        assertTrue(source.contains("collectIsPressedAsState()"))
        assertTrue(source.contains("motionPolicy.allowSpatialTransitions"))
        assertTrue(source.contains("stiffness = 400f"))
        assertTrue(source.contains("dampingRatio = 0.25f"))
        assertTrue(source.contains("targetValue = if (pressed) 12.dp else 32.dp"))
        assertTrue(source.contains("targetValue = if (pressed) 56.dp else 48.dp"))
        assertTrue(source.contains("targetValue = if (pressed) 1.1f else 1f"))
        assertTrue(source.contains(".height(56.dp)"))
        assertTrue(source.contains(".scale(contentScale)"))
    }

    @Test
    fun `generation settings description names only localized LLM parameters`() {
        val expected = linkedMapOf(
            "values" to "LLM parameters",
            "values-ar" to "معاملات LLM",
            "values-de" to "LLM-Parameter",
            "values-es" to "Parámetros del LLM",
            "values-fr" to "Paramètres du LLM",
            "values-ja" to "LLM パラメーター",
            "values-ko" to "LLM 매개변수",
            "values-pt-rBR" to "Parâmetros do LLM",
            "values-ru" to "Параметры LLM",
            "values-vi" to "Tham số LLM",
            "values-zh" to "LLM 参数",
            "values-zh-rTW" to "LLM 參數",
        )

        expected.forEach { (directory, value) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertEquals(
                "$directory settings_generation_desc",
                value,
                stringValue(strings, "settings_generation_desc"),
            )
        }
    }

    @Test
    fun `chat bottom dropdowns match the user message twenty four dp icon size`() {
        val attachment = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/AttachmentAddMenu.kt",
        )
        val bottomBar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val components = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBarComponents.kt",
        )
        val userMessage = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )

        assertTrue(components.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24"))
        assertTrue(attachment.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(bottomBar.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(components.contains("Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp)"))
        assertTrue(attachment.contains("Icons.Default.Add"))
        assertTrue(attachment.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(bottomBar.contains("Icons.Default.MoreVert"))
        assertTrue(bottomBar.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(userMessage.contains("leadingIcon = { Icon(Icons.Default.ContentCopy, null) }"))
    }

    private fun stringValue(xml: String, key: String): String {
        val regex = Regex("""<string name="$key">([^<]*)</string>""")
        return requireNotNull(regex.find(xml)) { "Missing $key" }.groupValues[1]
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
