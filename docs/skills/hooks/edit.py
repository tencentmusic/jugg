#!/usr/bin/env python3
"""Edit hook: record that this agent session used a write-capable tool."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    debug_log,
    emit_cursor_empty_response,
    extract_session_id,
    is_android_source_path,
    mark_session_write_seen,
    payload_debug_suffix,
    read_hook_state,
    read_json_payload,
    remember_project_cwd,
    state_file_path,
    write_hook_state,
)


CODEX_APPLY_PATCH_TOOL = "apply_patch"
PATCH_FILE_PREFIXES = (
    "*** Add File: ",
    "*** Update File: ",
    "*** Move to: ",
)
CLAUDE_EDIT_TOOLS = {"Edit", "Write"}
CODEBUDDY_EDIT_TOOLS = {"Edit", "Write"}
GEMINI_EDIT_TOOLS = {"write_file", "replace"}
CURSOR_FILE_EDIT_EVENT = "afterFileEdit"


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg edit hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def _codex_apply_patch_paths(payload: dict[str, Any]) -> list[str]:
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return []
    command = tool_input.get("command")
    if not isinstance(command, str):
        return []
    paths: list[str] = []
    for line in command.splitlines():
        stripped = line.strip()
        for prefix in PATCH_FILE_PREFIXES:
            if stripped.startswith(prefix):
                path = stripped[len(prefix):].strip()
                if path:
                    paths.append(path)
                break
    return paths


def _should_record_codex_write(payload: dict[str, Any]) -> bool:
    if payload.get("tool_name") != CODEX_APPLY_PATCH_TOOL:
        return False
    return any(is_android_source_path(path) for path in _codex_apply_patch_paths(payload))


def _should_record_claude_write(payload: dict[str, Any]) -> bool:
    if payload.get("tool_name") not in CLAUDE_EDIT_TOOLS:
        return False
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return False
    file_path = tool_input.get("file_path")
    if not isinstance(file_path, str):
        return False
    return is_android_source_path(file_path)

def _should_record_codebuddy_write(payload: dict[str, Any]) -> bool:
    if payload.get("tool_name") not in CODEBUDDY_EDIT_TOOLS:
        return False
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return False
    file_path = tool_input.get("file_path")
    if not isinstance(file_path, str):
        return False
    return is_android_source_path(file_path)


def _should_record_gemini_write(payload: dict[str, Any]) -> bool:
    if payload.get("tool_name") not in GEMINI_EDIT_TOOLS:
        return False
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return False
    file_path = tool_input.get("file_path")
    if not isinstance(file_path, str):
        return False
    return is_android_source_path(file_path)


def _should_record_cursor_write(payload: dict[str, Any]) -> bool:
    if payload.get("hook_event_name") != CURSOR_FILE_EDIT_EVENT:
        return False
    file_path = payload.get("file_path")
    if not isinstance(file_path, str):
        return False
    return is_android_source_path(file_path)


def main() -> int:
    try:
        return _main_impl()
    except Exception:
        debug_log("JUGG-EDIT", "unhandled exception; allowing edit to proceed")
        return 0


def _main_impl() -> int:
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

    if args.client == "codex" and not _should_record_codex_write(payload):
        debug_log(
            "JUGG-EDIT",
            f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
            "ignored codex edit payload outside Android source",
        )
        return 0
    if args.client == "claude" and not _should_record_claude_write(payload):
        debug_log(
            "JUGG-EDIT",
            f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
            "ignored claude edit payload outside Android source",
        )
        return 0
    if args.client == "codebuddy" and not _should_record_codebuddy_write(payload):
        debug_log(
            "JUGG-EDIT",
            f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
            "ignored codebuddy edit payload outside Android source",
        )
        return 0
    if args.client == "gemini" and not _should_record_gemini_write(payload):
        debug_log(
            "JUGG-EDIT",
            f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
            "ignored gemini edit payload outside Android source",
        )
        return 0
    if args.client == "cursor" and not _should_record_cursor_write(payload):
        debug_log(
            "JUGG-EDIT",
            f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
            "ignored cursor edit payload outside Android source",
        )
        if project_cwd_changed:
            write_hook_state(state_file, state)
        return 0
    mark_session_write_seen(state)
    write_hook_state(state_file, state)
    debug_log(
        "JUGG-EDIT",
        f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
        "session write recorded",
    )
    emit_cursor_empty_response(args.client)
    return 0


if __name__ == "__main__":
    sys.exit(main())
