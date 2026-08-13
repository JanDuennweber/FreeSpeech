class ApplicationMailer < ActionMailer::Base
  default from: -> { Setting["smtp_from"].presence || "freespeech@localhost" }
  layout "mailer"

  # Reload SMTP settings from DB before every delivery, so admin changes
  # take effect without restarting the server.
  before_action :reload_smtp_settings

  private

  def reload_smtp_settings
    return unless Rails.env.production?
    ActionMailer::Base.smtp_settings = ActionMailer::Base.smtp_settings.merge(
      address:   Setting["smtp_host"].presence  || ENV.fetch("SMTP_HOST", "localhost"),
      port:     (Setting["smtp_port"].presence  || ENV.fetch("SMTP_PORT", "25")).to_i,
      user_name: Setting["smtp_user"].presence  || ENV["SMTP_USER"],
      password:  Setting["smtp_password"].presence || ENV["SMTP_PASSWORD"],
    ).compact
    ActionMailer::Base.default_url_options[:host] =
      Setting["app_host"].presence || ENV.fetch("APP_HOST", "localhost:3001")
  end
end
