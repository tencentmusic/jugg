#!/usr/bin/env bash
# cmd/view-locate.sh — build params for the 'view-locate' subcommand

_view_locate_build_params() {
  local text="" resource_id="" content_desc=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --text) text="$2";         shift 2 ;;
      --id)   resource_id="$2";  shift 2 ;;
      --desc) content_desc="$2"; shift 2 ;;
      *) echo "Unknown option: $1" >&2; return 1 ;;
    esac
  done

  if [[ -z "$text" && -z "$resource_id" && -z "$content_desc" ]]; then
    echo "view-locate requires at least one selector: --text, --id, or --desc" >&2
    return 1
  fi

  local target="{"
  local first=true
  if [[ -n "$text" ]]; then
    target="${target}\"text\":\"$text\""
    first=false
  fi
  if [[ -n "$resource_id" ]]; then
    [[ "$first" == false ]] && target="$target,"
    target="${target}\"resourceId\":\"$resource_id\""
    first=false
  fi
  if [[ -n "$content_desc" ]]; then
    [[ "$first" == false ]] && target="$target,"
    target="${target}\"contentDesc\":\"$content_desc\""
  fi
  target="$target}"
  echo "{\"target\":$target}"
}

cmd_view_locate() {
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
  extra_params=$(_view_locate_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "ui_find" "$params")
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

cmd_view_locate() {
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
  extra_params=$(_view_locate_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "ui_find" "$params")
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
