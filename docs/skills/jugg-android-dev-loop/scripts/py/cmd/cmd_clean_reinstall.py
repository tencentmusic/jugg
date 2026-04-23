"""cmd_clean_reinstall — clear app data and reinstall APK."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_clean_reinstall(args: list[str]) -> None:
    jugglib.simple_call("clean-reinstall")
