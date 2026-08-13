# FreeSpeech Admin

Rails 8 web UI for managing the FreeSpeech backend.

## Tabs

| Tab | What it does |
|-----|-------------|
| **Config** | Whisper server URL & model, AI engine/model/API key, language list, log paths |
| **History** | Paginated view of the Whisper command log (filtering by lang / category / keyword, inline audio playback) |

## API endpoints (no auth — can be consumed by the app or Python server)

| Path | Response |
|------|----------|
| `GET /api/config` | JSON: all settings except the API key |
| `GET /api/languages` | JSON: active languages `[{code, name}, …]` |

## Quick start (development)

```bash
cd server/freespeech-admin
bundle install
bin/rails db:create db:migrate db:seed
bin/rails server -p 3001
```

Open **http://localhost:3001** — default login: `admin` / `freespeech`.

## Production deploy on pittyvaich

```bash
# 1. Create user and deploy directory
sudo useradd -r -s /sbin/nologin freespeech          # skip if already exists
sudo mkdir -p /opt/freespeech-admin
sudo chown freespeech:freespeech /opt/freespeech-admin

# 2. Copy app
sudo rsync -av --exclude='.git' --exclude='log' --exclude='tmp' \
  server/freespeech-admin/ /opt/freespeech-admin/

# 3. Create .env with secrets
sudo tee /opt/freespeech-admin/.env << 'EOF'
RAILS_ENV=production
SECRET_KEY_BASE=<output of: cd /opt/freespeech-admin && bundle exec rails secret>
FREESPEECH_ADMIN_USER=admin
FREESPEECH_ADMIN_PASSWORD=changeme
EOF
sudo chmod 600 /opt/freespeech-admin/.env
sudo chown freespeech:freespeech /opt/freespeech-admin/.env

# 4. Install gems and set up DB
cd /opt/freespeech-admin
sudo -u freespeech bundle install --deployment --without development test
sudo -u freespeech RAILS_ENV=production bundle exec rails db:create db:migrate db:seed

# 5. Install and start the service
sudo cp freespeech-admin.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now freespeech-admin
sudo systemctl status freespeech-admin
```

Admin UI is at **http://pittyvaich:3001**

## Configuration

All settings are persisted in the SQLite database (updated via the UI).

| Setting | Default | Description |
|---------|---------|-------------|
| `whisper_url` | `http://localhost:8080/v1/audio/transcriptions` | Whisper API endpoint |
| `whisper_model` | `large-v3` | Whisper model name |
| `ai_engine` | `ollama` | `ollama` / `gemini` / `openai` |
| `ai_model` | `qwen2.5:7b` | Model tag / name |
| `ai_base_url` | `http://localhost:11434` | For Ollama / custom OpenAI base |
| `ai_api_key` | *(blank)* | API key — never exposed via the JSON API |
| `log_jsonl_path` | `/var/log/freespeech/freespeech_commands.jsonl` | JSONL file written by the Python log server |
| `log_audio_dir` | `/var/log/freespeech/audio` | WAV directory |
| `log_max_entries` | `1000` | Rolling log cap |
| `log_max_wavs` | `100` | WAV file cap |

## Languages

English (en), German (de) and French (fr) are **protected** — they cannot be removed.
All other languages can be added (any valid BCP-47 code, e.g. `pt`, `zh-TW`, `ru`) or removed via the Config tab.
