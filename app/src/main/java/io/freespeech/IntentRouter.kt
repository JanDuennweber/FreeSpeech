package io.freespeech

import android.app.SearchManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore

// ── Category ───────────────────────────────────────────────────────────────────

enum class VoiceCategory(
    /** SharedPreferences key that stores the chosen package name for this category. */
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

data class ClassifiedIntent(val category: VoiceCategory, val query: String)

// ── Router ─────────────────────────────────────────────────────────────────────

object IntentRouter {

    // Keyword lists cover both German and English — Whisper may return either.
    private val callKeywords = listOf(
        "ruf an", "rufe an", "anrufen", "anruf an", "telefonier", "call",
    )
    private val musicKeywords = listOf(
        "spiele", "spiel", "höre", "play", "musik spielen", "music",
    )
    private val navKeywords = listOf(
        "navigiere nach", "navigier nach", "fahre nach", "fahre zu", "route nach",
        "weg zu", "wie komme ich nach", "navigate to", "drive to", "directions to",
        "take me to",
    )
    private val weatherKeywords = listOf(
        "wetter", "regen", "temperatur", "grad", "scheint", "schneit", "bewölkt",
        "vorhersage", "wind", "weather", "rain", "temperature", "forecast",
    )

    // ── classify ───────────────────────────────────────────────────────────────

    fun classify(transcript: String): ClassifiedIntent {
        val lower = transcript.trim().lowercase()

        // Order matters: call before music (both may contain a person's name).
        for (kw in callKeywords)   if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.CALL,      extractAfter(lower, kw).ifBlank { transcript })
        for (kw in musicKeywords)  if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.MUSIC,     extractAfter(lower, kw).ifBlank { transcript })
        for (kw in navKeywords)    if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.NAVIGATION, extractAfter(lower, kw).ifBlank { transcript })
        for (kw in weatherKeywords) if (lower.contains(kw)) return ClassifiedIntent(VoiceCategory.WEATHER, transcript)

        return ClassifiedIntent(VoiceCategory.WEB_SEARCH, transcript)
    }

    /** Returns the substring that follows [keyword] in [text], trimmed. */
    private fun extractAfter(text: String, keyword: String): String {
        val idx = text.indexOf(keyword)
        if (idx < 0) return ""
        return text.substring(idx + keyword.length).trimStart(' ', ',', ':', '"', '\'')
    }

    // ── buildIntent ────────────────────────────────────────────────────────────

    /**
     * Builds an Android intent for the classified command.
     * Returns null only for NONE (shouldn't occur in practice).
     */
    fun buildIntent(classified: ClassifiedIntent, prefs: SharedPreferences): Intent? {
        val pkg = prefs.getString(classified.category.prefKey, null)?.takeIf { it.isNotBlank() }

        return when (classified.category) {

            VoiceCategory.MUSIC ->
                // Standard Android intent: all major music apps declare an intent-filter for it.
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, classified.query)
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            VoiceCategory.WEATHER ->
                // No universal search intent for weather — open the app's main screen.
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
                // geo: URI is the universal nav intent; all nav apps declare an intent-filter.
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(classified.query)}")).apply {
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            VoiceCategory.CALL ->
                // Open the dialer — Android Auto routes this to the car's phone UI.
                Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            VoiceCategory.WEB_SEARCH, VoiceCategory.NONE ->
                browserIntent(classified.query, prefs)
        }
    }

    /** Human-readable confirmation label to show on screen before routing. */
    fun routingLabel(classified: ClassifiedIntent): String = when (classified.category) {
        VoiceCategory.MUSIC      -> "▶  ${classified.query}"
        VoiceCategory.NAVIGATION -> "🗺  ${classified.query}"
        VoiceCategory.WEATHER    -> "☁  Wetter wird geöffnet…"
        VoiceCategory.CALL       -> "📞  Wähle…"
        VoiceCategory.WEB_SEARCH -> "🔍  ${classified.query}"
        VoiceCategory.NONE       -> classified.query
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
