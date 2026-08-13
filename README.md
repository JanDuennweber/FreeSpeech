# FreeSpeech

**Open-source voice assistant for Android Auto on de-Googled Android.**

FreeSpeech gives users of privacy-respecting Android ROMs — Murena /e/OS, GrapheneOS, CalyxOS, LineageOS with microG — a working voice input engine for Android Auto. On these systems the microphone button in Android Auto shows "speech commands are currently not supported" because Google Assistant is absent. FreeSpeech fills that gap without sending your voice to Google.

## How it works

FreeSpeech registers itself as:

- An Android `RecognitionService` — handles keyboard microphone and speech-to-text in any app
- A `VoiceInteractionService` — handles the system digital assistant (long-press home / power button)
- An Android Auto **Car App** — appears in the AA launcher on the car screen as a first-class voice input UI

When invoked, FreeSpeech:

1. Records audio from the microphone
2. Sends it to a [Whisper](https://github.com/openai/whisper)-compatible HTTP endpoint of your choice
3. Returns the transcription

You control which Whisper server is used. Self-host one on your own hardware, use a server on your local network, or point it at any OpenAI-compatible API. Your voice never reaches Google.

## Known limitations — Android Auto mic button

**The car microphone button in Android Auto does not currently invoke FreeSpeech** on gearhead 14+ paired with a microG-based ROM. This is a gearhead compatibility issue, not a FreeSpeech bug.

### Root cause

When the mic button is pressed on the car display, gearhead's `DemandController` checks for Google Assistant via `com.google.android.googlequicksearchbox` directly — it does not consult the Android `ASSISTANT` role or the system `VoiceInteractionService`. If the microG stub for `googlequicksearchbox` does not implement the Google Assistant voice protocol (which current microG versions do not), gearhead logs:

```
E GH.DemandController: Assistant is unavailable due to: 0
```

…and shows "Sprachkommandos sind derzeit nicht verfügbar" without ever invoking FreeSpeech.

### What does work

| Invocation method | Works? |
|---|---|
| Car display mic button (Android Auto) | ✗ gearhead bypasses VoiceInteractionService |
| **FreeSpeech app in AA launcher (car screen)** | **✓ full mic UI, auto-starts listening** |
| Long-press power / home button on phone | ✓ invokes FreeSpeech as digital assistant |
| Keyboard microphone (any text field) | ✓ invokes FreeSpeech as RecognitionService |
| Voice input in other apps | ✓ |

The **recommended hands-free path** is via the AA launcher: open FreeSpeech from the car app list, it starts recording immediately, transcribes via Whisper, and shows the result on the car display.

### Conditions under which the car mic button would work

- **Older gearhead versions** (roughly pre-12.x) had a proper fallback to the system `VoiceInteractionService` when Google Assistant was unavailable. However, recent car head unit firmware enforces a minimum gearhead version via "Communication error 8 / security checks not passed", so downgrading is not always possible.
- **A microG update** that implements the Google Assistant voice interaction protocol for `googlequicksearchbox` would unblock this without any changes to FreeSpeech.
- If you get the car mic button working on any device/version combination, please open an issue and share your gearhead version and logcat output.

## Requirements

- Android 10 (API 29) or later
- A Whisper-compatible transcription endpoint (see below)
- Android Auto installed (gearhead APK — see installation notes for /e/OS)

## Setting up a Whisper server

Any server that speaks the OpenAI `/v1/audio/transcriptions` API format works. This repo includes `whisper_server.py` — a minimal Flask server using [faster-whisper](https://github.com/guillaumekln/faster-whisper).

### Install dependencies

```bash
pip install faster-whisper flask
```

### Run once (foreground)

```bash
python3 whisper_server.py
```

On first launch it downloads the `small` Whisper model (~500 MB). Once you see `* Running on http://0.0.0.0:8080` it is ready.

### Run permanently (background, survives logout)

```bash
nohup python3 whisper_server.py > whisper.log 2>&1 &
tail -f whisper.log   # watch startup, Ctrl+C to stop watching
```

### Run as a systemd service (starts on boot)

```bash
sudo nano /etc/systemd/system/whisper.service
```

Paste:

```ini
[Unit]
Description=Whisper transcription server
After=network.target

[Service]
User=YOUR_USERNAME
WorkingDirectory=/path/to/FreeSpeech
ExecStart=python3 /path/to/FreeSpeech/whisper_server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Then enable it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now whisper
sudo systemctl status whisper
```

### Configuration

Edit `whisper_server.py` to tune:

| Setting | Default | Options |
|---|---|---|
| Model size | `"small"` | `"tiny"`, `"base"`, `"medium"`, `"large"` |
| Language | `"de"` (German) | Any BCP-47 code, or remove for auto-detect |
| Device | `"cpu"` | `"cuda"` if you have an Nvidia GPU |

The FreeSpeech settings screen lets you enter the server URL, e.g. `http://192.168.1.10:8080/v1/audio/transcriptions`.

### Accessing the server from outside your network

If the Whisper server is on a private network, you can expose it through a DMZ or jump host using an SSH reverse tunnel:

```bash
# Run this on the Whisper server — requires GatewayPorts yes in sshd_config on the DMZ host
ssh -N -R 8082:localhost:8080 user@your-dmz-server
```

To keep the tunnel alive automatically, use `autossh`:

```bash
sudo apt install autossh   # or dnf install autossh
autossh -M 9090 -N -R 8082:localhost:8080 user@your-dmz-server
```

Or as a systemd service (same pattern as above, replace `ExecStart` with the `autossh` command).

## Installation

### 1. Build the APK

Install [Android Studio](https://developer.android.com/studio), open the `freespeech/` directory, and choose **Build → Build APK**.

Or from the command line (requires Android SDK):

```bash
cd freespeech
./gradlew assembleDebug
```

### 2. Install on your phone

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Set FreeSpeech as your speech recognition engine and digital assistant

On the phone:

**Settings → Apps → Default apps → Speech recognition → FreeSpeech**

**Settings → Apps → Default apps → Digital assistant → FreeSpeech**

(Paths may vary slightly by /e/OS version. Look for "Spracheingabe" and "Digitaler Assistent" on German-language systems.)

### 3b. Enable Unknown Sources in Android Auto

This allows sideloaded Car App Library apps (like FreeSpeech) to appear in the AA launcher:

1. Open Android Auto on the phone
2. Tap the version number **10 times** to unlock developer settings
3. **Developer settings → Unknown sources → On**

FreeSpeech will then appear in the car screen's app list the next time you connect to the car.

### 4. Configure the Whisper endpoint

Open the FreeSpeech app, enter your server URL, tap **Save**.

### 5. Test

- **Car screen (Android Auto)**: open the app list on the car display → tap FreeSpeech → it auto-starts listening
- **Phone button**: long-press the power or home button — FreeSpeech overlay should appear and start listening
- **Keyboard mic**: tap the microphone icon in any text field

## Notes for Murena /e/OS users

Android Auto itself (the `gearhead` package) does not come pre-installed on /e/OS and must be sideloaded manually. See the [installation guide](../apkBackup/howToRestoreAndroidAuto.txt) in this repository for the full procedure including how to back up and restore the APK across system updates.

## Roadmap

- [x] Android Auto Car App — FreeSpeech appears in AA launcher with auto-start mic UI
- [ ] Android Auto car mic button — blocked by gearhead + microG compatibility (see Known limitations)
- [ ] NLU layer: route transcriptions to contacts (calls), media apps, navigation
- [ ] WhatsApp message dictation via Accessibility Service
- [ ] On-device Whisper option (whisper.cpp, no server needed)
- [ ] Hotword wake support independent of Android Auto

## Privacy

FreeSpeech sends audio only to the endpoint you configure. No telemetry, no analytics, no third-party SDKs. The source is here — read it.

## Contributing

This project is in early development. If you get the car mic button working on any device/ROM/gearhead version combination, please open an issue and share:
- Your ROM and version
- gearhead version (`adb shell dumpsys package com.google.android.projection.gearhead | grep versionName`)
- googlequicksearchbox version (`adb shell dumpsys package com.google.android.googlequicksearchbox | grep versionName`)
- Relevant logcat around the mic button press

Every data point helps narrow down where the fallback path still exists.

## Licence

Apache 2.0
