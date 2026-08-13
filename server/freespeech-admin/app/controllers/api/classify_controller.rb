require "net/http"
require "json"
require "uri"

module Api
  # POST /api/classify
  #
  # Two-step "ping-pong" AI classification:
  #
  #   Ping — one LLM call classifies the transcript into a category.
  #           Standard categories (MUSIC/WEATHER/…) return the query in the
  #           same response — no second call needed.
  #           Custom topics (CUSTOM_EATING, CUSTOM_MOVIES, …) are also listed
  #           in the ping prompt.
  #
  #   Pong — when a custom topic is matched, a second focused LLM call
  #           transforms the raw transcript into the app-specific search query.
  #           E.g. "I like french fries" → "french fries near me" for Yelp.
  #
  # Supported engines: ollama (OpenAI-compat), gemini (OpenAI-compat),
  #                    openai (OpenAI-compat), anthropic (Messages API).
  class ClassifyController < BaseController

    VALID_CATEGORIES = %w[MUSIC WEATHER NAVIGATION CALL WEB_SEARCH NONE].freeze

    ENGINE_DEFAULTS = {
      "ollama"    => { base_url: "http://localhost:11434",                              model: "qwen2.5:7b",                api_key: "ollama" },
      "gemini"    => { base_url: "https://generativelanguage.googleapis.com/v1beta/openai", model: "gemini-2.0-flash-lite", api_key: "" },
      "openai"    => { base_url: "https://api.openai.com/v1",                           model: "gpt-4o-mini",               api_key: "" },
      "anthropic" => { base_url: "https://api.anthropic.com",                           model: "claude-3-5-haiku-20241022", api_key: "" },
    }.freeze

    # ── create (ping + optional pong) ─────────────────────────────────────────

    def create
      transcript = params[:transcript].to_s.strip
      if transcript.blank?
        render json: { error: "transcript is required" }, status: :bad_request
        return
      end

      app_labels    = params[:app_labels].to_h rescue {}
      user          = api_user
      custom_topics = CustomTopic.all.to_a   # cached for this request

      # Effective AI config: user override → system Setting → engine default.
      engine  = user&.effective_ai_engine   || Setting["ai_engine"]   || "ollama"
      deflt   = ENGINE_DEFAULTS[engine]     || ENGINE_DEFAULTS["ollama"]
      api_key = user&.effective_ai_api_key  || Setting["ai_api_key"]  || deflt[:api_key]
      base_url= user&.effective_ai_base_url || Setting["ai_base_url"] || deflt[:base_url]
      model   = user&.effective_ai_model    || Setting["ai_model"]    || deflt[:model]
      api_key = "ollama" if engine == "ollama" && api_key.blank?

      # ── Ping: classify into standard or custom category ──────────────────────
      ping_prompt = build_ping_prompt(transcript, app_labels, custom_topics)

      begin
        ping_text = fetch_llm_text(engine, base_url, api_key, model, ping_prompt, max_tokens: 256)
        result    = parse_ping_json(ping_text, custom_topics)

        # ── Pong: for custom topics, transform the transcript into the app query ─
        if result[:category] == "CUSTOM" && result[:topic]
          topic      = result.delete(:topic)
          pong_query = fetch_pong_query(transcript, topic, engine, base_url, api_key, model)
          result[:query]           = pong_query
          result[:uri_template]    = topic.uri_template
          result[:android_package] = topic.android_package.presence
          result[:app_label]       = topic.app_label
          result[:message]       ||= "#{topic.app_label}: #{pong_query}"
        end

        # Grammar-correct the spoken message when using Ollama.
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

    # ── Ping helpers ───────────────────────────────────────────────────────────

    def build_ping_prompt(transcript, app_labels, custom_topics)
      standard_lines = [
        ["MUSIC",      "Music"],
        ["WEATHER",    "Weather"],
        ["NAVIGATION", "Navigation"],
        ["CALL",       "Call"],
        ["WEB_SEARCH", "Web Search"],
      ].map { |cat, name| "- #{cat} (#{name}): #{app_labels[cat].presence || "System default"}" }

      custom_lines = custom_topics.map do |t|
        "- #{t.slug}: #{t.description} → app: #{t.app_label}"
      end

      all_lines = standard_lines + custom_lines

      <<~PROMPT.strip
        You are an intent classifier and voice assistant for a car.
        The user's speech was transcribed by Whisper and may contain recognition errors.

        Available categories with their configured apps:
        #{all_lines.join("\n")}
        - NONE: the request cannot be mapped to any of the above categories

        User said: "#{transcript}"

        Reply with exactly one JSON object and nothing else — no markdown, no explanation:
        {"category":"MUSIC","query":"David Bowie","message":"Playing David Bowie"}

        Rules:
        - category  one of the keys listed above (MUSIC, WEATHER, NAVIGATION, CALL, WEB_SEARCH,
                    CUSTOM_*, or NONE)
        - query     extracted subject (artist, destination, search term, etc.); empty for WEATHER/CALL
        - message   spoken text in the user's language:
                      for standard categories — short confirmation, max 8 words
                      for CUSTOM_* — a short confirmation mentioning the app (max 10 words)
                      for NONE — answer conversationally in 1-2 sentences (max 40 words) from general
                      knowledge if possible; if the question requires personal data or real-time info
                      you do not have, acknowledge that briefly and suggest the nearest applicable command
      PROMPT
    end

    # Parses the ping JSON; recognises both standard and CUSTOM_* categories.
    def parse_ping_json(text, custom_topics)
      text = text.gsub(/\A```(?:json)?\n?/, "").gsub(/\n?```\z/, "").strip
      obj      = JSON.parse(text)
      category = obj["category"].to_s.upcase

      # Custom topic match?
      if category.start_with?("CUSTOM_")
        matched = custom_topics.find { |t| t.slug == category }
        if matched
          return { category: "CUSTOM",
                   topic:    matched,
                   query:    obj["query"].to_s,
                   message:  obj["message"].to_s.presence }
        end
        # Unknown CUSTOM_* key — treat as NONE
        category = "NONE"
      end

      category = "NONE" unless VALID_CATEGORIES.include?(category)
      { category: category,
        query:    obj["query"].to_s,
        message:  obj["message"].to_s.presence }
    rescue JSON::ParserError => e
      raise "bad AI JSON (#{e.message}): #{text.first(200)}"
    end

    # ── Pong helper ────────────────────────────────────────────────────────────

    # Second LLM call: transform the user's raw transcript into a clean search
    # query for the custom topic's target app.
    def fetch_pong_query(transcript, topic, engine, base_url, api_key, model)
      hint = topic.transform_hint.presence ? "\n#{topic.transform_hint}" : ""
      prompt = <<~PROMPT.strip
        The user said: "#{transcript}"
        Context: #{topic.description}
        Target app: #{topic.app_label}#{hint}

        Extract a concise search query from the user's statement suitable for #{topic.app_label}.
        Reply with ONLY the search query — no quotes, no explanation, nothing else.
      PROMPT

      fetch_llm_text(engine, base_url, api_key, model, prompt, max_tokens: 80).strip
    rescue => e
      Rails.logger.warn "Custom topic pong failed (#{topic.name}): #{e.message}"
      transcript   # graceful fallback: send the original transcript as the query
    end

    # ── Shared LLM HTTP ────────────────────────────────────────────────────────

    # Returns the raw text content from the model (no JSON parsing).
    # Works for both OpenAI-compatible engines and the Anthropic Messages API.
    def fetch_llm_text(engine, base_url, api_key, model, prompt, max_tokens: 256)
      if engine == "anthropic"
        uri  = URI("#{base_url.chomp('/')}/v1/messages")
        http = build_http(uri)
        req  = Net::HTTP::Post.new(uri.request_uri,
                 "Content-Type"      => "application/json",
                 "x-api-key"         => api_key,
                 "anthropic-version" => "2023-06-01")
        req.body = { model: model, max_tokens: max_tokens,
                     messages: [{ role: "user", content: prompt }] }.to_json
        resp = http.request(req)
        raise "HTTP #{resp.code}: #{resp.body.to_s.first(200)}" unless resp.is_a?(Net::HTTPSuccess)
        JSON.parse(resp.body).dig("content", 0, "text").to_s.strip
      else
        # OpenAI-compatible: Ollama, Gemini, OpenAI
        uri  = URI("#{base_url.chomp('/')}/chat/completions")
        http = build_http(uri)
        req  = Net::HTTP::Post.new(uri.request_uri,
                 "Content-Type"  => "application/json",
                 "Authorization" => "Bearer #{api_key}")
        req.body = { model: model, temperature: 0, max_tokens: max_tokens,
                     messages: [{ role: "user", content: prompt }] }.to_json
        resp = http.request(req)
        raise "HTTP #{resp.code}: #{resp.body.to_s.first(200)}" unless resp.is_a?(Net::HTTPSuccess)
        JSON.parse(resp.body).dig("choices", 0, "message", "content").to_s.strip
      end
    end

    # Convenience wrappers kept for readability in create.
    def call_openai_compat(base_url, api_key, model, prompt)
      parse_ping_json(fetch_llm_text("openai_compat", base_url, api_key, model, prompt), [])
    end

    def call_anthropic(base_url, api_key, model, prompt)
      parse_ping_json(fetch_llm_text("anthropic", base_url, api_key, model, prompt), [])
    end

    def build_http(uri)
      http              = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl      = uri.scheme == "https"
      http.open_timeout = 10
      http.read_timeout = 25
      http
    end

    # ── Grammar correction via LanguageTool ────────────────────────────────────

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

      corrected = text.dup
      matches.sort_by { |m| -m["offset"] }.each do |match|
        next if match["replacements"].empty?
        corrected[match["offset"], match["length"]] = match["replacements"].first["value"]
      end
      Rails.logger.debug "Grammar corrected: #{text.inspect} → #{corrected.inspect}"
      corrected
    rescue => e
      Rails.logger.warn "LanguageTool grammar correction failed: #{e.message}"
      text
    end
  end
end
