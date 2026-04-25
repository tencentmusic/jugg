"""cmd_status — return current Jugg deploy state and uncompiled file summary."""

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_status(args: list[str]) -> None:
    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    response = jugglib.raw_call(port, "status", {"projectDir": project_dir})
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
    file_counts: dict = data.get("fileCounts", {})
    files: list = data.get("files", [])
    detail: str = data.get("detail", "")
    last_file_modified_time: str = data.get("lastFileModifiedTime", "")

    print(f"hasDevice: {str(has_device).lower()}")
    print(f"needFallback: {str(need_fallback).lower()}")
    if state_message:
        print(f"stateMessage: {state_message}")
    print(f"lastFileModifiedTime: {last_file_modified_time}")

    total = file_counts.get("total", 0)
    print(f"fileCounts: {json.dumps(file_counts)}")

    if total > 0:
        for path in files:
            print(f"  {path}")
        if detail:
            print(f"  ({detail})")
