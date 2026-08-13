# ── Default languages ─────────────────────────────────────────────────────────
[
  { code: "en", name: "English",  protected: true  },
  { code: "de", name: "German",   protected: true  },
  { code: "fr", name: "French",   protected: true  },
  { code: "es", name: "Spanish",  protected: false },
  { code: "it", name: "Italian",  protected: false },
  { code: "cs", name: "Czech",    protected: false },
].each do |attrs|
  Language.find_or_create_by!(code: attrs[:code]) do |l|
    l.name      = attrs[:name]
    l.protected = attrs[:protected]
  end
end

# ── Default settings ──────────────────────────────────────────────────────────
defaults = {
  "whisper_url"         => "http://localhost:8080/v1/audio/transcriptions",
  "whisper_model"       => "large-v3",

  "ai_engine"           => "ollama",               # gemini | openai | ollama
  "ai_api_key"          => "",
  "ai_base_url"         => "http://localhost:11434",
  "ai_model"            => "qwen2.5:7b",

  "log_jsonl_path"      => "/var/log/freespeech/freespeech_commands.jsonl",
  "log_audio_dir"       => "/var/log/freespeech/audio",
  "log_max_entries"     => "1000",
  "log_max_wavs"        => "100",
}

defaults.each { |k, v| Setting.find_or_create_by!(key: k) { |s| s.value = v } }
