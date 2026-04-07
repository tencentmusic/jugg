#!/usr/bin/env bash
# cmd/tap.sh — parse and build params for the 'tap' subcommand

# Build the JSON params for the tap MCP tool.
# Outputs JSON on stdout; exits non-zero with error message on stderr on failure.
_tap_build_params() {
  local action="tap"
  local x="" y="" end_x="" end_y=""
  local xp="" yp="" end_xp="" end_yp=""
  local text="" resource_id="" content_desc="" class_name=""
  local duration=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --action)    action="$2";       shift 2 ;;
      --x)         x="$2";            shift 2 ;;
      --y)         y="$2";            shift 2 ;;
      --end-x)     end_x="$2";        shift 2 ;;
      --end-y)     end_y="$2";        shift 2 ;;
      --xp)        xp="$2";           shift 2 ;;
      --yp)        yp="$2";           shift 2 ;;
      --end-xp)    end_xp="$2";       shift 2 ;;
      --end-yp)    end_yp="$2";       shift 2 ;;
      --text)      text="$2";         shift 2 ;;
      --id)        resource_id="$2";  shift 2 ;;
      --desc)      content_desc="$2"; shift 2 ;;
      --class)     class_name="$2";   shift 2 ;;
      --duration)  duration="$2";     shift 2 ;;
      *) echo "Unknown option: $1" >&2; return 1 ;;
    esac
  done

  # Resolve action name for MCP (long-press → longPress)
  local mcp_action
  case "$action" in
    tap)        mcp_action="tap" ;;
    long-press) mcp_action="longPress" ;;
    swipe)      mcp_action="swipe" ;;
    *) echo "Unknown action: $action" >&2; return 1 ;;
  esac

  # Validate swipe requires end coords
  if [[ "$mcp_action" == "swipe" ]]; then
    if [[ -n "$x" && (-z "$end_x" || -z "$end_y") ]]; then
      echo "swipe requires --end-x and --end-y" >&2; return 1
    fi
    if [[ -n "$xp" && (-z "$end_xp" || -z "$end_yp") ]]; then
      echo "swipe requires --end-xp and --end-yp" >&2; return 1
    fi
  fi

  # Coordinate mode (highest priority)
  if [[ -n "$x" && -n "$y" ]]; then
    local json="{\"action\":\"$mcp_action\",\"x\":$x,\"y\":$y"
    [[ -n "$end_x" ]] && json="$json,\"endX\":$end_x,\"endY\":$end_y"
    [[ -n "$duration" ]] && json="$json,\"duration\":$duration"
    echo "$json}"
    return 0
  fi

  # Percent mode
  if [[ -n "$xp" && -n "$yp" ]]; then
    local json="{\"action\":\"$mcp_action\",\"xPercent\":$xp,\"yPercent\":$yp"
    [[ -n "$end_xp" ]] && json="$json,\"endXPercent\":$end_xp,\"endYPercent\":$end_yp"
    [[ -n "$duration" ]] && json="$json,\"duration\":$duration"
    echo "$json}"
    return 0
  fi

  # Element mode
  if [[ -n "$text" || -n "$resource_id" || -n "$content_desc" ]]; then
    local json="{\"action\":\"$mcp_action\""
    [[ -n "$text" ]]         && json="$json,\"text\":\"$text\""
    [[ -n "$resource_id" ]]  && json="$json,\"resourceId\":\"$resource_id\""
    [[ -n "$content_desc" ]] && json="$json,\"contentDesc\":\"$content_desc\""
    [[ -n "$class_name" ]]   && json="$json,\"className\":\"$class_name\""
    echo "$json}"
    return 0
  fi

  echo "tap requires a selector (--text/--id/--desc), coordinates (--x/--y), or percent coords (--xp/--yp)" >&2
  return 1
}

_tap_parse_args() {
  _tap_build_params "$@"
}

cmd_tap() {
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
  extra_params=$(_tap_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "tap" "$params")
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

cmd_tap() {
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
  extra_params=$(_tap_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "tap" "$params")
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
