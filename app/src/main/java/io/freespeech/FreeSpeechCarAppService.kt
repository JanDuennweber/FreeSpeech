package io.freespeech

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class FreeSpeechCarAppService : CarAppService() {

    /**
     * Allow any gearhead host — appropriate for a sideloaded debug app.
     * A production release would verify the host's signing certificate here.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = FreeSpeechCarSession()
}
