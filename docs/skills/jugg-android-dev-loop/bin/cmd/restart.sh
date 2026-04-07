#!/usr/bin/env bash
# cmd/restart.sh — restart app with optional tap navigation steps

# (parse helpers defined at top so they can be sourced+tested independently)

_restart_parse_tap_step() {
  local step="$1"
  local raw_action selector
  raw_action="${step%%:*}"
  selector="${step#*:}"

  local mcp_action
  case "$raw_action" in
    tap)        mcp_action="tap" ;;
    long-press) mcp_action="longPress" ;;
    swipe)      mcp_action="swipe" ;;
    *)
      echo "Unknown tap action: '$raw_action'. Expected tap|long-press|swipe" >&2
      return 1
      ;;
  esac

  if [[ "$mcp_action" == "swipe" ]]; then
    IFS=',' read -ra coords <<< "$selector"
    if [[ ${#coords[@]} -ne 4 ]]; then
      echo "swipe step requires 4 coordinates: '$step'" >&2; return 1
    fi
    local x1="${coords[0]}" y1="${coords[1]}" x2="${coords[2]}" y2="${coords[3]}"
    if [[ "$x1" == *% ]]; then
      printf '{"action":"swipe","xPercent":%s,"yPercent":%s,"endXPercent":%s,"endYPercent":%s}' \
        "${x1%%%}" "${y1%%%}" "${x2%%%}" "${y2%%%}"
    else
      printf '{"action":"swipe","x":%s,"y":%s,"endX":%s,"endY":%s}' "$x1" "$y1" "$x2" "$y2"
    fi
    echo; return 0
  fi

  if [[ "$selector" == text=* ]]; then
    printf '{"action":"%s","text":"%s"}\n' "$mcp_action" "${selector#text=}"; return 0
  fi
  if [[ "$selector" == id=* ]]; then
    printf '{"action":"%s","resourceId":"%s"}\n' "$mcp_action" "${selector#id=}"; return 0
  fi
  if [[ "$selector" == desc=* ]]; then
    printf '{"action":"%s","contentDesc":"%s"}\n' "$mcp_action" "${selector#desc=}"; return 0
  fi

  if [[ "$selector" == *,* ]]; then
    IFS=',' read -ra coords <<< "$selector"
    if [[ ${#coords[@]} -ne 2 ]]; then
      echo "Expected 2 coordinates, got: '$selector'" >&2; return 1
    fi
    local cx="${coords[0]}" cy="${coords[1]}"
    if [[ "$cx" == *% ]]; then
      printf '{"action":"%s","xPercent":%s,"yPercent":%s}\n' \
        "$mcp_action" "${cx%%%}" "${cy%%%}"
    else
      printf '{"action":"%s","x":%s,"y":%s}\n' "$mcp_action" "$cx" "$cy"
    fi
    return 0
  fi

  echo "Cannot parse tap step selector: '$selector'" >&2
  return 1
}

_restart_build_tap_actions() {
  local steps=("$@")
  if [[ ${#steps[@]} -eq 0 ]]; then echo "[]"; return 0; fi
  local arr="[" first=true step json
  for step in "${steps[@]}"; do
    json=$(_restart_parse_tap_step "$step") || return 1
    [[ "$first" == true ]] && first=false || arr="$arr,"
    arr="$arr$json"
  done
  echo "$arr]"
}

cmd_restart() {
  local json_flag="" tap_steps=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --json) json_flag="--json"; shift ;;
      --tap)  tap_steps+=("$2"); shift 2 ;;
      --help) printf "Usage: jugg restart [--tap <step>...]\n"; return 0 ;;
      *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
  done

  local project_dir
  project_dir=$(_jugg_resolve_project_dir)
  local port
  port=$(_jugg_resolve_port)

  local tap_actions_json
  tap_actions_json=$(_restart_build_tap_actions "${tap_steps[@]}")

  local params
  params=$(printf '{"projectDir":"%s","tap_actions":%s}' "$project_dir" "$tap_actions_json")

  local response structured
  response=$(_jugg_raw_call "$port" "restart_app" "$params")
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
