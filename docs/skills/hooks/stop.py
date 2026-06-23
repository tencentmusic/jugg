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
    is_codebuddy_ide_payload,
    is_hook_block_disabled,
    emit_cursor_empty_response,
    emit_cursor_followup_response,
    extract_file_counts,
    extract_session_id,
    format_status_summary,
    has_session_write_seen,
    has_pending_files,
    read_hook_state,
    read_json_payload,
    read_status_snapshot,
    remember_project_cwd,
    session_write_needs_verification,
    state_file_path,
    write_hook_state,
)

STOP_BLOCK_MESSAGE = (
    "STOP GATE: Handle `jugg-android-dev-loop` before final response. "
    "Pending Android/Jugg changes were detected; enable the skill unless this is analysis-only/explanation-only "
    "work or verification is blocked by missing env/device. "
    "If skipped, say `Jugg dev loop skipped: <reason>`. "
    "Do not silently ignore this gate or replace it with a compile suggestion."
)
STOP_BLOCK_RETRY_WARNING = (
    "Notice: Jugg detect pending Android changes remain; allowing session stop after a repeated stop attempt. "
)
SYSTEM_MESSAGE_CLIENTS = {"codex", "claude"}


def emit_system_message(message: str) -> None:
    print(json.dumps({"systemMessage": message}, ensure_ascii=False))


def emit_codebuddy_stop_block(message: str, payload: dict[str, Any]) -> None:
    """Emit CodeBuddy Stop block on stdout; mirror to stderr only for IDE runtime."""
    print(json.dumps({"continue": False, "stopReason": message}, ensure_ascii=False))
    if is_codebuddy_ide_payload(payload):
        sys.stderr.write(f"{message}\n")


def emit_codebuddy_stop_allow(message: str) -> None:
    """Emit CodeBuddy Stop hook allow payload; systemMessage is user-visible only."""
    print(json.dumps({"continue": True, "systemMessage": message}, ensure_ascii=False))


def uses_system_message(client: str) -> bool:
    return client in SYSTEM_MESSAGE_CLIENTS


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg stop hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    try:
        return _main_impl()
    except Exception:
        # Safety net: never block the agent on unexpected errors.
        debug_log("JUGG-STOP", "unhandled exception; allowing stop")
        return 0


def _main_impl() -> int:
    args = _parse_args()
    payload = read_json_payload()
    client_part = f" client={args.client}" if args.client else ""
    debug_log("JUGG-STOP", f"hook triggered cwd={Path.cwd()}{client_part}")

    home = Path.home()
    cwd = str(Path.cwd())
    session_id = extract_session_id(payload)
    state_file = state_file_path(home, cwd, session_id)
    state = read_hook_state(state_file)
    project_cwd = cwd
    if args.client == "cursor":
        project_cwd, project_cwd_changed = remember_project_cwd(state, payload, cwd)
        if project_cwd_changed:
            write_hook_state(state_file, state)
    try:
        block_count = int(state.get("stopBlockCount", 0) or 0)
    except (TypeError, ValueError):
        block_count = 0

    debug_log("JUGG-STOP", f"resolved projectCwd={project_cwd}")
    structured = read_status_snapshot(home, project_cwd, timeout_seconds=10)
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

    if is_hook_block_disabled(home):
        debug_log("JUGG-STOP", "exit: allow stop because DISABLE_BLOCK flag is set")
        return 0

    if block_count == 0:
        state["stopBlockCount"] = 1
        persisted = write_hook_state(state_file, state)
        if not persisted:
            # If we cannot persist the block count, allow the stop rather than
            # risk blocking indefinitely on every subsequent attempt.
            debug_log("JUGG-STOP", "exit: allow stop because state persistence failed")
            return 0
        status_summary = format_status_summary(structured)
        block_message = (
            f"{STOP_BLOCK_MESSAGE}\n{status_summary}"
            if status_summary
            else STOP_BLOCK_MESSAGE
        )
        if args.client == "cursor":
            emit_cursor_followup_response(block_message)
            debug_log("JUGG-STOP", "exit: blocked stop with cursor followup")
            return 0
        if args.client == "codebuddy":
            emit_codebuddy_stop_block(block_message, payload)
            debug_log(
                "JUGG-STOP",
                "exit: blocked stop with codebuddy stopReason"
                + (" and stderr" if is_codebuddy_ide_payload(payload) else ""),
            )
            return 2
        sys.stderr.write(f"{block_message}\n")
        debug_log("JUGG-STOP", "exit: blocked stop because pending changes exist")
        return 2

    if uses_system_message(args.client):
        emit_system_message(STOP_BLOCK_RETRY_WARNING)
        debug_log("JUGG-STOP", f"exit: allow stop after repeated block with {args.client} systemMessage")
        return 0
    if args.client == "cursor":
        emit_cursor_empty_response(args.client)
        debug_log("JUGG-STOP", "exit: allow repeated cursor stop without followup")
        return 0
    if args.client == "codebuddy":
        emit_codebuddy_stop_allow(STOP_BLOCK_RETRY_WARNING)
        debug_log("JUGG-STOP", "exit: allow stop after repeated block with codebuddy systemMessage")
        return 0
    sys.stderr.write(f"{STOP_BLOCK_RETRY_WARNING}\n")
    debug_log("JUGG-STOP", "exit: allow stop after repeated block while pending changes remain")
    return 0


if __name__ == "__main__":
    sys.exit(main())
