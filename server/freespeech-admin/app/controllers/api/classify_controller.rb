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
  #           Custom topics (CUSTOM_*) and RAG topics (RAG_*) are also listed.
  #
  #   Pong (Custom) — second LLM call transforms the transcript into a clean
  #           app search query for the matched custom topic.
  #
  #   Pong (RAG)    — two further LLM calls:
  #           1. Extract a factsheet from the matched user document.
  #           2. Answer the original question augmented with the factsheet.
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
      custom_topics = CustomTopic.all.to_a        # cached for this request
      rag_docs      = user ? user.rag_documents.to_a : []   # empty for anonymous calls

      # Effective AI config: user override → system Setting → engine default.
      engine  = user&.effective_ai_engine   || Setting["ai_engine"]   || "ollama"
      deflt   = ENGINE_DEFAULTS[engine]     || ENGINE_DEFAULTS["ollama"]
      api_key = user&.effective_ai_api_key  || Setting["ai_api_key"]  || deflt[:api_key]
      base_url= user&.effective_ai_base_url || Setting["ai_base_url"] || deflt[:base_url]
      model   = user&.effective_ai_model    || Setting["ai_model"]    || deflt[:model]
      api_key = "ollama" if engine == "ollama" && api_key.blank?

      # ── Ping: classify into standard, custom, or RAG category ──────────────────
      ping_prompt = build_ping_prompt(transcript, app_labels, custom_topics, rag_docs)

      begin
        ping_text = fetch_llm_text(engine, base_url, api_key, model, ping_prompt, max_tokens: 256)
        result    = parse_ping_json(ping_text, custom_topics, rag_docs)

        if result[:category] == "RAG" && result[:rag_doc]
          # ── RAG pong: factsheet extraction + augmented answer ─────────────────
          doc        = result.delete(:rag_doc)
          ping_query = result[:query]

          factsheet  = fetch_rag_factsheet(transcript, doc, engine, base_url, api_key, model)
          answer     = fetch_rag_answer(transcript, factsheet, doc, engine, base_url, api_key, model)
          answer     = correct_grammar(answer) if engine == "ollama" && Setting["grammar_correct"] != "0"

          result[:category]      = "NONE"
          result[:message]       = answer
          result[:routing_chain] = "\"#{ping_query}\" → #{doc.keyword} → #{doc.filename}"

        elsif result[:category] == "CUSTOM" && result[:topic]
          # ── Custom topic pong: transform the transcript into the app query ─────
          topic      = result.delete(:topic)
          ping_query = result[:query]   # keyword the ping extracted — saved for routing chain

          pong_query         = fetch_pong_query(transcript, topic, engine, base_url, api_key, model)
          result[:query]     = pong_query
          result[:app_label] = topic.app_label

          # Target routing: app deep-link OR site-restricted web search
          if topic.target_type == "web_search"
            result[:search_urls]     = topic.url_list
            result[:uri_template]    = nil
            result[:android_package] = nil
          else
            result[:uri_template]    = topic.uri_template
            result[:android_package] = topic.android_package.presence
            result[:search_urls]     = nil
          end

          result[:message] ||= "#{topic.app_label}: #{pong_query}"

          # Routing chain — shows the full ping-pong path in the log:
          # "french fries" → Eating → Yelp
          # "tell me a joke" → Jokes → punscorner.com, reddit.com/r/Jokes
          result[:routing_chain] = "\"#{ping_query}\" → #{topic.name} → #{topic.target_display}"

          # Grammar-correct the spoken message when using Ollama.
          if engine == "ollama" && Setting["grammar_correct"] != "0" && result[:message].present?
            result[:message] = correct_grammar(result[:message])
          end

        else
          # Standard category — grammar-correct the NONE conversational answer.
          if engine == "ollama" && Setting["grammar_correct"] != "0" && result[:message].present?
            result[:message] = correct_grammar(result[:message])
          end
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

    def build_ping_prompt(transcript, app_labels, custom_topics, rag_docs = [])
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

      # RAG topics from the authenticated user's personal document library.
      # Format: RAG_KEYWORD: description → document: filename
      rag_lines = rag_docs.map do |d|
        "- #{d.slug}: #{d.description.presence || d.keyword} → document: #{d.filename}"
      end

      all_lines = standard_lines + custom_lines + rag_lines

      custom_pat = custom_topics.any? ? "CUSTOM_*, " : ""
      rag_pat    = rag_docs.any?      ? "RAG_*, "    : ""

      <<~PROMPT.strip
        You are an intent classifier and voice assistant for a car.
        The user's speech was transcribed by Whisper and may contain recognition errors.

        Available categories with their configured apps and documents:
        #{all_lines.join("\n")}
        - NONE: the request cannot be mapped to any of the above categories

        User said: "#{transcript}"

        Reply with exactly one JSON object and nothing else — no markdown, no explanation:
        {"category":"MUSIC","query":"David Bowie","message":"Playing David Bowie"}

        Rules:
        - category  one of the keys listed above (MUSIC, WEATHER, NAVIGATION, CALL, WEB_SEARCH,
                    #{custom_pat}#{rag_pat}or NONE)
        - query     extracted subject (artist, destination, search term, etc.); empty for WEATHER/CALL
        - message   spoken text in the user's language:
                      for standard categories — short confirmation, max 8 words
                      for CUSTOM_* — a short confirmation mentioning the app (max 10 words)
                      for RAG_* — a brief acknowledgement that you are looking in the document, max 10 words
                      for NONE — answer conversationally in 1-2 sentences (max 40 words) from general
                      knowledge if possible; if the question requires personal data or real-time info
                      you do not have, acknowledge that briefly and suggest the nearest applicable command
      PROMPT
    end

    # Parses the ping JSON; recognises standard, CUSTOM_*, and RAG_* categories.
    def parse_ping_json(text, custom_topics, rag_docs = [])
      text = text.gsub(/\A```(?:json)?\n?/, "").gsub(/\n?```\z/, "").strip
      obj      = JSON.parse(text)
      category = obj["category"].to_s.upcase

      # RAG document match? (checked before CUSTOM so user docs take priority)
      if category.start_with?("RAG_")
        matched = rag_docs.find { |d| d.slug == category }
        if matched
          return { category: "RAG",
                   rag_doc:  matched,
                   query:    obj["query"].to_s,
                   message:  obj["message"].to_s.presence }
        end
        # Unknown RAG_* key — treat as NONE
        category = "NONE"
      end

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

    # ── RAG pong helpers ───────────────────────────────────────────────────────

    # Step 1: Ask the LLM to extract a concise factsheet from the user's document
    # that is relevant to the query.  Sends up to 30 000 chars of document text.
    def fetch_rag_factsheet(transcript, doc, engine, base_url, api_key, model)
      excerpt = doc.content_excerpt(query: transcript)
      prompt  = <<~PROMPT.strip
        You are a research assistant helping a driver get a quick answer.
        The user asked: "#{transcript}"

        Below is an excerpt from a document titled "#{doc.filename}"
        about the topic "#{doc.keyword}".

        Extract the most relevant information to answer the user's question.
        Respond with short, concise bullet points only. Maximum 200 words.
        Do NOT include a preamble — start directly with the bullets.

        DOCUMENT EXCERPT:
        #{excerpt}
      PROMPT

      fetch_llm_text(engine, base_url, api_key, model, prompt,
                     max_tokens: 500, read_timeout: 60).strip
    rescue => e
      Rails.logger.warn "RAG factsheet failed (#{doc.keyword}): #{e.message}"
      ""   # empty factsheet — answer will still be attempted from general knowledge
    end

    # Step 2: Combine the original transcript with the factsheet and ask the LLM
    # to produce a short, spoken-friendly answer.
    def fetch_rag_answer(transcript, factsheet, doc, engine, base_url, api_key, model)
      context = if factsheet.present?
        "\n\nTake into account the following information from \"#{doc.filename}\":\n#{factsheet}"
      else
        ""
      end

      prompt = <<~PROMPT.strip
        Answer this question: "#{transcript}"#{context}

        Reply in 2-4 natural spoken sentences. Be specific and helpful.
        Use the provided document information when available.
        Reply in the same language as the user's question.
      PROMPT

      fetch_llm_text(engine, base_url, api_key, model, prompt,
                     max_tokens: 200, read_timeout: 30).strip
    rescue => e
      Rails.logger.warn "RAG answer failed (#{doc.keyword}): #{e.message}"
      transcript   # graceful fallback
    end

    # ── Custom topic pong helper ───────────────────────────────────────────────

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
    # +read_timeout+ can be raised for calls that send large document excerpts
    # (RAG factsheet extraction may take longer than the default 25 s).
    def fetch_llm_text(engine, base_url, api_key, model, prompt, max_tokens: 256, read_timeout: 25)
      if engine == "anthropic"
        uri  = URI("#{base_url.chomp('/')}/v1/messages")
        http = build_http(uri, read_timeout: read_timeout)
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
        http = build_http(uri, read_timeout: read_timeout)
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

    def build_http(uri, read_timeout: 25)
      http              = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl      = uri.scheme == "https"
      http.open_timeout = 10
      http.read_timeout = read_timeout
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
