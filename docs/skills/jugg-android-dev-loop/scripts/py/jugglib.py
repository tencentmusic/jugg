"""jugglib — Jugg CLI shared library (cross-platform Python replacement for _lib.sh).

Provides: port detection, projectDir resolution, record session management,
          HTTP dispatch, JSON parsing utilities, async compile polling.
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path
from typing import Any, Optional


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

def ping_port(port: int) -> bool:
    """Ping a port; return True if the Jugg MCP endpoint responds."""
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
            return '"jsonrpc"' in data
    except Exception:
        return False


def resolve_port() -> int:
    """Resolve the active Jugg port: check cache first, then scan 12320..12329."""
    cached = read_port_cache()
    if cached:
        port = int(cached)
        if ping_port(port):
            return port

    for port in range(12320, 12330):
        if ping_port(port):
            write_port_cache(port)
            return port

    print("ERROR: Jugg IDE plugin not found on ports 12320-12329. "
          "Is Android Studio running?", file=sys.stderr)
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
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    print_kv(structured)
    return structured


# ─── projectDir resolution ───────────────────────────────────────────────────

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


def resolve_project_dir() -> str:
    """Call list_projects and resolve projectDir from $PWD."""
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


# ─── async compile polling ───────────────────────────────────────────────────

def poll_compile(port: int, structured: dict) -> dict:
    """Poll get_compile_status until isFinal=true, then return the final structured JSON."""
    while True:
        is_final = structured.get("data", {}).get("isFinal", True)
        if is_final:
            break

        job_id = structured.get("data", {}).get("jobId", "")
        interval_ms = structured.get("data", {}).get("pollIntervalSuggestedMs", 2000)

        msg = structured.get("message", "")
        if msg:
            print(f"  {msg}", file=sys.stderr)

        time.sleep(interval_ms / 1000.0)

        if not job_id:
            print("ERROR: compile job has no jobId, cannot poll", file=sys.stderr)
            sys.exit(1)

        response = raw_call(port, "get-compile-status", {"jobId": job_id})
        structured = extract_structured(response)

    return structured


# ─── standard subcommand helpers ─────────────────────────────────────────────

def simple_call(tool: str, *, json_mode: bool = False,
                extra_params: Optional[dict] = None) -> dict:
    """Standard pattern: resolve project + port, call tool, handle output."""
    project_dir = resolve_project_dir()
    port = resolve_port()
    params = {"projectDir": project_dir}
    if extra_params:
        params.update(extra_params)

    response = raw_call(port, tool, params)
    structured = extract_structured(response)

    if json_mode:
        print(json.dumps(structured))
        return structured

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    print_kv(structured)
    return structured


def compile_call(tool: str, *, json_mode: bool = False,
                 progress_msg: str = "",
                 extra_params: Optional[dict] = None) -> dict:
    """Standard pattern for compile-like commands that need polling."""
    project_dir = resolve_project_dir()
    port = resolve_port()
    params = {"projectDir": project_dir}
    if extra_params:
        params.update(extra_params)

    if progress_msg:
        print(progress_msg, file=sys.stderr)

    response = raw_call(port, tool, params)
    structured = extract_structured(response)
    structured = poll_compile(port, structured)

    if json_mode:
        print(json.dumps(structured))
        return structured

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    print_kv(structured)
    return structured


def has_json_flag(args: list[str]) -> tuple[bool, list[str]]:
    """Extract --json flag from args, return (is_json, remaining_args)."""
    if "--json" in args:
        remaining = [a for a in args if a != "--json"]
        return True, remaining
    return False, args


def normalize_args(args: list[str]) -> list[str]:
    """Normalize CLI flags: accept both --kebab-case and --camelCase.

    Converts any --kebab-case flag to --camelCase so that subcommand parsers
    only need to match camelCase names (which equal the MCP parameter names).
    Non-flag tokens and --json are passed through unchanged.

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
