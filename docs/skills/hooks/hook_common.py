#!/usr/bin/env python3
"""Shared helpers for Jugg agent hook scripts."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Any


STATE_DIR_NAME = ".state"
SESSION_WRITE_SEEN_KEY = "sessionWriteSeen"
LAST_WRITE_TIME_MS_KEY = "lastWriteTimeMs"
JUGG_TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
DEBUG_LOG_ENV = "JUGG_HOOK_DEBUG_LOG"
DEBUG_PAYLOAD_ENV = "JUGG_HOOK_DEBUG_PAYLOAD"
DEFAULT_DEBUG_LOG_PATH = Path.home() / ".jugg" / "skills" / "hooks" / "jugg-hook-debug.log"
DEBUG_LOG_ROTATE_SIZE_BYTES = 1024 * 1024
DEBUG_LOG_BACKUP_SUFFIX = ".1"
ANDROID_SOURCE_SUFFIXES = (".java", ".kt", ".xml", ".gradle", ".gradle.kts")
ANDROID_SOURCE_NAMES = ("AndroidManifest.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
EXCLUDED_PATH_SEGMENTS = {"docs", "build", ".gradle", ".idea"}
SESSION_ID_KEYS = {
    "session_id",
    "sessionid",
    "conversation_id",
    "conversationid",
    "thread_id",
    "threadid",
    "run_id",
    "runid",
}


def _is_truthy(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on", "y"}


def should_log_payload() -> bool:
    return _is_truthy(os.environ.get(DEBUG_PAYLOAD_ENV, ""))


def payload_debug_text(payload: dict[str, Any]) -> str:
    try:
        return json.dumps(payload, ensure_ascii=False, sort_keys=True)
    except TypeError:
        return repr(payload)


def payload_debug_suffix(payload: dict[str, Any]) -> str:
    if not should_log_payload():
        return ""
    return f" payload={payload_debug_text(payload)}"


def rotate_debug_log_if_needed(log_path: Path) -> None:
    if not log_path.exists():
        return
    try:
        if log_path.stat().st_size < DEBUG_LOG_ROTATE_SIZE_BYTES:
            return
        backup_path = Path(f"{log_path}{DEBUG_LOG_BACKUP_SUFFIX}")
        if backup_path.exists():
            backup_path.unlink()
        log_path.rename(backup_path)
    except OSError:
        pass


def debug_log(prefix: str, message: str) -> None:
    log_path = Path(os.environ.get(DEBUG_LOG_ENV, str(DEFAULT_DEBUG_LOG_PATH))).expanduser()
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        rotate_debug_log_if_needed(log_path)
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
    if any(char.isspace() for char in normalized):
        return False
    parts = [part for part in normalized.split("/") if part]
    if any(part in EXCLUDED_PATH_SEGMENTS for part in parts):
        return False
    name = parts[-1] if parts else normalized
    if name in ANDROID_SOURCE_NAMES:
        return True
    return normalized.endswith(ANDROID_SOURCE_SUFFIXES)


def _extract_session_id_from_value(value: Any) -> str | None:
    if isinstance(value, dict):
        for key, child in value.items():
            lowered = key.strip().lower()
            if lowered in SESSION_ID_KEYS and isinstance(child, str) and child.strip():
                return child.strip()
            if lowered in {"session", "conversation", "thread", "run"} and isinstance(child, dict):
                nested_id = child.get("id")
                if isinstance(nested_id, str) and nested_id.strip():
                    return nested_id.strip()
            candidate = _extract_session_id_from_value(child)
            if candidate:
                return candidate
    elif isinstance(value, list):
        for child in value:
            candidate = _extract_session_id_from_value(child)
            if candidate:
                return candidate
    return None


def extract_session_id(payload: dict[str, Any]) -> str | None:
    return _extract_session_id_from_value(payload)


def state_file_path(home: Path, cwd: str, session_id: str | None = None) -> Path:
    state_dir = home / ".jugg" / "hooks" / STATE_DIR_NAME
    scope = cwd if not session_id else f"{cwd}\n{session_id}"
    digest = hashlib.sha1(scope.encode("utf-8")).hexdigest()
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


def has_session_write_seen(state: dict[str, Any]) -> bool:
    return bool(state.get(SESSION_WRITE_SEEN_KEY))


def mark_session_write_seen(state: dict[str, Any]) -> None:
    state[SESSION_WRITE_SEEN_KEY] = True
    state[LAST_WRITE_TIME_MS_KEY] = int(time.time() * 1000)


def extract_last_compile_time_ms(structured: dict[str, Any]) -> int:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return 0
    last_compile_time = data.get("lastCompileTime")
    if not isinstance(last_compile_time, str) or not last_compile_time.strip():
        return 0
    try:
        parsed_time = datetime.strptime(last_compile_time.strip(), JUGG_TIME_FORMAT)
    except ValueError:
        return 0
    return int(parsed_time.timestamp() * 1000)


def session_write_needs_verification(state: dict[str, Any], structured: dict[str, Any]) -> bool:
    if not has_session_write_seen(state):
        return False
    last_write_time_ms = safe_int(state.get(LAST_WRITE_TIME_MS_KEY, 0))
    if last_write_time_ms <= 0:
        # Legacy states only recorded a boolean write marker; keep the old conservative behavior.
        return True
    last_compile_time_ms = extract_last_compile_time_ms(structured)
    if last_compile_time_ms <= 0:
        return True
    # Jugg status exposes second-level precision. Treat the whole compile second as verified.
    return last_write_time_ms > last_compile_time_ms + 999


def jugg_cli_path(home: Path) -> Path:
    return home / ".jugg" / "bin" / "jugg.py"


def match_project_info(cwd: str, projects: list[Any]) -> dict[str, Any] | None:
    normalized_cwd = cwd.replace("\\", "/")
    best: dict[str, Any] | None = None
    best_length = -1
    for project in projects:
        if not isinstance(project, dict):
            continue
        project_dir = project.get("projectDir")
        if not isinstance(project_dir, str) or not project_dir:
            continue
        normalized_project_dir = project_dir.replace("\\", "/")
        is_match = normalized_cwd == normalized_project_dir or normalized_cwd.startswith(normalized_project_dir + "/")
        if is_match and len(project_dir) > best_length:
            best = project
            best_length = len(project_dir)
    return best


def project_allows_hooks(project_info: dict[str, Any]) -> bool:
    return project_info.get("hasBeenFullCompiled") is not False


def safe_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def extract_file_counts(structured: dict[str, Any]) -> dict[str, Any]:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return {}
    file_counts = data.get("fileCounts", {})
    if not isinstance(file_counts, dict):
        return {}
    return file_counts


def _format_plain_value(value: Any) -> str:
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return str(value)


def format_status_summary(structured: dict[str, Any]) -> str:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return ""
    lines = ["Jugg status:"]
    for key in (
        "hasDevice",
        "needFallback",
        "hasBeenFullCompiled",
        "enabledAndroidTest",
        "fileCounts",
        "lastCompileTime",
    ):
        if key in data:
            lines.append(f"  {key}: {_format_plain_value(data[key])}")
    return "\n".join(lines)


def extract_modified_file_names(structured: dict[str, Any], limit: int = 10) -> list[str]:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return []
    files = data.get("files", [])
    if not isinstance(files, list):
        return []
    names: list[str] = []
    for value in files:
        if not isinstance(value, str) or not value.strip():
            continue
        name = Path(value).name.strip()
        if not name or name in names:
            continue
        names.append(name)
        if len(names) >= limit:
            break
    return names


def has_pending_files(file_counts: dict[str, Any]) -> bool:
    if safe_int(file_counts.get("total", 0)) > 0:
        return True
    for value in file_counts.values():
        if safe_int(value) > 0:
            return True
    return False


def status_allows_hooks(structured: dict[str, Any]) -> bool:
    data = structured.get("data", {})
    return project_allows_hooks(data) if isinstance(data, dict) else True


def read_status_snapshot(
    home: Path,
    cwd: str,
    timeout_seconds: float | None = None,
) -> dict[str, Any] | None:
    jugg_cli = jugg_cli_path(home)
    if not jugg_cli.exists():
        debug_log("JUGG-HOOK", f"skip: jugg cli not found path={jugg_cli}")
        return None

    try:
        result = subprocess.run(
            [str(jugg_cli), "--console=json", "status"],
            capture_output=True,
            text=True,
            cwd=cwd,
            check=False,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired:
        debug_log("JUGG-HOOK", f"skip: jugg status timeout after {timeout_seconds}s")
        return None
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
    if not status_allows_hooks(structured):
        debug_log("JUGG-HOOK", "skip: project has not been full compiled by Jugg")
        return None
    return structured if isinstance(structured, dict) else None


def emit_cursor_empty_response(client: str) -> None:
    if client == "cursor":
        print("{}")
