#!/usr/bin/env python3
"""Start-phase hook: record Jugg status baseline for the current project."""

from __future__ import annotations

import json
import os
import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import debug_log, read_status_snapshot, state_file_path


def _debug_log(message: str) -> None:
    debug_log("JUGG-START", message)


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
    structured = read_status_snapshot(home, os.getcwd())
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


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg start hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    client_part = f" client={args.client}" if args.client else ""
    _debug_log(f"hook triggered cwd={os.getcwd()}{client_part}")
    home = Path.home()
    snapshot = _read_status_snapshot(home)
    if snapshot is None:
        _debug_log("exit: no snapshot generated")
        return 0

    state_file = state_file_path(home, os.getcwd())
    payload: dict[str, Any] = {"stopBlockCount": 0, "snapshot": snapshot}
    try:
        state_file.parent.mkdir(parents=True, exist_ok=True)
        state_file.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    except OSError as error:
        _debug_log(f"exit: failed to persist snapshot file={state_file} error={error}")
        return 0
    _debug_log(f"exit: snapshot saved file={state_file}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
