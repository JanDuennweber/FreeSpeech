package io.freespeech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [WakeWordService] after a device reboot when wake-word detection
 * was enabled by the user.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission in the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        if (prefs.getBoolean("wake_enabled", false)) {
            WakeWordService.start(context)
        }
    }
}
