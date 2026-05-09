#!/usr/bin/env python3
"""Edit hook: remind agents to verify Android source changes through Jugg."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    collect_strings,
    debug_log,
    emit_cursor_empty_response,
    is_android_source_path,
    read_hook_state,
    read_json_payload,
    read_status_snapshot,
    state_file_path,
    write_hook_state,
)


REMINDER_MESSAGE = (
    "You modified Android source files. During verification, enable the "
    "jugg-android-dev-loop skill and use Jugg CLI compile/deploy/gradle-build "
    "instead of running Gradle directly."
)


def collect_android_source_paths(payload: dict[str, Any]) -> list[str]:
    paths: list[str] = []
    for value in collect_strings(payload):
        if is_android_source_path(value) and value not in paths:
            paths.append(value)
    return paths


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg edit hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    paths = collect_android_source_paths(payload)
    debug_log("JUGG-EDIT", f"hook triggered cwd={Path.cwd()} client={args.client} paths={paths!r}")
    if not paths:
        emit_cursor_empty_response(args.client)
        return 0

    home = Path.home()
    cwd = str(Path.cwd())
    if read_status_snapshot(home, cwd) is None:
        debug_log("JUGG-EDIT", "exit: project is not available to jugg")
        emit_cursor_empty_response(args.client)
        return 0

    state_file = state_file_path(home, cwd)
    state = read_hook_state(state_file)
    state["androidEditPending"] = True
    existing_paths = state.get("androidEditPaths", [])
    if not isinstance(existing_paths, list):
        existing_paths = []
    state["androidEditPaths"] = sorted(set(existing_paths + paths))

    if not state.get("androidEditReminderShown"):
        state["androidEditReminderShown"] = True
        sys.stderr.write(f"{REMINDER_MESSAGE}\n")
        debug_log("JUGG-EDIT", "exit: reminder emitted")
    else:
        debug_log("JUGG-EDIT", "exit: reminder already emitted")

    write_hook_state(state_file, state)
    emit_cursor_empty_response(args.client)
    return 0


if __name__ == "__main__":
    sys.exit(main())
