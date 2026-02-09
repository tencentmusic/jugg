#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$SCRIPT_DIR/../jugg-android-dev-loop" && pwd)"
SKILL_NAME="$(basename "$SKILL_DIR")"
TARGET_ROOT="${CODEX_HOME:-$HOME/.codex}/skills"
FORCE="true"
INSTALL_MCP="true"
MCP_CLIENT="auto"
MCP_ENDPOINT="http://localhost:12320/mcp"
MCP_SERVER_NAME="jugg-mcp"

log_status() {
  local scope="$1"
  local agent="$2"
  local status="$3"
  local file="$4"
  local reason="${5:-}"
  if [[ -n "$reason" ]]; then
    echo "$scope agent=$agent status=$status file=$file reason=$reason"
  else
    echo "$scope agent=$agent status=$status file=$file"
  fi
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

usage() {
  cat <<USAGE
Usage:
  bash docs/skills/install/install_mcp_and_skill.sh [--target-root <dir>] [--force]
                                           [--with-mcp|--no-mcp]
                                           [--mcp-client <auto|codex|claude|gemini|all>]
                                           [--mcp-endpoint <url>]
                                           [--mcp-server-name <name>]

Examples:
  bash docs/skills/install/install_mcp_and_skill.sh
  bash docs/skills/install/install_mcp_and_skill.sh --with-mcp --mcp-client auto
  bash docs/skills/install/install_mcp_and_skill.sh --mcp-client all
USAGE
}

claude_home_dir() {
  if [[ -d "$HOME/.claude" ]]; then
    echo "$HOME/.claude"
  elif [[ -d "$HOME/.config/claude" ]]; then
    echo "$HOME/.config/claude"
  else
    echo "$HOME/.claude"
  fi
}

gemini_home_dir() {
  if [[ -n "${GEMINI_HOME:-}" ]]; then
    echo "$GEMINI_HOME"
  else
    echo "$HOME/.gemini"
  fi
}

declare -a AGENTS=()

resolve_agents() {
  AGENTS=()
  case "$MCP_CLIENT" in
    auto)
      [[ -d "$HOME/.codex" ]] && AGENTS+=("codex")
      ([[ -d "$HOME/.claude" ]] || [[ -d "$HOME/.config/claude" ]]) && AGENTS+=("claude")
      [[ -d "$(gemini_home_dir)" ]] && AGENTS+=("gemini")
      if [[ ${#AGENTS[@]} -eq 0 ]]; then
        log_status "AGENT" "auto" "skip" "-" "no_client_home_detected"
      fi
      ;;
    codex|claude|gemini)
      AGENTS+=("$MCP_CLIENT")
      ;;
    all)
      AGENTS=("codex" "claude" "gemini")
      ;;
    *)
      echo "Invalid --mcp-client: $MCP_CLIENT (expected: auto|codex|claude|gemini|all)" >&2
      exit 1
      ;;
  esac
}

skill_root_for_agent() {
  local agent="$1"
  case "$agent" in
    codex)
      echo "$TARGET_ROOT"
      ;;
    claude)
      echo "$(claude_home_dir)/skills"
      ;;
    gemini)
      echo "$(gemini_home_dir)/skills"
      ;;
  esac
}

install_skill_for_agent() {
  local agent="$1"
  local root
  root="$(skill_root_for_agent "$agent")"
  local dest="$root/$SKILL_NAME"

  mkdir -p "$root"

  if [[ -e "$dest" ]]; then
    if [[ "$FORCE" == "true" ]]; then
      rm -rf "$dest"
    else
      log_status "SKILL" "$agent" "fail" "$dest" "already_exists"
      return 0
    fi
  fi

  if cp -R "$SKILL_DIR" "$dest"; then
    log_status "SKILL" "$agent" "ok" "$dest"
  else
    log_status "SKILL" "$agent" "fail" "$dest" "copy_failed"
  fi
}

install_codex_mcp() {
  local file="${CODEX_HOME:-$HOME/.codex}/config.toml"

  if ! command_exists codex; then
    log_status "MCP" "codex" "fail" "$file" "command_not_found"
    return 0
  fi

  if codex mcp list 2>/dev/null | grep -Fq "$MCP_SERVER_NAME"; then
    if [[ "$FORCE" == "true" ]]; then
      codex mcp remove "$MCP_SERVER_NAME" >/dev/null 2>&1 || true
    else
      log_status "MCP" "codex" "fail" "$file" "already_exists"
      return 0
    fi
  fi

  if codex mcp add "$MCP_SERVER_NAME" --url "$MCP_ENDPOINT" >/dev/null 2>&1; then
    log_status "MCP" "codex" "ok" "$file"
  else
    log_status "MCP" "codex" "fail" "$file" "add_failed"
  fi
}

install_claude_mcp() {
  local file="$HOME/.claude.json"

  if ! command_exists claude; then
    log_status "MCP" "claude" "fail" "$file" "command_not_found"
    return 0
  fi

  if claude mcp list 2>/dev/null | grep -Fq "$MCP_SERVER_NAME"; then
    if [[ "$FORCE" == "true" ]]; then
      claude mcp remove --scope user "$MCP_SERVER_NAME" >/dev/null 2>&1 || true
    else
      log_status "MCP" "claude" "fail" "$file" "already_exists"
      return 0
    fi
  fi

  if claude mcp add --transport http --scope user "$MCP_SERVER_NAME" "$MCP_ENDPOINT" >/dev/null 2>&1; then
    log_status "MCP" "claude" "ok" "$file"
  else
    log_status "MCP" "claude" "fail" "$file" "add_failed"
  fi
}

install_gemini_mcp() {
  local file="${GEMINI_SETTINGS:-$(gemini_home_dir)/settings.json}"

  if ! command_exists python3; then
    log_status "MCP" "gemini" "fail" "$file" "python3_not_found"
    return 0
  fi

  mkdir -p "$(dirname "$file")"
  if python3 - "$file" "$MCP_SERVER_NAME" "$MCP_ENDPOINT" <<'PY'
import json
import os
import sys

settings_file = sys.argv[1]
server_name = sys.argv[2]
endpoint = sys.argv[3]

data = {}
if os.path.exists(settings_file):
    with open(settings_file, "r", encoding="utf-8") as f:
        content = f.read().strip()
        if content:
            data = json.loads(content)

mcp_servers = data.setdefault("mcpServers", {})
mcp_servers[server_name] = {"httpUrl": endpoint}

with open(settings_file, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
  then
    log_status "MCP" "gemini" "ok" "$file"
  else
    log_status "MCP" "gemini" "fail" "$file" "write_failed"
  fi
}

install_mcp_for_agent() {
  local agent="$1"
  case "$agent" in
    codex)
      install_codex_mcp
      ;;
    claude)
      install_claude_mcp
      ;;
    gemini)
      install_gemini_mcp
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-root)
      TARGET_ROOT="${2:-}"
      shift 2
      ;;
    --force)
      FORCE="true"
      shift
      ;;
    --with-mcp)
      INSTALL_MCP="true"
      shift
      ;;
    --no-mcp)
      INSTALL_MCP="false"
      shift
      ;;
    --mcp-client)
      MCP_CLIENT="${2:-}"
      shift 2
      ;;
    --mcp-endpoint)
      MCP_ENDPOINT="${2:-}"
      shift 2
      ;;
    --mcp-server-name)
      MCP_SERVER_NAME="${2:-}"
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

if [[ -z "$TARGET_ROOT" ]]; then
  echo "target root is empty" >&2
  exit 1
fi

resolve_agents

for agent in "${AGENTS[@]}"; do
  install_skill_for_agent "$agent"
done

if [[ "$INSTALL_MCP" == "true" ]]; then
  for agent in "${AGENTS[@]}"; do
    install_mcp_for_agent "$agent"
  done
fi
