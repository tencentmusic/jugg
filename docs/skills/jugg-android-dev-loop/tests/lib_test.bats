#!/usr/bin/env bats
# Tests for _lib.sh: port detection, projectDir resolution, jugg_call output parsing

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)"
LIB="$SCRIPT_DIR/bin/_lib.sh"

# ─── helpers ────────────────────────────────────────────────────────────────

setup() {
  # Use a temp dir for cache files so tests don't pollute ~/.cache/jugg
  export JUGG_CACHE_DIR
  JUGG_CACHE_DIR="$(mktemp -d)"
  export JUGG_PORT_CACHE="$JUGG_CACHE_DIR/port"
  export JUGG_RECORD_SESSION="$JUGG_CACHE_DIR/record_session"
}

teardown() {
  rm -rf "$JUGG_CACHE_DIR"
}

# Source the lib with a mock jugg_http so we can intercept HTTP calls
source_lib_with_mock_http() {
  local mock_response="$1"
  local mock_status="${2:-200}"
  # shellcheck disable=SC1090
  source "$LIB"
  # Override _jugg_http after sourcing
  _jugg_http() {
    echo "$mock_response"
    return "$([[ $mock_status -eq 200 ]] && echo 0 || echo 1)"
  }
}

# ─── port cache ─────────────────────────────────────────────────────────────

@test "_jugg_read_port_cache returns empty when no cache file" {
  source "$LIB"
  result=$(_jugg_read_port_cache)
  [ -z "$result" ]
}

@test "_jugg_write_port_cache writes port to cache file" {
  source "$LIB"
  _jugg_write_port_cache 12321
  [ -f "$JUGG_PORT_CACHE" ]
  [ "$(cat "$JUGG_PORT_CACHE")" = "12321" ]
}

@test "_jugg_read_port_cache returns previously written port" {
  source "$LIB"
  _jugg_write_port_cache 12323
  result=$(_jugg_read_port_cache)
  [ "$result" = "12323" ]
}

# ─── projectDir resolution ───────────────────────────────────────────────────

@test "_jugg_match_project_dir returns exact match" {
  source "$LIB"
  local projects="/project/alpha\n/project/beta"
  result=$(_jugg_match_project_dir "/project/alpha/src" "$(printf "%b" "$projects")")
  [ "$result" = "/project/alpha" ]
}

@test "_jugg_match_project_dir returns longest prefix match" {
  source "$LIB"
  local projects
  projects="$(printf "/project\n/project/sub")"
  result=$(_jugg_match_project_dir "/project/sub/src" "$projects")
  [ "$result" = "/project/sub" ]
}

@test "_jugg_match_project_dir returns empty when no match" {
  source "$LIB"
  local projects
  projects="$(printf "/project/alpha\n/project/beta")"
  result=$(_jugg_match_project_dir "/other/dir" "$projects")
  [ -z "$result" ]
}

@test "_jugg_match_project_dir handles single project" {
  source "$LIB"
  result=$(_jugg_match_project_dir "/my/project/module/src" "/my/project")
  [ "$result" = "/my/project" ]
}

@test "_jugg_match_project_dir requires slash boundary (no partial segment match)" {
  source "$LIB"
  result=$(_jugg_match_project_dir "/project_extra/src" "/project")
  [ -z "$result" ]
}

# ─── record session cache ───────────────────────────────────────────────────

@test "_jugg_record_session_exists returns false when no session file" {
  source "$LIB"
  run _jugg_record_session_exists
  [ "$status" -ne 0 ]
}

@test "_jugg_record_session_save writes sessionId to file" {
  source "$LIB"
  _jugg_record_session_save "sess-abc-123"
  [ -f "$JUGG_RECORD_SESSION" ]
  [ "$(cat "$JUGG_RECORD_SESSION")" = "sess-abc-123" ]
}

@test "_jugg_record_session_read returns saved sessionId" {
  source "$LIB"
  _jugg_record_session_save "sess-xyz-999"
  result=$(_jugg_record_session_read)
  [ "$result" = "sess-xyz-999" ]
}

@test "_jugg_record_session_clear removes session file" {
  source "$LIB"
  _jugg_record_session_save "sess-to-delete"
  _jugg_record_session_clear
  [ ! -f "$JUGG_RECORD_SESSION" ]
}

@test "_jugg_record_session_exists returns true when session file present" {
  source "$LIB"
  _jugg_record_session_save "active-session"
  run _jugg_record_session_exists
  [ "$status" -eq 0 ]
}

# ─── jugg_call output ────────────────────────────────────────────────────────

@test "_jugg_parse_kv outputs key: value lines from JSON data" {
  source "$LIB"
  json='{"status":"OK","data":{"file":"/tmp/shot.jpg"}}'
  result=$(_jugg_parse_kv "$json")
  echo "$result" | grep -q "status: OK"
  echo "$result" | grep -q "file: /tmp/shot.jpg"
}

@test "_jugg_parse_kv handles status ERROR" {
  source "$LIB"
  json='{"status":"ERROR","message":"No device found"}'
  result=$(_jugg_parse_kv "$json")
  echo "$result" | grep -q "status: ERROR"
  echo "$result" | grep -q "message: No device found"
}

@test "_jugg_extract_field extracts top-level field" {
  source "$LIB"
  json='{"status":"OK","data":{"jobId":"job-42"}}'
  result=$(_jugg_extract_field "$json" "status")
  [ "$result" = "OK" ]
}

@test "_jugg_extract_data_field extracts nested data field" {
  source "$LIB"
  json='{"status":"OK","data":{"file":"/tmp/rec.mp4"}}'
  result=$(_jugg_extract_data_field "$json" "file")
  [ "$result" = "/tmp/rec.mp4" ]
}
