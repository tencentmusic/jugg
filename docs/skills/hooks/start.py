#!/usr/bin/env python3
"""Start hook: record that an agent session has started."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import (
    LAST_WRITE_TIME_MS_KEY,
    SESSION_WRITE_FILE_NAMES_KEY,
    SESSION_WRITE_SEEN_KEY,
    debug_log,
    extract_session_id,
    read_hook_state,
    read_json_payload,
    state_file_path,
    write_hook_state,
)

TURN_STATE_KEYS = (
    SESSION_WRITE_SEEN_KEY,
    LAST_WRITE_TIME_MS_KEY,
    SESSION_WRITE_FILE_NAMES_KEY,
    "stopBlockCount",
    "gradleBlockCount",
    "gradleBlockedFingerprint",
)


def _debug_log(message: str) -> None:
    debug_log("JUGG-START", message)


def _parse_args():
    parser = ArgumentParser(description="Jugg start hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    client_part = f" client={args.client}" if args.client else ""
    cwd = str(Path.cwd())
    payload = read_json_payload()
    session_id = extract_session_id(payload)
    state_file = state_file_path(Path.home(), cwd, session_id)
    state = read_hook_state(state_file)
    removed_keys = [key for key in TURN_STATE_KEYS if key in state]
    for key in removed_keys:
        state.pop(key, None)
    if removed_keys:
        write_hook_state(state_file, state)
    _debug_log(
        f"hook triggered cwd={cwd}{client_part}; "
        f"clearedTurnStateKeys={removed_keys!r}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
