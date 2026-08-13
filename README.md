# FreeSpeech

**Open-source AI voice assistant for Android Auto on de-Googled Android.**

FreeSpeech gives users of privacy-respecting Android ROMs — Murena /e/OS, GrapheneOS, CalyxOS, LineageOS with microG — a working voice assistant for Android Auto. On these systems the car microphone button shows "speech commands are currently not supported" because Google Assistant is absent. FreeSpeech fills that gap without sending your voice to Google.

---

## What FreeSpeech does

1. **Listens** — records your voice from the phone or car microphone using a voice-activity detector (stops on silence, max 12 s)
2. **Transcribes** — sends the audio to your Whisper server; receives the text transcript
3. **Classifies** — an LLM (Ollama/Qwen locally, or Gemini/OpenAI/Anthropic via API) decides what you want and routes the request:
   - `MUSIC` → opens Tidal/Spotify/YouTube Music with your search
   - `NAVIGATION` → opens Google Maps / OsmAnd / Here
   - `CALL` → opens the dialler
   - `WEATHER` → opens your weather app
   - `WEB_SEARCH` → opens the browser
   - `CUSTOM_*` → ping-pong to a user-defined app (e.g. "eating" → Yelp) or site-restricted search
   - `RAG_*` → answers using a document you uploaded (travel guide, technical manual, …)
   - `NONE` → answers conversationally via TTS through the car speaker
4. **Speaks** — informational answers are converted to speech by Kokoro-FastAPI and played through the car audio (ducks music, doesn't stop it)

---

## Architecture

FreeSpeech has two parts: the **Android app** and the **FreeSpeech Console** (Rails backend). All heavy computation happens on your own servers — no cloud dependency.

```
┌──────────────────── Android Auto ─────────────────────────┐
│                                                           │
│  FreeSpeech Car App (on car screen)                       │
│   • records audio with VAD                               │
│   • sends WAV to Whisper → gets transcript               │
│   • sends transcript to Console /api/classify → intent   │
│   • launches target app  OR  plays TTS response          │
│                                                           │
│  WakeWordService (background, wakes FreeSpeech hands-free)│
└───────────────────────────────────────────────────────────┘
           │ HTTPS                        │ HTTPS
           ▼                              ▼
┌─────────────────────────────────────────────────────────────┐
│          FreeSpeech Console  (Rails 8 / SQLite)             │
│                                                             │
│  POST /api/classify  ─── Ping LLM → Pong transform/RAG     │
│  POST /api/tts       ─── proxy → Kokoro-FastAPI             │
│  GET  /              ─── Admin UI (Config / History / RAG)  │
└──────┬───────────────────────────────────────┬──────────────┘
       │                                       │
       ▼ OpenAI-compat /chat/completions       ▼ /v1/audio/transcriptions
┌──────────────┐                      ┌─────────────────────┐
│  Ollama      │                      │  Whisper server     │
│  (Qwen, …)   │                      │  (faster-whisper,   │
│  GPU/CPU     │                      │   whisper.cpp, …)   │
└──────────────┘                      └─────────────────────┘
       │                                       │
       └─────────── optional ──────────────────┘
              Kokoro-FastAPI (TTS)
              LanguageTool   (grammar)
```

---

## Deployment topologies

### Option A — Split setup: Console on DMZ host, AI on GPU server

This is the recommended setup when you have one machine with a GPU (for Whisper and Ollama) that lives on a private network, and a separate publicly-reachable host (e.g. a VPS or a home server in a DMZ) that your phone can reach over the internet.

```
                     internet
                         │
                         │  HTTPS (port 3001 or 443 via reverse proxy)
                         ▼
               ┌──────────────────┐
               │  pittyvaich      │   ← your public/DMZ host
               │  FreeSpeech      │     runs: Rails console
               │  Console (Rails) │             LanguageTool (opt.)
               └────────┬─────────┘
                        │  SSH reverse tunnel (localhost only)
                        │  :8080  → GPU server Whisper
                        │  :11434 → GPU server Ollama
                        │  :8880  → GPU server Kokoro-FastAPI
                        ▼
               ┌──────────────────┐
               │  GPU server      │   ← private network / home
               │  (stronger GPU)  │     runs: Whisper, Ollama, Kokoro
               └──────────────────┘
```

#### 1. GPU server — AI services

**Whisper** (faster-whisper):
```bash
pip install faster-whisper flask
python3 whisper_server.py          # listens on 0.0.0.0:8080
```

**Ollama**:
```bash
curl -fsSL https://ollama.ai/install.sh | sh
ollama serve                        # listens on 127.0.0.1:11434 by default
ollama pull qwen2.5:7b
```

**Kokoro-FastAPI** (TTS):
```bash
docker run -p 8880:8880 ghcr.io/remsky/kokoro-fastapi-cpu:v0.2.2
```
> Replace `cpu` with `gpu` and add `--gpus all` if your GPU supports CUDA.

#### 2. GPU server — SSH reverse tunnel to pittyvaich

The reverse tunnel makes the GPU server's ports reachable at `localhost` on pittyvaich. The console never exposes them to the internet.

```bash
# Run on the GPU server.
# -R remote_port:local_host:local_port
# This maps pittyvaich:127.0.0.1:8080 → GPU server:8080 (Whisper)
# and pittyvaich:127.0.0.1:11434 → GPU server:11434 (Ollama)
# and pittyvaich:127.0.0.1:8880  → GPU server:8880  (Kokoro)
ssh -N \
    -R 127.0.0.1:8080:localhost:8080 \
    -R 127.0.0.1:11434:localhost:11434 \
    -R 127.0.0.1:8880:localhost:8880 \
    user@pittyvaich
```

> **sshd_config on pittyvaich** must have `GatewayPorts no` (the default) — we bind to `127.0.0.1` only, so the tunnel ports are *not* exposed externally.

**Keep the tunnel alive with autossh** (install with `apt install autossh` / `dnf install autossh`):

```bash
autossh -M 9090 -N \
    -R 127.0.0.1:8080:localhost:8080 \
    -R 127.0.0.1:11434:localhost:11434 \
    -R 127.0.0.1:8880:localhost:8880 \
    user@pittyvaich
```

**As a systemd service on the GPU server** (starts on boot, restarts on failure):

```ini
# /etc/systemd/system/freespeech-tunnel.service
[Unit]
Description=FreeSpeech SSH reverse tunnel to pittyvaich
After=network-online.target
Wants=network-online.target

[Service]
User=YOUR_USERNAME
ExecStart=/usr/bin/autossh -M 9090 -N \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -R 127.0.0.1:8080:localhost:8080 \
    -R 127.0.0.1:11434:localhost:11434 \
    -R 127.0.0.1:8880:localhost:8880 \
    user@pittyvaich
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now freespeech-tunnel
sudo systemctl status freespeech-tunnel
```

> **SSH key authentication required** — the service must be able to connect without a passphrase. Generate a dedicated key if needed:
> ```bash
> ssh-keygen -t ed25519 -f ~/.ssh/freespeech_tunnel -N ""
> ssh-copy-id -i ~/.ssh/freespeech_tunnel.pub user@pittyvaich
> ```
> Then add `-i ~/.ssh/freespeech_tunnel` to the ExecStart line.

#### 3. pittyvaich — FreeSpeech Console

```bash
git clone https://github.com/JanDuennweber/FreeSpeech.git
cd FreeSpeech/server/freespeech-admin

bundle install
bin/rails db:setup      # creates DB, seeds default settings + example topics

# Start (development / quick test)
bin/rails server -b 0.0.0.0 -p 3001

# Production: use Kamal, Puma behind nginx, or Phusion Passenger.
```

**Console settings to enter via the web UI at `http://pittyvaich:3001`:**

| Setting | Value |
|---|---|
| Whisper Endpoint URL | `http://localhost:8080/v1/audio/transcriptions` |
| AI Engine | `ollama` |
| AI Base URL | `http://localhost:11434` |
| AI Model | `qwen2.5:7b` |
| TTS URL | `http://localhost:8880` |
| TTS Voice | `af_heart` (or any of the 5 Kokoro voices) |

Because the tunnel forwards all three services to `localhost`, the console sees them exactly as if they were running locally.

---

### Option B — Single-server setup

Everything on one machine (home server, VPS with GPU, or even a powerful laptop). Simpler to run and debug.

```
           internet
               │  HTTPS
               ▼
       ┌───────────────────────────────────────────┐
       │  my-server                                │
       │                                           │
       │  :3001  FreeSpeech Console (Rails)         │
       │  :8080  Whisper server (faster-whisper)    │
       │  :11434 Ollama (Qwen 2.5 7B or larger)    │
       │  :8880  Kokoro-FastAPI (TTS)               │
       │  :8010  LanguageTool (optional)            │
       └───────────────────────────────────────────┘
```

**Start all services** (each in its own terminal or as systemd units):

```bash
# Whisper
python3 whisper_server.py

# Ollama
ollama serve
ollama pull qwen2.5:7b

# Kokoro-FastAPI TTS
docker run -p 8880:8880 ghcr.io/remsky/kokoro-fastapi-cpu:v0.2.2

# LanguageTool (optional — grammar correction for Ollama answers)
docker run -p 8010:8010 erikvl87/languagetool

# FreeSpeech Console
cd FreeSpeech/server/freespeech-admin
bin/rails db:setup
bin/rails server -b 0.0.0.0 -p 3001
```

**Console settings:**

| Setting | Value |
|---|---|
| Whisper Endpoint URL | `http://localhost:8080/v1/audio/transcriptions` |
| AI Engine | `ollama` |
| AI Base URL | `http://localhost:11434` |
| AI Model | `qwen2.5:7b` |
| TTS URL | `http://localhost:8880` |
| LanguageTool URL | `http://localhost:8010` (blank → uses free public API) |

---

## Whisper server options

Any server that speaks the OpenAI `/v1/audio/transcriptions` API works.

### faster-whisper (included — `whisper_server.py`)

```bash
pip install faster-whisper flask
python3 whisper_server.py
```

Edit the top of `whisper_server.py` to change:

| Setting | Default | Options |
|---|---|---|
| Model | `"small"` | `tiny`, `base`, `medium`, `large-v3` |
| Language | `"de"` | Any BCP-47 code, or `None` for auto-detect |
| Device | `"cpu"` | `"cuda"` for NVIDIA GPU |

### whisper.cpp

Faster CPU-only option, runs on ARM too (Raspberry Pi, Apple Silicon):

```bash
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp && make
bash models/download-ggml-model.sh small
./server -m models/ggml-small.bin -l de --port 8080
```

### LocalAI / OpenAI API

Point the Whisper URL at any compatible endpoint — LocalAI, the real OpenAI API, or any self-hosted alternative.

---

## FreeSpeech Console features

The Console is a Rails 8 web application providing:

### ⚙ Config tab (admin)
- **Whisper** URL and model
- **AI engine** — Ollama (local), Gemini (free), OpenAI, or Anthropic
- **Grammar correction** — auto-fixes Ollama/Qwen responses via LanguageTool before TTS
- **TTS** — Kokoro-FastAPI URL, voice selection (5 voices: af_heart, af_sky, am_michael, bf_emma, bm_george), model
- **Custom topics** — flexible ping-pong topic→app/URL mappings (see below)
- **Language management**

### 📋 History tab (admin)
- Last 1 000 voice commands: transcript, intent, query, language, AI engine used, audio player
- Routing chain column shows the full ping-pong path for custom and RAG commands

### 📚 RAG Library (per user)
Each user can upload their own PDF or EPUB documents and map them to keywords.

### 👤 My Account
- Personal AI engine and API key override (use Claude while the default is Ollama)
- API token for the Android app

---

## AI classification — ping-pong mechanism

FreeSpeech uses a two-step "ping-pong" LLM pattern for intent routing:

**Ping** — one LLM call classifies the transcript into a category.  
**Pong** — a second focused call extracts or transforms the query for the target.

This allows natural speech ("I feel like eating something Italian") to be routed correctly without requiring exact command syntax.

### Standard categories

Single ping, no pong needed: `MUSIC`, `NAVIGATION`, `WEATHER`, `CALL`, `WEB_SEARCH`, `NONE`.

### Custom topics (admin-configured, `CUSTOM_*`)

Maps a keyword (e.g. "eating") to an app deep-link or a site-restricted web search.

| Ping result | Pong result | Action |
|---|---|---|
| `CUSTOM_EATING` | "french fries near me" | Opens `yelp://search?terms=french+fries+near+me` |
| `CUSTOM_JOKES` | "dad jokes" | Browser search: `dad jokes site:punscorner.com OR site:reddit.com/r/Jokes` |

Configure in the Console → Config → Custom Topics card.

### RAG topics (per-user, `RAG_*`)

Maps a keyword (e.g. "traveling") to an uploaded document. Three-step pong:

1. **Factsheet** — LLM extracts the most relevant section of the document (up to 30 K chars, keyword-density windowed for large files)
2. **Augmented answer** — LLM answers the original question using the factsheet
3. **TTS** — the answer is spoken through the car speaker

**Routing chain example in History:**
```
"nice beaches near cagliari" → traveling → sardinia_guide.epub
"battery storage" → regenerative energy → photovoltaikMadeEasy.pdf
```

---

## Android app setup

### 1. Build the APK

Requires Android SDK and Java 21:

```bash
cd freespeech
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
```

Or open the `freespeech/` directory in Android Studio and choose **Build → Build APK**.

### 2. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Set as default speech engine and assistant

**Settings → Apps → Default apps → Speech recognition → FreeSpeech**  
**Settings → Apps → Default apps → Digital assistant → FreeSpeech**

### 4. Enable Unknown Sources in Android Auto

Allows sideloaded Car App Library apps to appear in the AA launcher:

1. Open Android Auto on the phone
2. Tap the version number **10 times** (unlocks developer settings)
3. **Developer settings → Unknown sources → On**

### 5. Configure the app

Open FreeSpeech on the phone:

| Setting | Example |
|---|---|
| Whisper URL | `http://pittyvaich:8080/v1/audio/transcriptions` (split) or `http://my-server:8080/…` (single) |
| Console URL | `http://pittyvaich:3001` or `http://my-server:3001` |
| Use AI | ✅ (enable for smart classification) |
| AI Engine | Gemini / Ollama / OpenAI / Anthropic |
| Wake word | e.g. `hey freespeech` (triggers hands-free recording) |
| TTS voice | Configured server-side; this is a server setting |

### 6. Test

| Invocation | Works |
|---|---|
| **FreeSpeech app in AA launcher (car screen)** | ✅ Recommended — auto-starts listening |
| Long-press power / home button on phone | ✅ Digital assistant overlay |
| Keyboard microphone in any text field | ✅ Speech-to-text |
| Wake word (background service) | ✅ Hands-free |
| Car mic button (Android Auto) | ⚠ Depends on gearhead version (see Known limitations) |

---

## Wake word

When the wake word service is enabled (configured in the app settings), FreeSpeech listens continuously for the configured phrase. On detection:

- If the FreeSpeech Car App is in the foreground, recording starts immediately
- If not in the foreground, a car notification appears prompting the driver to open the app

The wake word detector runs locally on-device using Android's built-in speech recogniser — no network call.

---

## TTS voices (Kokoro-FastAPI)

Five voices are available. Configure the default in the Console → Config → TTS card.  
Users can override it in their profile.

| ID | Character |
|---|---|
| `af_heart` | Warm American female (default) |
| `af_sky` | Clear American female |
| `am_michael` | American male |
| `bf_emma` | British female |
| `bm_george` | British male |

---

## Known limitations — Android Auto mic button

The car microphone button does **not** currently invoke FreeSpeech on gearhead 14+ paired with microG ROMs. When pressed, gearhead's `DemandController` looks for `com.google.android.googlequicksearchbox` directly (not the system `VoiceInteractionService`). Since current microG does not implement the Google Assistant voice protocol, gearhead shows "Sprachkommandos sind derzeit nicht verfügbar" without ever calling FreeSpeech.

**The recommended path** is the FreeSpeech app in the AA launcher: it auto-starts listening the moment it becomes visible.

This limitation would be resolved by a microG update implementing the Google Assistant voice interaction protocol, or by older gearhead versions that fell back to `VoiceInteractionService`. If you get the car mic button working on any device/version combination, please open an issue.

---

## Notes for Murena /e/OS users

Android Auto (`gearhead`) does not ship pre-installed on /e/OS and must be sideloaded. See [apkBackup/howToRestoreAndroidAuto.txt](apkBackup/howToRestoreAndroidAuto.txt) for the full procedure including how to back up and restore the APK across system updates.

---

## Roadmap

- [x] Android Auto Car App — FreeSpeech in AA launcher, auto-starts mic
- [x] AI classification — Ollama, Gemini, OpenAI, Anthropic
- [x] Wake word detection — hands-free trigger, background service
- [x] Per-user AI engine + API key override
- [x] Command history log — JSONL + WAV archive, browser UI
- [x] Grammar correction — LanguageTool post-processing for Ollama answers
- [x] TTS — Kokoro-FastAPI, spoken responses through car speaker (ducks music)
- [x] Custom topics — flexible topic→app / topic→web-search ping-pong
- [x] RAG library — per-user PDF/EPUB upload, factsheet extraction, augmented answers
- [ ] Android Auto car mic button — blocked by gearhead + microG compatibility
- [ ] WhatsApp message dictation via Accessibility Service
- [ ] On-device Whisper (whisper.cpp, no server needed)
- [ ] Vector embeddings for RAG (currently keyword-density windowing)

---

## Privacy

FreeSpeech sends audio only to the endpoints you configure. No telemetry, no analytics, no third-party SDKs. With a self-hosted Whisper and Ollama your voice data never leaves your own hardware.

The Console stores the last 1 000 command transcripts and the last 100 audio recordings. No IP addresses or user IDs are stored in the log. RAG documents are stored as extracted plain text, scoped to each user — the original file is not retained.

---

## Contributing

Pull requests welcome. If you get the car mic button working on any device/ROM/gearhead combination, please open an issue with:

- ROM and version
- gearhead version: `adb shell dumpsys package com.google.android.projection.gearhead | grep versionName`
- googlequicksearchbox version: `adb shell dumpsys package com.google.android.googlequicksearchbox | grep versionName`
- Relevant logcat around the mic-button press

---

## Licence

Apache 2.0
