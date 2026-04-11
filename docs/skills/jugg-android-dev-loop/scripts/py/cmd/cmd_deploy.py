"""cmd_deploy — compile and deploy to device, poll until completion."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_deploy(args: list[str]) -> None:
    json_mode, _ = jugglib.has_json_flag(args)
    jugglib.compile_call("deploy", json_mode=json_mode,
                         progress_msg="Deploying...")
