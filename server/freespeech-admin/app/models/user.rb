class User < ApplicationRecord
  has_secure_password

  ROLES = %w[admin user].freeze

  validates :email,    presence: true, uniqueness: { case_sensitive: false },
                       format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :role,     inclusion: { in: ROLES }
  validates :api_token, presence: true, uniqueness: true

  before_validation :normalise_email
  before_validation :generate_api_token,          on: :create, unless: :api_token?
  before_validation :generate_confirmation_token, on: :create, unless: :confirmed?

  scope :confirmed,   -> { where.not(confirmed_at: nil) }
  scope :unconfirmed, -> { where(confirmed_at: nil) }

  def admin?    = role == "admin"
  def confirmed? = confirmed_at.present?

  def confirm!
    update!(confirmed_at: Time.current, confirmation_token: nil)
  end

  def regenerate_token!
    update!(api_token: SecureRandom.urlsafe_base64(32))
  end

  # Effective AI config: fall back to system Settings when user hasn't overridden.
  def effective_ai_engine    = ai_engine.presence    || Setting["ai_engine"]    || "ollama"
  def effective_ai_api_key   = ai_api_key.presence   || Setting["ai_api_key"]   || ""
  def effective_ai_base_url  = ai_base_url.presence  || Setting["ai_base_url"]  || "http://localhost:11434"
  def effective_ai_model     = ai_model.presence      || Setting["ai_model"]     || "qwen2.5:7b"

  private

  def normalise_email
    self.email = email.to_s.strip.downcase
  end

  def generate_api_token
    self.api_token = SecureRandom.urlsafe_base64(32)
  end

  def generate_confirmation_token
    self.confirmation_token = SecureRandom.urlsafe_base64(24)
  end
end
