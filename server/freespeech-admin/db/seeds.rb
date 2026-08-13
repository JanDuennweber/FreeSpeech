# ── Admin user ───────────────────────────────────────────────────────────────
admin_email    = ENV.fetch("ADMIN_EMAIL",    "admin@freespeech.local")
admin_password = ENV.fetch("ADMIN_PASSWORD", "freespeech")

unless User.exists?(email: admin_email)
  User.create!(
    email:        admin_email,
    password:     admin_password,
    role:         "admin",
    confirmed_at: Time.current,
  )
  puts "  Admin user created: #{admin_email} / #{admin_password}  ← CHANGE THIS PASSWORD!"
end

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
  # SMTP (leave blank to skip email; admin can confirm users manually)
  "smtp_host"           => "",
  "smtp_port"           => "587",
  "smtp_user"           => "",
  "smtp_password"       => "",
  "smtp_from"           => "freespeech@localhost",
  "app_host"            => "localhost:3001",
  "whisper_model"       => "large-v3",

  "ai_engine"           => "ollama",               # gemini | openai | ollama
  "ai_api_key"          => "",
  "ai_base_url"         => "http://localhost:11434",
  "ai_model"            => "qwen2.5:7b",
  "grammar_correct"     => "1",   # correct Ollama/qwen message grammar via LanguageTool
  "languagetool_url"    => "",    # blank = use free public API at api.languagetool.org

  "log_jsonl_path"      => "/var/log/freespeech/freespeech_commands.jsonl",
  "log_audio_dir"       => "/var/log/freespeech/audio",
  "log_max_entries"     => "1000",
  "log_max_wavs"        => "100",
}

defaults.each { |k, v| Setting.find_or_create_by!(key: k) { |s| s.value = v } }
