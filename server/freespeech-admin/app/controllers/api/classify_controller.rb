require "net/http"
require "json"
require "uri"

module Api
  # POST /api/classify
  # Classifies a voice transcript using the configured AI engine.
  # Optional: Authorization: Bearer <api_token> to use the user's personal AI config.
  # Anonymous requests use the system default (Ollama).
  class ClassifyController < BaseController

    VALID_CATEGORIES = %w[MUSIC WEATHER NAVIGATION CALL WEB_SEARCH NONE].freeze

    def create
      transcript = params[:transcript].to_s.strip
      if transcript.blank?
        render json: { error: "transcript is required" }, status: :bad_request
        return
      end

      app_labels = params[:app_labels].to_h rescue {}
      user = api_user

      # Determine effective AI config: user override > system default.
      engine   = user&.effective_ai_engine   || Setting["ai_engine"]   || "ollama"
      api_key  = user&.effective_ai_api_key  || Setting["ai_api_key"]  || ""
      base_url = user&.effective_ai_base_url || Setting["ai_base_url"] || "http://localhost:11434"
      model    = user&.effective_ai_model    || Setting["ai_model"]    || "qwen2.5:7b"

      # Ollama uses any non-empty key value; hosted engines need a real key.
      api_key = "ollama" if engine == "ollama" && api_key.blank?

      prompt = build_prompt(transcript, app_labels)

      begin
        result = call_ai(base_url.chomp("/"), api_key, model, prompt)
        result[:ai] = engine
        render json: result
      rescue => e
        Rails.logger.warn "ClassifyController: AI call failed: #{e.message}"
        render json: { error: "AI classification unavailable: #{e.message}" }, status: :service_unavailable
      end
    end

    private

    # ── AI call (OpenAI-compatible chat completions) ────────────────────────────

    def call_ai(base_url, api_key, model, prompt)
      uri  = URI("#{base_url}/chat/completions")
      http = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl         = uri.scheme == "https"
      http.open_timeout    = 10
      http.read_timeout    = 25

      body = {
        model:       model,
        messages:    [{ role: "user", content: prompt }],
        temperature: 0,
        max_tokens:  150,
      }.to_json

      req = Net::HTTP::Post.new(uri.request_uri,
        "Content-Type"  => "application/json",
        "Authorization" => "Bearer #{api_key}",
      )
      req.body = body

      resp = http.request(req)
      raise "HTTP #{resp.code}: #{resp.body.to_s.first(200)}" unless resp.is_a?(Net::HTTPSuccess)

      parse_chat_response(resp.body)
    end

    def parse_chat_response(json_str)
      envelope = JSON.parse(json_str)
      text     = envelope.dig("choices", 0, "message", "content").to_s.strip
      # Strip markdown fences the model may add despite instructions.
      text = text.gsub(/\A```(?:json)?\n?/, "").gsub(/\n?```\z/, "").strip

      obj      = JSON.parse(text)
      category = obj["category"].to_s.upcase
      category = "NONE" unless VALID_CATEGORIES.include?(category)

      {
        category: category,
        query:    obj["query"].to_s,
        message:  obj["message"].to_s.presence,
      }
    rescue JSON::ParserError => e
      raise "bad AI JSON (#{e.message}): #{json_str.first(200)}"
    end

    # ── Prompt (matches AiClassifier.kt exactly) ───────────────────────────────

    def build_prompt(transcript, app_labels)
      cat_lines = [
        ["MUSIC",      "Music"],
        ["WEATHER",    "Weather"],
        ["NAVIGATION", "Navigation"],
        ["CALL",       "Call"],
        ["WEB_SEARCH", "Web Search"],
      ].map { |cat, name| "- #{cat} (#{name}): #{app_labels[cat].presence || "System default"}" }

      <<~PROMPT.strip
        You are an intent classifier for a voice assistant built into a car.
        The user's speech was transcribed by Whisper and may contain recognition errors.

        Available categories with their configured apps:
        #{cat_lines.join("\n")}
        - NONE: command not understood

        User said: "#{transcript}"

        Reply with exactly one JSON object and nothing else — no markdown, no explanation:
        {"category":"MUSIC","query":"David Bowie","message":"Playing David Bowie"}

        Rules:
        - category  one of MUSIC, WEATHER, NAVIGATION, CALL, WEB_SEARCH, NONE
        - query     extracted subject (artist, destination, contact, search term); empty for WEATHER/CALL
        - message   confirmation in the user's language, max 8 words; if NONE, say in one sentence what kind of command to use instead
      PROMPT
    end
  end
end
