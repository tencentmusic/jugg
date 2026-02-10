#!/usr/bin/env bash
set -euo pipefail

# MCP e2e debug script for Jugg (Phase 3)
# default flow:
# 1) initialize
# 2) notifications/initialized
# 3) tools/list
# 4) tools/call emulator_list
# 5) tools/call device_list
# 6) tools/call screenshot

PROJECT_DIR=""
SERIAL=""
PORT=""

usage() {
  cat <<USAGE
Usage:
  tools/mcp_e2e.sh --project-dir <abs_path> [--serial <device_serial>] [--port <mcp_port>]

Examples:
  tools/mcp_e2e.sh --project-dir /Users/me/workspace/jugg_f1
  tools/mcp_e2e.sh --project-dir /Users/me/workspace/jugg_f1 --serial emulator-5554
  tools/mcp_e2e.sh --project-dir /Users/me/workspace/jugg_f1 --port 12320

Notes:
  - Jugg must already be initialized for this projectDir in IDE.
  - If --project-dir is not provided, read from env var jugg_project_dir.
  - endpoint: http://localhost:<port>/mcp
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      PROJECT_DIR="${2:-}"
      shift 2
      ;;
    --serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    --port)
      PORT="${2:-}"
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

if [[ -z "$PROJECT_DIR" ]]; then
  PROJECT_DIR="$(printenv 'jugg_project_dir' || true)"
fi

if [[ -z "$PROJECT_DIR" ]]; then
  echo "Error: --project-dir is required (or set env var jugg_project_dir)" >&2
  usage
  exit 1
fi

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "Error: projectDir does not exist: $PROJECT_DIR" >&2
  exit 1
fi

post_json() {
  local url="$1"
  local payload="$2"
  local extra_header="${3:-}"
  if [[ -n "$extra_header" ]]; then
    curl -sS -X POST "$url" -H "Content-Type: application/json" -H "$extra_header" -d "$payload"
  else
    curl -sS -X POST "$url" -H "Content-Type: application/json" -d "$payload"
  fi
}

probe_port() {
  local p
  for p in $(seq 12320 12329); do
    local url="http://localhost:${p}/mcp"
    local payload='{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
    if response="$(post_json "$url" "$payload" 2>/dev/null)"; then
      if [[ "$response" == *'"jsonrpc":"2.0"'* ]]; then
        echo "$p"
        return 0
      fi
    fi
  done
  return 1
}

if [[ -z "$PORT" ]]; then
  if ! PORT="$(probe_port)"; then
    echo "Error: MCP port not found in 12320..12329. Ensure IDE is running and Jugg has initialized the project." >&2
    exit 1
  fi
fi

BASE_URL="http://localhost:${PORT}/mcp"

echo "[mcp-e2e] endpoint: $BASE_URL"
echo "[mcp-e2e] projectDir: $PROJECT_DIR"
if [[ -n "$SERIAL" ]]; then
  echo "[mcp-e2e] serial: $SERIAL"
fi

echo
echo "== 1) initialize =="
INITIALIZE_PAYLOAD='{"jsonrpc":"2.0","id":1000,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-e2e-script","version":"1.0.0"}}}'
post_json "$BASE_URL" "$INITIALIZE_PAYLOAD" "MCP-Protocol-Version: 2025-06-18"

echo
echo
echo "== 2) notifications/initialized (expect 202-like accept) =="
NOTIFY_PAYLOAD='{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
post_json "$BASE_URL" "$NOTIFY_PAYLOAD"

echo
echo
echo "== 3) tools/list =="
TOOLS_LIST_PAYLOAD='{"jsonrpc":"2.0","id":1001,"method":"tools/list","params":{}}'
post_json "$BASE_URL" "$TOOLS_LIST_PAYLOAD"

echo
echo
echo "== 4) tools/call emulator_list =="
EMULATOR_LIST_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":1002,"method":"tools/call","params":{"name":"emulator_list","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
post_json "$BASE_URL" "$EMULATOR_LIST_PAYLOAD"

echo
echo
echo "== 5) tools/call device_list =="
DEVICE_LIST_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":1003,"method":"tools/call","params":{"name":"device_list","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
post_json "$BASE_URL" "$DEVICE_LIST_PAYLOAD"

echo
echo
echo "== 6) tools/call screenshot =="
if [[ -n "$SERIAL" ]]; then
  SCREENSHOT_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":1004,"method":"tools/call","params":{"name":"screenshot","arguments":{"projectDir":"$PROJECT_DIR","serial":"$SERIAL"}}}
JSON
)"
else
  SCREENSHOT_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":1004,"method":"tools/call","params":{"name":"screenshot","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
fi
post_json "$BASE_URL" "$SCREENSHOT_PAYLOAD"

echo
echo
echo "[mcp-e2e] done"
