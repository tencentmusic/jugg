"""cmd_reinstall — clear app data and reinstall APK."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_reinstall(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)
    jugglib.simple_call("clean_reinstall_apk", json_mode=json_mode)
