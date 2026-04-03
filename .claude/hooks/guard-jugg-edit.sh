#!/bin/bash
# PreToolUse hook: block Edit/Write unless required Jugg knowledge base docs have been read.
# Required: 00_overview.md + 99_index.md + at least 1 additional doc (total >= 3)
# Files outside the project directory are always allowed through without any checks.

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('session_id','default'))" 2>/dev/null)

# Allow edits to files outside the project directory
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json, os
d = json.load(sys.stdin)
fp = d.get('tool_input', {}).get('file_path', '')
print(os.path.realpath(fp) if fp else '')
" 2>/dev/null)
PROJECT_DIR=$(realpath "$(pwd)")
if [ -n "$FILE_PATH" ] && [[ "$FILE_PATH" != "$PROJECT_DIR"* ]]; then
    exit 0
fi

MARKER_DIR="/tmp/claude_jugg"
OVERVIEW_MARKER="${MARKER_DIR}/${SESSION_ID}_overview"
INDEX_MARKER="${MARKER_DIR}/${SESSION_ID}_index"

MISSING=""
[ ! -f "$OVERVIEW_MARKER" ] && MISSING="${MISSING} 00_overview.md"
[ ! -f "$INDEX_MARKER" ]    && MISSING="${MISSING} 99_index.md"

if [ -n "$MISSING" ]; then
    python3 -c "
import json
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'PreToolUse',
        'additionalContext': (
            'Edit/Write BLOCKED: Required Jugg knowledge base docs not read.\n'
            'You MUST read the following files NOW before retrying:\n'
            '  - docs/ai_knowledge/00_overview.md\n'
            '  - docs/ai_knowledge/99_index.md\n'
            'Then read at least one topic doc from 99_index.md routing table.\n'
            'Note: files outside the project directory bypass this check automatically.'
        )
    },
    'decision': 'block',
    'reason': 'Required knowledge base docs not read: $MISSING'
}))
"
    exit 2
fi

# Check total ai_knowledge docs read >= 3
DOC_COUNT=$(ls "${MARKER_DIR}/${SESSION_ID}_doc_"* 2>/dev/null | wc -l | tr -d ' ')
if [ "$DOC_COUNT" -lt 3 ]; then
    python3 -c "
import json
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'PreToolUse',
        'additionalContext': (
            'Edit/Write BLOCKED: Insufficient knowledge base docs read (required >= 3).\n'
            'Please read at least one topic doc from the routing table in docs/ai_knowledge/99_index.md\n'
            'before retrying. Pick the doc most relevant to your current task.'
        )
    },
    'decision': 'block',
    'reason': 'Insufficient knowledge base docs read (read: $DOC_COUNT, required: >= 3)'
}))
"
    exit 2
fi

exit 0
