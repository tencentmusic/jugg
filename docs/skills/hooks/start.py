#!/usr/bin/env python3
"""Start hook: record that an agent session has started."""

from __future__ import annotations

import sys
from argparse import ArgumentParser
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hook_common import debug_log


def _debug_log(message: str) -> None:
    debug_log("JUGG-START", message)


def _parse_args():
    parser = ArgumentParser(description="Jugg start hook.")
    parser.add_argument("--client", default="", help="Agent client name passed by hook installer.")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    client_part = f" client={args.client}" if args.client else ""
    _debug_log(f"hook triggered cwd={Path.cwd()}{client_part}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
