#!/usr/bin/env python3
"""
FreeSpeech command log server
==============================
Receives anonymous voice-command entries from the FreeSpeech Android app and
maintains a rolling JSON-lines file of the last 1 000 commands.

Each entry contains:
  cmd   – what the user said (Whisper transcript)
  cat   – intent category (MUSIC, NAVIGATION, WEATHER, CALL, WEB_SEARCH, NONE)
  query – extracted subject (artist, destination, …)
  lang  – BCP-47 language tag detected by Whisper ("de", "en", …)
  ai    – AI engine used ("gemini", "openai", "ollama"); absent for keyword matching
  ts    – ISO-8601 UTC timestamp (added by the server)

No audio, no IP addresses, no user IDs are stored.

──────────────────────────────────────────────────────────────────────────────
Usage
──────────────────────────────────────────────────────────────────────────────
  pip install flask
  python3 freespeech_log_server.py [--port 8081] [--log /var/log/freespeech.jsonl]

  # In FreeSpeech settings, set "Command log URL" to:
  #   http://<this-server>:<port>/v1/log

Endpoints
──────────────────────────────────────────────────────────────────────────────
  POST /v1/log          receive one entry from the app (JSON body)
  GET  /v1/log          browser-friendly HTML table (newest first)
  GET  /v1/log/raw      raw JSONL download
"""

import argparse
import json
import os
import threading
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, request, Response, jsonify

# ── Configuration ──────────────────────────────────────────────────────────────

DEFAULT_PORT    = 8081
DEFAULT_LOG     = Path(__file__).parent / "freespeech_commands.jsonl"
MAX_ENTRIES     = 1000

app    = Flask(__name__)
_lock  = threading.Lock()          # serialises all file access


# ── Helpers ────────────────────────────────────────────────────────────────────

def _read_entries(log_path: Path) -> list[dict]:
    """Read all JSONL entries; silently skip malformed lines."""
    if not log_path.exists():
        return []
    entries = []
    for line in log_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return entries


def _write_entries(log_path: Path, entries: list[dict]) -> None:
    """Overwrite the log file with [entries], keeping at most MAX_ENTRIES."""
    entries = entries[-MAX_ENTRIES:]          # rolling window
    log_path.write_text(
        "\n".join(json.dumps(e, ensure_ascii=False) for e in entries) + "\n",
        encoding="utf-8",
    )


def _fmt_entry(e: dict) -> str:
    """One-line human-readable representation of a log entry."""
    cat   = e.get("cat",   "?")
    query = e.get("query", "")
    result = f"{cat}: {query}" if query else cat

    lang = e.get("lang", "?")
    ai   = e.get("ai")
    meta = f"({lang}, ai:{ai})" if ai else f"({lang})"

    return f"{e.get('cmd', '')}  →  {result}  {meta}"


# ── Routes ─────────────────────────────────────────────────────────────────────

@app.route("/v1/log", methods=["POST"])
def receive_log():
    """Append one entry from the FreeSpeech app to the rolling log."""
    data = request.get_json(silent=True)
    if not data or "cmd" not in data:
        return jsonify({"error": "missing 'cmd' field"}), 400

    entry = {
        "ts":    datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cmd":   str(data.get("cmd",   "")).strip(),
        "cat":   str(data.get("cat",   "NONE")).upper(),
        "query": str(data.get("query", "")).strip(),
        "lang":  str(data.get("lang",  "?")).strip(),
    }
    if "ai" in data:
        entry["ai"] = str(data["ai"]).strip()

    log_path = app.config["LOG_PATH"]
    with _lock:
        entries = _read_entries(log_path)
        entries.append(entry)
        _write_entries(log_path, entries)

    return jsonify({"ok": True}), 200


@app.route("/v1/log", methods=["GET"])
def show_log():
    """Render the command log as an auto-refreshing HTML page."""
    log_path = app.config["LOG_PATH"]
    with _lock:
        entries = _read_entries(log_path)

    entries_rev = list(reversed(entries))     # newest first
    total = len(entries)
    cap   = MAX_ENTRIES

    rows = []
    for e in entries_rev:
        ts    = e.get("ts", "")
        cmd   = e.get("cmd", "")
        cat   = e.get("cat", "?")
        query = e.get("query", "")
        lang  = e.get("lang", "?")
        ai    = e.get("ai", "")

        result_html = f"<b>{cat}</b>: {_esc(query)}" if query else f"<b>{cat}</b>"
        meta_parts  = [f"<code>{lang}</code>"]
        if ai:
            meta_parts.append(f"ai:{_esc(ai)}")
        meta_html = f"({'&thinsp;'.join(meta_parts)})"

        rows.append(
            f"<tr>"
            f"<td class='ts'>{_esc(ts)}</td>"
            f"<td class='cmd'>{_esc(cmd)}</td>"
            f"<td class='arr'>→</td>"
            f"<td class='res'>{result_html}</td>"
            f"<td class='meta'>{meta_html}</td>"
            f"</tr>"
        )

    rows_html = "\n".join(rows) if rows else "<tr><td colspan='5' class='empty'>No commands logged yet.</td></tr>"

    html = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>FreeSpeech Command Log</title>
<style>
  body {{ font-family: system-ui, sans-serif; max-width: 1200px; margin: 2rem auto; padding: 0 1rem; background:#f8f9fa; color:#212529; }}
  h1   {{ font-size:1.4rem; margin-bottom:.25rem; }}
  .sub {{ color:#6c757d; font-size:.9rem; margin-bottom:1.5rem; }}
  table{{ border-collapse:collapse; width:100%; background:#fff; border-radius:6px; overflow:hidden; box-shadow:0 1px 4px rgba(0,0,0,.1); }}
  th   {{ background:#343a40; color:#fff; text-align:left; padding:.6rem .8rem; font-weight:600; font-size:.85rem; }}
  td   {{ padding:.5rem .8rem; border-bottom:1px solid #dee2e6; font-size:.88rem; vertical-align:top; }}
  tr:last-child td {{ border-bottom:none; }}
  tr:hover td {{ background:#f1f3f5; }}
  .ts  {{ white-space:nowrap; color:#6c757d; font-size:.8rem; min-width:9rem; }}
  .cmd {{ font-style:italic; max-width:30rem; word-break:break-word; }}
  .arr {{ color:#adb5bd; padding:0 .3rem; }}
  .res {{ min-width:12rem; }}
  .meta{{ white-space:nowrap; color:#6c757d; }}
  .meta code{{ background:#e9ecef; padding:.1em .3em; border-radius:3px; font-size:.8em; }}
  .empty {{ text-align:center; padding:2rem; color:#6c757d; }}
  a.dl {{ float:right; font-size:.85rem; color:#0d6efd; text-decoration:none; }}
  a.dl:hover {{ text-decoration:underline; }}
</style>
</head>
<body>
<h1>🎤 FreeSpeech Command Log
  <a class="dl" href="/v1/log/raw" title="Download raw JSONL">⬇ JSONL</a>
</h1>
<p class="sub">{total} of {cap} entries — refreshes every 30 s</p>
<table>
<thead><tr>
  <th>Time (UTC)</th><th>Command</th><th></th><th>Result</th><th>Meta</th>
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


# ── Escaping helper ────────────────────────────────────────────────────────────

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
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"TCP port to listen on (default {DEFAULT_PORT})")
    parser.add_argument("--log",  type=Path, default=DEFAULT_LOG,
                        help=f"Path to JSONL log file (default {DEFAULT_LOG})")
    parser.add_argument("--host", default="0.0.0.0",
                        help="Bind address (default 0.0.0.0)")
    args = parser.parse_args()

    args.log.parent.mkdir(parents=True, exist_ok=True)
    app.config["LOG_PATH"] = args.log

    print(f"FreeSpeech log server  →  http://{args.host}:{args.port}/v1/log")
    print(f"Log file               →  {args.log}")
    app.run(host=args.host, port=args.port, debug=False)
