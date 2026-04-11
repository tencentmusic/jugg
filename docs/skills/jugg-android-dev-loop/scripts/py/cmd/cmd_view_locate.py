"""cmd_view_locate — find a UI element's position and bounds."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    """Parse view-locate selectors."""
    target: dict = {}
    i = 0
    while i < len(args):
        if args[i] == "--text":
            target["text"] = args[i + 1]; i += 2
        elif args[i] == "--id":
            target["resourceId"] = args[i + 1]; i += 2
        elif args[i] == "--desc":
            target["contentDesc"] = args[i + 1]; i += 2
        else:
            print(f"Unknown option: {args[i]}", file=sys.stderr)
            sys.exit(1)

    if not target:
        print("view-locate requires at least one selector: --text, --id, or --desc",
              file=sys.stderr)
        sys.exit(1)

    return {"target": target}


def cmd_view_locate(args: list[str]) -> None:
    json_mode, remaining = jugglib.has_json_flag(args)
    extra = build_params(remaining)
    jugglib.simple_call("view-locate", json_mode=json_mode, extra_params=extra)
