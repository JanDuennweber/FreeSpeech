# FreeSpeech

**Open-source voice assistant for Android Auto on de-Googled Android.**

FreeSpeech gives users of privacy-respecting Android ROMs — Murena /e/OS, GrapheneOS, CalyxOS, LineageOS with microG — a working voice input engine for Android Auto. On these systems the microphone button in Android Auto shows "speech commands are currently not supported" because Google Assistant is absent. FreeSpeech fills that gap without sending your voice to Google.

## How it works

FreeSpeech registers itself as an Android `RecognitionService` — the standard system interface that Android Auto queries for speech recognition. When the microphone is pressed in Android Auto, the system routes the audio to FreeSpeech, which:

1. Records audio from the microphone
2. Sends it to a [Whisper](https://github.com/openai/whisper)-compatible HTTP endpoint of your choice
3. Returns the transcription to Android Auto

You control which Whisper server is used. Self-host one on your own hardware, use a server on your local network, or point it at any OpenAI-compatible API. Your voice never reaches Google.

## Requirements

- Android 10 (API 29) or later
- A Whisper-compatible transcription endpoint (see below)
- Android Auto installed (gearhead APK — see installation notes for /e/OS)

## Setting up a Whisper server

Any server that speaks the OpenAI `/v1/audio/transcriptions` API format works. The simplest self-hosted option:

```bash
pip install faster-whisper flask
```

Then run a minimal server (or use [faster-whisper-server](https://github.com/fedirz/faster-whisper-server), [LocalAI](https://github.com/mudler/LocalAI), or [whisper.cpp](https://github.com/ggerganov/whisper.cpp) with its built-in server).

The FreeSpeech settings screen lets you enter the URL, e.g. `http://192.168.1.10:8080/v1/audio/transcriptions`.

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

### 3. Set FreeSpeech as your speech recognition engine

On the phone:

**Settings → Apps → Default apps → Speech recognition → FreeSpeech**

(Path may vary slightly by /e/OS version.)

### 4. Configure the Whisper endpoint

Open the FreeSpeech app, enter your server URL, tap **Save**.

### 5. Test in Android Auto

Connect to your car and press the microphone button. FreeSpeech will record your voice and return the transcription.

## Notes for Murena /e/OS users

Android Auto itself (the `gearhead` package) does not come pre-installed on /e/OS and must be sideloaded manually. See the [installation guide](../apkBackup/howToRestoreAndroidAuto.txt) in this repository for the full procedure including how to back up and restore the APK across system updates.

## Roadmap

- [ ] Confirm Android Auto mic button integration end-to-end
- [ ] NLU layer: route transcriptions to contacts (calls), media apps, navigation
- [ ] WhatsApp message dictation via Accessibility Service
- [ ] On-device Whisper option (whisper.cpp, no server needed)
- [ ] Hotword wake support independent of Android Auto

## Privacy

FreeSpeech sends audio only to the endpoint you configure. No telemetry, no analytics, no third-party SDKs. The source is here — read it.

## Contributing

This project is in early development. If you get the mic button working in Android Auto on your device, please open an issue and share your logcat output — every device variant helps.

## Licence

Apache 2.0
