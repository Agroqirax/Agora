package com.newoether.agora.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import androidx.core.content.FileProvider
import com.newoether.agora.MainActivity
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * Invoked by the system on the assist gesture. Captures the on-screen text of the
 * foreground app via [AssistStructure] (no screenshot/OCR involved), writes it to a temp
 * file, and hands off to [MainActivity] exactly the way any other text-file attachment
 * would be handed to the chat composer — see [com.newoether.agora.ui.chat.bottombar.ChatComposerState.onPickFiles].
 *
 * Screen-text capture itself is gated by
 * [com.newoether.agora.data.SettingsManager.assistAttachScreenTextEnabled] (Settings →
 * Assistant) — off, and this is a pure app launcher with no context attached at all.
 *
 * No overlay UI is shown; this session is a pure trampoline. FLAG_SECURE windows and
 * password fields yield no text — the target app still opens normally, just with no
 * pre-attached context.
 */
class AgoraVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)

        // Single cached DataStore read, same synchronous pattern as
        // MainActivity.attachBaseContext's appLanguage lookup — not a network/disk-cold
        // read, so runBlocking here doesn't meaningfully block the assist gesture.
        val attachEnabled = kotlinx.coroutines.runBlocking {
            com.newoether.agora.data.SettingsManager(context).assistAttachScreenTextEnabled.first()
        }

        val capturedText = if (!attachEnabled) null else try {
            structure?.let { extractText(it) }
        } catch (e: Exception) {
            DebugLog.e("AgoraAssist", "Failed to extract assist structure text", e)
            null
        }

        val contextUri = capturedText
            ?.takeIf { it.isNotBlank() }
            ?.let { writeToTempFile(it) }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = AssistLaunch.ACTION_ASSIST_LAUNCH
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (contextUri != null) {
                putExtra(AssistLaunch.EXTRA_CONTEXT_URI, contextUri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setClipData(android.content.ClipData.newRawUri(null, contextUri))
            }
        }

        // Launches the target activity and finishes this (headless) session — there is no
        // content view to tear down since onCreateContentView was never overridden.
        context.startActivity(launchIntent)
        finish()
    }

    /** Walks every window's view tree, collecting visible text/hint text, skipping password
     *  fields. Returns null if nothing readable was found. Capped to avoid pathologically
     *  large screens (e.g. a long scrollable list the system chose to fully capture). */
    private fun extractText(structure: AssistStructure): String? {
        val sb = StringBuilder()
        val windowCount = structure.windowNodeCount
        for (w in 0 until windowCount) {
            val root = structure.getWindowNodeAt(w).rootViewNode ?: continue
            collectNodeText(root, sb)
            if (sb.length >= Constants.MAX_ASSIST_CONTEXT_LENGTH) break
        }
        if (sb.isEmpty()) return null
        return if (sb.length > Constants.MAX_ASSIST_CONTEXT_LENGTH) {
            sb.substring(0, Constants.MAX_ASSIST_CONTEXT_LENGTH) + "\n… [truncated]"
        } else {
            sb.toString()
        }
    }

    private fun collectNodeText(node: AssistStructure.ViewNode, sb: StringBuilder) {
        if (sb.length >= Constants.MAX_ASSIST_CONTEXT_LENGTH) return

        if (!isPasswordField(node)) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                sb.append(text).append('\n')
            } else {
                val hint = node.hint?.trim()
                if (!hint.isNullOrEmpty()) sb.append(hint).append('\n')
            }
        }

        for (i in 0 until node.childCount) {
            collectNodeText(node.getChildAt(i), sb)
            if (sb.length >= Constants.MAX_ASSIST_CONTEXT_LENGTH) return
        }
    }

    private fun isPasswordField(node: AssistStructure.ViewNode): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            (node.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /** Same pattern as [com.newoether.agora.ui.chat.ImageActions] for sharing files via
     *  FileProvider: write into cacheDir/shared (already declared in file_paths.xml). */
    private fun writeToTempFile(text: String): Uri? {
        return try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "assist_context_${UUID.randomUUID()}.txt")
            file.writeText(text)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            DebugLog.e("AgoraAssist", "Failed to write assist context temp file", e)
            null
        }
    }
}
