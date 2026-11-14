#!/usr/bin/env ruby

require 'date'
require 'json'
require 'rubygems/version'
require 'yaml'

CATEGORIES = {
  'feature' => ['Features', '新功能'],
  'optimize' => ['Optimizations', '优化'],
  'bugfix' => ['Bug fixes', '问题修复']
}.freeze

def parse_version(value)
  match = /\Av?((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))\z/.match(value.to_s)
  Gem::Version.new(match[1]) if match
end

def previous_version(path, current)
  pages = JSON.parse(File.read(path))
  unless pages.is_a?(Array) && pages.all? { |page| page.is_a?(Array) }
    abort "Invalid releases JSON page structure: #{path}"
  end

  candidates = pages.flatten(1).filter_map do |release|
    unless release.is_a?(Hash) && release['tag_name'].is_a?(String) &&
           [true, false].include?(release['draft']) && [true, false].include?(release['prerelease'])
      abort "Invalid release metadata in #{path}: #{release.inspect}"
    end
    next if release['draft'] || release['prerelease']

    version = parse_version(release['tag_name'])
    version if version && version < current
  end
  candidates.max || abort("No previous formal release below #{current} in #{path}.")
rescue JSON::ParserError => exception
  abort "Invalid releases JSON in #{path}: #{exception.message}"
end

def update_category(update)
  /\A\[(feature|optimize|bugfix)\]/.match(update.to_s)&.[](1)
end

def validate_release(release, version, path)
  date = release['date']
  unless date.is_a?(String) && /\A\d{4}\.\d{2}\.\d{2}\z/.match?(date)
    abort "Invalid date for release #{version} in #{path}: #{date.inspect}"
  end
  Date.strptime(date, '%Y.%m.%d')

  updates = release['updates']
  unless updates.is_a?(Array) && !updates.empty? &&
         updates.all? { |update| update.is_a?(String) && update_category(update) }
    abort "Release #{version} has invalid updates in #{path}: #{updates.inspect}"
  end
rescue Date::Error => exception
  abort "Invalid date for release #{version} in #{path}: #{date.inspect} (#{exception.message})"
end

def load_releases(path, previous, current)
  releases = YAML.safe_load(File.read(path))
  abort "Invalid changelog format: #{path}" unless releases.is_a?(Array)

  selected = {}
  releases.each do |release|
    next unless release.is_a?(Hash)

    version = parse_version(release['version'])
    next unless version && previous < version && version <= current

    abort "Duplicate release #{version} in #{path}." if selected.key?(version)

    validate_release(release, version, path)
    selected[version] = release
  end
  abort "Missing release #{current} in #{path}." unless selected.key?(current)

  selected
rescue Psych::Exception => exception
  abort "Invalid changelog format in #{path}: #{exception.message}"
end

def validate_languages(english, chinese)
  abort 'Release version sets do not match.' unless english.keys.sort == chinese.keys.sort

  english.each do |version, release|
    translated = chinese.fetch(version)
    abort "Release #{version} dates do not match." unless release['date'] == translated['date']
    unless release['updates'].size == translated['updates'].size
      abort "Release #{version} update counts do not match."
    end
    categories = release['updates'].map { |update| update_category(update) }
    unless categories == translated['updates'].map { |update| update_category(update) }
      abort "Release #{version} update categories do not match."
    end
  end
end

# Sort before grouping so every category keeps the same chronological order in both languages.
def render_notes(releases, language, language_index)
  updates = releases.sort_by { |version, release| [release['date'], version] }
                    .flat_map { |_, release| release['updates'] }
                    .group_by { |update| update_category(update) }
  sections = ["## #{language}"]
  CATEGORIES.each do |category, titles|
    next unless updates.key?(category)

    sections << "### #{titles[language_index]}\n\n" + updates[category].map { |update| "- #{update}" }.join("\n")
  end
  sections.join("\n\n")
end

def main
  abort "Usage: ruby #{$PROGRAM_NAME} <current-tag> <releases-json>" unless ARGV.size == 2
  current = parse_version(ARGV[0])
  abort "Invalid release version: #{ARGV[0]}" unless current

  previous = previous_version(ARGV[1], current)
  root_dir = File.expand_path('..', __dir__)
  english = load_releases(File.join(root_dir, 'change_log/change_log_rc.yaml'), previous, current)
  chinese = load_releases(File.join(root_dir, 'change_log/change_log_rc_cn.yaml'), previous, current)
  validate_languages(english, chinese)

  count = english.values.sum { |release| release['updates'].size }
  warn "Release range: #{previous} < version <= #{current}; versions: #{english.keys.sort.join(', ')}; " \
       "updates per language: #{count}"
  puts [render_notes(english, 'English', 0), render_notes(chinese, '中文', 1)].join("\n\n")
rescue SystemCallError => exception
  abort exception.message
end

main
