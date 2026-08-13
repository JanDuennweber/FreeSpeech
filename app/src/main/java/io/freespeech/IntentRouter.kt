package io.freespeech

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore

// ── Category ───────────────────────────────────────────────────────────────────

enum class VoiceCategory(
    /** SharedPreferences key storing the chosen package name for this category. */
    val prefKey: String,
    val displayName: String,
) {
    MUSIC     ("app_music",      "Musik"),
    WEATHER   ("app_weather",    "Wetter"),
    NAVIGATION("app_navigation", "Navigation"),
    CALL      ("app_call",       "Anruf"),
    WEB_SEARCH("app_websearch",  "Websuche"),
    NONE      ("",               ""),
}

/**
 * The result of classifying a transcript.
 *
 * @param aiMessage  When the AI classifier was used, it writes its own short confirmation
 *                   label in the user's language (e.g. "Spiele David Bowie mit Tidal").
 *                   When keyword matching was used, this is null and [routingLabel] falls
 *                   back to a generic label built from the category + query.
 */
data class ClassifiedIntent(
    val category:  VoiceCategory,
    val query:     String,
    val aiMessage: String? = null,
)

// ── Router ─────────────────────────────────────────────────────────────────────

object IntentRouter {

    // ── classify ───────────────────────────────────────────────────────────────

    /**
     * Classifies [transcript] into a [ClassifiedIntent].
     *
     * Strategy (in order):
     * 1. If "use_ai" is enabled in [prefs], try [AiClassifier]. On any failure
     *    (network, quota, timeout, bad JSON) it returns null and we fall through.
     * 2. Keyword matching — fast, fully offline, never fails.
     */
    fun classify(transcript: String, prefs: SharedPreferences, context: Context): ClassifiedIntent {
        if (prefs.getBoolean("use_ai", false)) {
            AiClassifier(prefs, context).classify(transcript)?.let { return it }
            // AI failed — fall through to keywords silently
        }
        return keywordClassify(transcript)
    }

    // ── keyword classifier ─────────────────────────────────────────────────────

    // Keyword lists cover both German and English — Whisper may return either.
    private val callKeywords = listOf(
        "ruf an", "rufe an", "anrufen", "anruf an", "telefonier", "call",
    )
    private val musicKeywords = listOf(
        "spiele", "spiel", "höre", "play", "listen to",
    )
    private val navKeywords = listOf(
        "navigiere nach", "navigier nach", "fahre nach", "fahre zu",
        "route nach", "weg zu", "wie komme ich nach",
        "navigate to", "drive to", "directions to", "take me to",
    )
    private val weatherKeywords = listOf(
        "wetter", "regen", "temperatur", "grad", "scheint", "schneit",
        "bewölkt", "vorhersage", "wind",
        "weather", "rain", "temperature", "forecast",
    )

    private fun keywordClassify(transcript: String): ClassifiedIntent {
        val t     = transcript.trim()
        val lower = t.lowercase()

        // Order matters: CALL before MUSIC (both may contain a person's name).
        for (kw in callKeywords)    if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.CALL,      extractAfter(lower, kw).ifBlank { t })
        for (kw in musicKeywords)   if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.MUSIC,     extractAfter(lower, kw).ifBlank { t })
        for (kw in navKeywords)     if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.NAVIGATION, extractAfter(lower, kw).ifBlank { t })
        for (kw in weatherKeywords) if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.WEATHER,   t)

        // Unknown — return NONE with a helpful not-understood message.
        return ClassifiedIntent(
            VoiceCategory.NONE,
            "",
            "Befehl nicht erkannt. Versuche: \"spiel Musik\", \"fahre nach Berlin\", \"ruf Anna an\". Oder aktiviere KI in den Einstellungen.",
        )
    }

    /** Returns the text that follows [keyword] in [text], trimmed of leading punctuation. */
    private fun extractAfter(text: String, keyword: String): String {
        val idx = text.indexOf(keyword)
        if (idx < 0) return ""
        return text.substring(idx + keyword.length).trimStart(' ', ',', ':', '"', '\'')
    }

    // ── buildIntent ────────────────────────────────────────────────────────────

    /**
     * Builds the Android intent for a classified command.
     * Returns null for [VoiceCategory.NONE] — the caller should display the
     * [ClassifiedIntent.aiMessage] without launching anything.
     */
    fun buildIntent(classified: ClassifiedIntent, prefs: SharedPreferences): Intent? {
        val pkg = prefs.getString(classified.category.prefKey, null)?.takeIf { it.isNotBlank() }

        return when (classified.category) {

            VoiceCategory.MUSIC ->
                // Standard Android intent — all major music apps declare a matching filter.
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, classified.query)
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            VoiceCategory.WEATHER ->
                if (pkg != null) {
                    Intent(Intent.ACTION_MAIN).apply {
                        setPackage(pkg)
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    browserIntent("wetter ${classified.query}", prefs)
                }

            VoiceCategory.NAVIGATION ->
                // geo: URI is the universal navigation intent.
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(classified.query)}")).apply {
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            VoiceCategory.CALL ->
                // Open the dialer; on Android Auto this routes to the car's phone UI.
                Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            VoiceCategory.WEB_SEARCH ->
                browserIntent(classified.query, prefs)

            VoiceCategory.NONE -> null  // not understood — caller shows message only
        }
    }

    // ── routingLabel ───────────────────────────────────────────────────────────

    /**
     * Short human-readable status to show on screen before launching.
     * Prefers the AI's own wording when available.
     */
    fun routingLabel(classified: ClassifiedIntent): String =
        classified.aiMessage ?: when (classified.category) {
            VoiceCategory.MUSIC      -> "Musik: ${classified.query}"
            VoiceCategory.NAVIGATION -> "Navigation: ${classified.query}"
            VoiceCategory.WEATHER    -> "Wetter wird geoeffnet…"
            VoiceCategory.CALL       -> "Waehle…"
            VoiceCategory.WEB_SEARCH -> "Suche: ${classified.query}"
            VoiceCategory.NONE       -> "Befehl nicht erkannt."
        }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun browserIntent(query: String, prefs: SharedPreferences): Intent {
        val pkg    = prefs.getString(VoiceCategory.WEB_SEARCH.prefKey, null)?.takeIf { it.isNotBlank() }
        val engine = prefs.getString("search_engine", "https://duckduckgo.com/?q=")!!
        return Intent(Intent.ACTION_VIEW, Uri.parse("$engine${Uri.encode(query)}")).apply {
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
