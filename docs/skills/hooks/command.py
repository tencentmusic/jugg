#!/usr/bin/env python3
"""Command hook: prevent raw Gradle verification after session writes."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    collect_strings,
    debug_log,
    is_hook_block_disabled,
    emit_cursor_empty_response,
    emit_cursor_permission_response,
    extract_file_counts,
    extract_session_id,
    format_status_summary,
    has_session_write_seen,
    has_pending_files,
    payload_debug_suffix,
    read_hook_state,
    read_json_payload,
    read_status_snapshot,
    remember_project_cwd,
    session_write_needs_verification,
    state_file_path,
    write_hook_state,
)


GRADLE_BLOCK_MESSAGE = (
    "COMMAND GATE: Handle `jugg-android-dev-loop` instead of raw Gradle verification. "
    "Pending Android/Jugg changes were detected; use the Jugg CLI compile/deploy path unless this command "
    "is intentionally outside verification or blocked by missing env/device. "
    "If skipped, say `Jugg CLI verification skipped: <reason>`. "
    "Do not silently replace the Jugg dev loop with raw Gradle."
)
GRADLE_RETRY_WARNING = (
    "Warning: raw Gradle verification is still not the Jugg dev loop. "
    "Allowing this repeated command attempt."
)
SYSTEM_MESSAGE_CLIENTS = {"codex", "claude"}
RAW_GRADLE_PATTERN = re.compile(r"(^|[\s;&|()])(?:\.[\\/])?gradlew(?:\.(?:bat|cmd))?(?:\s|$)")
SHELL_COMMAND_KEYS = {"command", "cmd", "script"}


def is_raw_gradle_command(command: str) -> bool:
    if "jugg.py" in command or "jugg gradle-build" in command:
        return False
    return RAW_GRADLE_PATTERN.search(command) is not None


def collect_command_strings(payload: dict[str, Any]) -> list[str]:
    commands: list[str] = []
    for value in collect_strings(payload):
        if is_raw_gradle_command(value) and value not in commands:
            commands.append(value)
    return commands


def _collect_shell_command_texts(value: Any) -> list[str]:
    commands: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key.strip().lower() in SHELL_COMMAND_KEYS and isinstance(child, str) and child.strip():
                if child not in commands:
                    commands.append(child)
                continue
            for command in _collect_shell_command_texts(child):
                if command not in commands:
                    commands.append(command)
    elif isinstance(value, list):
        for child in value:
            for command in _collect_shell_command_texts(child):
                if command not in commands:
                    commands.append(command)
    return commands


def _single_line_command(command: str) -> str:
    return command.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def pending_fingerprint(structured: dict[str, Any]) -> str:
    data = structured.get("data", {})
    files: list[str] = []
    if isinstance(data, dict):
        raw_files = data.get("files", [])
        if isinstance(raw_files, list):
            files = sorted({str(file).replace("\\", "/") for file in raw_files if str(file).strip()})
    payload = {
        "pendingModifiedFiles": extract_file_counts(structured),
        "files": files,
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha1(encoded.encode("utf-8")).hexdigest()


def emit_codex_deny(message: str) -> None:
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": message,
                }
            },
            ensure_ascii=False,
        )
    )


def emit_system_message(message: str) -> None:
    print(json.dumps({"systemMessage": message}, ensure_ascii=False))


def uses_system_message(client: str) -> bool:
    return client in SYSTEM_MESSAGE_CLIENTS


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg command hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    payload_suffix = payload_debug_suffix(payload)
    home = Path.home()
    cwd = str(Path.cwd())
    session_id = extract_session_id(payload)
    state_file = state_file_path(home, cwd, session_id)
    state = read_hook_state(state_file)
    project_cwd = cwd
    project_cwd_changed = False
    if args.client == "cursor":
        project_cwd, project_cwd_changed = remember_project_cwd(state, payload, cwd)
    shell_commands = _collect_shell_command_texts(payload)
    commands = collect_command_strings(payload)
    debug_log(
        "JUGG-COMMAND",
        f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix} "
        f"commands={commands!r}",
    )
    for shell_command in shell_commands:
        debug_log("JUGG-COMMAND", f"shellCommand={_single_line_command(shell_command)!r}")
    state_dirty = project_cwd_changed and bool(shell_commands or commands or has_session_write_seen(state))
    if state_dirty:
        write_hook_state(state_file, state)

    if not commands:
        emit_cursor_empty_response(args.client)
        return 0

    if not has_session_write_seen(state):
        debug_log("JUGG-COMMAND", "exit: allow raw gradle command because no session write was recorded")
        emit_cursor_empty_response(args.client)
        return 0

    structured = read_status_snapshot(home, project_cwd)
    if structured is None:
        debug_log("JUGG-COMMAND", "exit: project is not available to jugg")
        emit_cursor_empty_response(args.client)
        return 0

    has_pending = has_pending_files(extract_file_counts(structured))
    try:
        block_count = int(state.get("gradleBlockCount", 0) or 0)
    except (TypeError, ValueError):
        block_count = 0

    if not has_pending:
        if block_count > 0:
            state["gradleBlockCount"] = 0
            state.pop("gradleBlockedFingerprint", None)
            write_hook_state(state_file, state)
        debug_log("JUGG-COMMAND", "exit: allow raw gradle command because pendingModifiedFiles show no pending changes")
        emit_cursor_empty_response(args.client)
        return 0

    if not session_write_needs_verification(state, structured):
        if block_count > 0:
            state["gradleBlockCount"] = 0
            state.pop("gradleBlockedFingerprint", None)
            write_hook_state(state_file, state)
        debug_log(
            "JUGG-COMMAND",
            "exit: allow raw gradle command because session writes were already covered by Jugg verification",
        )
        emit_cursor_empty_response(args.client)
        return 0

    if is_hook_block_disabled(home):
        debug_log("JUGG-COMMAND", "exit: allow raw gradle command because DISABLE_BLOCK flag is set")
        emit_cursor_empty_response(args.client)
        return 0

    fingerprint = pending_fingerprint(structured)
    blocked_fingerprint = state.get("gradleBlockedFingerprint")

    if block_count == 0 or blocked_fingerprint != fingerprint:
        state["gradleBlockCount"] = 1
        state["gradleBlockedFingerprint"] = fingerprint
        write_hook_state(state_file, state)
        status_summary = format_status_summary(structured)
        block_message = f"{GRADLE_BLOCK_MESSAGE}\n{status_summary}" if status_summary else GRADLE_BLOCK_MESSAGE
        if args.client == "codex":
            emit_codex_deny(block_message)
            debug_log("JUGG-COMMAND", "exit: blocked raw gradle command with codex deny")
            return 0
        if args.client == "cursor":
            emit_cursor_permission_response("deny", block_message)
            debug_log("JUGG-COMMAND", "exit: blocked raw gradle command with cursor deny")
            return 0
        sys.stderr.write(f"{block_message}\n")
        debug_log("JUGG-COMMAND", "exit: blocked raw gradle command")
        return 2

    if uses_system_message(args.client):
        emit_system_message(GRADLE_RETRY_WARNING)
        debug_log("JUGG-COMMAND", f"exit: allow repeated raw gradle command with {args.client} systemMessage")
        return 0
    if args.client == "cursor":
        emit_cursor_permission_response("allow", GRADLE_RETRY_WARNING)
        debug_log("JUGG-COMMAND", "exit: allow repeated raw gradle command with cursor warning")
        return 0
    sys.stderr.write(f"{GRADLE_RETRY_WARNING}\n")
    debug_log("JUGG-COMMAND", "exit: allow repeated raw gradle command")
    emit_cursor_empty_response(args.client)
    return 0


if __name__ == "__main__":
    sys.exit(main())
