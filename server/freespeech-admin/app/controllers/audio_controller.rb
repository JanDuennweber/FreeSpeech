class AudioController < ApplicationController
  # Serve WAV files from the configured audio directory.
  # This lets the History tab play and download recordings without needing
  # the Python log server to be running on a separate port.

  def show
    audio_dir = Pathname.new(
      Setting["log_audio_dir"].presence ||
        Rails.root.join("../../audio").to_s
    )

    # Sanitise: only allow simple filenames (no path traversal)
    filename = File.basename(params[:filename].to_s)
    unless filename =~ /\A[\w\-]+\.wav\z/i
      head :bad_request and return
    end

    file_path = audio_dir.join(filename)
    unless file_path.exist?
      head :not_found and return
    end

    send_file file_path,
              type: "audio/wav",
              disposition: "inline",
              filename: filename
  end
end
