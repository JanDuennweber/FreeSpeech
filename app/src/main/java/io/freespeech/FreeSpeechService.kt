package io.freespeech

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

class FreeSpeechService : RecognitionService() {

    companion object {
        private const val TAG = "FreeSpeech"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private val audioBuffer = mutableListOf<Short>()

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        Log.i(TAG, "onStartListening")
        audioBuffer.clear()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            listener.error(SpeechRecognizer.ERROR_AUDIO)
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()

        Thread {
            val buf = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read > 0) {
                    synchronized(audioBuffer) { audioBuffer.addAll(buf.take(read)) }
                    val rms = Math.sqrt(buf.take(read).sumOf { it.toLong() * it }.toDouble() / read).toFloat()
                    listener.rmsChanged(rms)
                }
            }
        }.start()
    }

    override fun onStopListening(listener: Callback) {
        Log.i(TAG, "onStopListening")
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        listener.endOfSpeech()

        val samples: List<Short>
        synchronized(audioBuffer) { samples = audioBuffer.toList() }

        Thread {
            try {
                val wav = AudioUtils.buildWav(samples, SAMPLE_RATE)
                val transcript = AudioUtils.sendToWhisper(wav, this)
                Log.i(TAG, "Transcript: $transcript")
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(transcript)
                    )
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
                }
                listener.results(results)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                listener.error(SpeechRecognizer.ERROR_NETWORK)
            }
        }.start()
    }

    override fun onCancel(listener: Callback) {
        Log.i(TAG, "onCancel")
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
