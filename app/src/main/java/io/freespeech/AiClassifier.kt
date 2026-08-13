package io.freespeech

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import io.freespeech.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls a remote or local LLM (via the OpenAI chat completions API) to classify
 * a voice transcript into a [VoiceCategory] and extract the query string.
 *
 * Supported backends (all speak the same OpenAI-compatible JSON format):
 *  - Gemini   via https://generativelanguage.googleapis.com/v1beta/openai
 *  - OpenAI   via https://api.openai.com/v1
 *  - Ollama   via http://<host>:11434/v1  (local, fully private)
 *
 * [classify] returns null on any failure so the caller can fall back to
 * the simpler keyword classifier.
 */
class AiClassifier(
    private val prefs: SharedPreferences,
    private val context: Context,
) {

    companion object {
        private const val TAG = "AiClassifier"

        const val ENGINE_GEMINI = "gemini"
        const val ENGINE_OPENAI = "openai"
        const val ENGINE_OLLAMA = "ollama"

        data class Defaults(val baseUrl: String, val model: String, val apiKey: String)

        /** Sensible defaults per engine — shown in the settings screen. */
        val DEFAULTS: Map<String, Defaults> = mapOf(
            ENGINE_GEMINI to Defaults(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                model   = "gemini-2.0-flash-lite",
                apiKey  = "",   // free API key from ai.google.dev — no credit card required
            ),
            ENGINE_OPENAI to Defaults(
                baseUrl = "https://api.openai.com/v1",
                model   = "gpt-4o-mini",
                apiKey  = "",
            ),
            ENGINE_OLLAMA to Defaults(
                baseUrl = "http://localhost:11434/v1",
                model   = "qwen2.5:7b",
                apiKey  = "ollama",  // Ollama accepts any non-empty value
            ),
        )

        /**
         * Ordered engine keys — UI labels are string resources, not hardcoded here,
         * so the spinner in SettingsActivity can use the phone's locale.
         */
        val ENGINE_KEYS = listOf(ENGINE_GEMINI, ENGINE_OPENAI, ENGINE_OLLAMA)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Classifies [transcript] using the configured AI.
     * Returns null on any failure — caller should fall through to keyword matching.
     */
    fun classify(transcript: String): ClassifiedIntent? = try {
        val engine  = prefs.getString("ai_engine",   ENGINE_GEMINI)!!
        val def     = DEFAULTS[engine] ?: return null
        val baseUrl = prefs.getString("ai_base_url", def.baseUrl)!!.let { if (it.isBlank()) def.baseUrl else it }.trimEnd('/')
        val apiKey  = prefs.getString("ai_api_key",  def.apiKey)!!.let { if (it.isBlank()) def.apiKey else it }
        val model   = prefs.getString("ai_model",    def.model)!!.let  { if (it.isBlank()) def.model else it }

        val responseJson = callChatApi(baseUrl, apiKey, model, buildPrompt(transcript))
        parseResponse(responseJson)
    } catch (e: Exception) {
        Log.w(TAG, "AI classification failed: ${e.message}")
        null
    }

    // ── Prompt ─────────────────────────────────────────────────────────────────

    private fun buildPrompt(transcript: String): String {
        val categoryBlock = listOf(
            VoiceCategory.MUSIC,
            VoiceCategory.WEATHER,
            VoiceCategory.NAVIGATION,
            VoiceCategory.CALL,
            VoiceCategory.WEB_SEARCH,
        ).joinToString("\n") { cat ->
            "- ${cat.name} (${cat.displayName}): ${appLabel(cat)}"
        }

        return """
You are an intent classifier for a voice assistant built into a car.
The user's speech was transcribed by Whisper and may contain recognition errors.

Available categories with their configured apps:
$categoryBlock
- NONE: command not understood

User said: "$transcript"

Reply with exactly one JSON object and nothing else — no markdown, no explanation:
{"category":"MUSIC","query":"David Bowie","message":"Spiele David Bowie"}

Rules:
- category  one of MUSIC, WEATHER, NAVIGATION, CALL, WEB_SEARCH, NONE
- query     extracted subject (artist, destination, contact, search term); empty for WEATHER/CALL
- message   confirmation in the user's language, max 8 words; if NONE, say in one sentence what kind of command to use instead
        """.trimIndent()
    }

    /** Returns the human-readable app name configured for [cat], or the localised "System default". */
    private fun appLabel(cat: VoiceCategory): String {
        val pkg = prefs.getString(cat.prefKey, null)?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.app_option_system_default)
        return try {
            context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                .toString()
        } catch (_: PackageManager.NameNotFoundException) { pkg }
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private fun callChatApi(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0)
            .put("max_tokens", 120)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: throw Exception("Empty response")
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${text.take(200)}")
            return text
        }
    }

    // ── Parse ──────────────────────────────────────────────────────────────────

    private fun parseResponse(apiJson: String): ClassifiedIntent? {
        // Unwrap the chat completions envelope → get the model's text output.
        val text = JSONObject(apiJson)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .stripFences()

        Log.d(TAG, "AI reply: $text")

        val obj      = JSONObject(text)
        val catName  = obj.optString("category", "NONE").uppercase()
        val query    = obj.optString("query",    "")
        val message  = obj.optString("message",  "")

        val category = try { VoiceCategory.valueOf(catName) }
                       catch (_: IllegalArgumentException) { VoiceCategory.NONE }

        return ClassifiedIntent(category, query, message.ifBlank { null })
    }

    /** Strip any markdown code fences a model might add despite instructions. */
    private fun String.stripFences() =
        removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
