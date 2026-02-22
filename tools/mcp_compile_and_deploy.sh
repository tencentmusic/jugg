#!/usr/bin/env bash
set -euo pipefail

# MCP helper: call compile_and_deploy
# flow:
# 1) initialize
# 2) notifications/initialized
# 3) tools/call compile_and_deploy

PROJECT_DIR=""
PORT=""

usage() {
  cat <<USAGE
Usage:
  tools/mcp_compile_and_deploy.sh --project-dir <abs_path> [--port <mcp_port>]

Examples:
  tools/mcp_compile_and_deploy.sh --project-dir /Users/me/workspace/jugg_f1
  tools/mcp_compile_and_deploy.sh --project-dir /Users/me/workspace/jugg_f1 --port 12320

Notes:
  - Jugg must already be initialized for this projectDir in IDE.
  - If --project-dir is not provided, read from env var jugg_project_dir.
  - endpoint: http://localhost:<port>/jugg-mcp
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

print_human_text() {
  local response_json="$1"
  if [[ -z "$response_json" ]]; then
    echo "[mcp-compile-deploy] empty response, skip human-readable parsing"
    return 0
  fi

  if ! command -v python3 >/dev/null 2>&1; then
    echo "[mcp-compile-deploy] python3 not found, skip human-readable parsing"
    return 0
  fi

  python3 - "$response_json" <<'PY'
import json
import sys

raw = sys.argv[1]
try:
    payload = json.loads(raw)
except Exception as exc:
    print(f"[mcp-compile-deploy] failed to parse response JSON: {exc}")
    sys.exit(0)

result = payload.get("result") or {}
structured = result.get("structuredContent") or {}
message = structured.get("message") if isinstance(structured, dict) else None
detail = None
if isinstance(structured, dict):
    data = structured.get("data")
    if isinstance(data, dict):
        detail = data.get("detail")

print("[mcp-compile-deploy] human-readable output:")
if isinstance(message, str) and message.strip():
    print("--- structuredContent.message ---")
    print(message)

if isinstance(detail, str) and detail.strip():
    print("--- structuredContent.data.detail ---")
    print(detail)
PY
}

print_pretty_json() {
  local response_json="$1"
  if [[ -z "$response_json" ]]; then
    echo ""
    return 0
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$response_json" <<'PY'
import json
import sys

raw = sys.argv[1]
try:
    payload = json.loads(raw)
except Exception:
    print(raw)
    sys.exit(0)

result = payload.get("result")
if isinstance(result, dict):
    content = result.get("content")
    if isinstance(content, list):
        for item in content:
            if isinstance(item, dict) and "text" in item:
                item.pop("text", None)

    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        data = structured.get("data")
        if isinstance(data, dict) and "detail" in data:
            data.pop("detail", None)

print(json.dumps(payload, ensure_ascii=False, indent=2))
PY
  else
    echo "$response_json"
  fi
}

probe_port() {
  local p
  for p in $(seq 12320 12329); do
    local url="http://localhost:${p}/jugg-mcp"
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

BASE_URL="http://localhost:${PORT}/jugg-mcp"

echo "[mcp-compile-deploy] endpoint: $BASE_URL"
echo "[mcp-compile-deploy] projectDir: $PROJECT_DIR"

echo
echo "== 1) initialize =="
INITIALIZE_PAYLOAD='{"jsonrpc":"2.0","id":3000,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-compile-deploy-script","version":"1.0.0"}}}'
post_json "$BASE_URL" "$INITIALIZE_PAYLOAD" "MCP-Protocol-Version: 2025-06-18"

echo
echo
echo "== 2) notifications/initialized =="
NOTIFY_PAYLOAD='{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
post_json "$BASE_URL" "$NOTIFY_PAYLOAD"

echo
echo
echo "== 3) tools/call compile_and_deploy =="
COMPILE_DEPLOY_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":3001,"method":"tools/call","params":{"name":"compile_and_deploy","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
COMPILE_DEPLOY_RESPONSE="$(post_json "$BASE_URL" "$COMPILE_DEPLOY_PAYLOAD")"
print_pretty_json "$COMPILE_DEPLOY_RESPONSE"

echo
echo
print_human_text "$COMPILE_DEPLOY_RESPONSE"

echo
echo
echo "[mcp-compile-deploy] done"
