"""cmd_instrument — run androidTest from a source file anchor."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def _append_extra(extra_map: dict, token: str) -> None:
    if "=" in token:
        key, value = token.split("=", 1)
        key = key.strip()
        value = value.strip()
    else:
        key = token.strip()
        value = ""
    if not key:
        print("Invalid extras pair: key is empty", file=sys.stderr)
        sys.exit(1)
    extra_map[key] = value


def build_params(args: list[str]) -> dict:
    params: dict = {}
    extras: dict = {}
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--sourcePath":
            if i + 1 >= len(args):
                print(f"{arg} requires a value", file=sys.stderr)
                sys.exit(1)
            params["sourcePath"] = args[i + 1]
            i += 2
        elif arg == "--class":
            if i + 1 >= len(args):
                print(f"{arg} requires a value", file=sys.stderr)
                sys.exit(1)
            params["class"] = args[i + 1]
            i += 2
        elif arg == "--method":
            if i + 1 >= len(args):
                print(f"{arg} requires a value", file=sys.stderr)
                sys.exit(1)
            params["method"] = args[i + 1]
            i += 2
        elif arg in ("--package", "--testPackage", "--testsRegex", "--regex"):
            print(f"{arg} is not supported. Use --source-path with optional --class/--method.", file=sys.stderr)
            sys.exit(1)
        elif arg in ("--clazz", "--instrumentationRunner", "-e", "--e"):
            print(f"{arg} is not supported. Use --class/--runner/--extras.", file=sys.stderr)
            sys.exit(1)
        elif arg == "--runner":
            if i + 1 >= len(args):
                print(f"{arg} requires a value", file=sys.stderr)
                sys.exit(1)
            params["runner"] = args[i + 1]
            i += 2
        elif arg == "--extras":
            if i + 1 >= len(args):
                print("--extras requires a key=value;key2=value2 list", file=sys.stderr)
                sys.exit(1)
            pairs = [p.strip() for p in args[i + 1].split(";") if p.strip()]
            for pair in pairs:
                _append_extra(extras, pair)
            i += 2
        else:
            print(f"Unknown option: {arg}", file=sys.stderr)
            sys.exit(1)

    if extras:
        params["extras"] = extras
    if not params.get("sourcePath"):
        print("--source-path is required", file=sys.stderr)
        sys.exit(1)
    return params


def cmd_instrument(args: list[str]) -> None:
    remaining = jugglib.normalize_args(args)
    extra = build_params(remaining)
    jugglib.compile_call("instrument", progress_msg="Running instrument", extra_params=extra or None)
