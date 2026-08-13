class UserMailer < ApplicationMailer
  def confirmation_email(user)
    @user             = user
    @confirmation_url = confirm_url(token: user.confirmation_token)
    mail(to: user.email, subject: "Confirm your FreeSpeech Console account")
  end
end
