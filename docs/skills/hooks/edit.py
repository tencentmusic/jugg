#!/usr/bin/env python3
"""Edit hook: remind agents to verify Android source changes through Jugg."""

from __future__ import annotations

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
PATCH_FILE_PATTERN = re.compile(r"^\*\*\* (?:Update|Add|Delete) File:\s+(.+)$")
PATCH_MOVE_TO_PATTERN = re.compile(r"^\*\*\* Move to:\s+(.+)$")
GIT_STATUS_PATTERN = re.compile(r"^(?:M|A|D|R|C|U|MM|AM|RM|MD|AD|AA|UU|\?\?)\s+(.+)$")


def extract_path_candidates(value: str) -> list[str]:
    candidates = [value]
    for line in value.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        patch_match = PATCH_FILE_PATTERN.match(stripped)
        if patch_match:
            candidates.append(patch_match.group(1).strip())
            continue
        move_match = PATCH_MOVE_TO_PATTERN.match(stripped)
        if move_match:
            candidates.append(move_match.group(1).strip())
            continue
        status_match = GIT_STATUS_PATTERN.match(stripped)
        if status_match:
            candidates.append(status_match.group(1).strip())
            continue
        if stripped.startswith("git diff -- "):
            tail = stripped[len("git diff -- ") :].strip()
            candidates.extend(part for part in tail.split() if part)
    return candidates


def normalize_path_candidate(candidate: str) -> str:
    stripped = candidate.strip()
    patch_match = PATCH_FILE_PATTERN.match(stripped)
    if patch_match:
        return patch_match.group(1).strip()
    move_match = PATCH_MOVE_TO_PATTERN.match(stripped)
    if move_match:
        return move_match.group(1).strip()
    status_match = GIT_STATUS_PATTERN.match(stripped)
    if status_match:
        return status_match.group(1).strip()
    return stripped


def collect_android_source_paths(payload: dict[str, Any]) -> list[str]:
    paths: list[str] = []
    for value in collect_strings(payload):
        for candidate in extract_path_candidates(value):
            normalized = normalize_path_candidate(candidate)
            if is_android_source_path(normalized) and normalized not in paths:
                paths.append(normalized)
    return paths


def payload_debug_text(payload: dict[str, Any]) -> str:
    try:
        return json.dumps(payload, ensure_ascii=False, sort_keys=True)
    except TypeError:
        return repr(payload)


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg edit hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    payload_text = payload_debug_text(payload)
    paths = collect_android_source_paths(payload)
    debug_log(
        "JUGG-EDIT",
        f"hook triggered cwd={Path.cwd()} client={args.client} payload={payload_text} paths={paths!r}",
    )
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
