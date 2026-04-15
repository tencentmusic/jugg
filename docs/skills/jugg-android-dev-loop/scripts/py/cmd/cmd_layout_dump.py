"""cmd_layout_dump — export UI hierarchy to HTML file."""

import json
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
    json_mode, remaining = jugglib.has_json_flag(args)
    remaining = jugglib.normalize_args(remaining)
    extra = build_params(remaining)
    jugglib.simple_call("layout-dump", json_mode=json_mode, extra_params=extra)
