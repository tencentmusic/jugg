#!/usr/bin/env bash
set -euo pipefail

# Install this skill into Codex skill home for team distribution.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SKILL_NAME="$(basename "$SKILL_DIR")"
TARGET_ROOT="${CODEX_HOME:-$HOME/.codex}/skills"
FORCE="false"

usage() {
  cat <<USAGE
Usage:
  skills/jugg-mcp-android-loop/scripts/install_skill.sh [--target-root <dir>] [--force]

Examples:
  skills/jugg-mcp-android-loop/scripts/install_skill.sh
  skills/jugg-mcp-android-loop/scripts/install_skill.sh --target-root ~/.codex/skills --force
USAGE
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

mkdir -p "$TARGET_ROOT"
DEST="$TARGET_ROOT/$SKILL_NAME"

if [[ -e "$DEST" ]]; then
  if [[ "$FORCE" != "true" ]]; then
    echo "Skill already exists: $DEST"
    echo "Use --force to overwrite."
    exit 1
  fi
  rm -rf "$DEST"
fi

cp -R "$SKILL_DIR" "$DEST"

echo "Installed skill to: $DEST"
echo "Next: run setup script to configure MCP clients:"
echo "  python3 $DEST/scripts/setup_mcp_clients.py --all"
