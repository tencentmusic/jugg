#!/usr/bin/env python3
"""Stop hook: block stop when Android changes are pending since session baseline."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


STATE_DIR_NAME = ".state"
STOP_BLOCK_MESSAGE = (
    "Android code changes were detected in this session. "
    "Before stopping, you must enable the jugg-android-dev-loop skill and complete verification."
)
DEBUG_LOG_ENV = "JUGG_HOOK_DEBUG_LOG"
DEFAULT_DEBUG_LOG_PATH = Path.home() / ".jugg" / "skills" / "hooks" / "jugg-hook-debug.log"


def _debug_log(message: str) -> None:
    log_path = Path(os.environ.get(DEBUG_LOG_ENV, str(DEFAULT_DEBUG_LOG_PATH))).expanduser()
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"[JUGG-STOP] {timestamp} {message}\n")
    except Exception:
        pass


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
        "lastCompileTime": str(data.get("lastCompileTime", "")),
        "fileCounts": file_counts,
    }


def _read_status_snapshot(home: Path) -> dict[str, Any] | None:
    jugg_cli = _jugg_cli_path(home)
    if not jugg_cli.exists():
        _debug_log(f"skip: jugg cli not found path={jugg_cli}")
        return None

    result = subprocess.run(
        [str(jugg_cli), "--console=json", "status"],
        capture_output=True,
        text=True,
        cwd=os.getcwd(),
        check=False,
    )
    if result.returncode != 0:
        stderr_line = (result.stderr or "").strip().splitlines()
        stderr_hint = stderr_line[0] if stderr_line else ""
        _debug_log(
            f"skip: jugg status failed code={result.returncode} stderr={stderr_hint!r}"
        )
        return None
    try:
        structured = json.loads(result.stdout.strip() or "{}")
    except json.JSONDecodeError:
        _debug_log("skip: jugg status output is not valid json")
        return None
    if structured.get("status") != "OK":
        _debug_log(
            f"skip: jugg status not OK status={structured.get('status')!r} message={structured.get('message')!r}"
        )
        return None
    snapshot = _extract_snapshot(structured)
    _debug_log(
        "status snapshot loaded "
        f"lastFileModifiedTime={snapshot.get('lastFileModifiedTime')!r} "
        f"lastCompileTime={snapshot.get('lastCompileTime')!r} "
        f"fileCounts={snapshot.get('fileCounts')!r}"
    )
    return snapshot


def _is_snapshot_changed(previous: dict[str, Any], current: dict[str, Any]) -> bool:
    return str(previous.get("lastFileModifiedTime", "")) != str(current.get("lastFileModifiedTime", ""))


def _is_compile_invoked(previous: dict[str, Any], current: dict[str, Any]) -> bool:
    return str(previous.get("lastCompileTime", "")) != str(current.get("lastCompileTime", ""))


def _has_pending_files(file_counts: dict[str, Any]) -> bool:
    total = _safe_int(file_counts.get("total", 0))
    if total > 0:
        return True
    for value in file_counts.values():
        if _safe_int(value) > 0:
            return True
    return False


def should_block_stop(previous: dict[str, Any], current: dict[str, Any]) -> bool:
    if _is_compile_invoked(previous, current):
        return False
    if not _is_snapshot_changed(previous, current):
        return False
    file_counts = current.get("fileCounts", {})
    if not isinstance(file_counts, dict):
        file_counts = {}
    return _has_pending_files(file_counts)


def main() -> int:
    _debug_log(f"hook triggered cwd={os.getcwd()}")
    home = Path.home()
    state_file = _state_file_path(home, os.getcwd())
    if not state_file.exists():
        _debug_log(f"exit: state file not found file={state_file}")
        return 0

    try:
        previous = json.loads(state_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        _debug_log(f"exit: invalid state json file={state_file}")
        return 0
    if not isinstance(previous, dict):
        _debug_log(f"exit: state payload is not object file={state_file}")
        return 0

    current = _read_status_snapshot(home)
    if current is None:
        _debug_log("exit: no current snapshot generated")
        return 0

    should_block = should_block_stop(previous, current)
    _debug_log(
        "decision computed "
        f"shouldBlock={should_block} "
        f"previousLast={previous.get('lastFileModifiedTime')!r} "
        f"previousCompile={previous.get('lastCompileTime')!r} "
        f"currentLast={current.get('lastFileModifiedTime')!r} "
        f"currentCompile={current.get('lastCompileTime')!r} "
        f"currentFileCounts={current.get('fileCounts')!r}"
    )
    if should_block:
        print(STOP_BLOCK_MESSAGE)
        _debug_log("exit: blocked stop because Android changes are pending")
        return 2
    _debug_log("exit: allow stop")
    return 0


if __name__ == "__main__":
    sys.exit(main())
