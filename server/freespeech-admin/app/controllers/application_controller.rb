class ApplicationController < ActionController::Base
  protect_from_forgery with: :exception

  helper_method :current_user, :logged_in?, :admin?, :active_tab

  private

  # ── Session auth ───────────────────────────────────────────────────────────

  def current_user
    @current_user ||= User.find_by(id: session[:user_id]) if session[:user_id]
  end

  def logged_in?   = current_user.present?
  def admin?       = current_user&.admin?

  def require_login
    return if logged_in?
    redirect_to login_path, alert: "Please log in."
  end

  def require_admin
    return if admin?
    if logged_in?
      redirect_to profile_path, alert: "Admin access required."
    else
      redirect_to login_path, alert: "Please log in."
    end
  end

  # ── Tab highlight ──────────────────────────────────────────────────────────

  def active_tab
    case controller_name
    when "config", "languages" then :config
    when "history"              then :history
    when "profile"              then :profile
    else :config
    end
  end
end
