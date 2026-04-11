"""cmd_restart — restart app."""

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_restart(args: list[str]) -> None:
    json_mode = False
    i = 0
    while i < len(args):
        if args[i] == "--json":
            json_mode = True
            i += 1
        elif args[i] == "--help":
            print("Usage: jugg restart")
            return
        else:
            print(f"Unknown option: {args[i]}", file=sys.stderr)
            sys.exit(1)

    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()

    params = {"projectDir": project_dir}

    response = jugglib.raw_call(port, "restart_app", params)
    structured = jugglib.extract_structured(response)

    if json_mode:
        print(json.dumps(structured))
        return

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    jugglib.print_kv(structured)
