package io.freespeech

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Classifies a voice transcript by calling the FreeSpeech Console's
 * server-side AI endpoint (`POST /api/classify`).
 *
 * The server chooses the AI engine based on the caller:
 *  - No token (anonymous) → system default, typically Ollama/Qwen
 *  - Bearer token of a registered user → that user's personal engine / key
 *
 * Returns null on any failure so [IntentRouter] can fall back to
 * the local keyword classifier.
 *
 * Enabled when `console_url` is set in SharedPreferences.
 */
class ServerAiClassifier(
    private val prefs: SharedPreferences,
    private val context: Context,
) {
    companion object {
        private const val TAG = "ServerAI"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * @return [ClassifiedIntent] on success, null on any network / parse error.
     */
    fun classify(transcript: String): ClassifiedIntent? = try {
        val consoleUrl = prefs.getString("console_url", "").orEmpty().trimEnd('/')
        val userToken  = prefs.getString("user_token",  "").orEmpty().trim()

        val body = JSONObject()
            .put("transcript", transcript)
            .put("lang",       "")   // server uses whatever the AI returns
            .put("app_labels", buildAppLabels())

        val reqBuilder = Request.Builder()
            .url("$consoleUrl/api/classify")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (userToken.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $userToken")
        }

        http.newCall(reqBuilder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: throw Exception("empty body")
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${text.take(200)}")
            parseResponse(text)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Server AI failed (${e.message}) — falling back to keyword classifier")
        null
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Build a map of category → installed app label for the prompt. */
    private fun buildAppLabels(): JSONObject {
        val obj = JSONObject()
        listOf(
            VoiceCategory.MUSIC,
            VoiceCategory.WEATHER,
            VoiceCategory.NAVIGATION,
            VoiceCategory.CALL,
            VoiceCategory.WEB_SEARCH,
        ).forEach { cat ->
            val pkg = prefs.getString(cat.prefKey, null)?.takeIf { it.isNotBlank() }
            obj.put(cat.name, pkg?.let { appLabel(it) } ?: "System default")
        }
        return obj
    }

    private fun appLabel(pkg: String): String = try {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
            .toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    private fun parseResponse(json: String): ClassifiedIntent {
        val obj          = JSONObject(json)
        val catName      = obj.optString("category", "NONE").uppercase()
        val query        = obj.optString("query",    "")
        val message      = obj.optString("message",  "").ifBlank { null }
        // routing_chain is present for CUSTOM (app/web) and RAG responses; null otherwise.
        val routingChain = obj.optString("routing_chain", "").ifBlank { null }

        // Custom topic — server ran the pong transform and returns the full routing info.
        if (catName == "CUSTOM") {
            // Parse search_urls array (web-search target type)
            val urlsArr    = obj.optJSONArray("search_urls")
            val searchUrls = urlsArr?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }?.takeIf { it.isNotEmpty() }

            return ClassifiedIntent(
                category       = VoiceCategory.CUSTOM,
                query          = query,
                aiMessage      = message,
                uriTemplate    = obj.optString("uri_template",    "").ifBlank { null },
                androidPackage = obj.optString("android_package", "").ifBlank { null },
                appLabel       = obj.optString("app_label",       "").ifBlank { null },
                searchUrls     = searchUrls,
                routingChain   = routingChain,
            )
        }

        val category = try { VoiceCategory.valueOf(catName) }
                       catch (_: IllegalArgumentException) { VoiceCategory.NONE }
        // For NONE (including RAG responses) pass routing_chain so History shows the chain.
        return ClassifiedIntent(category, query, message, routingChain = routingChain)
    }
}
