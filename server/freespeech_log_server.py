#!/usr/bin/env python3
"""
FreeSpeech command log server
==============================
Receives anonymous voice-command entries from the FreeSpeech Android app and
maintains:
  • a rolling JSONL file of the last 1 000 text entries
  • a directory of the last 100 WAV recordings for audio analysis

Each log entry:
  ts    – ISO-8601 UTC timestamp (added by server)
  cmd   – Whisper transcript ("what the user said")
  cat   – intent category (MUSIC / NAVIGATION / WEATHER / CALL / WEB_SEARCH / NONE)
  query – extracted subject (artist, destination, …)
  lang  – BCP-47 language detected by Whisper ("de", "en", …)
  ai    – AI engine used ("gemini", "openai", "ollama"); absent for keyword matching
  wav   – WAV filename stored on server; absent when no audio was uploaded

No audio is stored beyond 100 commands; no IP addresses, no user IDs.

──────────────────────────────────────────────────────────────────────────────
Usage
──────────────────────────────────────────────────────────────────────────────
  pip install flask
  python3 freespeech_log_server.py [--port 8081] [--log PATH] [--audio-dir DIR]

  In FreeSpeech settings → "Command log URL":  http://<host>:8081/v1/log

Endpoints
──────────────────────────────────────────────────────────────────────────────
  POST /v1/log               receive multipart entry (meta field + optional audio file)
  GET  /v1/log               browser HTML table with inline audio players
  GET  /v1/log/raw           download raw JSONL
  GET  /v1/audio/<filename>  stream a stored WAV file
"""

import argparse
import json
import os
import threading
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, request, Response, send_from_directory

# ── Defaults ───────────────────────────────────────────────────────────────────

DEFAULT_PORT      = 8081
DEFAULT_LOG       = Path(__file__).parent / "freespeech_commands.jsonl"
DEFAULT_AUDIO_DIR = Path(__file__).parent / "audio"
MAX_LOG_ENTRIES   = 1000
MAX_WAV_FILES     = 100

app   = Flask(__name__)
_lock = threading.Lock()      # serialises all file I/O


# ── File helpers ───────────────────────────────────────────────────────────────

def _read_entries(log_path: Path) -> list[dict]:
    """Read JSONL, skip malformed lines."""
    if not log_path.exists():
        return []
    entries = []
    for line in log_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            try:
                entries.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return entries


def _write_entries(log_path: Path, entries: list[dict]) -> None:
    """Write entries (trimmed to MAX_LOG_ENTRIES) back to JSONL."""
    entries = entries[-MAX_LOG_ENTRIES:]
    log_path.write_text(
        "\n".join(json.dumps(e, ensure_ascii=False) for e in entries) + "\n",
        encoding="utf-8",
    )


def _save_wav(audio_dir: Path, ts_safe: str, lang: str, cat: str, data: bytes) -> str:
    """
    Save a WAV file and prune the directory to MAX_WAV_FILES.
    Returns the filename (not the full path).
    """
    audio_dir.mkdir(parents=True, exist_ok=True)

    # Filename: sortable timestamp + lang + category, e.g. 20260813_143211_de_MUSIC.wav
    lang_safe = (lang or "xx").replace("/", "-").replace("\\", "-")[:5]
    cat_safe  = (cat  or "NONE").replace("/", "-").upper()[:12]
    filename  = f"{ts_safe}_{lang_safe}_{cat_safe}.wav"
    (audio_dir / filename).write_bytes(data)

    # Prune: keep only the newest MAX_WAV_FILES files.
    wavs = sorted(audio_dir.glob("*.wav"))   # lexicographic = chronological (timestamp prefix)
    for old in wavs[:-MAX_WAV_FILES]:
        try:
            old.unlink()
        except OSError:
            pass

    return filename


# ── Routes ─────────────────────────────────────────────────────────────────────

@app.route("/v1/log", methods=["POST"])
def receive_log():
    """
    Accept a multipart/form-data POST from the FreeSpeech app.
    Fields:
      meta  – JSON string with cmd / cat / query / lang / ai?
      audio – WAV binary (optional)
    Also accepts plain application/json for backward compatibility.
    """
    # ── Parse metadata ─────────────────────────────────────────────────────────
    if request.content_type and "multipart" in request.content_type:
        meta_str = request.form.get("meta", "{}")
        try:
            data = json.loads(meta_str)
        except json.JSONDecodeError:
            return Response("bad meta JSON", status=400)
        audio_file = request.files.get("audio")
    else:
        # Backward-compat: plain JSON body (no WAV)
        data = request.get_json(silent=True) or {}
        audio_file = None

    if not data.get("cmd"):
        return Response("missing 'cmd' field", status=400)

    # ── Build log entry ────────────────────────────────────────────────────────
    now    = datetime.now(timezone.utc)
    ts_iso = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    ts_safe = now.strftime("%Y%m%d_%H%M%S")   # filesystem-safe

    lang = str(data.get("lang", "?")).strip()
    cat  = str(data.get("cat",  "NONE")).upper().strip()

    entry: dict = {
        "ts":    ts_iso,
        "cmd":   str(data.get("cmd",   "")).strip(),
        "cat":   cat,
        "query": str(data.get("query", "")).strip(),
        "lang":  lang,
    }
    if "ai" in data:
        entry["ai"] = str(data["ai"]).strip()

    # ── Store WAV (if provided) ────────────────────────────────────────────────
    log_path  = app.config["LOG_PATH"]
    audio_dir = app.config["AUDIO_DIR"]

    with _lock:
        if audio_file:
            wav_bytes = audio_file.read()
            if wav_bytes:
                wav_name = _save_wav(audio_dir, ts_safe, lang, cat, wav_bytes)
                entry["wav"] = wav_name

        # ── Append to JSONL ────────────────────────────────────────────────────
        entries = _read_entries(log_path)
        entries.append(entry)
        _write_entries(log_path, entries)

    return Response('{"ok":true}', status=200, mimetype="application/json")


@app.route("/v1/audio/<path:filename>")
def serve_audio(filename: str):
    """Stream a stored WAV file (for the inline browser player)."""
    audio_dir = app.config["AUDIO_DIR"]
    return send_from_directory(audio_dir, filename, mimetype="audio/wav")


@app.route("/v1/log", methods=["GET"])
def show_log():
    """Render the command log as an auto-refreshing HTML page with inline audio."""
    log_path  = app.config["LOG_PATH"]
    audio_dir = app.config["AUDIO_DIR"]

    with _lock:
        entries = _read_entries(log_path)

    entries_rev = list(reversed(entries))
    total = len(entries)
    cap   = MAX_LOG_ENTRIES
    n_wav = sum(1 for e in entries if e.get("wav"))

    rows = []
    for e in entries_rev:
        ts    = _esc(e.get("ts",    ""))
        cmd   = _esc(e.get("cmd",   ""))
        cat   = _esc(e.get("cat",   "?"))
        query = _esc(e.get("query", ""))
        lang  = _esc(e.get("lang",  "?"))
        ai    = e.get("ai", "")

        result_html = f"<b>{cat}</b>: {query}" if query else f"<b>{cat}</b>"

        meta_parts = [f"<code>{lang}</code>"]
        if ai:
            meta_parts.append(f"ai:{_esc(ai)}")
        meta_html = f"({'&thinsp;'.join(meta_parts)})"

        wav_name = e.get("wav", "")
        if wav_name and (audio_dir / wav_name).exists():
            audio_html = (
                f'<audio controls preload="none" style="height:22px;max-width:180px">'
                f'<source src="/v1/audio/{_esc(wav_name)}" type="audio/wav">'
                f'</audio>'
                f'<br><a href="/v1/audio/{_esc(wav_name)}" download '
                f'   style="font-size:.75rem;color:#6c757d">{_esc(wav_name)}</a>'
            )
        elif wav_name:
            # WAV was pruned (older than the last 100)
            audio_html = '<span style="color:#adb5bd;font-size:.8rem">pruned</span>'
        else:
            audio_html = '<span style="color:#dee2e6">–</span>'

        rows.append(
            f"<tr>"
            f"<td class='ts'>{ts}</td>"
            f"<td class='cmd'>{cmd}</td>"
            f"<td class='arr'>→</td>"
            f"<td class='res'>{result_html}</td>"
            f"<td class='meta'>{meta_html}</td>"
            f"<td class='aud'>{audio_html}</td>"
            f"</tr>"
        )

    rows_html = "\n".join(rows) if rows else (
        "<tr><td colspan='6' class='empty'>No commands logged yet.</td></tr>"
    )

    html = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>FreeSpeech Command Log</title>
<style>
  body  {{ font-family:system-ui,sans-serif; max-width:1400px; margin:2rem auto;
           padding:0 1rem; background:#f8f9fa; color:#212529; }}
  h1    {{ font-size:1.4rem; margin-bottom:.2rem; }}
  .sub  {{ color:#6c757d; font-size:.9rem; margin-bottom:1.5rem; }}
  table {{ border-collapse:collapse; width:100%; background:#fff;
           border-radius:6px; overflow:hidden;
           box-shadow:0 1px 4px rgba(0,0,0,.1); }}
  th    {{ background:#343a40; color:#fff; text-align:left;
           padding:.6rem .8rem; font-weight:600; font-size:.82rem; }}
  td    {{ padding:.45rem .8rem; border-bottom:1px solid #dee2e6;
           font-size:.86rem; vertical-align:middle; }}
  tr:last-child td {{ border-bottom:none; }}
  tr:hover td {{ background:#f1f3f5; }}
  .ts   {{ white-space:nowrap; color:#6c757d; font-size:.78rem; min-width:10rem; }}
  .cmd  {{ font-style:italic; max-width:26rem; word-break:break-word; }}
  .arr  {{ color:#adb5bd; padding:0 .3rem; width:1rem; }}
  .res  {{ min-width:11rem; }}
  .meta {{ white-space:nowrap; color:#6c757d; }}
  .meta code {{ background:#e9ecef; padding:.1em .3em;
               border-radius:3px; font-size:.78em; }}
  .aud  {{ min-width:13rem; }}
  .empty{{ text-align:center; padding:2rem; color:#6c757d; }}
  a.dl  {{ float:right; font-size:.85rem; color:#0d6efd; text-decoration:none; }}
  a.dl:hover {{ text-decoration:underline; }}
  audio {{ vertical-align:middle; }}
</style>
</head>
<body>
<h1>🎤 FreeSpeech Command Log
  <a class="dl" href="/v1/log/raw" title="Download raw JSONL">⬇ JSONL</a>
</h1>
<p class="sub">
  {total}&thinsp;/&thinsp;{cap} entries &nbsp;·&nbsp;
  {n_wav} recordings stored (last {MAX_WAV_FILES} kept) &nbsp;·&nbsp;
  auto-refreshes every 30 s
</p>
<table>
<thead><tr>
  <th>Time (UTC)</th>
  <th>Command (Whisper)</th>
  <th></th>
  <th>Result</th>
  <th>Meta</th>
  <th>Recording</th>
</tr></thead>
<tbody>
{rows_html}
</tbody>
</table>
</body>
</html>"""
    return Response(html, mimetype="text/html")


@app.route("/v1/log/raw", methods=["GET"])
def raw_log():
    """Download the raw JSONL file."""
    log_path = app.config["LOG_PATH"]
    with _lock:
        content = log_path.read_text(encoding="utf-8") if log_path.exists() else ""
    return Response(
        content,
        mimetype="text/plain",
        headers={"Content-Disposition": "attachment; filename=freespeech_commands.jsonl"},
    )


# ── HTML escape ────────────────────────────────────────────────────────────────

def _esc(s: str) -> str:
    return (
        s.replace("&", "&amp;")
         .replace("<", "&lt;")
         .replace(">", "&gt;")
         .replace('"', "&quot;")
    )


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="FreeSpeech anonymous command log server")
    parser.add_argument("--port",      type=int,  default=DEFAULT_PORT)
    parser.add_argument("--host",      default="0.0.0.0")
    parser.add_argument("--log",       type=Path, default=DEFAULT_LOG,
                        help="Path to JSONL log file")
    parser.add_argument("--audio-dir", type=Path, default=DEFAULT_AUDIO_DIR,
                        dest="audio_dir",
                        help="Directory for WAV recordings (last 100 kept)")
    args = parser.parse_args()

    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.audio_dir.mkdir(parents=True, exist_ok=True)

    app.config["LOG_PATH"]  = args.log
    app.config["AUDIO_DIR"] = args.audio_dir

    print(f"FreeSpeech log server")
    print(f"  HTML view  →  http://{args.host}:{args.port}/v1/log")
    print(f"  Log file   →  {args.log}")
    print(f"  WAV dir    →  {args.audio_dir}  (keep last {MAX_WAV_FILES})")
    app.run(host=args.host, port=args.port, debug=False)
