class CustomTopic < ApplicationRecord
  validates :name,         presence: true,
                           uniqueness: { case_sensitive: false },
                           format: { with: /\A[A-Za-z][A-Za-z0-9 _-]*\z/,
                                     message: "may only contain letters, numbers, spaces, hyphens and underscores" }
  validates :description,  presence: true
  validates :app_label,    presence: true
  validates :uri_template, presence: true,
                           format: { with: /{query}/,
                                     message: "must contain {query} as a placeholder" }

  default_scope { order(:position, :name) }

  # The key used in the AI classification prompt (e.g. "Eating" → "CUSTOM_EATING").
  def slug = "CUSTOM_#{name.upcase.gsub(/\W+/, '_')}"
end
