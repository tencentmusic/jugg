"""cmd_restart — restart app."""

from __future__ import annotations

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_restart(args: list[str]) -> None:
    for arg in args:
        if arg == "--help":
            print("Usage: jugg restart")
            return
        print(f"Unknown option: {arg}", file=sys.stderr)
        sys.exit(1)

    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    response = jugglib.raw_call(port, "restart", {"projectDir": project_dir})
    structured = jugglib.extract_structured(response)

    if jugglib.json_mode:
        print(json.dumps(structured))
        return

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    jugglib.print_kv(structured)
