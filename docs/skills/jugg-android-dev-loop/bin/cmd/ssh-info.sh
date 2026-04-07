#!/usr/bin/env bash
# cmd/ssh-info.sh — build params for the 'ssh-info' subcommand

_ssh_info_build_params() {
  local reason=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --reason) reason="$2"; shift 2 ;;
      *) echo "Unknown option: $1" >&2; return 1 ;;
    esac
  done

  if [[ -z "$reason" ]]; then
    echo "ssh-info requires --reason <reason>" >&2
    return 1
  fi

  # CLI invocation implies user consent
  printf '{"reason":"%s","userConsent":true}\n' "$reason"
}

cmd_ssh_info() {
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
  extra_params=$(_ssh_info_build_params "${args[@]}") || exit 1

  local params
  params=$(echo "$extra_params" | python3 -c "
import sys, json
d = json.load(sys.stdin)
d['projectDir'] = '$project_dir'
print(json.dumps(d))
")

  local response structured
  response=$(_jugg_raw_call "$port" "request_remote_ssh_info" "$params")
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
