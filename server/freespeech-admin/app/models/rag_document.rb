require "pdf/reader"
require "zip"
require "nokogiri"

class RagDocument < ApplicationRecord
  belongs_to :user

  # Maximum characters stored and sent to the LLM per call.
  # 30 K chars ≈ 7 500 tokens — well within all supported models' context windows.
  MAX_EXCERPT_CHARS = 30_000
  # Hard cap on how much extracted text we persist.
  MAX_STORED_CHARS  = 100_000

  validates :keyword,      presence: true
  validates :filename,     presence: true
  validates :file_content, presence: true, length: { minimum: 50,
              message: "is too short — the document could not be extracted or is nearly empty" }

  # ── slug ──────────────────────────────────────────────────────────────────────
  # Identifier used in the AI ping prompt, e.g. "RAG_TRAVELING".
  def slug
    "RAG_#{keyword.upcase.gsub(/\W+/, '_')}"
  end

  # ── content_excerpt ───────────────────────────────────────────────────────────
  # Returns up to MAX_EXCERPT_CHARS of text.  When the full document is larger, a
  # sliding-window heuristic locates the 30 K-char region with the highest density
  # of words from +query+ so the most relevant part is sent to the LLM.
  def content_excerpt(query: nil, max_chars: MAX_EXCERPT_CHARS)
    return file_content.first(max_chars) if file_content.length <= max_chars || query.blank?

    # Pick up to 5 meaningful keywords from the query
    words = query.downcase.split(/\W+/).reject { |w| w.length < 4 }.first(5)
    return file_content.first(max_chars) if words.empty?

    lower = file_content.downcase
    step  = 5_000
    best_score = -1
    best_start = 0

    (0...[file_content.length - max_chars]).step(step) do |start|
      window = lower[start, max_chars]
      score  = words.sum { |w| window.scan(w).length }
      if score > best_score
        best_score = score
        best_start = start
      end
    end

    file_content[best_start, max_chars]
  end

  # ── Text extraction (class methods) ──────────────────────────────────────────

  # Returns extracted plain text from a PDF file (raw bytes).
  # Raises on corrupted or DRM-protected files.
  def self.extract_pdf(data)
    reader = PDF::Reader.new(StringIO.new(data))
    text   = reader.pages.map { |p| p.text rescue "" }.join("\n")
    clean(text)
  end

  # Returns extracted plain text from an EPUB file (raw bytes).
  # EPUB is a ZIP archive of XHTML files; we parse them with Nokogiri.
  def self.extract_epub(data)
    text = +""
    Zip::File.open_buffer(StringIO.new(data)) do |zip|
      xhtml = zip.entries
        .select  { |e| e.name =~ /\.(x?html?)$/i }
        .sort_by { |e| e.name }   # rough reading-order proxy
      xhtml.each do |entry|
        html = entry.get_input_stream.read
        doc  = Nokogiri::HTML(html)
        doc.css("script, style, nav, aside").remove
        text << doc.text << "\n"
      end
    end
    clean(text)
  end

  private

  def self.clean(text)
    # Collapse runs of blank lines / whitespace; trim
    text.gsub(/\r\n?/, "\n")
        .gsub(/[ \t]+/, " ")
        .gsub(/\n{3,}/, "\n\n")
        .strip
        .first(MAX_STORED_CHARS)
  end
end
