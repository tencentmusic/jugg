#!/usr/bin/env bash
# cmd/record-start.sh — start screen recording (with concurrent-lock guard)

cmd_record_start() {
  local json_flag=""
  [[ "${1:-}" == "--json" ]] && json_flag="--json"

  if _jugg_record_session_exists; then
    echo "ERROR: A recording is already in progress. Run 'jugg record-stop' first." >&2
    exit 1
  fi

  local project_dir
  project_dir=$(_jugg_resolve_project_dir)
  local port
  port=$(_jugg_resolve_port)
  local params
  params=$(printf '{"projectDir":"%s"}' "$project_dir")

  local response structured
  response=$(_jugg_raw_call "$port" "start_record" "$params")
  structured=$(echo "$response" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(json.dumps(r.get('result',{}).get('structuredContent',{})))
")

  local status
  status=$(_jugg_extract_field "$structured" "status")
  if [[ "$status" != "OK" ]]; then
    local msg; msg=$(_jugg_extract_field "$structured" "message")
    printf "status: ERROR\nmessage: %s\n" "$msg" >&2; exit 1
  fi

  local session_id
  session_id=$(_jugg_extract_data_field "$structured" "sessionId")
  _jugg_record_session_save "$session_id"

  if [[ "$json_flag" == "--json" ]]; then
    echo "$structured"; return
  fi
  printf "status: OK\nmessage: Recording started\n"
}
