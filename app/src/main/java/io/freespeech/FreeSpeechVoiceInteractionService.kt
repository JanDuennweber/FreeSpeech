package io.freespeech

import android.service.voice.VoiceInteractionService

/**
 * Registers FreeSpeech as the system voice assistant (digital assistant).
 * Once set as the default assistant in Android settings, Android Auto's
 * microphone button will invoke this service instead of Google Assistant.
 */
class FreeSpeechVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}
