module Api
  class BaseController < ActionController::Base
    # API controllers: no CSRF, no session auth, JSON responses.
    protect_from_forgery with: :null_session

    # Resolve the calling user from a Bearer token, or nil for anonymous.
    def api_user
      return @api_user if defined?(@api_user)
      token = request.headers["Authorization"].to_s.sub(/\ABearer\s+/i, "")
      @api_user = token.present? ? User.find_by(api_token: token) : nil
    end

    helper_method :api_user
  end
end
