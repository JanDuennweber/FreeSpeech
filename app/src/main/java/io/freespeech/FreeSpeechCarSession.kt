package io.freespeech

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class FreeSpeechCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = FreeSpeechCarScreen(carContext)
}
