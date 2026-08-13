class ProfileController < ApplicationController
  before_action :require_login

  def show; end

  def update
    if params[:regenerate_token]
      current_user.regenerate_token!
      redirect_to profile_path, notice: "API token regenerated."
      return
    end

    # Update AI settings (engine overrides)
    ai_params = params.require(:user).permit(
      :ai_engine, :ai_api_key, :ai_base_url, :ai_model
    )
    # Blank string = "use system default" → store nil
    ai_params.transform_values! { |v| v.blank? ? nil : v.strip }

    # Password change (optional — only if new_password is filled)
    new_pass = params[:new_password].presence
    if new_pass
      unless current_user.authenticate(params[:current_password].to_s)
        redirect_to profile_path, alert: "Current password is incorrect."
        return
      end
      if new_pass != params[:confirm_password]
        redirect_to profile_path, alert: "New passwords don't match."
        return
      end
      ai_params[:password] = new_pass
    end

    if current_user.update(ai_params)
      redirect_to profile_path, notice: "Settings saved."
    else
      redirect_to profile_path, alert: current_user.errors.full_messages.join(", ")
    end
  end
end
