Rails.application.routes.draw do
  # ── Landing ───────────────────────────────────────────────────────────────────
  root "config#index"

  # ── Auth ─────────────────────────────────────────────────────────────────────
  get    "/login",    to: "sessions#new",      as: :login
  post   "/login",    to: "sessions#create"
  delete "/logout",   to: "sessions#destroy",  as: :logout

  get    "/register", to: "registrations#new",     as: :register
  post   "/register", to: "registrations#create"
  get    "/confirm",  to: "registrations#confirm",  as: :confirm

  # ── User profile (any logged-in user) ────────────────────────────────────────
  get    "/profile",                  to: "profile#show",   as: :profile
  patch  "/profile",                  to: "profile#update"
  post   "/profile/regenerate-token", to: "profile#update", as: :regenerate_token,
                                      defaults: { regenerate_token: "1" }

  # ── RAG Library (per-user document upload, any logged-in user) ───────────────
  resources :rag_documents, only: [:index, :create, :destroy]

  # ── Admin: config & history ───────────────────────────────────────────────────
  get    "/config",         to: "config#index",    as: :config
  patch  "/config",         to: "config#update"
  post   "/languages",      to: "languages#create"
  delete "/languages/:id",  to: "languages#destroy", as: :language

  post   "/custom_topics",      to: "custom_topics#create",  as: :custom_topics
  delete "/custom_topics/:id",  to: "custom_topics#destroy", as: :custom_topic

  get    "/history",        to: "history#index",   as: :history

  # ── Admin: user management ────────────────────────────────────────────────────
  namespace :admin do
    resources :users, only: [:index, :destroy] do
      member { post :confirm }
    end
  end

  # ── JSON API (public, token-optional) ────────────────────────────────────────
  # classify lives in Api:: namespace; config & languages reuse existing controllers.
  post "/api/classify",      to: "api/classify#create",       as: :api_classify
  post "/api/tts",           to: "api/tts#create",            as: :api_tts
  get  "/api/config",        to: "config#api",                as: :api_config,
                             defaults: { format: :json }
  get  "/api/languages",     to: "languages#index",           as: :api_languages,
                             defaults: { format: :json }
  get  "/api/custom_topics", to: "api/custom_topics#index",   as: :api_custom_topics,
                             defaults: { format: :json }

  # ── WAV serving ───────────────────────────────────────────────────────────────
  get "/audio/:filename", to: "audio#show", as: :audio_file,
                          constraints: { filename: /[\w\-]+\.wav/i }

  # ── Health ────────────────────────────────────────────────────────────────────
  get "/up", to: proc { [200, {}, ["ok"]] }
end
