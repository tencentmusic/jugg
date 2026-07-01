"""cmd_layout_dump — export UI hierarchy to HTML file."""

from __future__ import annotations

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    """Parse layout-dump specific flags."""
    params: dict = {}
    i = 0
    while i < len(args):
        if args[i] == "--rootLayout":
            if i + 1 >= len(args):
                print("--rootLayout requires a value", file=sys.stderr)
                sys.exit(1)
            params["rootLayout"] = args[i + 1]
            i += 2
        elif args[i] == "--includeGone":
            params["includeGone"] = True
            i += 1
        elif args[i] == "--allWindows":
            params["allWindows"] = True
            i += 1
        else:
            print(f"Unknown option: {args[i]}", file=sys.stderr)
            sys.exit(1)
    return params


def cmd_layout_dump(args: list[str]) -> None:
    remaining = jugglib.normalize_args(args)
    extra = build_params(remaining)
    jugglib.simple_call("layout-dump", extra_params=extra)
