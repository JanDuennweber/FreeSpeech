class CustomTopic < ApplicationRecord
  TARGET_TYPES = %w[app web_search].freeze

  validates :name,       presence: true,
                         uniqueness: { case_sensitive: false },
                         format: { with: /\A[A-Za-z][A-Za-z0-9 _-]*\z/,
                                   message: "may only contain letters, numbers, spaces, hyphens and underscores" }
  validates :description, presence: true
  validates :app_label,   presence: true
  validates :target_type, inclusion: { in: TARGET_TYPES }

  # App target — URI template required, must carry {query}
  validates :uri_template, presence: true,
                           format: { with: /{query}/, message: "must contain {query} as a placeholder" },
                           if: -> { target_type == "app" }

  # Web-search target — at least one URL required, max 5
  validates :search_urls, presence: true, if: -> { target_type == "web_search" }
  validate  :at_most_five_urls,           if: -> { target_type == "web_search" }

  default_scope { order(:position, :name) }

  # ── Helpers ─────────────────────────────────────────────────────────────────

  # CUSTOM_EATING, CUSTOM_MOVIE_NIGHT, … — used in AI prompt and ping response.
  def slug = "CUSTOM_#{name.upcase.gsub(/\W+/, '_')}"

  # Parsed, stripped URL list (max 5).
  def url_list
    search_urls.to_s.split(/[\n,]+/).map(&:strip).reject(&:blank?).first(5)
  end

  # Human-readable target for display and routing_chain logging.
  def target_display
    target_type == "web_search" ? url_list.join(", ") : app_label
  end

  private

  def at_most_five_urls
    errors.add(:search_urls, "may list at most 5 URLs") if url_list.size > 5
  end
end
