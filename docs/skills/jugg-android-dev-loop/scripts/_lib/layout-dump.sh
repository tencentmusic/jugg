#!/usr/bin/env bash
# cmd/layout-dump.sh — export UI hierarchy to HTML file

_layout_dump_build_params() {
  local root="" include_gone=false all_windows=false

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --root)          root="$2"; shift 2 ;;
      --include-gone)  include_gone=true; shift ;;
      --all-windows)   all_windows=true;  shift ;;
      *) echo "Unknown option: $1" >&2; return 1 ;;
    esac
  done

  local json="{" first=true
  if [[ -n "$root" ]]; then
    json="${json}\"rootLayout\":\"$root\""
    first=false
  fi
  if [[ "$include_gone" == true ]]; then
    [[ "$first" == false ]] && json="$json,"
    json="${json}\"isIncludeGone\":true"
    first=false
  fi
  if [[ "$all_windows" == true ]]; then
    [[ "$first" == false ]] && json="$json,"
    json="${json}\"isAllWindows\":true"
  fi
  echo "$json}"
}

cmd_layout_dump() {
  local json_flag="" args=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --json) json_flag="--json"; shift ;;
      *)      args+=("$1"); shift ;;
    esac
  done

  local project_dir
  project_dir=$(_jugg_resolve_project_dir)
  local port
  port=$(_jugg_resolve_port)

  local extra_params
  extra_params=$(_layout_dump_build_params "${args[@]}") || exit 1

  # Merge projectDir into params
  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "layout_dump" "$params")
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
  _jugg_parse_kv "$structured"
}
