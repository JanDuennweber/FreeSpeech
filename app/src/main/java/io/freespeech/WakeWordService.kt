package io.freespeech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that listens for the configured wake word using the
 * existing Whisper server — no on-device ML model required, fully offline-
 * capable on the local network, and supports any language or custom phrase.
 *
 * Detection algorithm (VAD-gated Whisper):
 *  1. Record until VAD detects end-of-speech (trailing silence) or max clip length
 *  2. Send clip to the configured Whisper endpoint
 *  3. If the transcript contains the wake word → notify [FreeSpeechCarScreen]
 *     via [wakeListener] and yield the microphone until the command is done
 *
 * Disabled by default.  Enabled via Settings → "Wake Word Detection" checkbox.
 * Auto-restarts after a device reboot via [BootReceiver] when enabled.
 *
 * AudioRecord conflict prevention:
 *  [commandInProgress] is set to true by [FreeSpeechCarScreen] (and by this
 *  service itself before invoking the listener) while the car screen owns the
 *  microphone.  The recording loop checks this flag and yields immediately,
 *  so the two AudioRecord instances never compete.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ID         = "wake_word"
        private const val NOTIF_ID           = 101
        private const val ACTION_STOP        = "io.freespeech.WAKE_STOP"

        /** Notification channel / ID for the car-screen trigger banner. */
        private const val CHANNEL_TRIGGER_ID  = "wake_trigger"
        const val WAKE_TRIGGER_NOTIF_ID       = 102
        private const val WAKE_TRIGGER_REQUEST = 200

        private const val SAMPLE_RATE       = 16000
        private const val SILENCE_RMS       = 400f
        private const val SILENCE_AFTER_MS  = 1_000L   // shorter than commands — wake word is brief
        private const val MAX_RECORD_MS     = 5_000L   // max clip per attempt
        private const val COMMAND_TIMEOUT_MS = 20_000L // safety reset if car screen hangs

        /**
         * True while [FreeSpeechCarScreen] owns the microphone.
         * Written from multiple threads — always read/write with this flag volatile.
         */
        @Volatile var commandInProgress = false

        /**
         * Set by [FreeSpeechCarScreen.onStart]; cleared on [FreeSpeechCarScreen.onStop].
         * Invoked on the detection background thread when the wake word fires.
         */
        @Volatile var wakeListener: (() -> Unit)? = null

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, WakeWordService::class.java))
    }

    @Volatile private var running = false
    private var detectThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createTriggerChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tapping "Stop" in the persistent notification sends ACTION_STOP.
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Mirror the change back to SharedPreferences so the Settings checkbox is in sync.
            getSharedPreferences("freespeech", MODE_PRIVATE)
                .edit().putBoolean("wake_enabled", false).apply()
            return START_NOT_STICKY
        }

        val prefs    = getSharedPreferences("freespeech", MODE_PRIVATE)
        val wakeWord = prefs.getString("wake_word", "")
            ?.trim()?.ifBlank { getString(R.string.settings_wake_word_hint) }
            ?: getString(R.string.settings_wake_word_hint)

        running = true
        startForeground(NOTIF_ID, buildNotification(wakeWord))
        detectThread = Thread { detectLoop(wakeWord) }.also { it.start() }
        return START_STICKY   // re-create after process kill
    }

    override fun onDestroy() {
        running = false
        detectThread?.interrupt()
        detectThread = null
    }

    // ── Detection loop ─────────────────────────────────────────────────────────

    private fun detectLoop(wakeWord: String) {
        Log.i(TAG, "Started — listening for \"$wakeWord\"")
        while (running) {
            try {
                if (commandInProgress) { Thread.sleep(100); continue }

                val samples = recordUtterance()
                if (samples == null)              { Thread.sleep(500); continue }
                if (samples.isEmpty() || commandInProgress) continue

                val wav = AudioUtils.buildWav(samples, SAMPLE_RATE)
                val result = try {
                    AudioUtils.sendToWhisper(wav, this)
                } catch (e: Exception) {
                    Log.d(TAG, "Whisper check failed (server down?): ${e.message}")
                    Thread.sleep(2_000)   // back off before retrying
                    continue
                }

                Log.d(TAG, "Heard: \"${result.transcript}\"")

                if (result.transcript.contains(wakeWord, ignoreCase = true)) {
                    Log.i(TAG, "Wake word matched!")
                    val listener = wakeListener
                    if (listener != null) {
                        commandInProgress = true
                        listener()          // → FreeSpeechCarScreen.startTranscription()
                        // Yield until the car screen clears the flag, with a safety timeout.
                        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
                        while (commandInProgress
                            && System.currentTimeMillis() < deadline
                            && running) {
                            Thread.sleep(100)
                        }
                        commandInProgress = false
                    } else {
                        // Car screen not visible — post a HUN on the car screen so the driver
                        // can tap to bring FreeSpeech to the foreground with one gesture.
                        Log.i(TAG, "Wake word fired; car screen not visible — posting trigger notification")
                        postTriggerNotification()
                    }
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Loop error: ${e.message}")
                Thread.sleep(1_000)
            }
        }
        Log.i(TAG, "Detection loop exited")
    }

    // ── VAD recording ──────────────────────────────────────────────────────────

    /**
     * Records until VAD detects end-of-speech, [MAX_RECORD_MS] is reached,
     * or [commandInProgress] / [running] is cleared by another component.
     *
     * @return null on AudioRecord init failure, empty list when no speech was
     *         detected (silence / ambient noise only), samples otherwise.
     */
    private fun recordUtterance(): List<Short>? {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return null }

        ar.startRecording()
        val samples      = mutableListOf<Short>()
        val buf          = ShortArray(bufSize / 2)
        val start        = System.currentTimeMillis()
        var hasSpeech    = false
        var silenceStart = -1L

        try {
            while (running && !commandInProgress) {
                if (System.currentTimeMillis() - start > MAX_RECORD_MS) break
                val read = ar.read(buf, 0, buf.size)
                if (read <= 0) continue
                samples.addAll(buf.take(read))
                val rms = Math.sqrt(
                    buf.take(read).sumOf { it.toLong() * it }.toDouble() / read
                ).toFloat()
                if (rms >= SILENCE_RMS) {
                    hasSpeech    = true
                    silenceStart = -1L
                } else if (hasSpeech) {
                    val now = System.currentTimeMillis()
                    if (silenceStart < 0) silenceStart = now
                    else if (now - silenceStart >= SILENCE_AFTER_MS) break
                }
            }
        } finally {
            ar.stop()
            ar.release()
        }
        return if (hasSpeech) samples else emptyList()
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(wakeWord: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text, wakeWord))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.wake_notification_stop), stopPi)
            .build()
    }

    // ── Trigger notification (wake word fired, car screen not visible) ──────────

    private fun createTriggerChannel() {
        val ch = NotificationChannel(
            CHANNEL_TRIGGER_ID,
            getString(R.string.wake_trigger_channel),
            NotificationManager.IMPORTANCE_HIGH,   // HIGH = HUN on car screen
        ).apply {
            description = getString(R.string.wake_trigger_channel)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * Posts a heads-up notification on the car screen when the wake word fires but
     * [FreeSpeechCarScreen] is not currently visible.  Tapping the notification
     * (or the steering-wheel OK button) delivers the content [PendingIntent] to the
     * [FreeSpeechCarAppService], which the Car App Library routes as a new session
     * intent — bringing FreeSpeech to the car-screen foreground.
     *
     * [FreeSpeechCarScreen.onStart] cancels this notification once the screen is visible.
     */
    private fun postTriggerNotification() {
        // Intent that, when tapped on the car screen, resumes / surfaces the car app.
        val carAppIntent = Intent(this, FreeSpeechCarAppService::class.java)
        val contentPi = PendingIntent.getService(
            this, WAKE_TRIGGER_REQUEST, carAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_TRIGGER_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.wake_trigger_title))
            .setContentText(getString(R.string.wake_trigger_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(getString(R.string.wake_trigger_title))
                    .setContentText(getString(R.string.wake_trigger_text))
                    .setContentIntent(contentPi)
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .build()
            )
            .build()

        NotificationManagerCompat.from(this).notify(WAKE_TRIGGER_NOTIF_ID, notification)
    }
}
