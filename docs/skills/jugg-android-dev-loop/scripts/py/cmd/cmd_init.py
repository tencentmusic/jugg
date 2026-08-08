"""cmd_init — initialize the standalone Jugg run configuration."""

from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_init(args: list[str]) -> None:
    if args:
        print(f"Unknown option: {args[0]}", file=sys.stderr)
        sys.exit(1)
    if jugglib.runtime_type_override == "idea":
        print("jugg init is only available in the standalone Runtime.", file=sys.stderr)
        sys.exit(1)
    jugglib.set_runtime_type_override("standalone")
    project_dir = jugglib.resolve_project_dir()
    response = jugglib.raw_call(jugglib.resolve_port(), "init", {"projectDir": project_dir})
    structured = jugglib.extract_structured(response)
    if jugglib.json_mode:
        print(json.dumps(structured))
        return
    if structured.get("status") != "OK":
        print(structured.get("message", "Jugg initialization failed."), file=sys.stderr)
        sys.exit(1)
    data = structured.get("data", {})
    print(structured.get("message", "Standalone project initialized successfully."))
    if isinstance(data, dict) and data.get("compileCommand"):
        print(f"compileCommand: {data['compileCommand']}")
