package io.freespeech

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("freespeech", Context.MODE_PRIVATE)
        val urlField = findViewById<EditText>(R.id.whisper_url)
        urlField.setText(
            prefs.getString("whisper_url", "http://your-server:8080/v1/audio/transcriptions")
        )

        findViewById<Button>(R.id.save_button).setOnClickListener {
            prefs.edit().putString("whisper_url", urlField.text.toString().trim()).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }
}
