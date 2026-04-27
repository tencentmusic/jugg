#!/usr/bin/env python3
"""Start-phase hook: record Jugg status baseline for the current project."""

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
DEBUG_LOG_ENV = "JUGG_HOOK_DEBUG_LOG"
DEFAULT_DEBUG_LOG_PATH = Path.home() / ".jugg" / "skills" / "hooks" / "jugg-hook-debug.log"


def _debug_log(message: str) -> None:
    log_path = Path(os.environ.get(DEBUG_LOG_ENV, str(DEFAULT_DEBUG_LOG_PATH))).expanduser()
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"[JUGG-START] {timestamp} {message}\n")
    except Exception:
        pass


def _jugg_cli_path(home: Path) -> Path:
    return home / ".jugg" / "bin" / "jugg.py"


def _state_file_path(home: Path, cwd: str) -> Path:
    state_dir = home / ".jugg" / "hooks" / STATE_DIR_NAME
    digest = hashlib.sha1(cwd.encode("utf-8")).hexdigest()
    return state_dir / f"{digest}.json"


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


def main() -> int:
    _debug_log(f"hook triggered cwd={os.getcwd()}")
    home = Path.home()
    snapshot = _read_status_snapshot(home)
    if snapshot is None:
        _debug_log("exit: no snapshot generated")
        return 0

    state_file = _state_file_path(home, os.getcwd())
    try:
        state_file.parent.mkdir(parents=True, exist_ok=True)
        state_file.write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
    except OSError as error:
        _debug_log(f"exit: failed to persist snapshot file={state_file} error={error}")
        return 0
    _debug_log(f"exit: snapshot saved file={state_file}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
