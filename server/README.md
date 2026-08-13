# FreeSpeech Server

The server component has two parts:

## FreeSpeech Console (current — `freespeech-admin/`)

A Rails 8 web application providing:
- Admin UI: AI engine config, grammar correction, TTS, custom topic mappings, command history
- Per-user RAG document library (upload PDF/EPUB, maps to topic keyword)
- API endpoints used by the Android app (`/api/classify`, `/api/tts`, `/api/custom_topics`)

See the [root README](../README.md) for full setup instructions including deployment topologies
(split GPU server + public console host vs. single-server).

```bash
cd freespeech-admin
bundle install
bin/rails db:setup
bin/rails server -b 0.0.0.0 -p 3001
```

## Legacy Python log server (`freespeech_log_server.py`)

The standalone Python/Flask log server that predates the Rails console.
It is no longer needed — the Rails console includes command history with audio playback.
Kept here for reference only.
