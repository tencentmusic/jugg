"""cmd_devices — list connected Android devices."""

from __future__ import annotations

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_devices(args: list[str]) -> None:
    jugglib.simple_call("devices")
