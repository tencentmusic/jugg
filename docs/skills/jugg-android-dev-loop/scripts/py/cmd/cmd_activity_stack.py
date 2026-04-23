"""cmd_activity_stack — show current Activity stack."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_activity_stack(args: list[str]) -> None:
    jugglib.simple_call("activity-stack")
