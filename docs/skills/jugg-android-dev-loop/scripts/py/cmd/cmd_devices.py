"""cmd_devices — list connected Android devices."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_devices(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)
    jugglib.simple_call("devices", json_mode=json_mode)
