#!/usr/bin/env python3
"""SessionStart hook: record Jugg status baseline for the current project."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


STATE_DIR_NAME = ".state"


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


def main() -> int:
    home = Path.home()
    snapshot = _read_status_snapshot(home)
    if snapshot is None:
        return 0

    state_file = _state_file_path(home, os.getcwd())
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps(snapshot, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
