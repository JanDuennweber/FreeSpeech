package io.freespeech

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget anonymous command logger.
 *
 * Posts a multipart request to the server URL in SharedPreferences "log_url":
 *   • "meta"  — JSON field: cmd / cat / query / lang / ai?
 *   • "audio" — WAV file (optional; omitted when null)
 *
 * The server stores the JSON in a rolling 1 000-entry JSONL file and keeps
 * the last 100 WAV files for audio analysis.
 *
 * No personal data: no IP addresses, no user IDs, no location.
 * Fails silently if the server is unreachable.
 *
 * Server-side counterpart: server/freespeech_log_server.py
 */
object CommandLogger {

    private const val TAG = "CommandLogger"

    // Longer read timeout than the fire-and-forget JSON post because we may be
    // uploading a WAV file (typically 100–400 KB over the local network).
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * @param transcript  Whisper transcript (what the user said)
     * @param category    Classified intent category
     * @param query       Extracted subject (artist, destination, …)
     * @param lang        BCP-47 language tag from Whisper ("de", "en", …)
     * @param aiEngine    Engine key if AI classification was used; null = keyword matching
     * @param wav         Raw WAV bytes to archive, or null to skip audio upload
     */
    fun post(
        context: Context,
        transcript: String,
        category: VoiceCategory,
        query: String,
        lang: String,
        aiEngine: String?,
        wav: ByteArray? = null,
    ) {
        val prefs  = context.getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        val logUrl = prefs.getString("log_url", "")?.trim()
        if (logUrl.isNullOrBlank()) return

        // Capture wav reference for the background thread.
        val wavSnapshot = wav

        Thread {
            try {
                val meta = JSONObject().apply {
                    put("cmd",   transcript)
                    put("cat",   category.name)
                    put("query", query)
                    put("lang",  lang)
                    if (aiEngine != null) put("ai", aiEngine)
                }

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("meta", meta.toString())
                    .also { builder ->
                        if (wavSnapshot != null) {
                            builder.addFormDataPart(
                                "audio", "command.wav",
                                wavSnapshot.toRequestBody("audio/wav".toMediaType()),
                            )
                        }
                    }
                    .build()

                val req = Request.Builder()
                    .url(logUrl)
                    .post(body)
                    .build()

                http.newCall(req).execute().use { /* fire-and-forget */ }
                Log.d(TAG, "Logged: $transcript → ${category.name}" +
                    if (wavSnapshot != null) " (+ ${wavSnapshot.size / 1024} KB audio)" else "")
            } catch (e: Exception) {
                Log.d(TAG, "Log post skipped: ${e.message}")
            }
        }.start()
    }
}
