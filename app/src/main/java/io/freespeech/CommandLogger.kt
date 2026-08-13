package io.freespeech

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget anonymous command logger.
 *
 * After each recognised command the app posts a small JSON record to the
 * server URL stored in [SharedPreferences] under "log_url".  The record
 * contains NO audio, NO personal data — only the transcribed text, the
 * category assigned to it, the Whisper-detected language, and (when AI was
 * used) the engine name.
 *
 * If "log_url" is empty or the server is unreachable the call is silently
 * dropped — it never blocks the main flow.
 *
 * Server-side counterpart: server/freespeech_log_server.py
 */
object CommandLogger {

    private const val TAG = "CommandLogger"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * @param transcript   Raw text from Whisper (what the user said)
     * @param category     Classified intent category
     * @param query        Extracted subject (artist, destination, …)
     * @param lang         BCP-47 tag from Whisper language detection ("de", "en", …)
     * @param aiEngine     Engine key if AI was used ("gemini", "openai", "ollama"),
     *                     null when the keyword classifier handled the command
     */
    fun post(
        context: Context,
        transcript: String,
        category: VoiceCategory,
        query: String,
        lang: String,
        aiEngine: String?,
    ) {
        val prefs  = context.getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        val logUrl = prefs.getString("log_url", "")?.trim()
        if (logUrl.isNullOrBlank()) return

        Thread {
            try {
                val payload = JSONObject().apply {
                    put("cmd",   transcript)
                    put("cat",   category.name)
                    put("query", query)
                    put("lang",  lang)
                    if (aiEngine != null) put("ai", aiEngine)
                }
                val req = Request.Builder()
                    .url(logUrl)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { /* fire-and-forget — ignore response body */ }
                Log.d(TAG, "Logged: $transcript → ${category.name}")
            } catch (e: Exception) {
                // Normal when no log server is running — keep it quiet.
                Log.d(TAG, "Log post skipped: ${e.message}")
            }
        }.start()
    }
}
