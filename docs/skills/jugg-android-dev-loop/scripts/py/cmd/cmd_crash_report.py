"""cmd_crash_report — collect latest app crash report."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_crash_report(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)
    jugglib.simple_call("crash_report", json_mode=json_mode)
