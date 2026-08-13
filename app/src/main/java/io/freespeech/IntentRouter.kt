package io.freespeech

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import io.freespeech.R

// ── Category ───────────────────────────────────────────────────────────────────

enum class VoiceCategory(
    /** SharedPreferences key storing the chosen package name for this category. */
    val prefKey: String,
    /**
     * English label used in the AI prompt — intentionally not localised so the
     * prompt stays consistent regardless of the phone's UI language.
     * User-facing category names live in strings.xml (settings_music_label etc.).
     */
    val displayName: String,
) {
    MUSIC     ("app_music",      "Music"),
    WEATHER   ("app_weather",    "Weather"),
    NAVIGATION("app_navigation", "Navigation"),
    CALL      ("app_call",       "Call"),
    WEB_SEARCH("app_websearch",  "Web Search"),
    NONE      ("",               ""),
    /**
     * A custom topic-to-app mapping defined in the FreeSpeech Console.
     * The [ClassifiedIntent.uriTemplate], [ClassifiedIntent.androidPackage], and
     * [ClassifiedIntent.appLabel] fields carry the routing details.
     */
    CUSTOM    ("",               ""),
}

/**
 * The result of classifying a transcript.
 *
 * @param aiMessage      When the AI classifier was used, it writes its own short confirmation
 *                       label in the user's language (e.g. "Spiele David Bowie mit Tidal").
 *                       When keyword matching was used, this is null and [routingLabel] falls
 *                       back to a generic label built from the category + query.
 * @param uriTemplate    For [VoiceCategory.CUSTOM]: URI template with `{query}` placeholder,
 *                       e.g. `yelp://search?terms={query}`. Null for standard categories.
 * @param androidPackage For [VoiceCategory.CUSTOM]: preferred app package (optional). If set,
 *                       the intent is targeted at that app; falls back to any URI handler.
 * @param appLabel       For [VoiceCategory.CUSTOM]: human-readable app name for display.
 */
data class ClassifiedIntent(
    val category:       VoiceCategory,
    val query:          String,
    val aiMessage:      String? = null,
    // ── Custom topic fields (null for standard categories) ─────────────────
    /** URI template with {query} placeholder for app deep-link targets. */
    val uriTemplate:    String? = null,
    /** Preferred Android package (optional, falls back to any URI handler). */
    val androidPackage: String? = null,
    /** Human-readable app/site label for display. */
    val appLabel:       String? = null,
    /** For web-search targets: list of domains to restrict the search to (max 5). */
    val searchUrls:     List<String>? = null,
    /** Full ping-pong routing chain for logging: `"keyword" → Topic → Target` */
    val routingChain:   String? = null,
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
        val consoleUrl = prefs.getString("console_url", "").orEmpty().trim()

        if (prefs.getBoolean("use_ai", false)) {
            if (consoleUrl.isNotEmpty()) {
                // Server-side AI via FreeSpeech Console — uses the user's
                // personal engine/key, or Ollama as the server default.
                ServerAiClassifier(prefs, context).classify(transcript)?.let { return it }
                // Server unreachable — skip local AI and go straight to keywords.
            } else {
                // Local AI (classic behaviour, no console configured).
                AiClassifier(prefs, context).classify(transcript)?.let { return it }
            }
            // AI path failed — fall through to keyword matching silently.
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

        // Unknown — NONE; routingLabel will show the localised not-understood message.
        return ClassifiedIntent(VoiceCategory.NONE, "")
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

            VoiceCategory.CUSTOM -> {
                val searchUrls = classified.searchUrls
                if (!searchUrls.isNullOrEmpty()) {
                    // Web-search target: build a browser query restricted to the
                    // configured domains using site: operators.
                    // 1 domain:  "dad jokes site:punscorner.com"
                    // 2+ domains: "dad jokes (site:punscorner.com OR site:reddit.com/r/Jokes)"
                    val siteFilter = if (searchUrls.size == 1) {
                        "site:${searchUrls[0]}"
                    } else {
                        searchUrls.joinToString(" OR ") { "site:$it" }
                    }
                    browserIntent("${classified.query} $siteFilter", prefs)
                } else {
                    // App deep-link target: fill {query} in the URI template.
                    val template = classified.uriTemplate ?: return null
                    val uri = Uri.parse(template.replace("{query}", Uri.encode(classified.query)))
                    Intent(Intent.ACTION_VIEW, uri).apply {
                        classified.androidPackage?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
        }
    }

    // ── routingLabel ───────────────────────────────────────────────────────────

    /**
     * Short localised status to show on screen before launching.
     * Prefers the AI's own wording (already in the user's language) when available.
     * Falls back to a string-resource label so the phone locale is respected.
     */
    fun routingLabel(classified: ClassifiedIntent, context: Context): String =
        classified.aiMessage ?: when (classified.category) {
            VoiceCategory.MUSIC      -> context.getString(R.string.routing_music,      classified.query)
            VoiceCategory.NAVIGATION -> context.getString(R.string.routing_navigation, classified.query)
            VoiceCategory.WEATHER    -> context.getString(R.string.routing_weather)
            VoiceCategory.CALL       -> context.getString(R.string.routing_call)
            VoiceCategory.WEB_SEARCH -> context.getString(R.string.routing_websearch,  classified.query)
            VoiceCategory.NONE       -> context.getString(R.string.routing_not_understood_suggestion)
            // Custom topic: server always provides aiMessage, but have a fallback.
            VoiceCategory.CUSTOM     -> classified.appLabel
                ?.let { "${it}: ${classified.query}" } ?: classified.query
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
