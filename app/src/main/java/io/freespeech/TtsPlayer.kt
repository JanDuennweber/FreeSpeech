package io.freespeech

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads synthesised speech from the FreeSpeech Console `/api/tts` proxy and
 * plays it through the car speakers using [MediaPlayer] with
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE audio attributes so the audio ducks any
 * currently playing music instead of stopping it entirely.
 *
 * The console acts as a proxy to whatever TTS server the admin configured
 * (default: a locally running Kokoro-FastAPI instance).  The Android app
 * only needs to reach the console — it never talks to the TTS server directly.
 *
 * TTS is silently skipped when:
 *  - `console_url` is not set in SharedPreferences
 *  - the console returns a non-2xx response (TTS not configured server-side)
 *  - any network / I-O error occurs
 *
 * The [onDone] callback is always invoked (even on failure) so callers can
 * reset UI state reliably.
 */
class TtsPlayer(private val context: Context, private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "TtsPlayer"

        // Audio attributes shared between AudioFocusRequest and MediaPlayer so
        // the system knows this is navigation-guidance style speech.
        private val AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)   // TTS generation + transfer
        .build()

    // Use an atomic counter to give each temp file a unique name so a new call
    // can't clobber the file while a previous MediaPlayer is still reading it.
    private val fileSeq = AtomicInteger(0)

    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var currentFocus: AudioFocusRequest? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Asynchronously fetches audio for [text] and plays it.
     * [onDone] is called when playback ends or on any failure — always, exactly once.
     * A second call while audio is playing stops the previous playback first.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val consoleUrl = prefs.getString("console_url", "").orEmpty().trimEnd('/')
        if (consoleUrl.isEmpty()) {
            onDone?.invoke()
            return
        }

        Thread {
            var tmpFile: File? = null
            try {
                // ── 1. Download audio from console TTS proxy ───────────────
                val body = JSONObject().put("text", text)
                val req  = Request.Builder()
                    .url("$consoleUrl/api/tts")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val audioBytes = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("console /api/tts HTTP ${resp.code}")
                    resp.body?.bytes() ?: throw Exception("empty TTS body")
                }

                // ── 2. Write to a uniquely named temp file ─────────────────
                val seq = fileSeq.incrementAndGet()
                tmpFile = File(context.cacheDir, "tts_${seq}.tmp").also {
                    it.writeBytes(audioBytes)
                }
                val tmpPath = tmpFile.absolutePath  // capture before lambda

                // ── 3. Stop any previous playback ──────────────────────────
                releaseCurrentPlayer()

                // ── 4. Request audio focus (duck music, don't stop it) ─────
                val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AUDIO_ATTRS)
                    .build()
                currentFocus = fr
                audioManager.requestAudioFocus(fr)

                // ── 5. Play via MediaPlayer ────────────────────────────────
                val mp = MediaPlayer().apply {
                    setAudioAttributes(AUDIO_ATTRS)
                    setDataSource(tmpPath)
                    prepare()

                    setOnCompletionListener {
                        Log.d(TAG, "TTS playback complete")
                        abandon()
                        File(tmpPath).delete()
                        onDone?.invoke()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        abandon()
                        File(tmpPath).delete()
                        onDone?.invoke()
                        true
                    }

                    start()
                }
                currentPlayer = mp
                Log.d(TAG, "TTS started: ${text.take(60)}…")

            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed: ${e.message}")
                tmpFile?.delete()
                abandon()
                onDone?.invoke()
            }
        }.start()
    }

    /** Stop any in-progress playback and release resources. */
    fun release() {
        abandon()
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun abandon() {
        currentFocus?.let { audioManager.abandonAudioFocusRequest(it) }
        currentFocus = null
        releaseCurrentPlayer()
    }

    private fun releaseCurrentPlayer() {
        currentPlayer?.release()
        currentPlayer = null
    }
}
