"""cmd_view_inspect — evaluate getter expressions on a View element."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def _require_value(args: list[str], index: int) -> str:
    if index + 1 >= len(args):
        print(f"{args[index]} requires a value", file=sys.stderr)
        sys.exit(1)
    return args[index + 1]


def build_params(args: list[str]) -> dict:
    """Parse view-inspect selectors and expressions."""
    target: dict = {}
    expressions: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--text":
            target["text"] = _require_value(args, i); i += 2
        elif args[i] == "--resourceId":
            target["resourceId"] = _require_value(args, i); i += 2
        elif args[i] == "--contentDesc":
            target["contentDesc"] = _require_value(args, i); i += 2
        elif args[i] == "--className":
            target["className"] = _require_value(args, i); i += 2
        elif args[i].startswith("--"):
            print(f"Unknown option: {args[i]}", file=sys.stderr)
            sys.exit(1)
        else:
            expressions.append(args[i]); i += 1

    if not target:
        print("view-inspect requires at least one selector: --text, --resourceId, or --contentDesc",
              file=sys.stderr)
        sys.exit(1)
    if not expressions:
        print("view-inspect requires at least one expression argument",
              file=sys.stderr)
        sys.exit(1)

    return {"target": target, "expressions": expressions}


def cmd_view_inspect(args: list[str]) -> None:
    remaining = jugglib.normalize_args(args)
    extra = build_params(remaining)
    jugglib.simple_call("view-inspect", extra_params=extra)
