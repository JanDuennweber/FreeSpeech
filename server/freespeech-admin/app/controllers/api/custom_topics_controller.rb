module Api
  # GET /api/custom_topics
  # Public endpoint — returns all custom topic definitions so the Android app
  # can include them in local AI prompts and build deep-link intents.
  class CustomTopicsController < BaseController
    def index
      render json: CustomTopic.all.as_json(
        only: %i[id name description app_label android_package uri_template transform_hint position]
      )
    end
  end
end
