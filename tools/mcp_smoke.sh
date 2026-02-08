#!/usr/bin/env bash
set -euo pipefail

# MCP smoke test for Jugg (Phase 1)
# - Auto-detect MCP port in 12320..12329 unless --port is provided
# - Call tools/list
# - Call tools/call list_projects
# - Call tools/call restart_app (optional --serial)

PROJECT_DIR=""
SERIAL=""
PORT=""

usage() {
  cat <<USAGE
用法:
  tools/mcp_smoke.sh --project-dir <abs_path> [--serial <device_serial>] [--port <mcp_port>]

示例:
  tools/mcp_smoke.sh --project-dir /Users/me/workspace/jugg_f1
  tools/mcp_smoke.sh --project-dir /Users/me/workspace/jugg_f1 --serial emulator-5554
  tools/mcp_smoke.sh --project-dir /Users/me/workspace/jugg_f1 --port 12320

说明:
  - 需要 IDE 内 Jugg 已初始化该 projectDir（MCP server 生命周期跟随 JuggInitializer）。
  - MCP endpoint: http://localhost:<port>/mcp
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

post_json() {
  local url="$1"
  local payload="$2"
  curl -sS -X POST "$url" -H "Content-Type: application/json" -d "$payload"
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

echo "[mcp-smoke] 使用端点: $BASE_URL"
echo "[mcp-smoke] projectDir: $PROJECT_DIR"
if [[ -n "$SERIAL" ]]; then
  echo "[mcp-smoke] serial: $SERIAL"
fi

echo
echo "== 1) tools/list =="
TOOLS_LIST_PAYLOAD='{"jsonrpc":"2.0","id":101,"method":"tools/list","params":{}}'
post_json "$BASE_URL" "$TOOLS_LIST_PAYLOAD"

echo
echo
echo "== 2) tools/call list_projects =="
LIST_PROJECTS_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"list_projects","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
post_json "$BASE_URL" "$LIST_PROJECTS_PAYLOAD"

echo
echo
echo "== 3) tools/call restart_app =="
if [[ -n "$SERIAL" ]]; then
  RESTART_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":103,"method":"tools/call","params":{"name":"restart_app","arguments":{"projectDir":"$PROJECT_DIR","serial":"$SERIAL"}}}
JSON
)"
else
  RESTART_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":103,"method":"tools/call","params":{"name":"restart_app","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
fi
post_json "$BASE_URL" "$RESTART_PAYLOAD"

echo
echo
echo "[mcp-smoke] 完成"
