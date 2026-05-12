#!/usr/bin/env python3
"""Edit hook: intentionally no-op; change detection is status-based in command/stop hooks."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import debug_log, emit_cursor_empty_response, payload_debug_suffix, read_json_payload


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg edit hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    payload_suffix = payload_debug_suffix(payload)
    debug_log("JUGG-EDIT", f"hook triggered cwd={Path.cwd()} client={args.client}{payload_suffix}")
    emit_cursor_empty_response(args.client)
    return 0


if __name__ == "__main__":
    sys.exit(main())
