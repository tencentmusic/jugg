#!/usr/bin/env bash
# cmd/gradle-build.sh — force Gradle build, poll until completion

cmd_gradle_build() {
  local json_flag=""
  [[ "${1:-}" == "--json" ]] && json_flag="--json"

  local project_dir
  project_dir=$(_jugg_resolve_project_dir)
  local port
  port=$(_jugg_resolve_port)
  local params
  params=$(printf '{"projectDir":"%s"}' "$project_dir")

  printf "Running Gradle build...\n" >&2
  local response structured
  response=$(_jugg_raw_call "$port" "force_gradle_compile" "$params")
  structured=$(echo "$response" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(json.dumps(r.get('result',{}).get('structuredContent',{})))
")

  structured=$(_jugg_poll_compile "$port" "$structured")

  if [[ "$json_flag" == "--json" ]]; then
    echo "$structured"; return
  fi

  local status
  status=$(_jugg_extract_field "$structured" "status")
  if [[ "$status" != "OK" ]]; then
    local msg; msg=$(_jugg_extract_field "$structured" "message")
    printf "status: ERROR\nmessage: %s\n" "$msg" >&2; exit 1
  fi
  _jugg_parse_kv "$structured"
}
