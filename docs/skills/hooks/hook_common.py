#!/usr/bin/env python3
"""Shared helpers for Jugg agent hook scripts."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any


STATE_DIR_NAME = ".state"
DEBUG_LOG_ENV = "JUGG_HOOK_DEBUG_LOG"
DEFAULT_DEBUG_LOG_PATH = Path.home() / ".jugg" / "skills" / "hooks" / "jugg-hook-debug.log"
ANDROID_SOURCE_SUFFIXES = (".java", ".kt", ".xml", ".gradle", ".gradle.kts")
ANDROID_SOURCE_NAMES = ("AndroidManifest.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
EXCLUDED_PATH_SEGMENTS = {"docs", "build", ".gradle", ".idea"}


def debug_log(prefix: str, message: str) -> None:
    log_path = Path(os.environ.get(DEBUG_LOG_ENV, str(DEFAULT_DEBUG_LOG_PATH))).expanduser()
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"[{prefix}] {timestamp} {message}\n")
    except Exception:
        pass


def read_json_payload() -> dict[str, Any]:
    raw = ""
    try:
        raw = os.fdopen(0, encoding="utf-8", closefd=False).read()
    except Exception:
        return {}
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def collect_strings(value: Any) -> list[str]:
    values: list[str] = []
    if isinstance(value, str):
        values.append(value)
    elif isinstance(value, dict):
        for child in value.values():
            values.extend(collect_strings(child))
    elif isinstance(value, list):
        for child in value:
            values.extend(collect_strings(child))
    return values


def is_android_source_path(value: str) -> bool:
    normalized = value.strip().replace("\\", "/")
    if not normalized or "\n" in normalized:
        return False
    parts = [part for part in normalized.split("/") if part]
    if any(part in EXCLUDED_PATH_SEGMENTS for part in parts):
        return False
    name = parts[-1] if parts else normalized
    if name in ANDROID_SOURCE_NAMES:
        return True
    return normalized.endswith(ANDROID_SOURCE_SUFFIXES)


def state_file_path(home: Path, cwd: str) -> Path:
    state_dir = home / ".jugg" / "hooks" / STATE_DIR_NAME
    digest = hashlib.sha1(cwd.encode("utf-8")).hexdigest()
    return state_dir / f"{digest}.json"


def read_hook_state(state_file: Path) -> dict[str, Any]:
    if not state_file.exists():
        return {}
    try:
        payload = json.loads(state_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def write_hook_state(state_file: Path, state: dict[str, Any]) -> bool:
    try:
        state_file.parent.mkdir(parents=True, exist_ok=True)
        state_file.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
        return True
    except OSError as error:
        debug_log("JUGG-HOOK", f"failed to persist state file={state_file} error={error}")
        return False


def jugg_cli_path(home: Path) -> Path:
    return home / ".jugg" / "bin" / "jugg.py"


def read_status_snapshot(home: Path, cwd: str) -> dict[str, Any] | None:
    jugg_cli = jugg_cli_path(home)
    if not jugg_cli.exists():
        debug_log("JUGG-HOOK", f"skip: jugg cli not found path={jugg_cli}")
        return None

    result = subprocess.run(
        [str(jugg_cli), "--console=json", "status"],
        capture_output=True,
        text=True,
        cwd=cwd,
        check=False,
    )
    if result.returncode != 0:
        stderr_line = (result.stderr or "").strip().splitlines()
        stderr_hint = stderr_line[0] if stderr_line else ""
        debug_log("JUGG-HOOK", f"skip: jugg status failed code={result.returncode} stderr={stderr_hint!r}")
        return None
    try:
        structured = json.loads(result.stdout.strip() or "{}")
    except json.JSONDecodeError:
        debug_log("JUGG-HOOK", "skip: jugg status output is not valid json")
        return None
    if structured.get("status") != "OK":
        debug_log(
            "JUGG-HOOK",
            f"skip: jugg status not OK status={structured.get('status')!r} message={structured.get('message')!r}",
        )
        return None
    return structured if isinstance(structured, dict) else None


def emit_cursor_empty_response(client: str) -> None:
    if client == "cursor":
        print("{}")
