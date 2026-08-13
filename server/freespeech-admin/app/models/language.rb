class Language < ApplicationRecord
  PROTECTED_CODES = %w[en de fr].freeze

  validates :code, presence: true, uniqueness: true,
                   format: { with: /\A[a-z]{2,3}(-[A-Z]{2})?\z/, message: "must be a BCP-47 tag (e.g. en, de, zh-TW)" }
  validates :name, presence: true

  before_destroy :prevent_protected_deletion

  # Display name plus code: "German (de)"
  def display
    "#{name} (#{code})"
  end

  private

  def prevent_protected_deletion
    if PROTECTED_CODES.include?(code)
      errors.add(:base, "#{name} (#{code}) is a protected language and cannot be removed")
      throw :abort
    end
  end
end
