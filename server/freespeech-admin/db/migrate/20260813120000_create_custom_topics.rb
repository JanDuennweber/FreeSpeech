class CreateCustomTopics < ActiveRecord::Migration[8.1]
  def change
    create_table :custom_topics do |t|
      # Human label shown in the UI and used to build the CUSTOM_* key in the AI prompt.
      t.string :name,           null: false

      # Sentence that tells the AI what belongs to this topic.
      # E.g. "Finding restaurants, food delivery, places to eat nearby"
      t.string :description,    null: false, default: ""

      # Label of the target Android app shown to the user.
      t.string :app_label,      null: false

      # Package name of the preferred Android app (optional — intent still fires
      # without it, any app that handles the URI will be chosen by the OS).
      t.string :android_package, null: false, default: ""

      # URI template used to launch the app. Use {query} as the placeholder for
      # the AI-extracted and URL-encoded search term.
      # Examples:
      #   yelp://search?terms={query}
      #   https://www.yelp.com/search?find_desc={query}
      #   geo:0,0?q={query}+restaurant
      #   vnd.youtube:///search?q={query}
      t.string :uri_template,   null: false

      # Optional extra sentence appended to the pong prompt so the AI can produce
      # better queries. E.g. "Phrase the query as a local restaurant search."
      t.string :transform_hint, null: false, default: ""

      # Display order (lower = shown first).
      t.integer :position,      null: false, default: 0

      t.timestamps
    end
    add_index :custom_topics, :name, unique: true
  end
end
