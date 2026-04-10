#!/usr/bin/env bash
# cmd/view-inspect.sh — build params for the 'view-inspect' subcommand

_view_inspect_build_params() {
  local text="" resource_id="" content_desc="" class_name=""
  local expressions=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --text)  text="$2";         shift 2 ;;
      --id)    resource_id="$2";  shift 2 ;;
      --desc)  content_desc="$2"; shift 2 ;;
      --class) class_name="$2";   shift 2 ;;
      --*) echo "Unknown option: $1" >&2; return 1 ;;
      *) expressions+=("$1");     shift ;;
    esac
  done

  if [[ -z "$text" && -z "$resource_id" && -z "$content_desc" ]]; then
    echo "view-inspect requires at least one selector: --text, --id, or --desc" >&2
    return 1
  fi

  if [[ ${#expressions[@]} -eq 0 ]]; then
    echo "view-inspect requires at least one expression argument" >&2
    return 1
  fi

  # Build target JSON
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
    first=false
  fi
  if [[ -n "$class_name" ]]; then
    [[ "$first" == false ]] && target="$target,"
    target="${target}\"className\":\"$class_name\""
  fi
  target="$target}"

  # Build expressions JSON array
  local exprs_json="["
  first=true
  local expr
  for expr in "${expressions[@]}"; do
    [[ "$first" == false ]] && exprs_json="$exprs_json,"
    exprs_json="${exprs_json}\"$expr\""
    first=false
  done
  exprs_json="$exprs_json]"

  echo "{\"target\":$target,\"expressions\":$exprs_json}"
}

cmd_view_inspect() {
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
  extra_params=$(_view_inspect_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "eval_view" "$params")
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

cmd_view_inspect() {
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
  extra_params=$(_view_inspect_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "eval_view" "$params")
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
