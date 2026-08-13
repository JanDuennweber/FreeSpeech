class LanguagesController < ApplicationController
  skip_before_action :verify_authenticity_token, only: [:index]

  # POST /languages  — add a new language
  def create
    @language = Language.new(language_params)
    if @language.save
      redirect_to config_path, notice: "Language \"#{@language.display}\" added."
    else
      redirect_to config_path, alert: @language.errors.full_messages.join(", ")
    end
  end

  # DELETE /languages/:id  — remove a language (protected ones are blocked in model)
  def destroy
    @language = Language.find(params[:id])
    if @language.destroy
      redirect_to config_path, notice: "Language \"#{@language.display}\" removed."
    else
      redirect_to config_path, alert: @language.errors.full_messages.join(", ")
    end
  end

  # GET /api/languages.json  — public JSON list
  def index
    render json: Language.order(:name).as_json(only: %i[code name])
  end

  private

  def language_params
    params.require(:language).permit(:code, :name)
  end
end
