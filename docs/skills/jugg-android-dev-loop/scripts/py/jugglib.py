"""jugglib — Jugg CLI shared library (cross-platform Python replacement for _lib.sh).

Provides: port detection, projectDir resolution, record session management,
          HTTP dispatch, JSON parsing utilities, async compile polling.
"""

import json
import os
import socket
import sys
import threading
import time
import urllib.request
import urllib.error
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Optional


# ─── cache paths (overridable via env) ───────────────────────────────────────

def _cache_dir() -> Path:
    d = os.environ.get("JUGG_CACHE_DIR")
    if d:
        return Path(d)
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    else:
        base = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    return base / "jugg"


def _port_cache_path() -> Path:
    p = os.environ.get("JUGG_PORT_CACHE")
    return Path(p) if p else _cache_dir() / "port"


def _record_session_path() -> Path:
    p = os.environ.get("JUGG_RECORD_SESSION")
    return Path(p) if p else _cache_dir() / "record_session"


def _ensure_cache_dir() -> None:
    _port_cache_path().parent.mkdir(parents=True, exist_ok=True)


# ─── port cache ──────────────────────────────────────────────────────────────

def read_port_cache() -> str:
    path = _port_cache_path()
    if path.is_file():
        return path.read_text().strip()
    return ""


def write_port_cache(port: int) -> None:
    _ensure_cache_dir()
    _port_cache_path().write_text(str(port))


# ─── port detection ──────────────────────────────────────────────────────────


@dataclass
class PortProbeResult:
    """Result of probing one local Jugg MCP port."""
    ok: bool
    summary: str
    retryable: bool


def _is_connection_refused(reason: Any) -> bool:
    if isinstance(reason, ConnectionRefusedError):
        return True
    return isinstance(reason, OSError) and getattr(reason, "errno", None) in (61, 111)


def _is_timeout(reason: Any) -> bool:
    return isinstance(reason, (TimeoutError, socket.timeout))


def _summarize_probe_exception(exc: Exception) -> PortProbeResult:
    if isinstance(exc, urllib.error.HTTPError):
        return PortProbeResult(False, f"http {exc.code}", exc.code >= 500)
    if isinstance(exc, urllib.error.URLError):
        reason = exc.reason
        if _is_connection_refused(reason):
            return PortProbeResult(False, "connection refused", False)
        if _is_timeout(reason):
            return PortProbeResult(False, "timed out", True)
        return PortProbeResult(False, f"url error: {reason}", True)
    if _is_timeout(exc):
        return PortProbeResult(False, "timed out", True)
    return PortProbeResult(False, f"{type(exc).__name__}: {exc}", True)


def _probe_port(port: int) -> PortProbeResult:
    """Ping a port and keep the failure reason for diagnostics."""
    try:
        body = json.dumps({
            "jsonrpc": "2.0", "id": 1, "method": "ping", "params": {}
        }).encode()
        req = urllib.request.Request(
            f"http://localhost:{port}/jugg-mcp",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=1) as resp:
            data = resp.read().decode()
            if '"jsonrpc"' in data:
                return PortProbeResult(True, "ok", False)
            return PortProbeResult(False, "unexpected response", False)
    except Exception as exc:
        return _summarize_probe_exception(exc)


def ping_port(port: int) -> bool:
    """Ping a port; return True if the Jugg MCP endpoint responds."""
    return _probe_port(port).ok


def _scan_ports(ports: Iterable[int]) -> dict[int, PortProbeResult]:
    return {port: _probe_port(port) for port in ports}


def _print_port_probe_failure(results: dict[int, PortProbeResult]) -> None:
    print("ERROR: Jugg IDE plugin not found on ports 12320-12329. "
          "Is Android Studio running?", file=sys.stderr)
    print("Port probe summary:", file=sys.stderr)
    for port in range(12320, 12330):
        result = results.get(port)
        summary = result.summary if result else "not checked"
        print(f"  {port}: {summary}", file=sys.stderr)


def resolve_port() -> int:
    """Resolve the active Jugg port: check cache first, then scan 12320..12329."""
    results: dict[int, PortProbeResult] = {}
    cached = read_port_cache()
    if cached:
        port = int(cached)
        result = _probe_port(port)
        results[port] = result
        if result.ok:
            return port

    scan_results = _scan_ports(range(12320, 12330))
    results.update(scan_results)
    for port, result in scan_results.items():
        if result.ok:
            write_port_cache(port)
            return port

    if any(result.retryable for result in results.values()):
        time.sleep(0.2)
        for port in range(12320, 12330):
            result = _probe_port(port)
            results[port] = result
            if result.ok:
                write_port_cache(port)
                return port

    _print_port_probe_failure(results)
    sys.exit(1)


# ─── HTTP dispatch ───────────────────────────────────────────────────────────

def http_post(port: int, body: str, timeout: int = 120) -> dict:
    """Low-level HTTP call; returns parsed JSON response."""
    req = urllib.request.Request(
        f"http://localhost:{port}/jugg-mcp",
        data=body.encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        print(f"ERROR: HTTP request failed: {e}", file=sys.stderr)
        sys.exit(1)


def raw_call(port: int, tool: str, params: dict) -> dict:
    """Assemble JSON-RPC 2.0 tools/call body and POST it."""
    body = json.dumps({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool, "arguments": params},
    })
    return http_post(port, body)


def extract_structured(response: dict) -> dict:
    """Extract structuredContent from a JSON-RPC response."""
    return response.get("result", {}).get("structuredContent", {})


# ─── high-level call ─────────────────────────────────────────────────────────

def jugg_call(tool: str, params: dict, *, json_mode: bool = False) -> dict:
    """High-level call: resolve port, POST, check status, return or print result."""
    port = resolve_port()
    response = raw_call(port, tool, params)
    structured = extract_structured(response)

    if json_mode:
        print(json.dumps(structured))
        return structured

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        detail = structured.get("data", {}).get("detail", "")
        error_output = f"status: ERROR\nmessage: {msg}"
        if detail:
            error_output += f"\ndetail: {detail}"
        print(error_output, file=sys.stderr)
        sys.exit(1)

    print_kv(structured)
    return structured


# ─── projectDir resolution ───────────────────────────────────────────────────

project_dir_override: str = ""


def match_project_dir(work_dir: str, projects: list[str]) -> str:
    """Given a working directory and a list of projectDirs,
    return the longest prefix match (slash-boundary-aware)."""
    # Normalize separators to forward slash for comparison
    norm_wd = work_dir.replace("\\", "/")
    best = ""
    for d in projects:
        if not d:
            continue
        norm_d = d.replace("\\", "/")
        if norm_wd == norm_d or norm_wd.startswith(norm_d + "/"):
            if len(d) > len(best):
                best = d
    return best


def set_project_dir_override(project_dir: str) -> None:
    """Set an explicit projectDir passed by the CLI global option."""
    global project_dir_override
    project_dir_override = project_dir


def resolve_project_dir() -> str:
    """Call list_projects and resolve projectDir from $PWD."""
    if project_dir_override:
        return project_dir_override

    port = resolve_port()
    response = raw_call(port, "list-projects", {})
    structured = extract_structured(response)
    projects_list = structured.get("data", {}).get("projects", [])
    project_dirs = [p.get("projectDir", "") for p in projects_list]

    cwd = os.getcwd()
    matched = match_project_dir(cwd, project_dirs)
    if not matched:
        print(f"ERROR: Current directory '{cwd}' is not under any Jugg project.",
              file=sys.stderr)
        print("       Run this command from within a project directory.",
              file=sys.stderr)
        sys.exit(1)
    return matched


# ─── record session cache ────────────────────────────────────────────────────

def record_session_exists() -> bool:
    return _record_session_path().is_file()


def record_session_save(session_id: str) -> None:
    _ensure_cache_dir()
    _record_session_path().write_text(session_id)


def record_session_read() -> str:
    return _record_session_path().read_text().strip()


def record_session_clear() -> None:
    path = _record_session_path()
    if path.exists():
        path.unlink()


# ─── JSON output utilities ───────────────────────────────────────────────────

def print_kv(structured: dict) -> None:
    """Print status + all data fields as 'key: value' lines."""
    status = structured.get("status", "")
    if status:
        print(f"status: {status}")
    message = structured.get("message", "")
    if message:
        print(f"message: {message}")

    data = structured.get("data", {})
    if isinstance(data, dict):
        for k, v in data.items():
            if isinstance(v, (dict, list)):
                print(f"{k}: {json.dumps(v)}")
            else:
                print(f"{k}: {v}")
    elif data:
        print(f"data: {data}")

    for art in structured.get("artifacts", []):
        if isinstance(art, dict):
            for k, v in art.items():
                print(f"{k}: {v}")


# ─── terminal spinner ───────────────────────────────────────────────────────

_SPINNER_FRAMES = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]
COMPILE_STATUS_WAIT_TIMEOUT_MS = 5000
COMPILE_IDLE_POLL_INTERVAL_SEC = 5.0
COMPILE_IDLE_HEARTBEAT_INTERVAL_SEC = 30.0
COMPILE_RUNNING_HEARTBEAT_INTERVAL_SEC = 30.0
IF_COMPILING_WAIT = "wait"
IF_COMPILING_INTERRUPT = "interrupt"


# Controlled by --console global flag (parsed in jugg.py).
# spinner_enabled: True only for --console=rich (human terminal use).
# json_mode: True only for --console=json (structured output for agents/scripts).
spinner_enabled: bool = False
json_mode: bool = False
# Controlled by --if-compiling global flag (parsed in jugg.py). CLI-only; not sent to MCP.
if_compiling: str = IF_COMPILING_WAIT


def _run_spinner(stop_event: threading.Event, label: str | list[str]) -> None:
    """Animate a braille spinner written to stderr.

    Disabled by default; only runs when jugglib.spinner_enabled is True.
    This prevents captured output (e.g. agent `2>&1`) from producing
    hundreds of spinner lines in logs.
    The jugg shell/cmd wrappers pass --spinner so human users still
    see the spinner; direct python3 calls and agent calls do not.

    When label is a single-element list, the spinner reads label[0] each frame
    so callers can update progress text without restarting the thread.
    """
    if not spinner_enabled or not sys.stderr.isatty():
        return

    def current_label() -> str:
        return label[0] if isinstance(label, list) else label

    i = 0
    max_line_len = 0
    while not stop_event.is_set():
        text = current_label()
        frame = _SPINNER_FRAMES[i % len(_SPINNER_FRAMES)]
        line = f"{frame} {text}..."
        padding = " " * max(0, max_line_len - len(line))
        max_line_len = max(max_line_len, len(line))
        sys.stderr.write(f"\r{line}{padding}")
        sys.stderr.flush()
        time.sleep(0.08)
        i += 1
    # Clear spinner line
    sys.stderr.write(f"\r{' ' * max_line_len}\r")
    sys.stderr.flush()


# ─── async compile polling ───────────────────────────────────────────────────

def _print_progress_heartbeat(text: str) -> None:
    """Print non-result progress to stderr for human-readable modes."""
    if json_mode:
        return
    normalized = text.strip()
    if not normalized:
        return
    print(normalized, file=sys.stderr)


def _extract_indicator_text(structured: dict) -> str:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return ""
    indicator = data.get("indicator", {})
    if not isinstance(indicator, dict):
        return ""
    text = indicator.get("text", "")
    return text if isinstance(text, str) else ""


def _handle_running_indicator(
    text: str,
    next_heartbeat_at: Optional[float],
    progress_label: Optional[list[str]],
) -> Optional[float]:
    if not text.strip():
        return next_heartbeat_at
    if spinner_enabled and progress_label is not None:
        progress_label[0] = text.strip()
        return next_heartbeat_at
    now = time.monotonic()
    if next_heartbeat_at is None or now >= next_heartbeat_at:
        _print_progress_heartbeat(text)
        return now + COMPILE_RUNNING_HEARTBEAT_INTERVAL_SEC
    return next_heartbeat_at


def poll_compile(
    port: int,
    project_dir: str,
    structured: dict,
    progress_label: Optional[list[str]] = None,
) -> dict:
    """Poll get_compile_status until status is no longer 'running'.

    The initial structured dict (from compile/deploy/gradle-build) contains isFinal + status.
    Subsequent dicts (from get-compile-status) contain only status, no isFinal.
    Use data.status != 'running' as the universal termination condition.
    """
    # Save logPath from initial response — get-compile-status does not return it.
    initial_log_path = structured.get("data", {}).get("logPath", "")
    next_indicator_heartbeat_at: Optional[float] = None

    while True:
        status = structured.get("data", {}).get("status", "")
        if status != "running":
            break

        job_id = structured.get("data", {}).get("jobId", "")
        if not job_id:
            print("ERROR: compile job has no jobId, cannot poll", file=sys.stderr)
            sys.exit(1)

        response = raw_call(
            port,
            "get-compile-status",
            {
                "projectDir": project_dir,
                "jobId": job_id,
                "waitTimeoutMs": COMPILE_STATUS_WAIT_TIMEOUT_MS,
            },
        )
        structured = extract_structured(response)
        if structured.get("data", {}).get("status") == "running":
            indicator_text = _extract_indicator_text(structured)
            next_indicator_heartbeat_at = _handle_running_indicator(
                indicator_text,
                next_indicator_heartbeat_at,
                progress_label,
            )

    # Restore logPath if missing from the polling response.
    if initial_log_path:
        data = structured.get("data")
        if isinstance(data, dict) and not data.get("logPath"):
            data["logPath"] = initial_log_path

    return structured


def _fetch_is_compiling(port: int, project_dir: str) -> bool:
    """Return whether a compile/deploy task is currently running on the IDE side."""
    response = raw_call(
        port,
        "status",
        {
            "projectDir": project_dir,
            "refreshChanges": False,
        },
    )
    structured = extract_structured(response)
    if structured.get("status") != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return False
    return bool(data.get("isCompiling", False))


def wait_for_compile_idle(
    port: int,
    project_dir: str,
    *,
    on_waiting: Optional[Callable[[], None]] = None,
) -> None:
    """Poll status until no compile/deploy task is running."""
    if not _fetch_is_compiling(port, project_dir):
        return
    if on_waiting is not None:
        on_waiting()
    next_heartbeat_at = time.monotonic() + COMPILE_IDLE_HEARTBEAT_INTERVAL_SEC
    while _fetch_is_compiling(port, project_dir):
        now = time.monotonic()
        if now >= next_heartbeat_at and not spinner_enabled:
            _print_progress_heartbeat("waiting for previous compile...")
            next_heartbeat_at = now + COMPILE_IDLE_HEARTBEAT_INTERVAL_SEC
        time.sleep(COMPILE_IDLE_POLL_INTERVAL_SEC)


# ─── standard subcommand helpers ─────────────────────────────────────────────

def simple_call(tool: str, *, json_mode: Optional[bool] = None,
                extra_params: Optional[dict] = None) -> dict:
    """Standard pattern: resolve project + port, call tool, handle output."""
    _json_mode = json_mode if json_mode is not None else globals()["json_mode"]
    project_dir = resolve_project_dir()
    port = resolve_port()
    params = {"projectDir": project_dir}
    if extra_params:
        params.update(extra_params)

    response = raw_call(port, tool, params)
    structured = extract_structured(response)

    if _json_mode:
        print(json.dumps(structured))
        return structured

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    print_kv(structured)
    return structured


def compile_call(tool: str, *, json_mode: Optional[bool] = None,
                 progress_msg: str = "",
                 extra_params: Optional[dict] = None) -> dict:
    """Standard pattern for compile-like commands that need polling."""
    _json_mode = json_mode if json_mode is not None else globals()["json_mode"]
    project_dir = resolve_project_dir()
    port = resolve_port()
    params = {"projectDir": project_dir}
    if extra_params:
        params.update(extra_params)

    label = progress_msg or "Compiling"
    spinner_label = [label]
    stop_event = threading.Event()
    spinner_thread = threading.Thread(
        target=_run_spinner, args=(stop_event, spinner_label), daemon=True
    )
    spinner_thread.start()
    interrupted = False
    try:
        if not spinner_enabled and not _json_mode:
            _print_progress_heartbeat(f"{label}...")
        if if_compiling == IF_COMPILING_WAIT:
            wait_for_compile_idle(
                port,
                project_dir,
                on_waiting=lambda: spinner_label.__setitem__(
                    0, "Waiting for previous compile"
                ),
            )
        response = raw_call(port, tool, params)
        structured = extract_structured(response)
        structured = poll_compile(port, project_dir, structured, progress_label=spinner_label)
    except KeyboardInterrupt:
        interrupted = True
    finally:
        stop_event.set()
        spinner_thread.join()

    if interrupted:
        if not _json_mode:
            print("Interrupted by user.", file=sys.stderr)
        sys.exit(130)

    if _json_mode:
        print(json.dumps(structured))
        return structured

    mcp_status = structured.get("status", "")
    data_status = structured.get("data", {}).get("status", "")
    # Compile failure: top-level MCP status may be OK (from get-compile-status polling),
    # but data.status will be "failed" or "canceled".
    is_compile_failed = data_status in ("failed", "canceled", "unknown")
    if mcp_status != "OK" or is_compile_failed:
        # Prefer data.message (actual compile error) over top-level message (always "executed successfully")
        msg = structured.get("data", {}).get("message") or structured.get("message", "Unknown error")
        detail = structured.get("data", {}).get("detail", "")
        log_path = structured.get("data", {}).get("logPath", "")
        is_compile_success = structured.get("data", {}).get("isCompileSuccess")
        is_deploy_success = structured.get("data", {}).get("isDeploySuccess")
        error_output = "status: ERROR"
        if tool != "compile" and is_compile_success is not None:
            error_output += f"\nisCompileSuccess: {str(is_compile_success).lower()}"
        if tool != "compile" and is_deploy_success is not None:
            error_output += f"\nisDeploySuccess: {str(is_deploy_success).lower()}"
        error_output += f"\nmessage: {msg}"
        if log_path:
            error_output += f"\nfull log: {log_path}"
        if detail:
            error_output += f"\ndetail:\n{detail}"
        print(error_output, file=sys.stderr)
        sys.exit(1)

    # Print consistently: status, isCompileSuccess, isDeploySuccess, message, full log, detail.
    # isCompileSuccess and isDeploySuccess are hidden for the "compile" command.
    status = structured.get("status", "")
    if status:
        print(f"status: {status}")
    data = structured.get("data", {})
    if tool != "compile":
        is_compile_success = data.get("isCompileSuccess")
        if is_compile_success is not None:
            print(f"isCompileSuccess: {str(is_compile_success).lower()}")
        is_deploy_success = data.get("isDeploySuccess")
        if is_deploy_success is not None:
            print(f"isDeploySuccess: {str(is_deploy_success).lower()}")
    # Prefer data.message (compile job result) over top-level message.
    message = data.get("message") or structured.get("message", "")
    if message:
        print(f"message: {message}")
    log_path = data.get("logPath", "")
    if log_path:
        print(f"full log: {log_path}")
    detail = data.get("detail", "")
    if detail:
        print(f"detail:\n{detail}")
    return structured


def normalize_args(args: list[str]) -> list[str]:
    """Normalize CLI flags: accept both --kebab-case and --camelCase.

    Converts any --kebab-case flag to --camelCase so that subcommand parsers
    only need to match camelCase names (which equal the MCP parameter names).

    Design note: Jugg CLI accepts both kebab-case (POSIX convention for humans)
    and camelCase (MCP parameter names for AI agents). Internally everything is
    camelCase to achieve zero-mapping between CLI and MCP.
    """
    import re
    result: list[str] = []
    for token in args:
        if token.startswith("--") and "-" in token[2:]:
            name = token[2:]
            camel = re.sub(r"-([a-z])", lambda m: m.group(1).upper(), name)
            result.append(f"--{camel}")
        else:
            result.append(token)
    return result
