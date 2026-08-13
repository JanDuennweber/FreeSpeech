package io.freespeech

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    // Spinner state: option lists whose index maps to a packageName / engine key.
    private lateinit var musicOptions:     List<AppOption>
    private lateinit var weatherOptions:   List<AppOption>
    private lateinit var navOptions:       List<AppOption>
    private lateinit var websearchOptions: List<AppOption>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("freespeech", Context.MODE_PRIVATE)

        // ── Language spinner ───────────────────────────────────────────────────

        val langSpinner = findViewById<Spinner>(R.id.spinner_language)
        val langLabels = LocaleHelper.SUPPORTED.mapIndexed { i, sl ->
            if (i == 0) getString(R.string.language_system_default) else sl.nativeName
        }
        ArrayAdapter(this, android.R.layout.simple_spinner_item, langLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            .let { langSpinner.adapter = it }
        val currentTag = LocaleHelper.currentTag()
        val langIdx = LocaleHelper.SUPPORTED.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)
        langSpinner.setSelection(langIdx)

        // ── Whisper URL ────────────────────────────────────────────────────────

        val urlField = findViewById<EditText>(R.id.whisper_url)
        urlField.setText(prefs.getString("whisper_url", "http://your-server:8080/v1/audio/transcriptions"))

        val logUrlField = findViewById<EditText>(R.id.log_url)
        logUrlField.setText(prefs.getString("log_url", ""))

        // ── FreeSpeech Console ─────────────────────────────────────────────────

        val consoleUrlField = findViewById<EditText>(R.id.console_url)
        val userTokenField  = findViewById<EditText>(R.id.user_token)
        val consoleNotice   = findViewById<TextView>(R.id.console_active_notice)

        val savedConsoleUrl = prefs.getString("console_url", "").orEmpty()
        consoleUrlField.setText(savedConsoleUrl)
        userTokenField.setText(prefs.getString("user_token", ""))

        // Show green notice when console is already configured.
        consoleNotice.visibility = if (savedConsoleUrl.isNotBlank()) View.VISIBLE else View.GONE

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
        ArrayAdapter(this, android.R.layout.simple_spinner_item, searchEngines.map { it.label })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            .let { seSpinner.adapter = it }
        val savedEngine = prefs.getString("search_engine", searchEngines[0].urlPrefix)
        seSpinner.setSelection(searchEngines.indexOfFirst { it.urlPrefix == savedEngine }.coerceAtLeast(0))

        // ── AI section ─────────────────────────────────────────────────────────

        setupAiSection(prefs)

        // ── Wake word section ──────────────────────────────────────────────────

        val wakeEnabledCheck = findViewById<CheckBox>(R.id.use_wake_word)
        val wakeWordField    = findViewById<EditText>(R.id.wake_word)
        wakeEnabledCheck.isChecked = prefs.getBoolean("wake_enabled", false)
        wakeWordField.setText(prefs.getString("wake_word", getString(R.string.settings_wake_word_hint)))

        // ── Save ───────────────────────────────────────────────────────────────

        findViewById<Button>(R.id.save_button).setOnClickListener {
            // Apply locale — triggers Activity recreation if it changed.
            val selectedTag = LocaleHelper.SUPPORTED
                .getOrElse(langSpinner.selectedItemPosition) { LocaleHelper.SUPPORTED[0] }
                .tag
            LocaleHelper.apply(selectedTag)

            val newConsoleUrl = consoleUrlField.text.toString().trim()
            consoleNotice.visibility = if (newConsoleUrl.isNotBlank()) View.VISIBLE else View.GONE

            val edit = prefs.edit()
                .putString("whisper_url",  urlField.text.toString().trim())
                .putString("log_url",      logUrlField.text.toString().trim())
                .putString("console_url",  newConsoleUrl)
                .putString("user_token",   userTokenField.text.toString().trim())
                .putString(VoiceCategory.MUSIC.prefKey,      selectedPkg(R.id.spinner_music,     musicOptions))
                .putString(VoiceCategory.WEATHER.prefKey,    selectedPkg(R.id.spinner_weather,   weatherOptions))
                .putString(VoiceCategory.NAVIGATION.prefKey, selectedPkg(R.id.spinner_nav,       navOptions))
                .putString(VoiceCategory.WEB_SEARCH.prefKey, selectedPkg(R.id.spinner_websearch, websearchOptions))
                .putString("search_engine", searchEngines[seSpinner.selectedItemPosition].urlPrefix)
                // AI settings
                .putBoolean("use_ai", findViewById<CheckBox>(R.id.use_ai).isChecked)
                .putString("ai_engine", AiClassifier.ENGINE_KEYS.getOrElse(
                    findViewById<Spinner>(R.id.spinner_ai_engine).selectedItemPosition
                ) { AiClassifier.ENGINE_GEMINI })
                .putString("ai_api_key", findViewById<EditText>(R.id.ai_api_key).text.toString().trim())
                .putString("ai_base_url", findViewById<EditText>(R.id.ai_base_url).text.toString().trim())
                .putString("ai_model",   findViewById<EditText>(R.id.ai_model).text.toString().trim())

                // Wake word
                .putBoolean("wake_enabled", wakeEnabledCheck.isChecked)
                .putString("wake_word",     wakeWordField.text.toString().trim()
                    .ifBlank { getString(R.string.settings_wake_word_hint) })

            edit.apply()

            // Start or stop the wake word service to match the new setting.
            if (wakeEnabledCheck.isChecked) WakeWordService.start(this)
            else                            WakeWordService.stop(this)

            Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        }
    }

    // ── AI section setup ───────────────────────────────────────────────────────

    private fun setupAiSection(prefs: android.content.SharedPreferences) {
        val useAiCheck    = findViewById<CheckBox>(R.id.use_ai)
        val aiConfig      = findViewById<LinearLayout>(R.id.ai_config)
        val engineSpinner = findViewById<Spinner>(R.id.spinner_ai_engine)
        val engineHint    = findViewById<TextView>(R.id.ai_engine_hint)
        val apiKeyField   = findViewById<EditText>(R.id.ai_api_key)
        val baseUrlField  = findViewById<EditText>(R.id.ai_base_url)
        val modelField    = findViewById<EditText>(R.id.ai_model)

        // Show/hide AI config when checkbox changes.
        useAiCheck.isChecked = prefs.getBoolean("use_ai", false)
        aiConfig.visibility = if (useAiCheck.isChecked) View.VISIBLE else View.GONE
        useAiCheck.setOnCheckedChangeListener { _, checked ->
            aiConfig.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Engine spinner — labels come from string resources so the phone locale is respected.
        val engineLabels = listOf(
            getString(R.string.engine_gemini),
            getString(R.string.engine_openai),
            getString(R.string.engine_ollama),
            getString(R.string.engine_anthropic),
        )
        ArrayAdapter(this, android.R.layout.simple_spinner_item, engineLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            .let { engineSpinner.adapter = it }

        val savedEngineKey = prefs.getString("ai_engine", AiClassifier.ENGINE_GEMINI)!!
        val savedEngineIdx = AiClassifier.ENGINE_KEYS.indexOf(savedEngineKey).coerceAtLeast(0)

        // Load saved values into the text fields (NOT from defaults — user may have customised them).
        val def = AiClassifier.DEFAULTS[savedEngineKey]!!
        apiKeyField.setText(prefs.getString("ai_api_key",  ""))
        baseUrlField.setText(prefs.getString("ai_base_url", def.baseUrl))
        modelField.setText(prefs.getString("ai_model",    def.model))
        updateEngineHint(engineHint, savedEngineKey)

        // When the user picks a different engine, auto-fill defaults for that engine.
        // We skip the first call (triggered by setSelection below) via `skipFirst`.
        var skipFirst = true
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (skipFirst) { skipFirst = false; return }
                val key = AiClassifier.ENGINE_KEYS.getOrNull(pos) ?: return
                val d   = AiClassifier.DEFAULTS[key] ?: return
                // Only auto-fill URL and model — don't overwrite a key the user already typed.
                baseUrlField.setText(d.baseUrl)
                modelField.setText(d.model)
                if (key == AiClassifier.ENGINE_OLLAMA) apiKeyField.setText("")
                updateEngineHint(engineHint, key)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        engineSpinner.setSelection(savedEngineIdx)
    }

    private fun updateEngineHint(hint: TextView, engineKey: String) {
        hint.text = when (engineKey) {
            AiClassifier.ENGINE_GEMINI    -> getString(R.string.engine_hint_gemini)
            AiClassifier.ENGINE_OPENAI    -> getString(R.string.engine_hint_openai)
            AiClassifier.ENGINE_OLLAMA    -> getString(R.string.engine_hint_ollama)
            AiClassifier.ENGINE_ANTHROPIC -> getString(R.string.engine_hint_anthropic)
            else -> ""
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setupSpinner(spinnerId: Int, options: List<AppOption>, savedPkg: String?) {
        val spinner = findViewById<Spinner>(spinnerId)
        ArrayAdapter(this, android.R.layout.simple_spinner_item, options.map { it.label })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            .let { spinner.adapter = it }
        val idx = if (savedPkg != null) options.indexOfFirst { it.packageName == savedPkg } else 0
        spinner.setSelection(idx.coerceAtLeast(0))
    }

    private fun selectedPkg(spinnerId: Int, options: List<AppOption>): String {
        val pos = findViewById<Spinner>(spinnerId).selectedItemPosition
        return options.getOrNull(pos)?.packageName ?: ""
    }
}
