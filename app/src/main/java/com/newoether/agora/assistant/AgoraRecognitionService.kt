package com.newoether.agora.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required stub: the `voice_interaction_service` metadata must reference a
 * [RecognitionService] belonging to this package (the system binds to it directly), even
 * though Agora's assist flow doesn't use speech-to-text — it captures the on-screen text
 * via [AgoraVoiceInteractionSession.onHandleAssist] instead. If anything ever does start
 * listening on this service, immediately report "not supported" rather than hang.
 */
class AgoraRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {
        // No-op: nothing was ever started.
    }

    override fun onStopListening(listener: Callback?) {
        // No-op: nothing was ever started.
    }
}
