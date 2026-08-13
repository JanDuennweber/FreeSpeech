package io.freespeech

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    // Spinner state: each entry is the AppOption list for that category,
    // used to map spinner position → packageName on save.
    private lateinit var musicOptions:     List<AppOption>
    private lateinit var weatherOptions:   List<AppOption>
    private lateinit var navOptions:       List<AppOption>
    private lateinit var websearchOptions: List<AppOption>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("freespeech", Context.MODE_PRIVATE)

        // ── Whisper URL ────────────────────────────────────────────────────────

        val urlField = findViewById<EditText>(R.id.whisper_url)
        urlField.setText(prefs.getString("whisper_url", "http://your-server:8080/v1/audio/transcriptions"))

        // ── App spinners ───────────────────────────────────────────────────────

        musicOptions     = AppCandidates.forCategory(this, VoiceCategory.MUSIC)
        weatherOptions   = AppCandidates.forCategory(this, VoiceCategory.WEATHER)
        navOptions       = AppCandidates.forCategory(this, VoiceCategory.NAVIGATION)
        websearchOptions = AppCandidates.forCategory(this, VoiceCategory.WEB_SEARCH)

        setupSpinner(R.id.spinner_music,     musicOptions,     prefs.getString(VoiceCategory.MUSIC.prefKey,      null))
        setupSpinner(R.id.spinner_weather,   weatherOptions,   prefs.getString(VoiceCategory.WEATHER.prefKey,    null))
        setupSpinner(R.id.spinner_nav,       navOptions,       prefs.getString(VoiceCategory.NAVIGATION.prefKey, null))
        setupSpinner(R.id.spinner_websearch, websearchOptions, prefs.getString(VoiceCategory.WEB_SEARCH.prefKey, null))

        // ── Search engine ──────────────────────────────────────────────────────

        val seSpinner = findViewById<Spinner>(R.id.spinner_searchengine)
        val seAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, searchEngines.map { it.label })
        seAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        seSpinner.adapter = seAdapter
        val savedEngine = prefs.getString("search_engine", searchEngines[0].urlPrefix)
        val seIdx = searchEngines.indexOfFirst { it.urlPrefix == savedEngine }.coerceAtLeast(0)
        seSpinner.setSelection(seIdx)

        // ── Save ───────────────────────────────────────────────────────────────

        findViewById<Button>(R.id.save_button).setOnClickListener {
            prefs.edit()
                .putString("whisper_url", urlField.text.toString().trim())
                .putString(VoiceCategory.MUSIC.prefKey,      selectedPkg(R.id.spinner_music,     musicOptions))
                .putString(VoiceCategory.WEATHER.prefKey,    selectedPkg(R.id.spinner_weather,   weatherOptions))
                .putString(VoiceCategory.NAVIGATION.prefKey, selectedPkg(R.id.spinner_nav,       navOptions))
                .putString(VoiceCategory.WEB_SEARCH.prefKey, selectedPkg(R.id.spinner_websearch, websearchOptions))
                .putString("search_engine", searchEngines[seSpinner.selectedItemPosition].urlPrefix)
                .apply()
            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setupSpinner(spinnerId: Int, options: List<AppOption>, savedPkg: String?) {
        val spinner = findViewById<Spinner>(spinnerId)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        // Restore saved selection (or keep index 0 = "Systemauswahl").
        val idx = if (savedPkg != null) options.indexOfFirst { it.packageName == savedPkg } else 0
        spinner.setSelection(idx.coerceAtLeast(0))
    }

    private fun selectedPkg(spinnerId: Int, options: List<AppOption>): String {
        val pos = findViewById<Spinner>(spinnerId).selectedItemPosition
        return options.getOrNull(pos)?.packageName ?: ""
    }
}
