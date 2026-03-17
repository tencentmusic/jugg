#!/bin/bash
# PostToolUse hook: track when required Jugg knowledge base docs are read.
# Creates session-scoped marker files so the pre-edit guard can verify compliance.

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('session_id','default'))" 2>/dev/null)
FILE_PATH=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tool_input',{}).get('file_path',''))" 2>/dev/null)

MARKER_DIR="/tmp/claude_jugg"
mkdir -p "$MARKER_DIR"

# Track required docs individually
if echo "$FILE_PATH" | grep -q "00_overview\.md"; then
    touch "${MARKER_DIR}/${SESSION_ID}_overview"
fi

if echo "$FILE_PATH" | grep -q "99_index\.md"; then
    touch "${MARKER_DIR}/${SESSION_ID}_index"
fi

# Count total distinct ai_knowledge docs read this session
if echo "$FILE_PATH" | grep -q "docs/ai_knowledge/"; then
    DOC_NAME=$(basename "$FILE_PATH")
    touch "${MARKER_DIR}/${SESSION_ID}_doc_${DOC_NAME}"
fi

exit 0
