package com.newoether.agora.assistant

import android.service.voice.VoiceInteractionService

/**
 * Marker service that lets Agora register for the android.app.role.ASSISTANT role.
 * No behavior lives here — [AgoraVoiceInteractionSessionService] / [AgoraVoiceInteractionSession]
 * do the actual work when the system assist gesture invokes Agora. This class exists purely
 * because the OS requires a running VoiceInteractionService component to bind the role to.
 */
class AgoraVoiceInteractionService : VoiceInteractionService()
