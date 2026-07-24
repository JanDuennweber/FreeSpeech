package io.freespeech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

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
                    val sumSq = buf.take(read).sumOf { it.toLong() * it }
                    val rms = Math.sqrt(sumSq.toDouble() / read).toFloat()
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
                val wav = buildWav(samples, SAMPLE_RATE)
                val transcript = sendToWhisper(wav)
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

    private fun buildWav(samples: List<Short>, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)            // chunk size
            putShort(1)           // PCM
            putShort(1)           // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate
            putShort(2)           // block align
            putShort(16)          // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }
        out.write(header.array())
        val sampleBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sampleBytes.putShort(it) }
        out.write(sampleBytes.array())
        return out.toByteArray()
    }

    private fun sendToWhisper(wav: ByteArray): String {
        val prefs = getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        val url = prefs.getString(
            "whisper_url",
            "http://10.0.2.2:8080/v1/audio/transcriptions"  // 10.0.2.2 = host machine from emulator
        )!!

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "de")  // change to your language or remove for auto-detect
            .build()

        val request = Request.Builder().url(url).post(body).build()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Whisper HTTP ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Whisper response")
            return JSONObject(bodyStr).getString("text").trim()
        }
    }
}
