class CreateRagDocuments < ActiveRecord::Migration[8.1]
  def change
    create_table :rag_documents do |t|
      t.references :user,        null: false, foreign_key: true
      t.string  :keyword,        null: false, default: ""  # topic keyword, e.g. "traveling"
      t.string  :description,    null: false, default: ""  # for the AI ping prompt
      t.string  :filename,       null: false, default: ""  # original upload name (display only)
      t.text    :file_content,   null: false, default: ""  # extracted plain text
      t.integer :position,       null: false, default: 0
      t.timestamps
    end

    add_index :rag_documents, [:user_id, :keyword]
  end
end
