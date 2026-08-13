class RegistrationsController < ApplicationController
  def new
    redirect_to profile_path if logged_in?
    @user = User.new
  end

  def create
    @user = User.new(registration_params)
    @user.role = "user"  # enforce — no self-promotion to admin

    if @user.save
      UserMailer.confirmation_email(@user).deliver_now rescue nil
      # If SMTP is not configured the mailer logs to stdout and no exception propagates.
      # The admin can manually confirm users from the Users page.
      render :pending
    else
      render :new, status: :unprocessable_entity
    end
  end

  def confirm
    user = User.find_by(confirmation_token: params[:token].to_s)

    if user.nil?
      redirect_to login_path, alert: "Invalid or expired confirmation link."
    elsif user.confirmed?
      redirect_to login_path, notice: "Account already confirmed. Please log in."
    else
      user.confirm!
      session[:user_id] = user.id   # auto-login after confirmation
      render :confirmed, locals: { user: user }
    end
  end

  private

  def registration_params
    params.require(:user).permit(:email, :password, :password_confirmation)
  end
end
