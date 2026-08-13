Rails.application.routes.draw do
  root "config#index"

  # Config tab – GET shows form, PATCH saves it
  get    "/config",          to: "config#index",  as: :config
  patch  "/config",          to: "config#update"

  # Language management (within config tab)
  post   "/languages",       to: "languages#create"
  delete "/languages/:id",   to: "languages#destroy", as: :language

  # History tab
  get    "/history",         to: "history#index",  as: :history

  # JSON API – consumed by the Python log server and the Android app
  get    "/api/config",      to: "config#api",        defaults: { format: :json }
  get    "/api/languages",   to: "languages#index",   defaults: { format: :json }

  # WAV audio serving (read from configured log_audio_dir)
  get    "/audio/:filename",   to: "audio#show",       as: :audio_file,
                               constraints: { filename: /[\w\-]+\.wav/i }

  # Health check
  get    "/up", to: proc { [200, {}, ["ok"]] }
end
