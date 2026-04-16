"""cmd_status — return current Jugg deploy state and uncompiled file summary."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_status(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)
    jugglib.simple_call("status", json_mode=json_mode)
