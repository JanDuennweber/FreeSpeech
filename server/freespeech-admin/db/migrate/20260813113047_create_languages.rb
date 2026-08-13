class CreateLanguages < ActiveRecord::Migration[8.1]
  def change
    create_table :languages do |t|
      t.string  :code,      null: false
      t.string  :name,      null: false
      t.boolean :protected, null: false, default: false
      t.timestamps
    end
    add_index :languages, :code, unique: true
  end
end
