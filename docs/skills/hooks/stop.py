#!/usr/bin/env python3
"""Stop hook: block stop when Jugg status reports pending file changes."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    debug_log,
    extract_file_counts,
    extract_modified_file_names,
    extract_session_id,
    has_pending_files,
    read_hook_state,
    read_json_payload,
    read_status_snapshot,
    state_file_path,
    write_hook_state,
)

STOP_BLOCK_MESSAGE = (
    "Android code changes were detected in this session. "
    "Before stopping, you must enable the jugg-android-dev-loop skill and complete verification."
)
STOP_BLOCK_RETRY_WARNING = (
    "Warning: pending Android changes remain; allowing session stop after a repeated stop attempt. "
    "Run deploy/verification when you continue."
)


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg stop hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    client_part = f" client={args.client}" if args.client else ""
    debug_log("JUGG-STOP", f"hook triggered cwd={Path.cwd()}{client_part}")

    home = Path.home()
    cwd = str(Path.cwd())
    session_id = extract_session_id(payload)
    state_file = state_file_path(home, cwd, session_id)
    state = read_hook_state(state_file)
    try:
        block_count = int(state.get("stopBlockCount", 0) or 0)
    except (TypeError, ValueError):
        block_count = 0

    structured = read_status_snapshot(home, cwd, timeout_seconds=10)
    if structured is None:
        debug_log("JUGG-STOP", "exit: project is not available to jugg")
        return 0

    file_counts = extract_file_counts(structured)
    has_pending = has_pending_files(file_counts)
    debug_log(
        "JUGG-STOP",
        f"decision computed hasPending={has_pending} stopBlockCount={block_count} fileCounts={file_counts!r}",
    )

    if not has_pending:
        if block_count > 0:
            state["stopBlockCount"] = 0
            write_hook_state(state_file, state)
        debug_log("JUGG-STOP", "exit: allow stop because fileCounts show no pending changes")
        return 0

    if block_count == 0:
        state["stopBlockCount"] = 1
        write_hook_state(state_file, state)
        modified_files = extract_modified_file_names(structured)
        if modified_files:
            modified_text = ", ".join(modified_files)
            sys.stderr.write(f"{STOP_BLOCK_MESSAGE} Modified files: {modified_text}\n")
        else:
            sys.stderr.write(f"{STOP_BLOCK_MESSAGE}\n")
        debug_log("JUGG-STOP", "exit: blocked stop because pending changes exist")
        return 2

    sys.stderr.write(f"{STOP_BLOCK_RETRY_WARNING}\n")
    debug_log("JUGG-STOP", "exit: allow stop after repeated block while pending changes remain")
    return 0


if __name__ == "__main__":
    sys.exit(main())
