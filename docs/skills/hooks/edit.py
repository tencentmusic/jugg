#!/usr/bin/env python3
"""Edit hook: record that this agent session used a write-capable tool."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    debug_log,
    emit_cursor_empty_response,
    extract_session_id,
    mark_session_write_seen,
    payload_debug_suffix,
    read_hook_state,
    read_json_payload,
    remember_project_cwd,
    state_file_path,
    write_hook_state,
)


def _parse_args() -> Any:
    parser = ArgumentParser(description="Jugg edit hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    payload = read_json_payload()
    payload_suffix = payload_debug_suffix(payload)
    home = Path.home()
    cwd = str(Path.cwd())
    session_id = extract_session_id(payload)
    state_file = state_file_path(home, cwd, session_id)
    state = read_hook_state(state_file)
    project_cwd = cwd
    if args.client == "cursor":
        project_cwd, _ = remember_project_cwd(state, payload, cwd)
    mark_session_write_seen(state)
    write_hook_state(state_file, state)
    debug_log(
        "JUGG-EDIT",
        f"hook triggered cwd={cwd} projectCwd={project_cwd} client={args.client}{payload_suffix}; "
        "session write recorded",
    )
    emit_cursor_empty_response(args.client)
    return 0


if __name__ == "__main__":
    sys.exit(main())
