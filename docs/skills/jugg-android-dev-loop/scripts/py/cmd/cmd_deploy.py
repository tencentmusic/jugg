"""cmd_deploy — compile and deploy to device, poll until completion."""

from __future__ import annotations

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    params = {}
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--alwaysRestartApp":
            if i + 1 >= len(args):
                print("--alwaysRestartApp requires a value (true|false)", file=sys.stderr)
                sys.exit(1)
            val = args[i + 1].lower()
            if val not in ("true", "false"):
                print("--alwaysRestartApp value must be true or false", file=sys.stderr)
                sys.exit(1)
            params["alwaysRestartApp"] = val == "true"
            i += 2
        else:
            print(f"Unknown option: {arg}", file=sys.stderr)
            sys.exit(1)
    return params


def cmd_deploy(args: list[str]) -> None:
    remaining = jugglib.normalize_args(args)
    extra = build_params(remaining)
    jugglib.compile_call("deploy", progress_msg="Deploying", extra_params=extra or None)
