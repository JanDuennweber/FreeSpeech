class Admin::UsersController < ApplicationController
  before_action :require_admin

  def index
    @users = User.order(:email)
  end

  # POST /admin/users/:id/confirm  — admin manually confirms a registration
  def confirm
    user = User.find(params[:id])
    if user.confirmed?
      redirect_to admin_users_path, notice: "#{user.email} is already confirmed."
    else
      user.confirm!
      redirect_to admin_users_path, notice: "#{user.email} confirmed."
    end
  end

  # DELETE /admin/users/:id
  def destroy
    user = User.find(params[:id])
    if user == current_user
      redirect_to admin_users_path, alert: "You cannot delete your own account."
    else
      user.destroy!
      redirect_to admin_users_path, notice: "User deleted."
    end
  end
end
