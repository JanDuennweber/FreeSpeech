# FreeSpeech – Privacy Policy

*Last updated: 2026-08-21*

## Summary

FreeSpeech is designed around one principle: **your voice data stays on your own hardware**. The app does not collect, transmit, or sell any personal data to the developer or any third party controlled by the developer.

---

## What data FreeSpeech processes

| Data | Where it goes | Who controls it |
|---|---|---|
| Voice recordings | Your self-hosted Whisper server | You |
| Transcribed text (commands) | Your self-hosted LLM (Ollama) or a cloud API you configure | You / your chosen provider |
| TTS responses | Your self-hosted Kokoro-FastAPI server | You |
| App settings (server URLs, API keys) | Stored locally on your device only | You |
| Command history & audio logs | Stored locally on your device only | You |

---

## Optional cloud API providers

If you configure FreeSpeech to use a cloud LLM instead of a local one (Gemini, OpenAI, or Anthropic), your transcribed voice commands are sent to that provider under **their** privacy policy:

- [Google Gemini Privacy Policy](https://policies.google.com/privacy)
- [OpenAI Privacy Policy](https://openai.com/policies/privacy-policy)
- [Anthropic Privacy Policy](https://www.anthropic.com/privacy)

FreeSpeech does not use any cloud API by default. Cloud API use requires you to explicitly enter an API key in the app settings.

---

## What FreeSpeech does NOT do

- Does not send voice or text data to the developer
- Does not use analytics or crash-reporting SDKs
- Does not contain advertising SDKs
- Does not require a Google account or Google Play Services
- Does not store any data outside your device or your own servers

---

## Android permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Capture voice commands via the car microphone |
| `INTERNET` | Communicate with your self-hosted servers on your local network or VPN |
| `POST_NOTIFICATIONS` | Show wake-word detection status notifications |
| `FOREGROUND_SERVICE` | Keep wake-word detection running while the screen is off |

---

## Data retention

Audio logs and command history are stored locally on your device and can be deleted at any time from the FreeSpeech Console screen inside the app. No copies exist elsewhere unless you have configured a self-hosted server that retains them — that retention is under your own control.

---

## Children's privacy

FreeSpeech is not directed at children under 13 and does not knowingly collect any information from them.

---

## Changes to this policy

If this policy changes materially, the updated version will be published at this URL with a new "Last updated" date.

---

## Contact

For questions about this privacy policy, open an issue at:
**https://github.com/JanDuennweber/FreeSpeech/issues**
