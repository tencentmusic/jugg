#!/usr/bin/env ruby

require 'yaml'

version = ARGV.first.to_s.sub(/\Av/, '')
abort 'Release version is required.' if version.empty?

def load_release(path, version)
  releases = YAML.safe_load(File.read(path))
  abort "Invalid changelog format: #{path}" unless releases.is_a?(Array)

  matches = releases.select { |release| release.is_a?(Hash) && release['version'].to_s == version }
  abort "Expected exactly one #{version} section in #{path}, found #{matches.size}." unless matches.one?

  release = matches.first
  updates = release['updates']
  unless updates.is_a?(Array) && updates.all? { |update| update.is_a?(String) && !update.empty? } && !updates.empty?
    abort "Release #{version} has invalid updates in #{path}."
  end
  release
end

root_dir = File.expand_path('..', __dir__)
english = load_release(File.join(root_dir, 'change_log/change_log_rc.yaml'), version)
chinese = load_release(File.join(root_dir, 'change_log/change_log_rc_cn.yaml'), version)

abort "Release #{version} dates do not match." unless english['date'].to_s == chinese['date'].to_s
abort "Release #{version} update counts do not match." unless english['updates'].size == chinese['updates'].size

puts "## English\n\n"
english['updates'].each { |update| puts "- #{update}" }
puts "\n## 中文\n\n"
chinese['updates'].each { |update| puts "- #{update}" }
