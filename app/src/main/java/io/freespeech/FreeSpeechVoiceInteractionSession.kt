package io.freespeech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.widget.TextView

class FreeSpeechVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "FreeSpeechSession"
        private const val SAMPLE_RATE = 16000

        /** Stop recording after this many ms of silence following detected speech. */
        private const val SILENCE_AFTER_SPEECH_MS = 1500L

        /** RMS below this value counts as silence (PCM 16-bit range 0–32767). */
        private const val SILENCE_RMS_THRESHOLD = 400f

        /** Absolute maximum recording duration, even without silence detection. */
        private const val MAX_RECORD_MS = 12_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView

    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateContentView(): View {
        val view = layoutInflater.inflate(R.layout.session_overlay, null)
        statusText = view.findViewById(R.id.status_text)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setStatus("Zuhören…")
        Thread(::runTranscription).start()
    }

    override fun onHide() {
        super.onHide()
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ── Core flow ──────────────────────────────────────────────────────────────

    private fun runTranscription() {
        try {
            val samples = recordWithVad()
            if (samples.isEmpty()) { hide(); return }

            setStatus("Verarbeitung…")
            val wav = AudioUtils.buildWav(samples, SAMPLE_RATE)
            val transcript = AudioUtils.sendToWhisper(wav, context)
            Log.i(TAG, "Transcript: $transcript")

            if (transcript.isBlank()) {
                setStatus("(nichts erkannt)")
                mainHandler.postDelayed({ hide() }, 2_000)
            } else {
                // Classify and show routing label before hiding.
                val prefs      = context.getSharedPreferences("freespeech", android.content.Context.MODE_PRIVATE)
                val classified = IntentRouter.classify(transcript)
                val label      = IntentRouter.routingLabel(classified)
                setStatus(label)
                Log.i(TAG, "Category: ${classified.category}, query: ${classified.query}")

                val intent = IntentRouter.buildIntent(classified, prefs)
                if (intent != null) {
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not start activity for ${classified.category}", e)
                        setStatus("App nicht gefunden:\n${e.message}")
                    }
                }
                mainHandler.postDelayed({ hide() }, 2_500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            setStatus("Fehler: ${e.message}")
            mainHandler.postDelayed({ hide() }, 2_000)
        }
    }

    // ── Audio recording with VAD ───────────────────────────────────────────────

    /**
     * Records audio until:
     * - speech has been detected AND then 1.5 s of silence has elapsed, OR
     * - MAX_RECORD_MS is reached.
     *
     * Returns an empty list on initialisation failure.
     */
    private fun recordWithVad(): List<Short> {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            return emptyList()
        }

        audioRecord?.startRecording()
        isRecording = true

        val samples = mutableListOf<Short>()
        val buf = ShortArray(bufSize / 2)
        val recordingStart = System.currentTimeMillis()
        var hasSpeech = false
        var silenceStart = -1L

        while (isRecording) {
            if (System.currentTimeMillis() - recordingStart > MAX_RECORD_MS) break

            val read = audioRecord?.read(buf, 0, buf.size) ?: break
            if (read <= 0) continue

            samples.addAll(buf.take(read))

            val rms = Math.sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read).toFloat()

            if (rms >= SILENCE_RMS_THRESHOLD) {
                hasSpeech = true
                silenceStart = -1L
            } else if (hasSpeech) {
                val now = System.currentTimeMillis()
                if (silenceStart < 0L) silenceStart = now
                else if (now - silenceStart >= SILENCE_AFTER_SPEECH_MS) break
            }
        }

        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return samples
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setStatus(msg: String) = mainHandler.post { statusText.text = msg }
}
