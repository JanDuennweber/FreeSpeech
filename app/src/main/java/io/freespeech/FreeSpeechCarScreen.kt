package io.freespeech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class FreeSpeechCarScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "FreeSpeechCar"
        private const val SAMPLE_RATE = 16000

        /** Stop recording after this many ms of silence following detected speech. */
        private const val SILENCE_AFTER_SPEECH_MS = 1500L

        /** RMS below this counts as silence (PCM 16-bit range 0–32767). */
        private const val SILENCE_RMS_THRESHOLD = 400f

        /** Absolute recording ceiling, even without silence detection. */
        private const val MAX_RECORD_MS = 12_000L

        /** How long the transcript is shown before the screen resets to ready. */
        private const val RESULT_DISPLAY_MS = 4_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var status = "Wie kann ich helfen?"
    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null

    init {
        // Auto-start recording when the screen becomes visible; stop when it leaves focus.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startTranscription()
            override fun onStop(owner: LifecycleOwner) = cleanup()
        })
    }

    // ── Template ──────────────────────────────────────────────────────────────

    override fun onGetTemplate(): Template {
        val micAction = Action.Builder()
            .setTitle(if (isRecording) "Aufnahme läuft…" else "Erneut")
            .setOnClickListener { if (!isRecording) startTranscription() }
            .build()

        return MessageTemplate.Builder(status)
            .setTitle("FreeSpeech")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(micAction).build())
            .build()
    }

    // ── Core flow ──────────────────────────────────────────────────────────────

    private fun startTranscription() {
        if (isRecording) return
        Thread {
            try {
                setStatus("Zuhören…")
                val samples = recordWithVad()
                if (samples.isEmpty()) {
                    setStatus("Mikrofon nicht verfügbar.\nBitte \"Erneut\" tippen.")
                    return@Thread
                }

                setStatus("Verarbeitung…")
                val wav = AudioUtils.buildWav(samples, SAMPLE_RATE)
                // Use applicationContext so SharedPreferences resolve correctly inside CarContext.
                val appContext = carContext.applicationContext
                val transcript = AudioUtils.sendToWhisper(wav, appContext)
                Log.i(TAG, "Transcript: $transcript")

                if (transcript.isBlank()) {
                    setStatus("(nichts erkannt)")
                } else {
                    val prefs      = appContext.getSharedPreferences("freespeech", android.content.Context.MODE_PRIVATE)
                    val classified = IntentRouter.classify(transcript, prefs, appContext)
                    val label      = IntentRouter.routingLabel(classified)
                    Log.i(TAG, "Category: ${classified.category}, query: ${classified.query}")
                    setStatus(label)

                    if (classified.category != VoiceCategory.NONE) {
                        val intent = IntentRouter.buildIntent(classified, prefs)
                        if (intent != null) {
                            try {
                                carContext.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Could not start activity for ${classified.category}", e)
                                setStatus("App nicht gefunden:\n${e.message}")
                            }
                        }
                    }
                }

                // Reset to ready state after displaying the result.
                mainHandler.postDelayed({
                    status = "Wie kann ich helfen?"
                    invalidate()
                }, RESULT_DISPLAY_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                setStatus("Fehler: ${e.message}\nBitte \"Erneut\" tippen.")
            }
        }.start()
    }

    // ── Audio recording with VAD ───────────────────────────────────────────────

    /**
     * Records from the phone mic until:
     * - speech has been detected AND 1.5 s of silence has elapsed, or
     * - MAX_RECORD_MS is reached.
     *
     * Returns an empty list if the AudioRecord fails to initialise.
     */
    private fun recordWithVad(): List<Short> {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            ar.release()
            return emptyList()
        }

        audioRecord = ar
        ar.startRecording()
        isRecording = true
        mainHandler.post { invalidate() } // refresh button label → "Aufnahme läuft…"

        val samples = mutableListOf<Short>()
        val buf = ShortArray(bufSize / 2)
        val recordingStart = System.currentTimeMillis()
        var hasSpeech = false
        var silenceStart = -1L

        while (isRecording) {
            if (System.currentTimeMillis() - recordingStart > MAX_RECORD_MS) break

            val read = ar.read(buf, 0, buf.size)
            if (read <= 0) continue

            samples.addAll(buf.take(read))

            val rms = Math.sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read).toFloat()
            if (rms >= SILENCE_RMS_THRESHOLD) {
                hasSpeech = true
                silenceStart = -1L
            } else if (hasSpeech) {
                val now = System.currentTimeMillis()
                if (silenceStart < 0) silenceStart = now
                else if (now - silenceStart >= SILENCE_AFTER_SPEECH_MS) break
            }
        }

        isRecording = false
        ar.stop()
        ar.release()
        audioRecord = null
        mainHandler.post { invalidate() } // refresh button label → "Erneut"
        return samples
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setStatus(msg: String) {
        status = msg
        mainHandler.post { invalidate() }
    }

    private fun cleanup() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
