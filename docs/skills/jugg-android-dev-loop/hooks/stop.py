#!/usr/bin/env python3
"""Stop hook: block stop when Android changes are pending since session baseline."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


STATE_DIR_NAME = ".state"
STOP_BLOCK_MESSAGE = (
    "Android code changes were detected in this session. "
    "Before stopping, you must enable the jugg-android-dev-loop skill and complete compile verification."
)


def _jugg_cli_path(home: Path) -> Path:
    return home / ".jugg" / "bin" / "jugg.py"


def _state_file_path(home: Path, cwd: str) -> Path:
    state_dir = home / ".jugg" / "hooks" / STATE_DIR_NAME
    digest = hashlib.sha1(cwd.encode("utf-8")).hexdigest()
    return state_dir / f"{digest}.json"


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
        "fileCounts": file_counts,
    }


def _read_status_snapshot(home: Path) -> dict[str, Any] | None:
    jugg_cli = _jugg_cli_path(home)
    if not jugg_cli.exists():
        return None

    result = subprocess.run(
        [str(jugg_cli), "--console=json", "status"],
        capture_output=True,
        text=True,
        cwd=os.getcwd(),
        check=False,
    )
    if result.returncode != 0:
        return None
    try:
        structured = json.loads(result.stdout.strip() or "{}")
    except json.JSONDecodeError:
        return None
    if structured.get("status") != "OK":
        return None
    return _extract_snapshot(structured)


def _is_snapshot_changed(previous: dict[str, Any], current: dict[str, Any]) -> bool:
    return str(previous.get("lastFileModifiedTime", "")) != str(current.get("lastFileModifiedTime", ""))


def _has_pending_files(file_counts: dict[str, Any]) -> bool:
    total = _safe_int(file_counts.get("total", 0))
    if total > 0:
        return True
    for value in file_counts.values():
        if _safe_int(value) > 0:
            return True
    return False


def should_block_stop(previous: dict[str, Any], current: dict[str, Any]) -> bool:
    if not _is_snapshot_changed(previous, current):
        return False
    file_counts = current.get("fileCounts", {})
    if not isinstance(file_counts, dict):
        file_counts = {}
    return _has_pending_files(file_counts)


def main() -> int:
    home = Path.home()
    state_file = _state_file_path(home, os.getcwd())
    if not state_file.exists():
        return 0

    try:
        previous = json.loads(state_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return 0
    if not isinstance(previous, dict):
        return 0

    current = _read_status_snapshot(home)
    if current is None:
        return 0

    if should_block_stop(previous, current):
        print(STOP_BLOCK_MESSAGE)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
