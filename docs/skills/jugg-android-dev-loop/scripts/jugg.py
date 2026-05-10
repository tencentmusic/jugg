#!/usr/bin/env python3
"""jugg — Jugg CLI main entry point (cross-platform Python version).

Dispatches to subcommand handlers that wrap Jugg MCP tools.
"""

import sys
import os

# Ensure the py/ subdirectory is on the path so jugglib and cmd can be imported
# Use realpath to resolve symlinks so the py/ directory is found correctly
# when this script is invoked via the wrapper symlink (e.g. ~/.local/bin/jugg -> ~/.jugg/bin/jugg)
SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, "py"))

from help_registry import COMMAND_HELP, format_command_help

USAGE_HEAD = """\
Usage: jugg [--console=plain|rich|json] [--project-dir <path>] <subcommand> [options]
       jugg help <subcommand>

Global options:
  --console=rich      Enable progress spinner; human-readable output (default for shell wrapper)
  --console=plain     No spinner; plain text output (default for direct python3 calls)
  --console=json      Structured JSON output; implies no spinner
  --project-dir PATH   Use this projectDir instead of resolving it from the current directory
"""

_CONSOLE_VALUES = ("plain", "rich", "json")

# Lazy-import map: subcommand -> (module_name, function_name)
COMMANDS = {
    "version":        ("cmd_version",        "cmd_version"),
    "compile":        ("cmd_compile",        "cmd_compile"),
    "deploy":         ("cmd_deploy",         "cmd_deploy"),
    "gradle-build":   ("cmd_gradle_build",   "cmd_gradle_build"),
    "clean-reinstall": ("cmd_clean_reinstall", "cmd_clean_reinstall"),
    "restart":        ("cmd_restart",        "cmd_restart"),
    "instrument":     ("cmd_instrument",     "cmd_instrument"),
    "status":         ("cmd_status",         "cmd_status"),
    "layout-dump":    ("cmd_layout_dump",    "cmd_layout_dump"),
    "view-locate":    ("cmd_view_locate",    "cmd_view_locate"),
    "view-inspect":   ("cmd_view_inspect",   "cmd_view_inspect"),
    "tap":            ("cmd_tap",            "cmd_tap"),
    "devices":        ("cmd_devices",        "cmd_devices"),
    "activity-stack": ("cmd_activity_stack", "cmd_activity_stack"),
    "ssh-info":       ("cmd_ssh_info",       "cmd_ssh_info"),
    "wait-logs":      ("cmd_wait_logs",      "cmd_wait_logs"),
}


def top_usage() -> str:
    lines = [USAGE_HEAD, "Subcommands:"]
    for name in COMMANDS:
        lines.append(f"  {name:<18} {COMMAND_HELP[name].summary}")
    lines.extend([
        "",
        "Run 'jugg help <subcommand>' or 'jugg <subcommand> --help' for subcommand options.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    args = sys.argv[1:]

    import jugglib
    # Extract global flags before subcommand dispatch.
    console = "plain"
    project_dir = ""
    remaining = []
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("--console="):
            val = a[len("--console="):]
            if val not in _CONSOLE_VALUES:
                print(f"jugg: invalid --console value '{val}' (choose: plain, rich, json)",
                      file=sys.stderr)
                sys.exit(1)
            console = val
        elif a == "--project-dir":
            if i + 1 >= len(args):
                print("jugg: --project-dir requires a path", file=sys.stderr)
                sys.exit(1)
            project_dir = args[i + 1]
            i += 1
        elif a.startswith("--project-dir="):
            project_dir = a[len("--project-dir="):]
            if not project_dir:
                print("jugg: --project-dir requires a path", file=sys.stderr)
                sys.exit(1)
        else:
            remaining.append(a)
        i += 1
    args = remaining

    jugglib.spinner_enabled = (console == "rich")
    jugglib.json_mode = (console == "json")
    jugglib.set_project_dir_override(project_dir)

    if not args or args[0] in ("--help", "-h"):
        print(top_usage(), file=sys.stderr, end="")
        sys.exit(0)

    if args[0] == "help":
        if len(args) == 1:
            print(top_usage(), file=sys.stderr, end="")
            sys.exit(0)
        if len(args) > 2:
            print("jugg: help accepts at most one subcommand", file=sys.stderr)
            sys.exit(1)
        help_item = COMMAND_HELP.get(args[1])
        if help_item is None:
            print(f"jugg: unknown subcommand '{args[1]}'", file=sys.stderr)
            print("Run 'jugg help' for a list of available subcommands.", file=sys.stderr)
            sys.exit(1)
        print(format_command_help(help_item), file=sys.stderr, end="")
        sys.exit(0)

    subcmd = args[0]
    args = args[1:]

    if subcmd not in COMMANDS:
        print(f"jugg: unknown subcommand '{subcmd}'", file=sys.stderr)
        print("Run 'jugg' for a list of available subcommands.", file=sys.stderr)
        sys.exit(1)

    if "--help" in args or "-h" in args:
        print(format_command_help(COMMAND_HELP[subcmd]), file=sys.stderr, end="")
        sys.exit(0)

    module_name, func_name = COMMANDS[subcmd]

    # Import the subcommand module from the cmd/ package
    import importlib
    mod = importlib.import_module(f"cmd.{module_name}")
    func = getattr(mod, func_name)
    func(args)


if __name__ == "__main__":
    main()
