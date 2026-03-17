#!/bin/bash
# PreToolUse hook: block Edit/Write unless required Jugg knowledge base docs have been read.
# Required: 00_overview.md + 99_index.md + at least 1 additional doc (total >= 3)

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('session_id','default'))" 2>/dev/null)

MARKER_DIR="/tmp/claude_jugg"
OVERVIEW_MARKER="${MARKER_DIR}/${SESSION_ID}_overview"
INDEX_MARKER="${MARKER_DIR}/${SESSION_ID}_index"

MISSING=""
[ ! -f "$OVERVIEW_MARKER" ] && MISSING="${MISSING} 00_overview.md"
[ ! -f "$INDEX_MARKER" ]    && MISSING="${MISSING} 99_index.md"

if [ -n "$MISSING" ]; then
    echo "⛔ Required Jugg knowledge base docs not read. Edit blocked." >&2
    echo "   Please read the following docs first:${MISSING}" >&2
    echo "   Path: docs/ai_knowledge/" >&2
    exit 2
fi

# Check total ai_knowledge docs read >= 3
DOC_COUNT=$(ls "${MARKER_DIR}/${SESSION_ID}_doc_"* 2>/dev/null | wc -l | tr -d ' ')
if [ "$DOC_COUNT" -lt 3 ]; then
    echo "⛔ Insufficient knowledge base docs read (read: ${DOC_COUNT}, required: >= 3)." >&2
    echo "   Continue reading topic docs per the task routing table in 99_index.md." >&2
    exit 2
fi

exit 0
