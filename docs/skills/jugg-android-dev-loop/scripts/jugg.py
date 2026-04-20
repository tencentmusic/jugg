#!/usr/bin/env python3
"""jugg — Jugg CLI main entry point (cross-platform Python version).

Dispatches to subcommand handlers that wrap Jugg MCP tools.
"""

import sys
import os

# Ensure the py/ subdirectory is on the path so jugglib and cmd can be imported
# Use realpath to resolve symlinks so the py/ directory is found correctly
# when this script is invoked via a symlink (e.g. ~/.local/bin/jugg -> ~/.jugg/bin/jugg.py)
SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, "py"))

USAGE = """\
Usage: jugg <subcommand> [options]

Subcommands:
  compile             Compile modified sources (no deploy)
  deploy              Compile and deploy to device (waits for completion)
  gradle-build        Force Gradle build (waits for completion)
  clean-reinstall     Clear app data and reinstall APK
  restart             Restart the app
  status              Show current deploy state and uncompiled file summary
  layout-dump         Export UI hierarchy to HTML file
  view-locate         Find a UI element's position and bounds
  view-inspect        Evaluate getter expressions on a View element
  tap                 Perform tap/long-press/swipe on device
  devices             List connected devices
  activity-stack      Show current Activity stack
  crash-report        Collect latest app crash report
  ssh-info            Request remote SSH troubleshooting info when enable remote compile

Run 'jugg <subcommand> --help' for subcommand options.
"""

# Lazy-import map: subcommand -> (module_name, function_name)
COMMANDS = {
    "crash-report":   ("cmd_crash_report",   "cmd_crash_report"),
    "compile":        ("cmd_compile",        "cmd_compile"),
    "deploy":         ("cmd_deploy",         "cmd_deploy"),
    "gradle-build":   ("cmd_gradle_build",   "cmd_gradle_build"),
    "clean-reinstall": ("cmd_clean_reinstall", "cmd_clean_reinstall"),
    "restart":        ("cmd_restart",        "cmd_restart"),
    "status":         ("cmd_status",         "cmd_status"),
    "layout-dump":    ("cmd_layout_dump",    "cmd_layout_dump"),
    "view-locate":    ("cmd_view_locate",    "cmd_view_locate"),
    "view-inspect":   ("cmd_view_inspect",   "cmd_view_inspect"),
    "tap":            ("cmd_tap",            "cmd_tap"),
    "devices":        ("cmd_devices",        "cmd_devices"),
    "activity-stack": ("cmd_activity_stack", "cmd_activity_stack"),
    "ssh-info":       ("cmd_ssh_info",       "cmd_ssh_info"),
}


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("--help", "-h", "help"):
        print(USAGE, file=sys.stderr)
        sys.exit(1)

    subcmd = sys.argv[1]
    args = sys.argv[2:]

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
