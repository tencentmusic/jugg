#!/usr/bin/env bash
set -euo pipefail

# MCP e2e verifier for ViewHierarchy Phase 3.
# Coverage:
# 1) initialize + notifications/initialized
# 2) layout_dump repeated checks (json/xml expectation)
# 3) optional tap element-mode check

PROJECT_DIR=""
PORT=""
REPEAT=3
EXPECT_LAYOUT="json"
SKIP_TAP=0
TAP_TEXT=""
TAP_RESOURCE_ID=""
TAP_CONTENT_DESC=""
TAP_CLASS_NAME=""
TAP_EXPECT_STATUS="OK"

usage() {
  cat <<'USAGE'
Usage:
  tools/mcp_viewhierarchy_e2e.sh --project-dir <abs_path> [options]

Options:
  --port <mcp_port>                 MCP port (default: auto-detect 12320..12329)
  --repeat <n>                      layout_dump repeat count (default: 3)
  --expect-layout <json|any>        expected layout_dump file extension (default: json)
  --skip-tap                        skip tap verification
  --tap-text <value>                element selector text for tap
  --tap-resource-id <value>         element selector resourceId for tap
  --tap-content-desc <value>        element selector contentDesc for tap
  --tap-class-name <value>          optional className filter for tap
  --tap-expect-status <OK|ERROR>    expected tap status when tap is enabled (default: OK)
  -h, --help                        show this help

Examples:
  tools/mcp_viewhierarchy_e2e.sh --project-dir /abs/project
  tools/mcp_viewhierarchy_e2e.sh --project-dir /abs/project --repeat 5 --tap-text Login
  tools/mcp_viewhierarchy_e2e.sh --project-dir /abs/project --expect-layout json --skip-tap
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
    --repeat)
      REPEAT="${2:-}"
      shift 2
      ;;
    --expect-layout)
      EXPECT_LAYOUT="${2:-}"
      shift 2
      ;;
    --skip-tap)
      SKIP_TAP=1
      shift
      ;;
    --tap-text)
      TAP_TEXT="${2:-}"
      shift 2
      ;;
    --tap-resource-id)
      TAP_RESOURCE_ID="${2:-}"
      shift 2
      ;;
    --tap-content-desc)
      TAP_CONTENT_DESC="${2:-}"
      shift 2
      ;;
    --tap-class-name)
      TAP_CLASS_NAME="${2:-}"
      shift 2
      ;;
    --tap-expect-status)
      TAP_EXPECT_STATUS="${2:-}"
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
  echo "Error: --project-dir is required (or set env var jugg_project_dir)." >&2
  exit 1
fi
if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "Error: projectDir does not exist: $PROJECT_DIR" >&2
  exit 1
fi
if [[ "$EXPECT_LAYOUT" != "json" && "$EXPECT_LAYOUT" != "any" ]]; then
  echo "Error: --expect-layout must be json/any." >&2
  exit 1
fi
if [[ "$TAP_EXPECT_STATUS" != "OK" && "$TAP_EXPECT_STATUS" != "ERROR" ]]; then
  echo "Error: --tap-expect-status must be OK or ERROR." >&2
  exit 1
fi
if ! [[ "$REPEAT" =~ ^[0-9]+$ ]] || [[ "$REPEAT" -le 0 ]]; then
  echo "Error: --repeat must be a positive integer." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 is required." >&2
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
    echo "Error: MCP port not found in 12320..12329. Ensure IDE is running and Jugg has initialized this project." >&2
    exit 1
  fi
fi

BASE_URL="http://localhost:${PORT}/jugg-mcp"
echo "[vh-mcp-e2e] endpoint: $BASE_URL"
echo "[vh-mcp-e2e] projectDir: $PROJECT_DIR"
echo "[vh-mcp-e2e] repeat: $REPEAT"
echo "[vh-mcp-e2e] expect-layout: $EXPECT_LAYOUT"

echo
echo "== 1) initialize =="
INITIALIZE_PAYLOAD='{"jsonrpc":"2.0","id":5100,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"vh-e2e-script","version":"1.0.0"}}}'
post_json "$BASE_URL" "$INITIALIZE_PAYLOAD" "MCP-Protocol-Version: 2025-06-18" >/dev/null

echo "== 2) notifications/initialized =="
NOTIFY_PAYLOAD='{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
post_json "$BASE_URL" "$NOTIFY_PAYLOAD" >/dev/null

LAYOUT_OK_COUNT=0
LAYOUT_JSON_COUNT=0

for i in $(seq 1 "$REPEAT"); do
  echo
  echo "== 3.$i) tools/call layout_dump =="
  LAYOUT_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":$((5200 + i)),"method":"tools/call","params":{"name":"layout_dump","arguments":{"projectDir":"$PROJECT_DIR"}}}
JSON
)"
  LAYOUT_RESPONSE="$(post_json "$BASE_URL" "$LAYOUT_PAYLOAD")"
  echo "$LAYOUT_RESPONSE"

  parsed="$(
    RESPONSE_JSON="$LAYOUT_RESPONSE" python3 - <<'PY'
import json
import os
import sys

resp = os.environ["RESPONSE_JSON"]
try:
    payload = json.loads(resp)
except Exception:
    print("PARSE_ERROR")
    sys.exit(0)

result = payload.get("result", {})
structured = result.get("structuredContent", {})
status = structured.get("status")
data = structured.get("data") if isinstance(structured, dict) else {}
file_path = data.get("file") if isinstance(data, dict) else None
ext = ""
if isinstance(file_path, str) and "." in file_path:
    ext = file_path.rsplit(".", 1)[-1].lower()
print(f"{status}\t{file_path or ''}\t{ext}")
PY
  )"
  if [[ "$parsed" == "PARSE_ERROR" ]]; then
    echo "Error: failed to parse layout_dump response." >&2
    exit 1
  fi
  layout_status="$(echo "$parsed" | awk -F '\t' '{print $1}')"
  layout_file="$(echo "$parsed" | awk -F '\t' '{print $2}')"
  layout_ext="$(echo "$parsed" | awk -F '\t' '{print $3}')"

  if [[ "$layout_status" != "OK" ]]; then
    echo "Error: layout_dump status is $layout_status (expect OK)." >&2
    exit 1
  fi
  if [[ -z "$layout_file" ]]; then
    echo "Error: layout_dump returned empty data.file." >&2
    exit 1
  fi
  if [[ "$layout_ext" == "json" ]]; then
    LAYOUT_JSON_COUNT=$((LAYOUT_JSON_COUNT + 1))
  else
    echo "Error: unexpected layout file extension: $layout_ext (file=$layout_file)" >&2
    exit 1
  fi
  if [[ "$EXPECT_LAYOUT" != "any" && "$layout_ext" != "$EXPECT_LAYOUT" ]]; then
    echo "Error: layout_dump extension mismatch. expect=$EXPECT_LAYOUT, actual=$layout_ext" >&2
    exit 1
  fi
  if [[ ! -f "$layout_file" ]]; then
    echo "Error: layout file not found on host: $layout_file" >&2
    exit 1
  fi
  LAYOUT_OK_COUNT=$((LAYOUT_OK_COUNT + 1))
done

if [[ "$SKIP_TAP" -eq 0 ]]; then
  if [[ -z "$TAP_TEXT" && -z "$TAP_RESOURCE_ID" && -z "$TAP_CONTENT_DESC" ]]; then
    echo
    echo "[vh-mcp-e2e] tap skipped: no element selector provided."
  else
    echo
    echo "== 4) tools/call tap (element mode) =="
    TAP_ARGS_JSON="$(
      PROJECT_DIR="$PROJECT_DIR" TAP_TEXT="$TAP_TEXT" TAP_RESOURCE_ID="$TAP_RESOURCE_ID" TAP_CONTENT_DESC="$TAP_CONTENT_DESC" TAP_CLASS_NAME="$TAP_CLASS_NAME" \
        python3 - <<'PY'
import json
import os

args = {"projectDir": os.environ["PROJECT_DIR"]}
if os.environ["TAP_TEXT"]:
    args["text"] = os.environ["TAP_TEXT"]
if os.environ["TAP_RESOURCE_ID"]:
    args["resourceId"] = os.environ["TAP_RESOURCE_ID"]
if os.environ["TAP_CONTENT_DESC"]:
    args["contentDesc"] = os.environ["TAP_CONTENT_DESC"]
if os.environ["TAP_CLASS_NAME"]:
    args["className"] = os.environ["TAP_CLASS_NAME"]
print(json.dumps(args, ensure_ascii=False))
PY
    )"
    TAP_PAYLOAD="$(cat <<JSON
{"jsonrpc":"2.0","id":5300,"method":"tools/call","params":{"name":"tap","arguments":$TAP_ARGS_JSON}}
JSON
)"
    TAP_RESPONSE="$(post_json "$BASE_URL" "$TAP_PAYLOAD")"
    echo "$TAP_RESPONSE"

    tap_parsed="$(
      RESPONSE_JSON="$TAP_RESPONSE" python3 - <<'PY'
import json
import os
import sys

resp = os.environ["RESPONSE_JSON"]
try:
    payload = json.loads(resp)
except Exception:
    print("PARSE_ERROR")
    sys.exit(0)
result = payload.get("result", {})
structured = result.get("structuredContent", {})
status = structured.get("status")
error_code = structured.get("errorCode")
data = structured.get("data") if isinstance(structured, dict) else {}
mode = data.get("mode") if isinstance(data, dict) else None
print(f"{status}\t{error_code or ''}\t{mode or ''}")
PY
    )"
    if [[ "$tap_parsed" == "PARSE_ERROR" ]]; then
      echo "Error: failed to parse tap response." >&2
      exit 1
    fi
    tap_status="$(echo "$tap_parsed" | awk -F '\t' '{print $1}')"
    tap_error_code="$(echo "$tap_parsed" | awk -F '\t' '{print $2}')"
    tap_mode="$(echo "$tap_parsed" | awk -F '\t' '{print $3}')"

    if [[ "$tap_status" != "$TAP_EXPECT_STATUS" ]]; then
      echo "Error: tap status mismatch. expect=$TAP_EXPECT_STATUS actual=$tap_status errorCode=$tap_error_code" >&2
      exit 1
    fi
    if [[ "$tap_mode" != "element" ]]; then
      echo "Error: tap mode expected element, actual=$tap_mode" >&2
      exit 1
    fi
  fi
fi

echo
echo "== Summary =="
echo "[vh-mcp-e2e] layout_dump OK count: $LAYOUT_OK_COUNT/$REPEAT"
echo "[vh-mcp-e2e] layout_dump json count: $LAYOUT_JSON_COUNT"
if [[ "$EXPECT_LAYOUT" == "json" ]]; then
  echo "[vh-mcp-e2e] server path expected and validated by .json outputs."
fi
echo "[vh-mcp-e2e] done"
