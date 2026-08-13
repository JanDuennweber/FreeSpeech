class CreateUsers < ActiveRecord::Migration[8.1]
  def change
    create_table :users do |t|
      t.string   :email,              null: false
      t.string   :password_digest,    null: false
      t.string   :api_token,          null: false
      t.string   :role,               null: false, default: "user"

      # Email confirmation
      t.datetime :confirmed_at
      t.string   :confirmation_token

      # Per-user AI overrides (nil = use system defaults)
      t.string   :ai_engine
      t.text     :ai_api_key
      t.string   :ai_base_url
      t.string   :ai_model

      t.timestamps
    end

    add_index :users, :email,              unique: true
    add_index :users, :api_token,          unique: true
    add_index :users, :confirmation_token, unique: true
  end
end
