class AddWebSearchToCustomTopics < ActiveRecord::Migration[8.1]
  def change
    # "app" (default) = deep-link via uri_template
    # "web_search"    = browser search restricted to search_urls
    add_column :custom_topics, :target_type, :string, null: false, default: "app"
    add_column :custom_topics, :search_urls, :text,   null: false, default: ""
    # uri_template is now optional — only required when target_type == "app"
    change_column_null :custom_topics, :uri_template, true
    change_column_default :custom_topics, :uri_template, from: nil, to: ""
  end
end
