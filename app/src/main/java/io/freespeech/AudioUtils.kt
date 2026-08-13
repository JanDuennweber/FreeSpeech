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

    /**
     * Pair of transcript text + the language Whisper detected in the audio.
     * [language] is a BCP-47 tag ("de", "en", "fr", …) or "?" when the server
     * did not return language info (e.g. it speaks only the basic JSON format).
     */
    data class WhisperResult(val transcript: String, val language: String)

    /** Whisper's lowercase English language names → BCP-47 tags. */
    private val WHISPER_LANG_MAP = mapOf(
        "german"     to "de", "english"    to "en", "french"    to "fr",
        "spanish"    to "es", "italian"    to "it", "czech"     to "cs",
        "portuguese" to "pt", "dutch"      to "nl", "polish"    to "pl",
        "russian"    to "ru", "chinese"    to "zh", "japanese"  to "ja",
        "korean"     to "ko", "arabic"     to "ar", "turkish"   to "tr",
        "swedish"    to "sv", "norwegian"  to "no", "danish"    to "da",
        "finnish"    to "fi", "hungarian"  to "hu", "romanian"  to "ro",
        "ukrainian"  to "uk", "greek"      to "el", "hebrew"    to "he",
    )

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

    /**
     * Sends [wav] to the configured Whisper endpoint and returns the transcript
     * together with the language Whisper detected.
     *
     * We request [verbose_json] so the server also tells us *which language* it
     * heard — useful for the anonymous command log. No language hint is sent so
     * Whisper auto-detects; this works correctly for all supported app locales.
     * Falls back gracefully if the server returns plain JSON without a language field.
     */
    fun sendToWhisper(wav: ByteArray, context: Context): WhisperResult {
        val prefs = context.getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        val url = prefs.getString(
            "whisper_url",
            "http://10.0.2.2:8080/v1/audio/transcriptions"
        )!!

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            // No hardcoded language= param — let Whisper auto-detect from the audio.
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Whisper HTTP ${response.code}")
            val bodyStr = response.body?.string() ?: throw Exception("Empty Whisper response")
            val json       = JSONObject(bodyStr)
            val transcript = json.getString("text").trim()
            val langName   = json.optString("language", "")           // e.g. "german"
            val langTag    = WHISPER_LANG_MAP[langName.lowercase()]
                ?: if (langName.isNotBlank()) langName.take(2).lowercase() else "?"
            return WhisperResult(transcript, langTag)
        }
    }
}
