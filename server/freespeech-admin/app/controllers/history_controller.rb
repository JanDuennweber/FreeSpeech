require "json"

class HistoryController < ApplicationController
  before_action :require_admin
  PER_PAGE = 50

  def index
    jsonl_path = Setting["log_jsonl_path"].presence || default_jsonl_path
    audio_dir  = Setting["log_audio_dir"].presence  || default_audio_dir

    entries = read_jsonl(jsonl_path)

    # Filtering
    @lang_filter = params[:lang].presence
    @cat_filter  = params[:cat].presence
    @q_filter    = params[:q].presence

    entries = entries.select { |e| e["lang"] == @lang_filter } if @lang_filter
    entries = entries.select { |e| e["cat"]  == @cat_filter  } if @cat_filter
    entries = entries.select { |e| e["cmd"].to_s.downcase.include?(@q_filter.downcase) } if @q_filter

    @total = entries.size

    # Newest-first pagination
    entries   = entries.reverse
    @page     = [params[:page].to_i, 1].max
    @pages    = [(@total.to_f / PER_PAGE).ceil, 1].max
    @entries  = entries.slice((@page - 1) * PER_PAGE, PER_PAGE) || []

    # Available filter options (from full unfiltered data)
    all = read_jsonl(jsonl_path)
    @langs = all.map { |e| e["lang"] }.uniq.compact.sort
    @cats  = all.map { |e| e["cat"]  }.uniq.compact.sort

    @audio_dir = Pathname.new(audio_dir)
    @cap       = Setting["log_max_entries"].to_i.nonzero? || 1000
    @n_wav     = all.count { |e| e["wav"] }
    @max_wav   = Setting["log_max_wavs"].to_i.nonzero? || 100
  end

  private

  def read_jsonl(path)
    file = Pathname.new(path)
    return [] unless file.exist?

    file.each_line.filter_map do |line|
      JSON.parse(line.strip) rescue nil
    end
  rescue => e
    Rails.logger.warn "HistoryController: could not read #{path}: #{e.message}"
    []
  end

  def default_jsonl_path
    Rails.root.join("../../freespeech_commands.jsonl").to_s
  end

  def default_audio_dir
    Rails.root.join("../../audio").to_s
  end
end
