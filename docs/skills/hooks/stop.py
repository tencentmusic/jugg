#!/usr/bin/env python3
"""Stop hook: block stop after agent Android source edit operations."""

from __future__ import annotations

import json
import os
import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import debug_log, read_status_snapshot, state_file_path

STOP_BLOCK_MESSAGE = (
    "Android source edits were detected in this session. "
    "Before stopping, you must enable the jugg-android-dev-loop skill for verification."
)
STOP_BLOCK_RETRY_WARNING = (
    "Warning: pending Android changes remain; allowing session stop after a repeated stop attempt. "
    "Run deploy/verification when you continue."
)
STATE_SNAPSHOT_KEY = "snapshot"
STATE_STOP_BLOCK_COUNT_KEY = "stopBlockCount"
STATE_ANDROID_EDIT_PENDING_KEY = "androidEditPending"
STATE_ANDROID_EDIT_PATHS_KEY = "androidEditPaths"
STATE_ANDROID_EDIT_COMPILE_TIME_KEY = "androidEditBaselineCompileTime"
STATUS_SNAPSHOT_TIMEOUT_SECONDS = 10
MAX_REPORTED_FILE_NAMES = 10


def _debug_log(message: str) -> None:
    debug_log("JUGG-STOP", message)


def _safe_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _extract_snapshot(structured: dict[str, Any]) -> dict[str, Any]:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        data = {}
    file_counts = data.get("fileCounts", {})
    if not isinstance(file_counts, dict):
        file_counts = {}
    return {
        "cwd": os.getcwd(),
        "lastFileModifiedTime": str(data.get("lastFileModifiedTime", "")),
        "lastCompileTime": str(data.get("lastCompileTime", "")),
        "fileCounts": file_counts,
    }


def _read_status_snapshot(home: Path) -> dict[str, Any] | None:
    structured = read_status_snapshot(home, os.getcwd(), timeout_seconds=STATUS_SNAPSHOT_TIMEOUT_SECONDS)
    if structured is None:
        return None
    snapshot = _extract_snapshot(structured)
    _debug_log(
        "status snapshot loaded "
        f"lastFileModifiedTime={snapshot.get('lastFileModifiedTime')!r} "
        f"lastCompileTime={snapshot.get('lastCompileTime')!r} "
        f"fileCounts={snapshot.get('fileCounts')!r}"
    )
    return snapshot


def _has_android_edit(state: dict[str, Any]) -> bool:
    return bool(state.get(STATE_ANDROID_EDIT_PENDING_KEY))


def _edit_baseline_compile_time(state: dict[str, Any], previous: dict[str, Any]) -> str:
    value = state.get(STATE_ANDROID_EDIT_COMPILE_TIME_KEY)
    if isinstance(value, str):
        return value
    return str(previous.get("lastCompileTime", ""))


def _file_name(path: str) -> str:
    return Path(path.replace("\\", "/")).name


def format_modified_file_summary(paths: list[Any]) -> str:
    names: list[str] = []
    for path in paths:
        if not isinstance(path, str):
            continue
        name = _file_name(path)
        if name and name not in names:
            names.append(name)
    visible = names[:MAX_REPORTED_FILE_NAMES]
    if len(names) > MAX_REPORTED_FILE_NAMES:
        visible.append("...")
    return ", ".join(visible)


def build_stop_block_message(paths: list[Any]) -> str:
    summary = format_modified_file_summary(paths)
    if not summary:
        return STOP_BLOCK_MESSAGE
    return f"{STOP_BLOCK_MESSAGE}\nModified files: {summary}"


def should_block_stop(
    state: dict[str, Any],
    previous: dict[str, Any],
    current: dict[str, Any] | None,
) -> bool:
    if not _has_android_edit(state):
        return False
    edit_baseline = _edit_baseline_compile_time(state, previous)
    if current is not None and edit_baseline != str(current.get("lastCompileTime", "")):
        return False
    return True


def parse_stored_state(raw: dict[str, Any]) -> tuple[dict[str, Any], int]:
    """Split persisted JSON into baseline snapshot and stopBlockCount (legacy flat files supported)."""
    nested = raw.get(STATE_SNAPSHOT_KEY)
    if isinstance(nested, dict):
        return nested, _safe_int(raw.get(STATE_STOP_BLOCK_COUNT_KEY, 0))
    legacy = {
        key: value
        for key, value in raw.items()
        if key not in (STATE_STOP_BLOCK_COUNT_KEY, STATE_SNAPSHOT_KEY)
    }
    return legacy, _safe_int(raw.get(STATE_STOP_BLOCK_COUNT_KEY, 0))


def write_hook_state(state_file: Path, state: dict[str, Any], snapshot: dict[str, Any], stop_block_count: int) -> None:
    """Persist stopBlockCount while preserving edit-hook state fields."""
    payload = dict(state)
    payload[STATE_STOP_BLOCK_COUNT_KEY] = stop_block_count
    payload[STATE_SNAPSHOT_KEY] = snapshot
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def compute_stop_hook_result(
    should_block: bool, stop_block_count: int, edited_paths: list[Any]
) -> tuple[int, int | None, str | None]:
    """
    Decide hook exit behavior and whether stopBlockCount must be persisted.

    Returns (exit_code, new_stop_block_count_or_none, stderr_message_or_none).
    exit_code 2 blocks stop; 0 allows. When the second value is int, caller must persist it.
    """
    if not should_block:
        if stop_block_count > 0:
            return 0, 0, None
        return 0, None, None
    if stop_block_count == 0:
        return 2, 1, build_stop_block_message(edited_paths)
    return 0, None, STOP_BLOCK_RETRY_WARNING


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg stop hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    client_part = f" client={args.client}" if args.client else ""
    _debug_log(f"hook triggered cwd={os.getcwd()}{client_part}")
    home = Path.home()
    state_file = state_file_path(home, os.getcwd())
    if not state_file.exists():
        _debug_log(f"exit: state file not found file={state_file}")
        return 0

    try:
        raw_state = json.loads(state_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        _debug_log(f"exit: invalid state json file={state_file}")
        return 0
    if not isinstance(raw_state, dict):
        _debug_log(f"exit: state payload is not object file={state_file}")
        return 0

    previous_snapshot, stop_block_count = parse_stored_state(raw_state)

    current = _read_status_snapshot(home)
    edited_paths = raw_state.get(STATE_ANDROID_EDIT_PATHS_KEY, [])
    if not isinstance(edited_paths, list):
        edited_paths = []
    should_block = should_block_stop(raw_state, previous_snapshot, current)
    _debug_log(
        "decision computed "
        f"shouldBlock={should_block} stopBlockCount={stop_block_count} "
        f"androidEditPending={raw_state.get(STATE_ANDROID_EDIT_PENDING_KEY)!r} "
        f"androidEditPaths={edited_paths!r} "
        f"androidEditCompile={raw_state.get(STATE_ANDROID_EDIT_COMPILE_TIME_KEY)!r} "
        f"previousLast={previous_snapshot.get('lastFileModifiedTime')!r} "
        f"previousCompile={previous_snapshot.get('lastCompileTime')!r} "
        f"currentLast={(current or {}).get('lastFileModifiedTime')!r} "
        f"currentCompile={(current or {}).get('lastCompileTime')!r}"
    )
    exit_code, new_count, stderr_message = compute_stop_hook_result(should_block, stop_block_count, edited_paths)
    if stderr_message:
        sys.stderr.write(f"{stderr_message}\n")
    if new_count is not None:
        try:
            write_hook_state(state_file, raw_state, previous_snapshot, new_count)
        except OSError as error:
            _debug_log(f"exit: failed to persist stop state file={state_file} error={error}")
            return 0
    if exit_code == 2:
        _debug_log("exit: blocked stop because Android source edits are pending")
    elif should_block and stop_block_count > 0:
        _debug_log("exit: allow stop after repeated block (pending changes remain)")
    else:
        _debug_log("exit: allow stop")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
