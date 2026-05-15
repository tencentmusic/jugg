#!/usr/bin/env python3
"""Stop hook: block stop when Jugg status reports pending file changes."""

from __future__ import annotations

import sys
import json
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    debug_log,
    extract_file_counts,
    extract_modified_file_names,
    extract_session_id,
    format_status_summary,
    has_session_write_seen,
    has_pending_files,
    read_hook_state,
    read_json_payload,
    read_status_snapshot,
    session_write_needs_verification,
    state_file_path,
    write_hook_state,
)

STOP_BLOCK_MESSAGE = (
    "Android code changes were detected in this session. "
    "Before stopping, you must enable the jugg-android-dev-loop skill and complete verification."
)
STOP_BLOCK_RETRY_WARNING = (
    "Warning: pending Android changes remain; allowing session stop after a repeated stop attempt. "
)
SYSTEM_MESSAGE_CLIENTS = {"codex", "claude"}


def emit_system_message(message: str) -> None:
    print(json.dumps({"systemMessage": message}, ensure_ascii=False))


def uses_system_message(client: str) -> bool:
    return client in SYSTEM_MESSAGE_CLIENTS


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
    session_write_seen = has_session_write_seen(state)
    needs_verification = session_write_needs_verification(state, structured)
    debug_log(
        "JUGG-STOP",
        "decision computed "
        f"hasPending={has_pending} sessionWriteSeen={session_write_seen} "
        f"needsVerification={needs_verification} "
        f"stopBlockCount={block_count} pendingModifiedFiles={file_counts!r}",
    )

    if not has_pending or not needs_verification:
        if block_count > 0:
            state["stopBlockCount"] = 0
            write_hook_state(state_file, state)
        reason = (
            "pendingModifiedFiles show no pending changes"
            if not has_pending
            else "session writes were already covered by Jugg verification"
            if session_write_seen
            else "no session write was recorded"
        )
        debug_log("JUGG-STOP", f"exit: allow stop because {reason}")
        return 0

    if block_count == 0:
        state["stopBlockCount"] = 1
        write_hook_state(state_file, state)
        modified_files = extract_modified_file_names(structured)
        status_summary = format_status_summary(structured)
        details: list[str] = []
        if modified_files:
            modified_text = ", ".join(modified_files)
            details.append(f"Modified files: {modified_text}")
        if status_summary:
            details.append(status_summary)
        suffix = "\n".join(details)
        if suffix:
            sys.stderr.write(f"{STOP_BLOCK_MESSAGE}\n{suffix}\n")
        else:
            sys.stderr.write(f"{STOP_BLOCK_MESSAGE}\n")
        debug_log("JUGG-STOP", "exit: blocked stop because pending changes exist")
        return 2

    if uses_system_message(args.client):
        emit_system_message(STOP_BLOCK_RETRY_WARNING)
        debug_log("JUGG-STOP", f"exit: allow stop after repeated block with {args.client} systemMessage")
        return 0
    sys.stderr.write(f"{STOP_BLOCK_RETRY_WARNING}\n")
    debug_log("JUGG-STOP", "exit: allow stop after repeated block while pending changes remain")
    return 0


if __name__ == "__main__":
    sys.exit(main())
