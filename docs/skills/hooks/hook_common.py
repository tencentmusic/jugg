#!/usr/bin/env python3
"""Shared helpers for Jugg agent hook scripts."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


STATE_DIR_NAME = ".state"
HOOK_BLOCK_DISABLED_FLAG_NAME = "DISABLE_BLOCK"
SESSION_WRITE_SEEN_KEY = "sessionWriteSeen"
LAST_WRITE_TIME_MS_KEY = "lastWriteTimeMs"
SESSION_WRITE_FILE_NAMES_KEY = "sessionWriteFileNames"
PROJECT_CWD_KEY = "projectCwd"
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
PROJECT_CWD_KEYS = {
    "cwd",
    "pwd",
    "workingdirectory",
    "currentworkingdirectory",
    "workspace",
    "workspaces",
    "workspacefolder",
    "workspacefolders",
    "workspaceroot",
    "workspaceroots",
    "projectdir",
    "projectdirectory",
    "rootdir",
    "rootpath",
}
FILE_PATH_KEYS = {
    "file",
    "filepath",
    "filename",
    "path",
    "uri",
}
PROJECT_ROOT_MARKERS = ("settings.gradle", "settings.gradle.kts", "gradlew")


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


def _debug_payload_read(message: str) -> None:
    env_keys = sorted(
        key
        for key in os.environ
        if "CURSOR" in key.upper() or "HOOK" in key.upper() or "WORKSPACE" in key.upper()
    )
    debug_log("JUGG-HOOK", f"{message} stdin_isatty={os.isatty(0)} envKeys={env_keys[:20]!r}")


def _read_stdin_bytes() -> bytes:
    if os.isatty(0):
        return b""
    chunks: list[bytes] = []
    try:
        while True:
            chunk = os.read(0, 65536)
            if not chunk:
                break
            chunks.append(chunk)
    except OSError as error:
        _debug_payload_read(f"payload read failed error={error!r}")
        return b""
    return b"".join(chunks)


def _payload_encodings() -> list[str]:
    encodings = ["utf-8-sig", "utf-16", "utf-16-le", "utf-16-be"]
    if os.name == "nt":
        encodings.append("mbcs")
    return encodings


def _parse_payload_bytes(raw_bytes: bytes) -> tuple[dict[str, Any], str] | None:
    errors: list[str] = []
    for encoding in _payload_encodings():
        try:
            raw = raw_bytes.decode(encoding)
        except UnicodeDecodeError as error:
            errors.append(f"{encoding}: decode {error!r}")
            continue
        if not raw.strip("\x00\r\n\t "):
            continue
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as error:
            errors.append(f"{encoding}: json {error!r}")
            continue
        if isinstance(payload, dict):
            return payload, encoding
        errors.append(f"{encoding}: root {type(payload).__name__}")
    if errors:
        _debug_payload_read(
            f"payload parse failed byteLength={len(raw_bytes)} errors={errors!r} "
            f"hexPreview={raw_bytes[:160].hex()!r}"
        )
    return None


def read_json_payload() -> dict[str, Any]:
    raw_bytes = _read_stdin_bytes()
    if not raw_bytes.strip(b"\x00\r\n\t "):
        _debug_payload_read("payload read empty")
        return {}
    parsed = _parse_payload_bytes(raw_bytes)
    if parsed is None:
        return {}
    payload, encoding = parsed
    debug_log("JUGG-HOOK", f"payload read ok encoding={encoding} keys={sorted(payload.keys())!r}")
    return payload


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


def _normalized_payload_key(key: str) -> str:
    return "".join(char for char in key.strip().lower() if char.isalnum())


def _path_from_string(value: str, base_cwd: str | None = None) -> Path | None:
    candidate = value.strip()
    if not candidate or "\n" in candidate:
        return None
    if candidate.startswith("file://"):
        candidate = candidate[7:]
    path = Path(candidate).expanduser()
    if not path.is_absolute():
        if not base_cwd:
            return None
        path = Path(base_cwd).expanduser() / path
    try:
        return path.resolve(strict=False)
    except OSError:
        return None


def _directory_from_path_value(value: str, base_cwd: str | None = None) -> str | None:
    path = _path_from_string(value, base_cwd)
    if path is None:
        return None
    directory = path if path.is_dir() else path.parent
    return str(directory.resolve()) if directory.exists() else None


def _find_project_root_from_path(value: str, base_cwd: str | None = None) -> str | None:
    path = _path_from_string(value, base_cwd)
    if path is None:
        return None
    directory = path if path.is_dir() else path.parent
    if not directory.exists():
        return None
    for current in (directory, *directory.parents):
        if any((current / marker).exists() for marker in PROJECT_ROOT_MARKERS):
            return str(current.resolve())
    return None


def _collect_keyed_strings(value: Any, keys: set[str]) -> list[str]:
    values: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            normalized_key = _normalized_payload_key(key)
            if normalized_key in keys:
                values.extend(item for item in collect_strings(child) if item.strip())
                continue
            values.extend(_collect_keyed_strings(child, keys))
    elif isinstance(value, list):
        for child in value:
            values.extend(_collect_keyed_strings(child, keys))
    return values


def resolve_project_cwd(state: dict[str, Any], payload: dict[str, Any], fallback_cwd: str) -> str:
    stored_cwd = state.get(PROJECT_CWD_KEY)
    if isinstance(stored_cwd, str):
        resolved_stored = _directory_from_path_value(stored_cwd)
        if resolved_stored:
            return resolved_stored

    for value in _collect_keyed_strings(payload, PROJECT_CWD_KEYS):
        project_root = _find_project_root_from_path(value, fallback_cwd)
        if project_root:
            return project_root

    for value in _collect_keyed_strings(payload, FILE_PATH_KEYS):
        project_root = _find_project_root_from_path(value, fallback_cwd)
        if project_root:
            return project_root

    for value in _collect_keyed_strings(payload, PROJECT_CWD_KEYS):
        resolved_cwd = _directory_from_path_value(value, fallback_cwd)
        if resolved_cwd:
            return resolved_cwd

    return str(Path(fallback_cwd).resolve())


def remember_project_cwd(
    state: dict[str, Any],
    payload: dict[str, Any],
    fallback_cwd: str,
) -> tuple[str, bool]:
    project_cwd = resolve_project_cwd(state, payload, fallback_cwd)
    if state.get(PROJECT_CWD_KEY) == project_cwd:
        return project_cwd, False
    state[PROJECT_CWD_KEY] = project_cwd
    return project_cwd, True


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


def resolve_hooks_dir(home: Path | None = None) -> Path:
    resolved_home = home if home is not None else Path.home()
    return resolved_home / ".jugg" / "skills" / "hooks"


def is_hook_block_disabled(home: Path | None = None) -> bool:
    return (resolve_hooks_dir(home) / HOOK_BLOCK_DISABLED_FLAG_NAME).is_file()


def state_file_path(home: Path, cwd: str, session_id: str | None = None) -> Path:
    state_dir = resolve_hooks_dir(home) / STATE_DIR_NAME
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


def is_codebuddy_ide_payload(payload: dict[str, Any]) -> bool:
    """True when CodeBuddy hook stdin identifies the IDE runtime (not CLI)."""
    client = payload.get("client")
    return isinstance(client, str) and client.strip().lower() == "codebuddyide"


def has_session_write_seen(state: dict[str, Any]) -> bool:
    return bool(state.get(SESSION_WRITE_SEEN_KEY) or get_session_write_file_names(state))


def _extract_file_name(value: str) -> str | None:
    normalized = value.strip().replace("\\", "/")
    if not normalized:
        return None
    name = Path(normalized).name.strip()
    return name or None


def get_session_write_file_names(state: dict[str, Any]) -> list[str]:
    raw = state.get(SESSION_WRITE_FILE_NAMES_KEY)
    if not isinstance(raw, list):
        return []
    names: list[str] = []
    for value in raw:
        if not isinstance(value, str):
            continue
        name = _extract_file_name(value)
        if not name or name in names:
            continue
        names.append(name)
    return names


def mark_session_write_seen(state: dict[str, Any], file_paths: list[str] | None = None) -> None:
    state[SESSION_WRITE_SEEN_KEY] = True
    state[LAST_WRITE_TIME_MS_KEY] = int(time.time() * 1000)
    if not file_paths:
        return
    names = get_session_write_file_names(state)
    for path in file_paths:
        name = _extract_file_name(path)
        if not name or name in names:
            continue
        names.append(name)
    if names:
        state[SESSION_WRITE_FILE_NAMES_KEY] = names


def extract_status_file_names(structured: dict[str, Any]) -> list[str]:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return []
    files = data.get("files", [])
    if not isinstance(files, list):
        return []
    names: list[str] = []
    for value in files:
        if not isinstance(value, str):
            continue
        name = _extract_file_name(value)
        if not name or name in names:
            continue
        names.append(name)
    return names


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
    session_file_names = get_session_write_file_names(state)
    if session_file_names:
        status_file_names = set(extract_status_file_names(structured))
        if not any(name in status_file_names for name in session_file_names):
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


def jugg_cli_command(home: Path) -> list[str]:
    jugg_cli = jugg_cli_path(home)
    if os.name == "nt":
        return [sys.executable, str(jugg_cli)]
    return [str(jugg_cli)]


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
    file_counts = data.get("pendingModifiedFiles", {})
    if not isinstance(file_counts, dict):
        return {}
    return file_counts


def _format_plain_value(value: Any) -> str:
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return str(value)


def format_pending_modified_file_names(data: dict[str, Any], limit: int | None = None) -> str:
    """Return comma-separated basenames from status data.files."""
    files = data.get("files", [])
    if not isinstance(files, list):
        return ""
    names: list[str] = []
    for value in files:
        if not isinstance(value, str) or not value.strip():
            continue
        name = Path(value).name.strip()
        if not name or name in names:
            continue
        names.append(name)
        if limit is not None and len(names) >= limit:
            break
    return ", ".join(names)


def format_status_summary(structured: dict[str, Any]) -> str:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return ""
    lines = ["Jugg status:"]
    for key in (
        "hasDevice",
        "needFallback",
        "executionType",
        "hasBeenFullCompiled",
        "enabledAndroidTest",
        "pendingModifiedFiles",
        "lastCompileTime",
    ):
        if key == "pendingModifiedFiles":
            if key in data or "files" in data:
                lines.append(f"  pendingModifiedFiles: {format_pending_modified_file_names(data)}")
            continue
        if key in data:
            lines.append(f"  {key}: {_format_plain_value(data[key])}")
    return "\n".join(lines)


def extract_modified_file_names(structured: dict[str, Any], limit: int = 10) -> list[str]:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return []
    names_text = format_pending_modified_file_names(data, limit=limit)
    if not names_text:
        return []
    return names_text.split(", ")


def has_pending_files(file_counts: dict[str, Any]) -> bool:
    if safe_int(file_counts.get("total", 0)) > 0:
        return True
    for value in file_counts.values():
        if safe_int(value) > 0:
            return True
    return False


def status_execution_type(structured: dict[str, Any]) -> str:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return ""
    execution_type = data.get("executionType")
    return execution_type.strip().lower() if isinstance(execution_type, str) else ""


def status_is_remote_compile(structured: dict[str, Any]) -> bool:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return False
    if status_execution_type(structured) == "remote":
        return True
    return data.get("isRemoteCompile") is True


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
            [
                *jugg_cli_command(home),
                "--console=json",
                "status",
                "--refresh-changes",
                "true",
                "--full-info",
                "true",
            ],
            capture_output=True,
            text=True,
            cwd=cwd,
            check=False,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired:
        debug_log("JUGG-HOOK", f"skip: jugg status timeout after {timeout_seconds}s")
        return None
    except OSError as error:
        debug_log("JUGG-HOOK", f"skip: jugg status failed to start error={error}")
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


def emit_cursor_permission_response(permission: str, message: str = "") -> None:
    payload: dict[str, Any] = {"permission": permission}
    if message:
        payload["user_message"] = message
        payload["agent_message"] = message
    print(json.dumps(payload, ensure_ascii=False))


def emit_cursor_followup_response(message: str) -> None:
    print(json.dumps({"followup_message": message}, ensure_ascii=False))
