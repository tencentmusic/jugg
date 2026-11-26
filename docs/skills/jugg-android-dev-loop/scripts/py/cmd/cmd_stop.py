"""cmd_stop — stop all standalone Runtime processes."""

from __future__ import annotations

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_stop(args: list[str]) -> None:
    if args:
        print(f"Unknown option: {args[0]}", file=sys.stderr)
        sys.exit(1)
    if jugglib.runtime_type_override == "idea":
        print("jugg stop is only available for the standalone Runtime.", file=sys.stderr)
        sys.exit(1)

    launcher = jugglib._standalone_launcher_path()
    if not launcher.is_file():
        print(f"ERROR: Jugg standalone launcher not found: {launcher}", file=sys.stderr)
        print("       Install the standalone runtime or set JUGG_STANDALONE_LAUNCHER.", file=sys.stderr)
        sys.exit(1)

    command = [str(launcher), "--stop-all"]
    if sys.platform == "win32" and launcher.suffix.lower() in (".bat", ".cmd"):
        command = ["cmd", "/c", *command]
    result = subprocess.run(command, capture_output=True, text=True)
    message = _result_message(result)

    if jugglib.json_mode:
        print(json.dumps({
            "status": "OK" if result.returncode == 0 else "ERROR",
            "message": message,
            "data": {"scope": "all"},
        }))
    elif result.returncode == 0:
        print(message)
    else:
        if result.stdout:
            print(result.stdout, end="")
        if result.stderr:
            print(result.stderr, file=sys.stderr, end="")
    if result.returncode != 0:
        sys.exit(1)


def _result_message(result: subprocess.CompletedProcess[str]) -> str:
    if result.returncode != 0:
        return result.stderr.strip() or result.stdout.strip() or "Unable to stop Jugg standalone Runtimes."
    lines = (result.stdout or result.stderr).strip().splitlines()
    if lines:
        return lines[-1]
    return "All Jugg standalone Runtimes stopped."
