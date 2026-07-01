"""cmd_gradle_build — force Gradle build, poll until completion."""

from __future__ import annotations

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_gradle_build(args: list[str]) -> None:
    jugglib.compile_call("gradle-build", progress_msg="Running Gradle build")
