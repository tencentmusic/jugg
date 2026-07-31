"""cmd_status — return current Jugg deploy state and uncompiled file summary."""

from __future__ import annotations

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    params = {}
    i = 0
    while i < len(args):
        arg = args[i]
        if arg in ("--refreshChanges", "--fullInfo"):
            param_name = arg[2:]
            if i + 1 >= len(args):
                print(f"{arg} requires a value (true|false)", file=sys.stderr)
                sys.exit(1)
            val = args[i + 1].lower()
            if val not in ("true", "false"):
                print(f"{arg} value must be true or false", file=sys.stderr)
                sys.exit(1)
            params[param_name] = val == "true"
            i += 2
        else:
            print(f"Unknown option: {arg}", file=sys.stderr)
            sys.exit(1)
    return params


def cmd_status(args: list[str]) -> None:
    remaining = jugglib.normalize_args(args)
    extra = build_params(remaining)
    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    params = {"projectDir": project_dir}
    params.update(extra)
    response = jugglib.raw_call(port, "status", params)
    structured = jugglib.extract_structured(response)

    if jugglib.json_mode:
        print(json.dumps(structured))
        return

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    _print_status(structured)


def _print_status(structured: dict) -> None:
    data = structured.get("data", {})
    if not isinstance(data, dict):
        return

    has_device: bool = data.get("hasDevice", False)
    need_fallback: bool = data.get("needFallback", False)
    state_message: str = data.get("stateMessage", "")
    file_counts: dict = data.get("pendingModifiedFiles", {})
    files: list = data.get("files", [])
    detail: str = data.get("detail", "")
    last_file_modified_time: str = data.get("lastFileModifiedTime", "")
    last_compile_time: str = data.get("lastCompileTime", "")
    enabled_android_test: bool = data.get("enabledAndroidTest", False)
    has_been_full_compiled: bool = data.get("hasBeenFullCompiled", False)
    is_compiling: bool = data.get("isCompiling", False)

    print(f"hasDevice: {str(has_device).lower()}")
    print(f"needFallback: {str(need_fallback).lower()}")
    print(f"hasBeenFullCompiled: {str(has_been_full_compiled).lower()}")
    print(f"enabledAndroidTest: {str(enabled_android_test).lower()}")
    print(f"isCompiling: {str(is_compiling).lower()}")
    if state_message:
        print(f"stateMessage: {state_message}")
    print(f"lastFileModifiedTime: {last_file_modified_time}")
    print(f"lastCompileTime: {last_compile_time}")

    total = file_counts.get("total", 0)
    print(f"pendingModifiedFiles: {json.dumps(file_counts)}")

    if total > 0:
        for path in files:
            print(f"  {path}")
        if detail:
            print(f"  ({detail})")
