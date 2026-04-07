#!/usr/bin/env bash
# cmd/screenshot.sh — capture a device screenshot

cmd_screenshot() {
  local json_flag=""
  [[ "${1:-}" == "--json" ]] && json_flag="--json"

  local project_dir
  project_dir=$(_jugg_resolve_project_dir)
  local params
  params=$(printf '{"projectDir":"%s"}' "$project_dir")

  local response
  response=$(_jugg_resolve_port)
  response=$(_jugg_raw_call "$response" "screenshot" "$params")
  local structured
  structured=$(echo "$response" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(json.dumps(r.get('result',{}).get('structuredContent',{})))
")

  if [[ "$json_flag" == "--json" ]]; then
    echo "$structured"; return
  fi

  local status
  status=$(_jugg_extract_field "$structured" "status")
  if [[ "$status" != "OK" ]]; then
    local msg; msg=$(_jugg_extract_field "$structured" "message")
    printf "status: ERROR\nmessage: %s\n" "$msg" >&2; exit 1
  fi

  local file
  file=$(_jugg_extract_data_field "$structured" "file")
  printf "status: OK\nfile: %s\n" "$file"
}
