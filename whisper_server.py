from flask import Flask, request, jsonify
from faster_whisper import WhisperModel
import tempfile, os

app = Flask(__name__)
model = WhisperModel("small", device="cpu", compute_type="int8")

@app.route("/v1/audio/transcriptions", methods=["POST"])
def transcribe():
    audio = request.files["file"]
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        audio.save(f.name)
        tmp = f.name
    try:
        segments, _ = model.transcribe(tmp, language="de")
        text = " ".join(s.text.strip() for s in segments)
    finally:
        os.unlink(tmp)
    return jsonify({"text": text})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
