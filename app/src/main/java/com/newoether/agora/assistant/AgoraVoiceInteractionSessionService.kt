package com.newoether.agora.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates a new [AgoraVoiceInteractionSession] for each assist invocation. Kept as a thin
 * factory per the platform's expected split between "session service" (lifecycle) and
 * "session" (the actual interaction instance).
 */
class AgoraVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AgoraVoiceInteractionSession(this)
    }
}
