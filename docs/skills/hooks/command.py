#!/usr/bin/env python3
"""Command hook: prevent raw Gradle verification after Android edits."""

from __future__ import annotations

import re
import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    collect_strings,
    debug_log,
    emit_cursor_empty_response,
    read_hook_state,
    read_json_payload,
    read_status_snapshot,
    state_file_path,
    write_hook_state,
)


GRADLE_BLOCK_MESSAGE = (
    "Android source changes are pending. Do not verify with raw Gradle here; "
    "enable the jugg-android-dev-loop skill and run Jugg CLI compile/deploy/gradle-build instead."
)
GRADLE_RETRY_WARNING = (
    "Warning: raw Gradle verification is still not the Jugg dev loop. "
    "Allowing this repeated command attempt, but final verification should use Jugg CLI."
)
RAW_GRADLE_PATTERN = re.compile(r"(^|[\s;&|()])(?:\./)?gradlew?(?:\s|$)")


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


def should_block_gradle_command(payload: dict[str, Any], state: dict[str, Any]) -> bool:
    has_android_edit = bool(state.get("androidEditPending") or state.get("androidEditReminderShown"))
    return has_android_edit and bool(collect_command_strings(payload))


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg command hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    home = Path.home()
    cwd = str(Path.cwd())
    state_file = state_file_path(home, cwd)
    state = read_hook_state(state_file)
    commands = collect_command_strings(payload)
    debug_log("JUGG-COMMAND", f"hook triggered cwd={cwd} client={args.client} commands={commands!r}")

    if not should_block_gradle_command(payload, state):
        emit_cursor_empty_response(args.client)
        return 0
    if read_status_snapshot(home, cwd) is None:
        debug_log("JUGG-COMMAND", "exit: project is not available to jugg")
        emit_cursor_empty_response(args.client)
        return 0

    try:
        block_count = int(state.get("gradleBlockCount", 0) or 0)
    except (TypeError, ValueError):
        block_count = 0
    if block_count == 0:
        state["gradleBlockCount"] = 1
        write_hook_state(state_file, state)
        sys.stderr.write(f"{GRADLE_BLOCK_MESSAGE}\n")
        debug_log("JUGG-COMMAND", "exit: blocked raw gradle command")
        return 2

    sys.stderr.write(f"{GRADLE_RETRY_WARNING}\n")
    debug_log("JUGG-COMMAND", "exit: allow repeated raw gradle command")
    emit_cursor_empty_response(args.client)
    return 0


if __name__ == "__main__":
    sys.exit(main())
