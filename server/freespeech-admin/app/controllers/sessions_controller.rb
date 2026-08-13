class SessionsController < ApplicationController
  def new
    redirect_to root_path if logged_in?
  end

  def create
    user = User.find_by(email: params[:email].to_s.strip.downcase)

    if user&.authenticate(params[:password]) && user.confirmed?
      session[:user_id] = user.id
      redirect_to (admin? ? config_path : profile_path),
                  notice: "Welcome back, #{user.email}!"
    elsif user && !user.confirmed?
      redirect_to login_path,
                  alert: "Account not yet confirmed. Check your email for the confirmation link."
    else
      flash.now[:alert] = "Invalid email or password."
      render :new, status: :unprocessable_entity
    end
  end

  def destroy
    session.delete(:user_id)
    redirect_to login_path, notice: "Logged out."
  end
end
