#!/usr/bin/env bash
set -euo pipefail

# MCP helper: list available AVDs then start emulator
# flow:
# 1) initialize
# 2) notifications/initialized
# 3) tools/call emulator_list
# 4) tools/call start_emulator

PROJECT_DIR=""
PORT=""
AVD_NAME=""
WAIT_SEC="45"

usage() {
  cat <<USAGE
用法:
  tools/mcp_list_and_start_emulator.sh --project-dir <abs_path> [--avd-name <name>] [--wait-sec <0..300>] [--port <mcp_port>]

示例:
  tools/mcp_list_and_start_emulator.sh --project-dir /Users/me/workspace/jugg_f1
  tools/mcp_list_and_start_emulator.sh --project-dir /Users/me/workspace/jugg_f1 --avd-name Pixel_8_API_35
  tools/mcp_list_and_start_emulator.sh --project-dir /Users/me/workspace/jugg_f1 --wait-sec 90

说明:
  - 需要 IDE 内 Jugg 已初始化该 projectDir。
  - endpoint: http://localhost:<port>/mcp
  - 未传 --avd-name 时，交由 MCP start_emulator 默认策略（通常选择第一条 AVD）。
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      PROJECT_DIR="${2:-}"
      shift 2
      ;;
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --avd-name)
      AVD_NAME="${2:-}"
      shift 2
      ;;
    --wait-sec)
      WAIT_SEC="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$PROJECT_DIR" ]]; then
  echo "错误: --project-dir 必填" >&2
  usage
  exit 1
fi

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "错误: projectDir 不存在: $PROJECT_DIR" >&2
  exit 1
fi

if ! [[ "$WAIT_SEC" =~ ^[0-9]+$ ]]; then
  echo "错误: --wait-sec 必须是整数" >&2
  exit 1
fi

if (( WAIT_SEC < 0 || WAIT_SEC > 300 )); then
  echo "错误: --wait-sec 取值范围必须是 0..300" >&2
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
    echo "错误: 未探测到 MCP 端口（12320..12329）。请确认 IDE 已启动且 Jugg 已初始化项目。" >&2
    exit 1
  fi
fi

BASE_URL="http://localhost:${PORT}/mcp"

echo "[mcp-start-emulator] 使用端点: $BASE_URL"
echo "[mcp-start-emulator] projectDir: $PROJECT_DIR"
if [[ -n "$AVD_NAME" ]]; then
  echo "[mcp-start-emulator] avdName: $AVD_NAME"
fi
echo "[mcp-start-emulator] waitSec: $WAIT_SEC"

echo
echo "== 1) initialize =="
INITIALIZE_PAYLOAD='{"jsonrpc":"2.0","id":2000,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-list-and-start-emulator","version":"1.0.0"}}}'
post_json "$BASE_URL" "$INITIALIZE_PAYLOAD" "MCP-Protocol-Version: 2025-06-18"

echo
echo
echo "== 2) notifications/initialized =="
NOTIFY_PAYLOAD='{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
post_json "$BASE_URL" "$NOTIFY_PAYLOAD"

echo
echo
echo "== 3) tools/call emulator_list =="
EMULATOR_LIST_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":2001,"method":"tools/call","params":{"name":"emulator_list","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
post_json "$BASE_URL" "$EMULATOR_LIST_PAYLOAD"

echo
echo
echo "== 4) tools/call start_emulator =="
if [[ -n "$AVD_NAME" ]]; then
  START_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":2002,"method":"tools/call","params":{"name":"start_emulator","arguments":{"projectDir":"$PROJECT_DIR","avdName":"$AVD_NAME","waitForDeviceSec":$WAIT_SEC}}}
JSON
)"
else
  START_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":2002,"method":"tools/call","params":{"name":"start_emulator","arguments":{"projectDir":"$PROJECT_DIR","waitForDeviceSec":$WAIT_SEC}}}
JSON
)"
fi
post_json "$BASE_URL" "$START_PAYLOAD"

echo
echo
echo "[mcp-start-emulator] 完成"

