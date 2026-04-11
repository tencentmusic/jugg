"""cmd_screenshot — capture a device screenshot."""

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_screenshot(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)

    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    params = {"projectDir": project_dir}

    response = jugglib.raw_call(port, "screenshot", params)
    structured = jugglib.extract_structured(response)

    if json_mode:
        print(json.dumps(structured))
        return

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    file_path = structured.get("data", {}).get("file", "")
    print(f"status: OK\nfile: {file_path}")
