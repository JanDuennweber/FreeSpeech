# FreeSpeech log server

Lightweight Python/Flask service that receives anonymous voice-command entries
from FreeSpeech Android apps and shows them in a browser.

## What is stored

| Field | Example | Description |
|-------|---------|-------------|
| `ts`    | `2026-08-13T14:32:11Z` | UTC timestamp (server-side) |
| `cmd`   | `spiel David Bowie` | Whisper transcript |
| `cat`   | `MUSIC` | Intent category |
| `query` | `David Bowie` | Extracted subject |
| `lang`  | `de` | Language Whisper detected |
| `ai`    | `gemini` | AI engine used (omitted for keyword matching) |
| `wav`   | `20260813_143211_de_MUSIC.wav` | WAV filename (last 100 commands only) |

- **JSONL log**: rolling 1 000-entry text file
- **WAV archive**: rolling directory of the last 100 recordings (≈ 100–400 KB each, ~40 MB total)
- **No IP addresses, no user IDs are stored.**

## Quick start

```bash
pip install flask
python3 freespeech_log_server.py
```

Open **http://localhost:8081/v1/log** in a browser to see the table.

## Options

```
--port       8081                          TCP port (default 8081)
--host       0.0.0.0                       Bind address
--log        ./freespeech_commands.jsonl   JSONL log file path
--audio-dir  ./audio                       WAV storage directory (last 100 kept)
```

## FreeSpeech app setup

In the FreeSpeech settings screen, set **Command log URL** to:

```
http://<your-server>:8081/v1/log
```

Leave the field blank to disable logging entirely.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/log` | Receive multipart entry from the app (`meta` JSON + optional `audio` WAV) |
| `GET`  | `/v1/log` | HTML table with inline audio players, auto-refreshes every 30 s |
| `GET`  | `/v1/log/raw` | Download raw JSONL file |
| `GET`  | `/v1/audio/<filename>` | Stream a stored WAV file |

JSONL keeps the last **1 000** entries; WAV directory keeps the last **100** recordings.

## systemd service

```bash
# Create a dedicated user
sudo useradd -r -s /sbin/nologin freespeech
sudo mkdir -p /opt/freespeech-log /var/log/freespeech/audio
sudo chown -R freespeech:freespeech /opt/freespeech-log /var/log/freespeech
sudo cp freespeech_log_server.py /opt/freespeech-log/

# Install and start the service
sudo cp freespeech-log.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now freespeech-log
sudo systemctl status freespeech-log
```
