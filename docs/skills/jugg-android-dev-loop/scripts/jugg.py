#!/usr/bin/env python3
"""jugg — Jugg CLI main entry point (cross-platform Python version).

Dispatches to subcommand handlers that wrap Jugg MCP tools.
"""

import sys
import os

# Ensure the py/ subdirectory is on the path so jugglib and cmd can be imported
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, "py"))

USAGE = """\
Usage: jugg <subcommand> [options]

Subcommands:
  screenshot          Capture a device screenshot
  crash-report        Collect latest app crash report
  compile             Compile modified sources (no deploy)
  deploy              Compile and deploy to device (waits for completion)
  gradle-build        Force Gradle build (waits for completion)
  reinstall           Clear app data and reinstall APK
  restart             Restart the app
  record-start        Start screen recording
  record-stop         Stop screen recording and output mp4 path
  layout-dump         Export UI hierarchy to HTML file
  view-locate         Find a UI element's position and bounds
  view-inspect        Evaluate getter expressions on a View element
  tap                 Perform tap/long-press/swipe on device
  devices             List connected devices
  activity-stack      Show current Activity stack
  ssh-info            Request remote SSH troubleshooting info

Run 'jugg <subcommand> --help' for subcommand options.
"""

# Lazy-import map: subcommand -> (module_name, function_name)
COMMANDS = {
    "screenshot":     ("cmd_screenshot",     "cmd_screenshot"),
    "crash-report":   ("cmd_crash_report",   "cmd_crash_report"),
    "compile":        ("cmd_compile",        "cmd_compile"),
    "deploy":         ("cmd_deploy",         "cmd_deploy"),
    "gradle-build":   ("cmd_gradle_build",   "cmd_gradle_build"),
    "reinstall":      ("cmd_reinstall",      "cmd_reinstall"),
    "restart":        ("cmd_restart",        "cmd_restart"),
    "record-start":   ("cmd_record_start",   "cmd_record_start"),
    "record-stop":    ("cmd_record_stop",    "cmd_record_stop"),
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
