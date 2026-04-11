"""cmd_view_inspect — evaluate getter expressions on a View element."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    """Parse view-inspect selectors and expressions."""
    target: dict = {}
    expressions: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--text":
            target["text"] = args[i + 1]; i += 2
        elif args[i] == "--id":
            target["resourceId"] = args[i + 1]; i += 2
        elif args[i] == "--desc":
            target["contentDesc"] = args[i + 1]; i += 2
        elif args[i] == "--class":
            target["className"] = args[i + 1]; i += 2
        elif args[i].startswith("--"):
            print(f"Unknown option: {args[i]}", file=sys.stderr)
            sys.exit(1)
        else:
            expressions.append(args[i]); i += 1

    if not target:
        print("view-inspect requires at least one selector: --text, --id, or --desc",
              file=sys.stderr)
        sys.exit(1)
    if not expressions:
        print("view-inspect requires at least one expression argument",
              file=sys.stderr)
        sys.exit(1)

    return {"target": target, "expressions": expressions}


def cmd_view_inspect(args: list[str]) -> None:
    json_mode, remaining = jugglib.has_json_flag(args)
    extra = build_params(remaining)
    jugglib.simple_call("eval_view", json_mode=json_mode, extra_params=extra)
