"""cmd_record_start — start screen recording (with concurrent-lock guard)."""

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_record_start(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)

    if jugglib.record_session_exists():
        print("ERROR: A recording is already in progress. "
              "Run 'jugg record-stop' first.", file=sys.stderr)
        sys.exit(1)

    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    params = {"projectDir": project_dir}

    response = jugglib.raw_call(port, "start_record", params)
    structured = jugglib.extract_structured(response)

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    session_id = structured.get("data", {}).get("sessionId", "")
    jugglib.record_session_save(session_id)

    if json_mode:
        print(json.dumps(structured))
        return

    print("status: OK\nmessage: Recording started")
