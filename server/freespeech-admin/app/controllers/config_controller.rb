class ConfigController < ApplicationController
  before_action :require_admin, except: [:api]
  skip_before_action :verify_authenticity_token, only: [:api]
  # /api/config is intentionally public — no sensitive data exposed (api_key omitted).

  SETTING_KEYS = %w[
    whisper_url
    whisper_model
    ai_engine
    ai_api_key
    ai_base_url
    ai_model
    grammar_correct
    languagetool_url
    tts_url
    tts_voice
    tts_model
    log_jsonl_path
    log_audio_dir
    log_max_entries
    log_max_wavs
    smtp_host
    smtp_port
    smtp_user
    smtp_password
    smtp_from
    app_host
  ].freeze

  def index
    @settings  = Setting.to_h
    @languages = Language.order(:name)
  end

  def update
    permitted = params.require(:settings).permit(*SETTING_KEYS)
    permitted.each { |k, v| Setting.set(k, v) }
    redirect_to config_path, notice: "Settings saved."
  end

  # JSON API — consumed by Python log server / Android app
  def api
    render json: {
      settings:  Setting.to_h.except("ai_api_key"),   # never expose the key externally
      languages: Language.order(:name).as_json(only: %i[code name]),
    }
  end
end
