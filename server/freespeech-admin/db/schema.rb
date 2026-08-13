# This file is auto-generated from the current state of the database. Instead
# of editing this file, please use the migrations feature of Active Record to
# incrementally modify your database, and then regenerate this schema definition.
#
# This file is the source Rails uses to define your schema when running `bin/rails
# db:schema:load`. When creating a new database, `bin/rails db:schema:load` tends to
# be faster and is potentially less error prone than running all of your
# migrations from scratch. Old migrations may fail to apply correctly if those
# migrations use external dependencies or application code.
#
# It's strongly recommended that you check this file into your version control system.

ActiveRecord::Schema[8.1].define(version: 2026_08_13_140000) do
  create_table "custom_topics", force: :cascade do |t|
    t.string "android_package", default: "", null: false
    t.string "app_label", null: false
    t.datetime "created_at", null: false
    t.string "description", default: "", null: false
    t.string "name", null: false
    t.integer "position", default: 0, null: false
    t.text "search_urls", default: "", null: false
    t.string "target_type", default: "app", null: false
    t.string "transform_hint", default: "", null: false
    t.datetime "updated_at", null: false
    t.string "uri_template", default: ""
    t.index ["name"], name: "index_custom_topics_on_name", unique: true
  end

  create_table "languages", force: :cascade do |t|
    t.string "code", null: false
    t.datetime "created_at", null: false
    t.string "name", null: false
    t.boolean "protected", default: false, null: false
    t.datetime "updated_at", null: false
    t.index ["code"], name: "index_languages_on_code", unique: true
  end

  create_table "rag_documents", force: :cascade do |t|
    t.datetime "created_at", null: false
    t.string "description", default: "", null: false
    t.text "file_content", default: "", null: false
    t.string "filename", default: "", null: false
    t.string "keyword", default: "", null: false
    t.integer "position", default: 0, null: false
    t.datetime "updated_at", null: false
    t.integer "user_id", null: false
    t.index ["user_id", "keyword"], name: "index_rag_documents_on_user_id_and_keyword"
    t.index ["user_id"], name: "index_rag_documents_on_user_id"
  end

  create_table "settings", force: :cascade do |t|
    t.datetime "created_at", null: false
    t.string "key", null: false
    t.datetime "updated_at", null: false
    t.text "value"
    t.index ["key"], name: "index_settings_on_key", unique: true
  end

  create_table "users", force: :cascade do |t|
    t.text "ai_api_key"
    t.string "ai_base_url"
    t.string "ai_engine"
    t.string "ai_model"
    t.string "api_token", null: false
    t.string "confirmation_token"
    t.datetime "confirmed_at"
    t.datetime "created_at", null: false
    t.string "email", null: false
    t.string "password_digest", null: false
    t.string "role", default: "user", null: false
    t.datetime "updated_at", null: false
    t.index ["api_token"], name: "index_users_on_api_token", unique: true
    t.index ["confirmation_token"], name: "index_users_on_confirmation_token", unique: true
    t.index ["email"], name: "index_users_on_email", unique: true
  end

  add_foreign_key "rag_documents", "users"
end
