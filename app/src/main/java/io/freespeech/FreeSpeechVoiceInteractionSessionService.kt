package io.freespeech

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class FreeSpeechVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        FreeSpeechVoiceInteractionSession(this)
}
