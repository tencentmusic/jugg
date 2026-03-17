#!/bin/bash
# TDD enforcement hook: when editing src/main, verify src/test has uncommitted changes.
# Called by Claude Code PreToolUse hook for Edit/Write tools.

# Extract file_path from ARGUMENTS (JSON)
FILE_PATH=$(echo "$ARGUMENTS" | python3 -c "
import json, sys
try:
    args = json.load(sys.stdin)
    print(args.get('file_path', ''))
except:
    print('')
" 2>/dev/null)

# Only check paths that contain src/main
if ! echo "$FILE_PATH" | grep -q "src/main"; then
    exit 0
fi

# Find the git repo root for the file
REPO_ROOT=$(git -C "$(dirname "$FILE_PATH")" rev-parse --show-toplevel 2>/dev/null)
if [ -z "$REPO_ROOT" ]; then
    exit 0
fi

# Check for any uncommitted changes (staged or unstaged) under src/test
CHANGED_TEST_FILES=$(git -C "$REPO_ROOT" status --porcelain -- "*/src/test/*" 2>/dev/null)

if [ -z "$CHANGED_TEST_FILES" ]; then
    echo "⚠️  TDD Check FAILED: You are editing src/main but there are no uncommitted changes in src/test."
    echo "    Please write a failing test first before modifying business code."
    echo "    File: $FILE_PATH"
    exit 2
fi

exit 0
