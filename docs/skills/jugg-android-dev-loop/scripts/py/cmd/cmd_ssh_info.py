"""cmd_ssh_info — request remote SSH troubleshooting info when enable remote compile."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def build_params(args: list[str]) -> dict:
    """Parse ssh-info flags."""
    reason = ""
    i = 0
    while i < len(args):
        if args[i] == "--reason":
            if i + 1 >= len(args):
                print("--reason requires a value", file=sys.stderr)
                sys.exit(1)
            reason = args[i + 1]
            i += 2
        else:
            print(f"Unknown option: {args[i]}", file=sys.stderr)
            sys.exit(1)

    if not reason:
        print("ssh-info requires --reason <reason>", file=sys.stderr)
        sys.exit(1)

    return {"reason": reason}


def cmd_ssh_info(args: list[str]) -> None:
    json_mode, remaining = jugglib.has_json_flag(args)
    remaining = jugglib.normalize_args(remaining)
    extra = build_params(remaining)
    jugglib.simple_call("ssh-info", json_mode=json_mode,
                        extra_params=extra)
