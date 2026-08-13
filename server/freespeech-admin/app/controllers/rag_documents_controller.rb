class RagDocumentsController < ApplicationController
  before_action :require_login

  MAX_UPLOAD_BYTES = 20 * 1024 * 1024   # 20 MB

  def index
    @rag_documents = current_user.rag_documents.order(:position, :created_at)
  end

  def create
    upload = params[:rag_document]&.fetch(:file, nil)
    unless upload.is_a?(ActionDispatch::Http::UploadedFile)
      redirect_to rag_documents_path, alert: "Please choose a PDF or EPUB file to upload."
      return
    end

    if upload.size > MAX_UPLOAD_BYTES
      redirect_to rag_documents_path,
        alert: "File too large (max 20 MB). Got #{(upload.size / 1_048_576.0).round(1)} MB."
      return
    end

    data     = upload.read
    filename = upload.original_filename.to_s
    ext      = File.extname(filename).downcase

    content = case ext
              when ".pdf"  then RagDocument.extract_pdf(data)
              when ".epub" then RagDocument.extract_epub(data)
              else
                redirect_to rag_documents_path,
                  alert: "Unsupported file type '#{ext}'. Please upload a PDF or EPUB."
                return
              end

    doc = current_user.rag_documents.build(
      keyword:      params[:rag_document][:keyword].to_s.strip,
      description:  params[:rag_document][:description].to_s.strip,
      filename:     filename,
      file_content: content,
      position:     current_user.rag_documents.count,
    )

    if doc.save
      redirect_to rag_documents_path,
        notice: "\"#{filename}\" added to your RAG library (#{content.length} characters extracted)."
    else
      @rag_documents = current_user.rag_documents.order(:position, :created_at)
      @error = doc.errors.full_messages.to_sentence
      flash.now[:alert] = "Could not save document: #{@error}"
      render :index, status: :unprocessable_entity
    end

  rescue => e
    Rails.logger.error "RAG document extraction failed: #{e.class}: #{e.message}"
    redirect_to rag_documents_path,
      alert: "Could not extract text from the file: #{e.message.first(120)}"
  end

  def destroy
    doc = current_user.rag_documents.find(params[:id])
    doc.destroy
    redirect_to rag_documents_path, notice: "\"#{doc.filename}\" removed from your RAG library."
  rescue ActiveRecord::RecordNotFound
    redirect_to rag_documents_path, alert: "Document not found."
  end
end
