"""cmd_wait_logs — block until app log marker, crash, or timeout."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    params = {}
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--marker":
            if i + 1 >= len(args):
                print("--marker requires a value", file=sys.stderr)
                sys.exit(1)
            params["marker"] = args[i + 1]
            i += 2
        elif arg == "--tags":
            if i + 1 >= len(args):
                print("--tags requires a value (comma-separated)", file=sys.stderr)
                sys.exit(1)
            params["tags"] = [t.strip() for t in args[i + 1].split(",") if t.strip()]
            i += 2
        elif arg == "--timeoutMs":
            if i + 1 >= len(args):
                print("--timeoutMs requires a value", file=sys.stderr)
                sys.exit(1)
            try:
                params["timeoutMs"] = int(args[i + 1])
            except ValueError:
                print("--timeoutMs must be an integer", file=sys.stderr)
                sys.exit(1)
            i += 2
        else:
            print(f"Unknown option: {arg}", file=sys.stderr)
            sys.exit(1)
    return params


def cmd_wait_logs(args: list[str]) -> None:
    json_mode, remaining = jugglib.has_json_flag(args)
    remaining = jugglib.normalize_args(remaining)
    extra = build_params(remaining)
    jugglib.simple_call("wait-logs", json_mode=json_mode, extra_params=extra or None)
