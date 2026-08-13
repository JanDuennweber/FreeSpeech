class Setting < ApplicationRecord
  validates :key, presence: true, uniqueness: true

  # Retrieve a setting value by key; return default if absent.
  def self.[](key)
    find_by(key: key.to_s)&.value
  end

  # Set (upsert) a key/value pair.
  def self.set(key, value)
    record = find_or_initialize_by(key: key.to_s)
    record.value = value.to_s
    record.save!
  end

  # All settings as a plain Ruby hash.
  def self.to_h
    all.each_with_object({}) { |s, h| h[s.key] = s.value }
  end
end
