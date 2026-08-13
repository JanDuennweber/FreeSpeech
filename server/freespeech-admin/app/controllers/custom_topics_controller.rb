class CustomTopicsController < ApplicationController
  before_action :require_admin

  def create
    @topic = CustomTopic.new(topic_params)
    if @topic.save
      redirect_to config_path, notice: "Custom topic "#{@topic.name}" added."
    else
      redirect_to config_path, alert: "Could not save topic: #{@topic.errors.full_messages.to_sentence}"
    end
  end

  def destroy
    topic = CustomTopic.find(params[:id])
    topic.destroy!
    redirect_to config_path, notice: "Custom topic "#{topic.name}" removed."
  rescue ActiveRecord::RecordNotFound
    redirect_to config_path, alert: "Topic not found."
  end

  private

  def topic_params
    params.require(:custom_topic).permit(
      :name, :description, :app_label, :android_package,
      :uri_template, :transform_hint, :position
    )
  end
end
