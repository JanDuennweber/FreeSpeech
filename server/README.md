# FreeSpeech log server

Lightweight Python/Flask service that receives anonymous voice-command entries
from FreeSpeech Android apps and shows them in a browser.

## What is logged

| Field | Example | Description |
|-------|---------|-------------|
| `ts`  | `2026-08-13T14:32:11Z` | UTC timestamp (server-side) |
| `cmd` | `spiel David Bowie` | Whisper transcript |
| `cat` | `MUSIC` | Intent category |
| `query` | `David Bowie` | Extracted subject |
| `lang` | `de` | Language Whisper detected |
| `ai`  | `gemini` | AI engine used (omitted for keyword matching) |

**No audio, no IP addresses, no user IDs are stored.**

## Quick start

```bash
pip install flask
python3 freespeech_log_server.py
```

Open **http://localhost:8081/v1/log** in a browser to see the table.

## Options

```
--port  8081                          TCP port (default 8081)
--host  0.0.0.0                       Bind address
--log   ./freespeech_commands.jsonl   JSONL file path
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
| `POST` | `/v1/log` | Receive one entry (JSON body) from the app |
| `GET`  | `/v1/log` | HTML table, auto-refreshes every 30 s |
| `GET`  | `/v1/log/raw` | Download raw JSONL file |

The file keeps the last **1 000** entries; older ones are discarded automatically.

## systemd service

```bash
# Create a dedicated user
sudo useradd -r -s /sbin/nologin freespeech
sudo mkdir -p /opt/freespeech-log /var/log/freespeech
sudo chown freespeech:freespeech /opt/freespeech-log /var/log/freespeech
sudo cp freespeech_log_server.py /opt/freespeech-log/

# Install and start the service
sudo cp freespeech-log.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now freespeech-log
sudo systemctl status freespeech-log
```
