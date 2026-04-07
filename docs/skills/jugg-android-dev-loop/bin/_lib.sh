#!/usr/bin/env bash
# _lib.sh — Jugg CLI shared library
# Provides: port detection, projectDir resolution, record session management,
#           HTTP dispatch, JSON parsing utilities.

set -euo pipefail

# ─── cache paths (overridable by tests) ──────────────────────────────────────

JUGG_CACHE_DIR="${JUGG_CACHE_DIR:-$HOME/.cache/jugg}"
JUGG_PORT_CACHE="${JUGG_PORT_CACHE:-$JUGG_CACHE_DIR/port}"
JUGG_RECORD_SESSION="${JUGG_RECORD_SESSION:-$JUGG_CACHE_DIR/record_session}"

_jugg_ensure_cache_dir() {
  mkdir -p "$(dirname "$JUGG_PORT_CACHE")"
}

# ─── port cache ───────────────────────────────────────────────────────────────

_jugg_read_port_cache() {
  [[ -f "$JUGG_PORT_CACHE" ]] && cat "$JUGG_PORT_CACHE" || true
}

_jugg_write_port_cache() {
  _jugg_ensure_cache_dir
  echo "$1" > "$JUGG_PORT_CACHE"
}

# Ping a port; return 0 if the Jugg MCP endpoint responds.
_jugg_ping_port() {
  local port="$1"
  local response
  response=$(curl -sf --max-time 1 \
    -X POST "http://localhost:${port}/jugg-mcp" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}' 2>/dev/null) || return 1
  echo "$response" | grep -q '"jsonrpc"' || return 1
}

# Resolve the active Jugg port: check cache first, then scan 12320..12329.
_jugg_resolve_port() {
  local cached
  cached=$(_jugg_read_port_cache)
  if [[ -n "$cached" ]] && _jugg_ping_port "$cached"; then
    echo "$cached"
    return
  fi
  local port
  for port in $(seq 12320 12329); do
    if _jugg_ping_port "$port"; then
      _jugg_write_port_cache "$port"
      echo "$port"
      return
    fi
  done
  echo "ERROR: Jugg IDE plugin not found on ports 12320-12329. Is Android Studio running?" >&2
  exit 1
}

# ─── projectDir resolution ────────────────────────────────────────────────────

# Given a working directory and a newline-separated list of projectDirs,
# return the longest prefix match (slash-boundary-aware).
_jugg_match_project_dir() {
  local work_dir="$1"
  local projects="$2"
  local best=""
  local dir
  while IFS= read -r dir; do
    [[ -z "$dir" ]] && continue
    # Must match at a path boundary: either exact or followed by /
    if [[ "$work_dir" == "$dir" || "$work_dir" == "$dir/"* ]]; then
      if [[ ${#dir} -gt ${#best} ]]; then
        best="$dir"
      fi
    fi
  done <<< "$projects"
  echo "$best"
}

# Call list_projects and resolve projectDir from $PWD.
_jugg_resolve_project_dir() {
  local port
  port=$(_jugg_resolve_port)
  local response
  response=$(_jugg_raw_call "$port" "list_projects" "{}")
  local projects
  projects=$(echo "$response" | _jugg_jq_list_projects)
  local matched
  matched=$(_jugg_match_project_dir "$PWD" "$projects")
  if [[ -z "$matched" ]]; then
    echo "ERROR: Current directory '$PWD' is not under any Jugg project." >&2
    echo "       Run this command from within a project directory." >&2
    exit 1
  fi
  echo "$matched"
}

# Extract projectDir list from list_projects response.
# Expected shape: {"status":"OK","data":{"projects":[{"projectDir":"/path"},...]}}
_jugg_jq_list_projects() {
  python3 -c "
import sys, json
data = json.load(sys.stdin)
projects = data.get('result', {}).get('structuredContent', {}).get('data', {}).get('projects', [])
for p in projects:
    d = p.get('projectDir', '')
    if d:
        print(d)
"
}

# ─── HTTP dispatch ────────────────────────────────────────────────────────────

# Low-level HTTP call; returns raw response body.
_jugg_http() {
  local port="$1"
  local body="$2"
  curl -sf --max-time 30 \
    -X POST "http://localhost:${port}/jugg-mcp" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# Assemble JSON-RPC 2.0 tools/call body and POST it.
_jugg_raw_call() {
  local port="$1"
  local tool="$2"
  local params="$3"
  local body
  body=$(printf '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}' \
    "$tool" "$params")
  _jugg_http "$port" "$body"
}

# High-level call: resolve port + projectDir, inject projectDir into params,
# POST, check status, print result.
# Usage: jugg_call <tool_name> <params_json> [--json]
jugg_call() {
  local tool="$1"
  local params="$2"
  local raw_flag="${3:-}"
  local port
  port=$(_jugg_resolve_port)
  local response
  response=$(_jugg_raw_call "$port" "$tool" "$params")
  local structured
  structured=$(echo "$response" | python3 -c "
import sys, json
r = json.load(sys.stdin)
sc = r.get('result', {}).get('structuredContent', {})
print(json.dumps(sc))
" 2>/dev/null || echo '{"status":"ERROR","message":"Failed to parse response"}')

  if [[ "$raw_flag" == "--json" ]]; then
    echo "$structured"
    return
  fi

  local status
  status=$(_jugg_extract_field "$structured" "status")
  if [[ "$status" != "OK" ]]; then
    local msg
    msg=$(_jugg_extract_field "$structured" "message")
    printf "status: ERROR\nmessage: %s\n" "$msg" >&2
    exit 1
  fi
  _jugg_parse_kv "$structured"
}

# ─── record session cache ─────────────────────────────────────────────────────

_jugg_record_session_exists() {
  [[ -f "$JUGG_RECORD_SESSION" ]]
}

_jugg_record_session_save() {
  _jugg_ensure_cache_dir
  echo "$1" > "$JUGG_RECORD_SESSION"
}

_jugg_record_session_read() {
  cat "$JUGG_RECORD_SESSION"
}

_jugg_record_session_clear() {
  rm -f "$JUGG_RECORD_SESSION"
}

# ─── JSON parsing utilities ───────────────────────────────────────────────────

# Extract a top-level string field from a JSON object.
_jugg_extract_field() {
  local json="$1"
  local field="$2"
  echo "$json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
v = d.get('$field', '')
print(v)
"
}

# Extract a field nested under 'data'.
_jugg_extract_data_field() {
  local json="$1"
  local field="$2"
  echo "$json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
v = d.get('data', {}).get('$field', '')
print(v)
"
}

# Print status + all data fields as 'key: value' lines.
_jugg_parse_kv() {
  local json="$1"
  echo "$json" | python3 -c "
import sys, json

def flatten(d, prefix=''):
    for k, v in d.items():
        key = (prefix + '.' + k) if prefix else k
        if isinstance(v, dict):
            flatten(v, key)
        else:
            print(f'{key}: {v}')

d = json.load(sys.stdin)
# Always print status first
status = d.get('status', '')
if status:
    print(f'status: {status}')
message = d.get('message', '')
if message:
    print(f'message: {message}')
data = d.get('data', {})
if isinstance(data, dict):
    for k, v in data.items():
        if isinstance(v, (dict, list)):
            print(f'{k}: {json.dumps(v)}')
        else:
            print(f'{k}: {v}')
elif data:
    print(f'data: {data}')
artifacts = d.get('artifacts', [])
for art in artifacts:
    if isinstance(art, dict):
        for k, v in art.items():
            print(f'{k}: {v}')
"
}

# ─── async compile polling ────────────────────────────────────────────────────

# Poll get_compile_status until isFinal=true, then return the final structured JSON.
# Args: <port> <initial_structured_json>
_jugg_poll_compile() {
  local port="$1"
  local structured="$2"

  while true; do
    local is_final
    is_final=$(echo "$structured" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(str(d.get('data', {}).get('isFinal', True)).lower())
" 2>/dev/null || echo "true")

    [[ "$is_final" == "true" ]] && break

    local job_id interval
    job_id=$(echo "$structured" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('data', {}).get('jobId', ''))
" 2>/dev/null || true)
    interval=$(echo "$structured" | python3 -c "
import sys, json
d = json.load(sys.stdin)
ms = d.get('data', {}).get('pollIntervalSuggestedMs', 2000)
print(ms)
" 2>/dev/null || echo "2000")

    # Print progress message if present
    local msg
    msg=$(echo "$structured" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('message', ''))
" 2>/dev/null || true)
    [[ -n "$msg" ]] && printf "  %s\n" "$msg" >&2

    sleep "$(echo "scale=3; $interval/1000" | bc)"

    if [[ -z "$job_id" ]]; then
      echo "ERROR: compile job has no jobId, cannot poll" >&2
      exit 1
    fi

    local raw
    raw=$(_jugg_raw_call "$port" "get_compile_status" "{\"jobId\":\"$job_id\"}")
    structured=$(echo "$raw" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(json.dumps(r.get('result',{}).get('structuredContent',{})))
")
  done

  echo "$structured"
}
