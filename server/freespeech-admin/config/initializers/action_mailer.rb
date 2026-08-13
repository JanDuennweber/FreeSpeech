# Dynamic SMTP config: reads env vars, with fallback to SMTP settings in the DB.
# The DB lookup is skipped during initialisation (DB may not exist yet).
Rails.application.config.action_mailer.delivery_method = :smtp
Rails.application.config.action_mailer.raise_delivery_errors = false

Rails.application.config.action_mailer.smtp_settings = {
  address:              ENV.fetch("SMTP_HOST",     "localhost"),
  port:                 ENV.fetch("SMTP_PORT",     "25").to_i,
  user_name:            ENV["SMTP_USER"].presence,
  password:             ENV["SMTP_PASSWORD"].presence,
  authentication:       ENV["SMTP_USER"].present? ? :plain : nil,
  enable_starttls_auto: ENV.fetch("SMTP_STARTTLS", "true") == "true",
}

# In development: print emails to the Rails log instead of sending them.
if Rails.env.development?
  Rails.application.config.action_mailer.delivery_method = :logger
end

Rails.application.config.action_mailer.default_url_options = {
  host:     ENV.fetch("APP_HOST",     "localhost:3001"),
  protocol: ENV.fetch("APP_PROTOCOL", "http"),
}
