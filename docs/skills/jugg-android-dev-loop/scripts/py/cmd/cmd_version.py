"""cmd_version — show CLI version and plugin version from all initialized projects."""

from __future__ import annotations

import json
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib

CLI_VERSION = "1.0.14"


def cmd_version(args: list[str]) -> None:
    port = jugglib.resolve_port()
    response = jugglib.raw_call(port, "version", {})
    structured = jugglib.extract_structured(response)

    if jugglib.json_mode:
        print(json.dumps({"cliVersion": CLI_VERSION, "plugin": structured}))
        return

    plugin_status = structured.get("status", "")
    print(f"cli version: {CLI_VERSION}")

    if plugin_status != "OK":
        msg = structured.get("message", "Unknown error")
        print(f"plugin version: ERROR ({msg})", file=sys.stderr)
        sys.exit(1)

    data = structured.get("data", {})
    plugin_version = data.get("pluginVersion", "unknown")
    print(f"plugin version: {plugin_version}")

    projects: dict | None = data.get("projects")
    if projects:
        print("  (versions differ across projects)")
        for project_dir, version in sorted(projects.items()):
            print(f"  {project_dir}: {version}")
