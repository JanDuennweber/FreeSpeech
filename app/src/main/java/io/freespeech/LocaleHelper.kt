package io.freespeech

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Manages the app-level locale override.
 *
 * Uses [AppCompatDelegate.setApplicationLocales], which:
 *  - on Android 13+ delegates to the platform per-app language API
 *  - on older versions is handled entirely by AppCompat (no Application.onCreate needed)
 *
 * Calling [apply] automatically triggers activity recreation so the UI re-inflates
 * with the correct locale — no manual recreate() call needed.
 */
object LocaleHelper {

    data class SupportedLocale(
        /** BCP-47 language tag, e.g. "de". Empty string = follow the phone's system locale. */
        val tag: String,
        /** Name of the language written in that language (always shown in native script). */
        val nativeName: String,
    )

    /**
     * Ordered list of locales shown in the settings spinner.
     * The first entry (empty tag) means "use the phone's system language".
     * The display label for that entry comes from [R.string.language_system_default].
     * All other entries use [nativeName] directly (proper nouns, not translated).
     */
    val SUPPORTED: List<SupportedLocale> = listOf(
        SupportedLocale("",   ""),          // system default — label from strings.xml
        SupportedLocale("de", "Deutsch"),
        SupportedLocale("en", "English"),
        SupportedLocale("es", "Español"),
        SupportedLocale("fr", "Français"),
        SupportedLocale("it", "Italiano"),
        SupportedLocale("cs", "Čeština"),
    )

    /**
     * Applies [tag] as the app-specific locale.
     * An empty tag resets to the system default.
     * AppCompat recreates the current activity automatically.
     */
    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else               LocaleListCompat.forLanguageTags(tag)
        )
    }

    /**
     * Returns the currently applied locale tag (e.g. "de", "fr"), or an empty
     * string when FreeSpeech is following the phone's system language.
     */
    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ""
        val full = locales[0]?.toLanguageTag() ?: return ""
        // Match against our supported tags; strip any region subtag (e.g. "de-DE" → "de").
        return SUPPORTED.firstOrNull { it.tag.isNotEmpty() && full.startsWith(it.tag) }?.tag ?: ""
    }
}
