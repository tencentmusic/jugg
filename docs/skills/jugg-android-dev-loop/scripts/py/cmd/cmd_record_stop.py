"""cmd_record_stop — stop screen recording and output mp4 path."""

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_record_stop(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)

    if not jugglib.record_session_exists():
        print("ERROR: No recording in progress. "
              "Run 'jugg record-start' first.", file=sys.stderr)
        sys.exit(1)

    session_id = jugglib.record_session_read()
    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    params = {"projectDir": project_dir, "sessionId": session_id}

    response = jugglib.raw_call(port, "record-stop", params)
    structured = jugglib.extract_structured(response)

    status = structured.get("status", "")
    if status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"status: ERROR\nmessage: {msg}", file=sys.stderr)
        sys.exit(1)

    jugglib.record_session_clear()

    if json_mode:
        print(json.dumps(structured))
        return

    file_path = structured.get("data", {}).get("file", "")
    print(f"status: OK\nfile: {file_path}")
