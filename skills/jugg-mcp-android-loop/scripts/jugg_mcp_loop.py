#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

PORT_START = 12320
PORT_END = 12329


def post_json(
    url: str,
    payload: Dict[str, Any],
    headers: Optional[Dict[str, str]] = None,
    timeout_sec: int = 300,
) -> Tuple[int, Optional[Dict[str, Any]], str]:
    data = json.dumps(payload).encode("utf-8")
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    req = urllib.request.Request(url=url, data=data, headers=request_headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as response:
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
    except urllib.error.URLError as e:
        return 0, None, str(e)


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
    def __init__(self, endpoint: str, timeout_sec: int = 300):
        self.endpoint = endpoint
        self.timeout_sec = timeout_sec
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
        status, parsed, body = post_json(
            self.endpoint,
            payload,
            headers={"MCP-Protocol-Version": "2025-06-18"},
            timeout_sec=self.timeout_sec,
        )
        ok = status == 200 and isinstance(parsed, dict) and parsed.get("error") is None
        self._record_step("initialize", ok, "ok" if ok else f"failed: {body}")
        if not ok:
            return False

        notify_payload = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {}
        }
        n_status, _, n_body = post_json(self.endpoint, notify_payload, timeout_sec=self.timeout_sec)
        n_ok = n_status in (200, 202)
        self._record_step("notifications/initialized", n_ok, "ok" if n_ok else f"failed: {n_body}")
        return n_ok

    def tools_list(self) -> bool:
        status, parsed, body = post_json(self.endpoint, {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/list",
            "params": {}
        }, timeout_sec=self.timeout_sec)
        ok = status == 200 and isinstance(parsed, dict) and parsed.get("error") is None
        self._record_step("tools/list", ok, "ok" if ok else f"failed: {body}")
        return ok

    def tools_call(
        self,
        name: str,
        arguments: Dict[str, Any],
        timeout_sec: Optional[int] = None,
    ) -> Tuple[bool, str, Dict[str, Any], List[str]]:
        status, parsed, body = post_json(
            self.endpoint,
            {
                "jsonrpc": "2.0",
                "id": self._next_id(),
                "method": "tools/call",
                "params": {
                    "name": name,
                    "arguments": arguments,
                }
            },
            timeout_sec=timeout_sec or self.timeout_sec,
        )
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

    def tools_call_required(
        self,
        name: str,
        arguments: Dict[str, Any],
        timeout_sec: Optional[int] = None,
    ) -> Tuple[bool, str, Dict[str, Any], List[str]]:
        ok, msg, structured, artifacts = self.tools_call(name, arguments, timeout_sec=timeout_sec)
        self._record_step(name, ok, msg, {"artifacts": artifacts} if artifacts else None)
        return ok, msg, structured, artifacts


def _record_timeout(args: argparse.Namespace) -> int:
    return max(args.timeout_sec, args.record_duration + args.record_timeout_buffer_sec)


def run_runtime_actions(runner: McpRunner, args: argparse.Namespace) -> bool:
    app_start_args: Dict[str, Any] = {"projectDir": args.project_dir, "activity": args.start_activity}
    if args.serial:
        app_start_args["serial"] = args.serial
    ok, _, _, _ = runner.tools_call_required("app_start", app_start_args)
    if not ok:
        return False

    for idx in range(max(1, args.tap_repeat)):
        tap_args: Dict[str, Any] = {"projectDir": args.project_dir, "x": args.tap_x, "y": args.tap_y}
        if args.serial:
            tap_args["serial"] = args.serial
        ok, msg, _, _ = runner.tools_call("tap", tap_args)
        step_name = "tap" if args.tap_repeat == 1 else f"tap#{idx + 1}"
        runner._record_step(step_name, ok, msg)
        if not ok:
            return False

    return True


def run_record_with_actions(runner: McpRunner, args: argparse.Namespace) -> Tuple[bool, List[str]]:
    record_args: Dict[str, Any] = {
        "projectDir": args.project_dir,
        "durationSec": args.record_duration,
        "activity": args.start_activity,
        "tapX": args.tap_x,
        "tapY": args.tap_y,
        "preTapDelaySec": args.pre_tap_delay_sec,
        "tapRepeat": args.tap_repeat,
        "tapIntervalSec": args.tap_interval_sec,
        "recordStartDelaySec": args.record_start_delay_sec,
    }
    if args.serial:
        record_args["serial"] = args.serial

    ok, msg, _, artifacts = runner.tools_call(
        "record",
        record_args,
        timeout_sec=_record_timeout(args),
    )
    runner._record_step("record(flow)", ok, msg, {"artifacts": artifacts})
    if ok:
        return True, artifacts

    retry_ok, retry_msg, _, retry_artifacts = runner.tools_call(
        "record",
        record_args,
        timeout_sec=_record_timeout(args),
    )
    runner._record_step("record(flow_retry)", retry_ok, retry_msg, {"artifacts": retry_artifacts})
    return retry_ok, retry_artifacts


def run_loop(args: argparse.Namespace) -> Dict[str, Any]:
    endpoint = probe_endpoint(args.port)
    runner = McpRunner(endpoint, timeout_sec=args.timeout_sec)
    runner.summary["mode"] = args.mode

    if not runner.initialize():
        return runner.summary

    if not runner.tools_list():
        return runner.summary

    ok, _, _, _ = runner.tools_call_required("list_projects", {"projectDir": args.project_dir})
    if not ok:
        return runner.summary

    ok, msg, data, _ = runner.tools_call("device_list", {"projectDir": args.project_dir})
    runner._record_step(
        "device_list",
        ok,
        msg,
        {"devices": data.get("data", {}).get("devices") if isinstance(data.get("data"), dict) else data.get("devices")},
    )
    if not ok:
        return runner.summary

    build_ok = True
    if args.mode == "clean_reinstall":
        ok, _, _, _ = runner.tools_call_required("clean_reinstall", {"projectDir": args.project_dir})
        build_ok = ok
    else:
        ok_compile, _, _, _ = runner.tools_call_required("compile", {"projectDir": args.project_dir})
        ok_deploy, _, _, _ = runner.tools_call_required("deploy", {"projectDir": args.project_dir})

        build_ok = ok_compile and ok_deploy
        if (not build_ok) and args.fallback_clean_reinstall:
            ok_clean, msg_clean, _, _ = runner.tools_call("clean_reinstall", {"projectDir": args.project_dir})
            runner._record_step("clean_reinstall(fallback)", ok_clean, msg_clean)
            build_ok = ok_clean

    if not build_ok:
        return runner.summary

    all_artifacts: List[str] = []
    artifact_ok = True

    if args.with_record:
        ok_record, record_artifacts = run_record_with_actions(runner, args)
        artifact_ok = artifact_ok and ok_record
        all_artifacts.extend(record_artifacts)
    else:
        runtime_ok = run_runtime_actions(runner, args)
        if not runtime_ok:
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
    parser.add_argument("--with-record", action="store_true", help="Record with in-record app_start/tap actions")
    parser.add_argument("--record-duration", type=int, default=10, help="Record duration seconds (1..180)")
    parser.add_argument("--record-start-delay-sec", type=float, default=0.8, help="Delay after record starts before app_start")
    parser.add_argument("--record-timeout-buffer-sec", type=int, default=120, help="Extra timeout buffer for record call")
    parser.add_argument("--timeout-sec", type=int, default=300, help="HTTP timeout seconds per MCP call")
    parser.add_argument("--start-activity", default=".MainActivity", help="Activity for MCP app_start or record(flow)")
    parser.add_argument("--tap-x", type=int, default=540, help="Tap x for MCP tap or record(flow)")
    parser.add_argument("--tap-y", type=int, default=530, help="Tap y for MCP tap or record(flow)")
    parser.add_argument("--pre-tap-delay-sec", type=float, default=2.0, help="Delay after app_start before first tap")
    parser.add_argument("--tap-repeat", type=int, default=2, help="How many taps to perform")
    parser.add_argument("--tap-interval-sec", type=float, default=1.5, help="Delay between repeated taps")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_dir = Path(args.project_dir)
    if not project_dir.exists() or not project_dir.is_dir():
        print(json.dumps({"ok": False, "error": f"projectDir not exists: {args.project_dir}"}, ensure_ascii=False))
        return 2

    args.record_duration = max(1, min(180, args.record_duration))
    args.record_timeout_buffer_sec = max(30, args.record_timeout_buffer_sec)
    args.tap_repeat = max(1, args.tap_repeat)

    try:
        summary = run_loop(args)
    except Exception as e:
        summary = {"ok": False, "error": str(e), "steps": []}

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
