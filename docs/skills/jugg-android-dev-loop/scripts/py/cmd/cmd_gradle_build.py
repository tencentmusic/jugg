"""cmd_gradle_build — force Gradle build, poll until completion."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_gradle_build(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)
    jugglib.compile_call("force_gradle_compile", json_mode=json_mode,
                         progress_msg="Running Gradle build...")
