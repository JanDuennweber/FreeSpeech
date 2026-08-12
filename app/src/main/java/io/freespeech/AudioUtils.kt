package io.freespeech

import android.content.Context
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

object AudioUtils {

    fun buildWav(samples: List<Short>, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)             // chunk size
            putShort(1)            // PCM
            putShort(1)            // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate
            putShort(2)            // block align
            putShort(16)           // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
        }
        out.write(header.array())
        val sampleBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sampleBytes.putShort(it) }
        out.write(sampleBytes.array())
        return out.toByteArray()
    }

    fun sendToWhisper(wav: ByteArray, context: Context): String {
        val prefs = context.getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        val url = prefs.getString(
            "whisper_url",
            "http://10.0.2.2:8080/v1/audio/transcriptions"
        )!!

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "de")
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Whisper HTTP ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Whisper response")
            return JSONObject(bodyStr).getString("text").trim()
        }
    }
}
