require "net/http"
require "json"
require "uri"

module Api
  # POST /api/classify
  # Classifies a voice transcript using the configured AI engine.
  # Optional: Authorization: Bearer <api_token> to use the user's personal AI config.
  # Anonymous requests use the system default (Ollama).
  #
  # Supported engines (value of ai_engine setting / user override):
  #   ollama     – OpenAI-compatible local server (any non-empty key accepted)
  #   gemini     – Google Gemini via the OpenAI-compatible endpoint
  #   openai     – OpenAI chat completions API
  #   anthropic  – Anthropic Messages API (different format from OpenAI)
  class ClassifyController < BaseController

    VALID_CATEGORIES = %w[MUSIC WEATHER NAVIGATION CALL WEB_SEARCH NONE].freeze

    ENGINE_DEFAULTS = {
      "ollama"    => { base_url: "http://localhost:11434",                                              model: "qwen2.5:7b",                    api_key: "ollama" },
      "gemini"    => { base_url: "https://generativelanguage.googleapis.com/v1beta/openai",             model: "gemini-2.0-flash-lite",         api_key: "" },
      "openai"    => { base_url: "https://api.openai.com/v1",                                           model: "gpt-4o-mini",                   api_key: "" },
      "anthropic" => { base_url: "https://api.anthropic.com",                                           model: "claude-3-5-haiku-20241022",     api_key: "" },
    }.freeze

    def create
      transcript = params[:transcript].to_s.strip
      if transcript.blank?
        render json: { error: "transcript is required" }, status: :bad_request
        return
      end

      app_labels = params[:app_labels].to_h rescue {}
      user       = api_user

      # Effective config: user override → system Setting → engine default.
      engine  = user&.effective_ai_engine   || Setting["ai_engine"]   || "ollama"
      deflt   = ENGINE_DEFAULTS[engine]     || ENGINE_DEFAULTS["ollama"]

      api_key  = user&.effective_ai_api_key  || Setting["ai_api_key"]  || deflt[:api_key]
      base_url = user&.effective_ai_base_url || Setting["ai_base_url"] || deflt[:base_url]
      model    = user&.effective_ai_model    || Setting["ai_model"]    || deflt[:model]

      api_key = "ollama" if engine == "ollama" && api_key.blank?

      prompt = build_prompt(transcript, app_labels)

      begin
        result = engine == "anthropic" ?
                   call_anthropic(base_url.chomp("/"), api_key, model, prompt) :
                   call_openai_compat(base_url.chomp("/"), api_key, model, prompt)

        # Grammar-correct the spoken confirmation message when using Ollama/qwen,
        # since local models occasionally produce grammatically broken output.
        if engine == "ollama" && Setting["grammar_correct"] != "0" && result[:message].present?
          result[:message] = correct_grammar(result[:message])
        end

        result[:ai] = engine
        render json: result
      rescue => e
        Rails.logger.warn "ClassifyController: AI call failed (#{engine}): #{e.message}"
        render json: { error: "AI classification unavailable: #{e.message}" }, status: :service_unavailable
      end
    end

    private

    # ── OpenAI-compatible chat completions (Ollama, Gemini, OpenAI) ────────────

    def call_openai_compat(base_url, api_key, model, prompt)
      uri  = URI("#{base_url}/chat/completions")
      http = build_http(uri)
      req  = Net::HTTP::Post.new(uri.request_uri,
               "Content-Type"  => "application/json",
               "Authorization" => "Bearer #{api_key}")
      req.body = {
        model:       model,
        messages:    [{ role: "user", content: prompt }],
        temperature: 0,
        max_tokens:  150,
      }.to_json

      resp = http.request(req)
      raise "HTTP #{resp.code}: #{resp.body.to_s.first(200)}" unless resp.is_a?(Net::HTTPSuccess)

      envelope = JSON.parse(resp.body)
      text     = envelope.dig("choices", 0, "message", "content").to_s.strip
      parse_intent_json(text)
    end

    # ── Anthropic Messages API ─────────────────────────────────────────────────
    # Endpoint:  POST <base_url>/v1/messages
    # Auth:      x-api-key header + anthropic-version header
    # Response:  content[0].text  (not choices[0].message.content)

    def call_anthropic(base_url, api_key, model, prompt)
      uri  = URI("#{base_url}/v1/messages")
      http = build_http(uri)
      req  = Net::HTTP::Post.new(uri.request_uri,
               "Content-Type"       => "application/json",
               "x-api-key"          => api_key,
               "anthropic-version"  => "2023-06-01")
      req.body = {
        model:      model,
        max_tokens: 150,
        messages:   [{ role: "user", content: prompt }],
      }.to_json

      resp = http.request(req)
      raise "HTTP #{resp.code}: #{resp.body.to_s.first(200)}" unless resp.is_a?(Net::HTTPSuccess)

      envelope = JSON.parse(resp.body)
      text     = envelope.dig("content", 0, "text").to_s.strip
      parse_intent_json(text)
    end

    # ── Shared helpers ─────────────────────────────────────────────────────────

    def build_http(uri)
      http              = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl      = uri.scheme == "https"
      http.open_timeout = 10
      http.read_timeout = 25
      http
    end

    def parse_intent_json(text)
      # Strip markdown code fences some models add despite instructions.
      text = text.gsub(/\A```(?:json)?\n?/, "").gsub(/\n?```\z/, "").strip

      obj      = JSON.parse(text)
      category = obj["category"].to_s.upcase
      category = "NONE" unless VALID_CATEGORIES.include?(category)

      { category: category,
        query:    obj["query"].to_s,
        message:  obj["message"].to_s.presence }
    rescue JSON::ParserError => e
      raise "bad AI JSON (#{e.message}): #{text.first(200)}"
    end

    # ── Grammar correction via LanguageTool ────────────────────────────────────
    # Uses the LanguageTool REST API (/v2/check) to fix grammar/spelling in the
    # short confirmation message produced by Ollama/qwen, which often has minor
    # grammatical errors. Falls back to the original text on any failure.

    def correct_grammar(text)
      lt_base = Setting["languagetool_url"].presence || "https://api.languagetool.org"
      uri     = URI("#{lt_base.chomp('/')}/v2/check")

      http              = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl      = uri.scheme == "https"
      http.open_timeout = 5
      http.read_timeout = 8

      req      = Net::HTTP::Post.new(uri.request_uri,
                   "Content-Type" => "application/x-www-form-urlencoded")
      req.body = URI.encode_www_form(text: text, language: "auto")

      resp = http.request(req)
      unless resp.is_a?(Net::HTTPSuccess)
        Rails.logger.warn "LanguageTool #{resp.code} — skipping grammar correction"
        return text
      end

      matches = JSON.parse(resp.body)["matches"] || []
      return text if matches.empty?

      # Apply replacements from right to left so earlier offsets stay valid.
      corrected = text.dup
      matches.sort_by { |m| -m["offset"] }.each do |match|
        next if match["replacements"].empty?
        corrected[match["offset"], match["length"]] = match["replacements"].first["value"]
      end
      Rails.logger.debug "Grammar corrected: #{text.inspect} → #{corrected.inspect}"
      corrected
    rescue => e
      Rails.logger.warn "LanguageTool grammar correction failed: #{e.message}"
      text   # original message is always the safe fallback
    end

    # ── Prompt (identical wording to AiClassifier.kt) ─────────────────────────

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
