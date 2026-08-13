package io.freespeech

import android.content.Context
import android.content.pm.PackageManager

/** A selectable app for a given VoiceCategory. [packageName] is null for "let Android choose". */
data class AppOption(val label: String, val packageName: String?)

/** Known candidate apps per category; filtered at runtime to only installed packages. */
object AppCandidates {

    private val catalog: Map<VoiceCategory, List<AppOption>> = mapOf(

        VoiceCategory.MUSIC to listOf(
            AppOption("Systemauswahl (Standard)",   null),
            AppOption("Spotify",                    "com.spotify.music"),
            AppOption("Tidal",                      "com.aspiro.tidal"),
            AppOption("YouTube Music",              "com.google.android.apps.youtube.music"),
            AppOption("SoundCloud",                 "com.soundcloud.android"),
            AppOption("VLC",                        "org.videolan.vlc"),
            AppOption("Jellyfin",                   "org.jellyfin.mobile"),
            AppOption("Subsonic / Ultrasonic",      "org.moire.ultrasonic"),
            AppOption("AntennaPod (Podcast)",       "de.danoeh.antennapod"),
        ),

        VoiceCategory.WEATHER to listOf(
            AppOption("Browser (Suche)",             null),
            AppOption("DWD WarnWetter",              "de.dwd.warnwetter"),
            AppOption("WetterOnline",                "de.wetteronline.wetterapp"),
            AppOption("wetter.com",                  "de.wetter.com"),
            AppOption("Yr",                          "no.nrk.yr"),
            AppOption("OpenWeatherMap",              "uk.co.openweather.myweather"),
            AppOption("Weather Underground",         "com.wunderground.android.weather"),
        ),

        VoiceCategory.NAVIGATION to listOf(
            AppOption("Systemauswahl (Standard)",   null),
            AppOption("OsmAnd+",                    "net.osmand"),
            AppOption("OsmAnd",                     "net.osmand.plus"),
            AppOption("Maps (Google-Stub)",          "com.google.android.apps.maps"),
            AppOption("HERE WeGo",                  "com.here.app.maps"),
            AppOption("Magic Earth",                "com.generalmagic.magicearth"),
            AppOption("Waze",                       "com.waze"),
            AppOption("Organic Maps",               "app.organicmaps"),
        ),

        VoiceCategory.WEB_SEARCH to listOf(
            AppOption("Systemauswahl (Standard)",   null),
            AppOption("Firefox",                    "org.mozilla.firefox"),
            AppOption("Fennec F-Droid",             "org.mozilla.fennec_fdroid"),
            AppOption("Mull",                       "us.spotco.fennec_dos"),
            AppOption("Brave",                      "com.brave.browser"),
            AppOption("DuckDuckGo",                 "com.duckduckgo.mobile.android"),
            AppOption("Bromite / Cromite",          "org.cromite.cromite"),
        ),
    )

    /** Returns only the candidates actually installed on the device. */
    fun forCategory(context: Context, category: VoiceCategory): List<AppOption> {
        val pm = context.packageManager
        return (catalog[category] ?: emptyList()).filter { opt ->
            opt.packageName == null || pm.isInstalled(opt.packageName)
        }
    }

    private fun PackageManager.isInstalled(pkg: String): Boolean = try {
        getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

// Search-engine options are URL prefixes, not app packages.
data class SearchEngineOption(val label: String, val urlPrefix: String)

val searchEngines = listOf(
    SearchEngineOption("DuckDuckGo",  "https://duckduckgo.com/?q="),
    SearchEngineOption("Google",      "https://www.google.com/search?q="),
    SearchEngineOption("Startpage",   "https://www.startpage.com/search?q="),
    SearchEngineOption("Ecosia",      "https://www.ecosia.org/search?q="),
    SearchEngineOption("Bing",        "https://www.bing.com/search?q="),
)
