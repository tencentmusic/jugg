#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.request
import urllib.error
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

PORT_START = 12320
PORT_END = 12329


def post_json(url: str, payload: Dict[str, Any], headers: Optional[Dict[str, str]] = None) -> Tuple[int, Optional[Dict[str, Any]], str]:
    data = json.dumps(payload).encode("utf-8")
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    req = urllib.request.Request(url=url, data=data, headers=request_headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            status = response.getcode()
            body = response.read().decode("utf-8") if response else ""
            parsed = json.loads(body) if body.strip() else None
            return status, parsed, body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else ""
        parsed = None
        try:
            parsed = json.loads(body) if body.strip() else None
        except Exception:
            pass
        return e.code, parsed, body


def probe_endpoint(port: Optional[int]) -> str:
    if port is not None:
        return f"http://localhost:{port}/mcp"

    for p in range(PORT_START, PORT_END + 1):
        url = f"http://localhost:{p}/mcp"
        status, parsed, _ = post_json(url, {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/list",
            "params": {}
        })
        if status == 200 and isinstance(parsed, dict) and parsed.get("jsonrpc") == "2.0":
            return url

    raise RuntimeError("No MCP endpoint found on ports 12320..12329")


class McpRunner:
    def __init__(self, endpoint: str):
        self.endpoint = endpoint
        self._id = 100
        self.summary: Dict[str, Any] = {
            "ok": False,
            "endpoint": endpoint,
            "mode": "",
            "steps": [],
            "artifacts": [],
        }

    def _next_id(self) -> int:
        self._id += 1
        return self._id

    def _record_step(self, name: str, ok: bool, message: str, extra: Optional[Dict[str, Any]] = None) -> None:
        item = {"name": name, "ok": ok, "message": message}
        if extra:
            item.update(extra)
        self.summary["steps"].append(item)

    def initialize(self) -> bool:
        payload = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "jugg-mcp-loop", "version": "1.0.0"}
            }
        }
        status, parsed, body = post_json(self.endpoint, payload, headers={"MCP-Protocol-Version": "2025-06-18"})
        ok = status == 200 and isinstance(parsed, dict) and parsed.get("error") is None
        self._record_step("initialize", ok, "ok" if ok else f"failed: {body}")
        if not ok:
            return False

        notify_payload = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {}
        }
        n_status, _, n_body = post_json(self.endpoint, notify_payload)
        n_ok = n_status in (200, 202)
        self._record_step("notifications/initialized", n_ok, "ok" if n_ok else f"failed: {n_body}")
        return n_ok

    def tools_list(self) -> bool:
        status, parsed, body = post_json(self.endpoint, {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/list",
            "params": {}
        })
        ok = status == 200 and isinstance(parsed, dict) and parsed.get("error") is None
        self._record_step("tools/list", ok, "ok" if ok else f"failed: {body}")
        return ok

    def tools_call(self, name: str, arguments: Dict[str, Any]) -> Tuple[bool, str, Dict[str, Any], List[str]]:
        status, parsed, body = post_json(self.endpoint, {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": arguments,
            }
        })
        if status != 200 or not isinstance(parsed, dict):
            return False, f"http={status}, body={body}", {}, []

        if parsed.get("error"):
            return False, f"jsonrpc_error={parsed['error']}", {}, []

        result = parsed.get("result") or {}
        structured = result.get("structuredContent") if isinstance(result, dict) else {}
        if not isinstance(structured, dict):
            structured = result if isinstance(result, dict) else {}

        is_ok = structured.get("status") == "OK"
        msg = str(structured.get("message") or "")
        artifacts = []
        for item in structured.get("artifacts") or []:
            if isinstance(item, dict) and item.get("path"):
                artifacts.append(str(item["path"]))

        return is_ok, msg, structured, artifacts


def run_loop(args: argparse.Namespace) -> Dict[str, Any]:
    endpoint = probe_endpoint(args.port)
    runner = McpRunner(endpoint)
    runner.summary["mode"] = args.mode

    if not runner.initialize():
        return runner.summary

    if not runner.tools_list():
        return runner.summary

    ok, msg, _, _ = runner.tools_call("list_projects", {"projectDir": args.project_dir})
    runner._record_step("list_projects", ok, msg)
    if not ok:
        return runner.summary

    ok, msg, data, _ = runner.tools_call("device_list", {"projectDir": args.project_dir})
    runner._record_step("device_list", ok, msg, {"devices": data.get("data", {}).get("devices") if isinstance(data.get("data"), dict) else data.get("devices")})
    if not ok:
        return runner.summary

    build_ok = True
    if args.mode == "clean_reinstall":
        ok, msg, _, _ = runner.tools_call("clean_reinstall", {"projectDir": args.project_dir})
        runner._record_step("clean_reinstall", ok, msg)
        build_ok = ok
    else:
        ok_compile, msg_compile, _, _ = runner.tools_call("compile", {"projectDir": args.project_dir})
        runner._record_step("compile", ok_compile, msg_compile)

        ok_deploy, msg_deploy, _, _ = runner.tools_call("deploy", {"projectDir": args.project_dir})
        runner._record_step("deploy", ok_deploy, msg_deploy)

        build_ok = ok_compile and ok_deploy
        if (not build_ok) and args.fallback_clean_reinstall:
            ok_clean, msg_clean, _, _ = runner.tools_call("clean_reinstall", {"projectDir": args.project_dir})
            runner._record_step("clean_reinstall(fallback)", ok_clean, msg_clean)
            build_ok = ok_clean

    if not build_ok:
        return runner.summary

    restart_args: Dict[str, Any] = {"projectDir": args.project_dir}
    if args.serial:
        restart_args["serial"] = args.serial
    ok, msg, _, _ = runner.tools_call("restart_app", restart_args)
    runner._record_step("restart_app", ok, msg)
    if not ok:
        return runner.summary

    artifact_calls: List[Tuple[str, Dict[str, Any]]] = []

    shot_args: Dict[str, Any] = {"projectDir": args.project_dir}
    if args.serial:
        shot_args["serial"] = args.serial
    artifact_calls.append(("screenshot", shot_args))

    dump_args: Dict[str, Any] = {"projectDir": args.project_dir}
    if args.serial:
        dump_args["serial"] = args.serial
    artifact_calls.append(("layout_dump", dump_args))

    if args.with_record:
        record_args: Dict[str, Any] = {"projectDir": args.project_dir, "durationSec": args.record_duration}
        if args.serial:
            record_args["serial"] = args.serial
        artifact_calls.append(("record", record_args))

    all_artifacts: List[str] = []
    artifact_ok = True
    for name, call_args in artifact_calls:
        ok, msg, _, artifacts = runner.tools_call(name, call_args)
        runner._record_step(name, ok, msg, {"artifacts": artifacts})
        if not ok:
            artifact_ok = False
        all_artifacts.extend(artifacts)

    runner.summary["artifacts"] = all_artifacts
    runner.summary["ok"] = build_ok and artifact_ok and len(all_artifacts) > 0
    return runner.summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Jugg MCP Android compile-verify closed loop")
    parser.add_argument("--project-dir", required=True, help="Absolute project directory path")
    parser.add_argument("--serial", default="", help="Optional device serial")
    parser.add_argument("--port", type=int, default=None, help="Optional MCP port")
    parser.add_argument("--mode", choices=["compile_deploy", "clean_reinstall"], default="compile_deploy")
    parser.add_argument("--fallback-clean-reinstall", action="store_true", help="Fallback to clean_reinstall when compile/deploy fails")
    parser.add_argument("--with-record", action="store_true", help="Also run record for verification")
    parser.add_argument("--record-duration", type=int, default=10, help="Record duration seconds (1..180)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_dir = Path(args.project_dir)
    if not project_dir.exists() or not project_dir.is_dir():
        print(json.dumps({"ok": False, "error": f"projectDir not exists: {args.project_dir}"}, ensure_ascii=False))
        return 2

    args.record_duration = max(1, min(180, args.record_duration))

    summary = run_loop(args)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
