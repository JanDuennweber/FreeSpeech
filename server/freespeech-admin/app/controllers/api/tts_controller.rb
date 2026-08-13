require "net/http"
require "json"
require "uri"

module Api
  # POST /api/tts
  # Proxies a text-to-speech request to the configured TTS server and streams
  # the generated audio back to the caller.
  #
  # The Android app posts here (using the same console_url it already knows) so
  # the TTS server does not need to be reachable from the phone directly —
  # useful when the TTS server runs locally on pittyvaich.
  #
  # Request body: { "text": "sentence to speak" }
  # Response:     audio/wav (or whatever content-type the TTS server returns)
  #
  # Error cases (JSON):
  #   400 — text param missing
  #   503 — TTS server URL not configured in admin settings
  #   502 — TTS server returned an error
  class TtsController < BaseController

    # Recognised voices for the admin dropdown.  Keys are the voice IDs sent to
    # the TTS server; values are human-readable labels shown in the UI.
    VOICES = {
      "af_heart"   => "Aria · US Female, warm",
      "af_sky"     => "Sky · US Female, bright",
      "am_michael" => "Michael · US Male, deep",
      "bf_emma"    => "Emma · British Female, clear",
      "bm_george"  => "George · British Male, calm",
    }.freeze

    DEFAULT_VOICE = "af_heart"
    DEFAULT_MODEL = "kokoro"

    def create
      text = params[:text].to_s.strip
      return render json: { error: "text is required" }, status: :bad_request if text.blank?

      tts_url = Setting["tts_url"].presence
      if tts_url.blank?
        render json: { error: "TTS server not configured — set the TTS Server URL in the Config tab" },
               status: :service_unavailable
        return
      end

      voice = Setting["tts_voice"].presence || DEFAULT_VOICE
      model = Setting["tts_model"].presence || DEFAULT_MODEL

      uri  = URI("#{tts_url.chomp('/')}/v1/audio/speech")
      http = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl      = uri.scheme == "https"
      http.open_timeout = 10
      http.read_timeout = 60   # TTS generation can take a moment for longer passages

      req = Net::HTTP::Post.new(uri.request_uri, "Content-Type" => "application/json")
      req.body = {
        model:           model,
        input:           text,
        voice:           voice,
        response_format: "wav",
      }.to_json

      resp = http.request(req)

      unless resp.is_a?(Net::HTTPSuccess)
        Rails.logger.warn "TTS server HTTP #{resp.code}: #{resp.body.to_s.first(200)}"
        render json: { error: "TTS server error: HTTP #{resp.code}" }, status: :bad_gateway
        return
      end

      content_type = resp["content-type"].presence || "audio/wav"
      send_data resp.body, type: content_type, disposition: "inline", filename: "tts.wav"

    rescue => e
      Rails.logger.warn "TtsController error: #{e.message}"
      render json: { error: "TTS unavailable: #{e.message}" }, status: :service_unavailable
    end
  end
end
