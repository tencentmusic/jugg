#!/usr/bin/env bash
set -euo pipefail

# Directly verify app-side ViewHierarchy LocalSocket server (Phase 2).
# Flow:
# 1) resolve app pid -> socket candidates(jugg_vh_<pid>, jugg_vh)
# 2) adb forward tcp:<port> localabstract:<socket>
# 3) send one JSON action line and read one JSON response line

PACKAGE_NAME=""
SERIAL=""
ACTION="layout_dump"
TEXT=""
RESOURCE_ID=""
CONTENT_DESC=""
CLASS_NAME=""
X=""
Y=""
EXPECT_STATUS="ok"
OUTPUT_FILE=""

usage() {
  cat <<'USAGE'
Usage:
  tools/viewhierarchy_socket_probe.sh --package <applicationId> [options]

Options:
  --serial <device_serial>         target device serial (default: first online device)
  --action <layout_dump|find_and_tap|tap_coordinate>
  --text <value>                   selector text for find_and_tap
  --resource-id <value>            selector resourceId for find_and_tap
  --content-desc <value>           selector contentDesc for find_and_tap
  --class-name <value>             selector className for find_and_tap
  --x <int> --y <int>              coordinates for tap_coordinate
  --expect-status <ok|error|any>   expected response status (default: ok)
  --output <file>                  write raw response line to file
  -h, --help                       show this help

Examples:
  tools/viewhierarchy_socket_probe.sh --package com.example.app
  tools/viewhierarchy_socket_probe.sh --package com.example.app --action find_and_tap --text Login
  tools/viewhierarchy_socket_probe.sh --package com.example.app --action tap_coordinate --x 400 --y 900
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      PACKAGE_NAME="${2:-}"
      shift 2
      ;;
    --serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    --action)
      ACTION="${2:-}"
      shift 2
      ;;
    --text)
      TEXT="${2:-}"
      shift 2
      ;;
    --resource-id)
      RESOURCE_ID="${2:-}"
      shift 2
      ;;
    --content-desc)
      CONTENT_DESC="${2:-}"
      shift 2
      ;;
    --class-name)
      CLASS_NAME="${2:-}"
      shift 2
      ;;
    --x)
      X="${2:-}"
      shift 2
      ;;
    --y)
      Y="${2:-}"
      shift 2
      ;;
    --expect-status)
      EXPECT_STATUS="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_FILE="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$PACKAGE_NAME" ]]; then
  echo "Error: --package is required." >&2
  usage
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "Error: no online adb device found, and --serial not provided." >&2
  exit 1
fi

if [[ "$EXPECT_STATUS" != "ok" && "$EXPECT_STATUS" != "error" && "$EXPECT_STATUS" != "any" ]]; then
  echo "Error: --expect-status must be one of ok/error/any." >&2
  exit 1
fi

if [[ "$ACTION" == "tap_coordinate" ]]; then
  if [[ -z "$X" || -z "$Y" ]]; then
    echo "Error: tap_coordinate requires --x and --y." >&2
    exit 1
  fi
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required." >&2
  exit 1
fi

resolve_pid() {
  local serial="$1"
  local pkg="$2"
  local pid
  pid="$(adb -s "$serial" shell "pidof $pkg 2>/dev/null" | tr -d '\r' | awk '{print $1}')"
  if [[ -n "$pid" ]]; then
    echo "$pid"
    return 0
  fi
  pid="$(adb -s "$serial" shell "ps | grep '$pkg'" | tr -d '\r' | awk 'NR==1{for(i=1;i<=NF;i++){if($i ~ /^[0-9]+$/){print $i; exit}}}')"
  if [[ -n "$pid" ]]; then
    echo "$pid"
    return 0
  fi
  return 1
}

reserve_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

build_request_json() {
  ACTION="$ACTION" TEXT="$TEXT" RESOURCE_ID="$RESOURCE_ID" CONTENT_DESC="$CONTENT_DESC" CLASS_NAME="$CLASS_NAME" X="$X" Y="$Y" \
    python3 - <<'PY'
import json
import os

action = os.environ["ACTION"]
params = {}
if action == "find_and_tap":
    params = {
        "text": os.environ["TEXT"] or None,
        "resourceId": os.environ["RESOURCE_ID"] or None,
        "contentDesc": os.environ["CONTENT_DESC"] or None,
        "className": os.environ["CLASS_NAME"] or None,
    }
elif action == "tap_coordinate":
    params = {
        "x": int(os.environ["X"]),
        "y": int(os.environ["Y"]),
    }

print(json.dumps({"action": action, "params": params}, ensure_ascii=False))
PY
}

send_request_to_socket() {
  local socket_name="$1"
  local local_port="$2"
  local request_json="$3"

  python3 - "$local_port" "$request_json" <<'PY'
import socket
import sys

port = int(sys.argv[1])
payload = sys.argv[2] + "\n"

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.settimeout(6)
sock.connect(("127.0.0.1", port))
sock.sendall(payload.encode("utf-8"))
buf = b""
while b"\n" not in buf:
    data = sock.recv(4096)
    if not data:
        break
    buf += data
sock.close()
line = buf.split(b"\n", 1)[0].decode("utf-8", errors="replace").strip()
print(line)
PY
}

check_status() {
  local response_json="$1"
  local expect="$2"
  RESPONSE_JSON="$response_json" EXPECT_STATUS="$expect" python3 - <<'PY'
import json
import os
import sys

resp = os.environ["RESPONSE_JSON"]
expect = os.environ["EXPECT_STATUS"]
try:
    payload = json.loads(resp)
except Exception:
    print("[vh-socket] invalid JSON response")
    sys.exit(2)

status = str(payload.get("status", "")).lower()
message = payload.get("message")
data = payload.get("data")
print(f"[vh-socket] status={status or '<empty>'}")
if message:
    print(f"[vh-socket] message={message}")
if isinstance(data, dict):
    print(f"[vh-socket] data_keys={','.join(sorted(data.keys()))}")

if expect == "any":
    sys.exit(0)
if status != expect:
    print(f"[vh-socket] expect status={expect}, actual={status}")
    sys.exit(1)
PY
}

PID=""
if PID="$(resolve_pid "$SERIAL" "$PACKAGE_NAME")"; then
  SOCKET_CANDIDATES=("jugg_vh_${PID}" "jugg_vh")
else
  SOCKET_CANDIDATES=("jugg_vh")
fi

REQUEST_JSON="$(build_request_json)"
echo "[vh-socket] serial: $SERIAL"
echo "[vh-socket] package: $PACKAGE_NAME"
echo "[vh-socket] action: $ACTION"
echo "[vh-socket] request: $REQUEST_JSON"
echo "[vh-socket] sockets: ${SOCKET_CANDIDATES[*]}"

RESPONSE=""
SUCCESS_SOCKET=""
for socket_name in "${SOCKET_CANDIDATES[@]}"; do
  local_port="$(reserve_port)"
  if ! adb -s "$SERIAL" forward "tcp:${local_port}" "localabstract:${socket_name}" >/dev/null 2>&1; then
    echo "[vh-socket] forward failed for socket=${socket_name}, continue."
    continue
  fi

  set +e
  response_line="$(send_request_to_socket "$socket_name" "$local_port" "$REQUEST_JSON" 2>/dev/null)"
  rc=$?
  set -e
  adb -s "$SERIAL" forward --remove "tcp:${local_port}" >/dev/null 2>&1 || true

  if [[ $rc -eq 0 && -n "$response_line" ]]; then
    RESPONSE="$response_line"
    SUCCESS_SOCKET="$socket_name"
    break
  fi
done

if [[ -z "$RESPONSE" ]]; then
  echo "Error: failed to connect any ViewHierarchy socket and get response." >&2
  exit 1
fi

echo "[vh-socket] connected socket: $SUCCESS_SOCKET"
echo "[vh-socket] raw response: $RESPONSE"

if [[ -n "$OUTPUT_FILE" ]]; then
  mkdir -p "$(dirname "$OUTPUT_FILE")"
  printf '%s\n' "$RESPONSE" > "$OUTPUT_FILE"
  echo "[vh-socket] response written to $OUTPUT_FILE"
fi

check_status "$RESPONSE" "$EXPECT_STATUS"

echo "[vh-socket] done"
