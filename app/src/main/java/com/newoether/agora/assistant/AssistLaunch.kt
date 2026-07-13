package com.newoether.agora.assistant

/** Intent contract between [AgoraVoiceInteractionSession] and `MainActivity`. */
object AssistLaunch {
    /** Action on the intent MainActivity is started with after an assist capture. */
    const val ACTION_ASSIST_LAUNCH = "com.newoether.agora.action.ASSIST_LAUNCH"

    /**
     * String extra: a content:// URI (granted via FileProvider) pointing at a temp .txt
     * file containing the captured on-screen text. Absent/null if nothing readable was
     * found (e.g. a FLAG_SECURE window) — MainActivity should still open a fresh chat.
     */
    const val EXTRA_CONTEXT_URI = "com.newoether.agora.extra.ASSIST_CONTEXT_URI"
}
