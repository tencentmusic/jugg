#!/usr/bin/env bash
# cmd/record-stop.sh — stop screen recording and output mp4 path

cmd_record_stop() {
  local json_flag=""
  [[ "${1:-}" == "--json" ]] && json_flag="--json"

  if ! _jugg_record_session_exists; then
    echo "ERROR: No recording in progress. Run 'jugg record-start' first." >&2
    exit 1
  fi

  local session_id
  session_id=$(_jugg_record_session_read)

  local project_dir
  project_dir=$(_jugg_resolve_project_dir)
  local port
  port=$(_jugg_resolve_port)
  local params
  params=$(printf '{"projectDir":"%s","sessionId":"%s"}' "$project_dir" "$session_id")

  local response structured
  response=$(_jugg_raw_call "$port" "stop_record" "$params")
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

  _jugg_record_session_clear

  if [[ "$json_flag" == "--json" ]]; then
    echo "$structured"; return
  fi

  local file
  file=$(_jugg_extract_data_field "$structured" "file")
  printf "status: OK\nfile: %s\n" "$file"
}
