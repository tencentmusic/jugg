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

USAGE = """\
Usage: jugg [--console=plain|rich|json] <subcommand> [options]

Global options:
  --console=rich      Enable progress spinner; human-readable output (default for shell wrapper)
  --console=plain     No spinner; plain text output (default for direct python3 calls)
  --console=json      Structured JSON output; implies no spinner

Subcommands:
  version             Show CLI version and plugin version from all initialized projects
  compile             Compile modified sources (no deploy)
  deploy              Compile and deploy to device (waits for completion)
  gradle-build        Force Gradle build (waits for completion)
  clean-reinstall     Clear app data and reinstall APK
  restart             Restart the app
  instrument          Run androidTest with am instrument-like arguments
  status              Show current deploy state and uncompiled file summary
  layout-dump         Export UI hierarchy to HTML file
  view-locate         Find a UI element's position and bounds
  view-inspect        Evaluate getter expressions on a View element
  tap                 Perform tap/long-press/swipe on device
  devices             List connected devices
  activity-stack      Show current Activity stack
  ssh-info            Request remote SSH troubleshooting info when enable remote compile
  wait-logs           Block until app log marker, crash, or timeout

Run 'jugg <subcommand> --help' for subcommand options.
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


def main() -> None:
    args = sys.argv[1:]

    import jugglib
    # Extract global --console=<value> flag before subcommand dispatch.
    console = "plain"
    remaining = []
    for a in args:
        if a.startswith("--console="):
            val = a[len("--console="):]
            if val not in _CONSOLE_VALUES:
                print(f"jugg: invalid --console value '{val}' (choose: plain, rich, json)",
                      file=sys.stderr)
                sys.exit(1)
            console = val
        else:
            remaining.append(a)
    args = remaining

    jugglib.spinner_enabled = (console == "rich")
    jugglib.json_mode = (console == "json")

    if not args or args[0] in ("--help", "-h", "help"):
        print(USAGE, file=sys.stderr)
        sys.exit(1)

    subcmd = args[0]
    args = args[1:]

    if subcmd not in COMMANDS:
        print(f"jugg: unknown subcommand '{subcmd}'", file=sys.stderr)
        print("Run 'jugg' for a list of available subcommands.", file=sys.stderr)
        sys.exit(1)

    module_name, func_name = COMMANDS[subcmd]

    # Import the subcommand module from the cmd/ package
    import importlib
    mod = importlib.import_module(f"cmd.{module_name}")
    func = getattr(mod, func_name)
    func(args)


if __name__ == "__main__":
    main()
