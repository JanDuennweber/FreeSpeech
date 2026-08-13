class ApplicationController < ActionController::Base
  # Basic-auth protection — set FREESPEECH_ADMIN_PASSWORD env var on the server.
  # Falls back to "freespeech" if the env var is absent (change it!).
  http_basic_authenticate_with(
    name:     ENV.fetch("FREESPEECH_ADMIN_USER",     "admin"),
    password: ENV.fetch("FREESPEECH_ADMIN_PASSWORD", "freespeech"),
  )

  # Allow forms without CSRF only for the JSON API endpoints.
  protect_from_forgery with: :exception

  helper_method :active_tab

  private

  def active_tab
    case controller_name
    when "config", "languages" then :config
    when "history"              then :history
    else :config
    end
  end
end
