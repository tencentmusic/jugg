"""cmd_compile — compile modified sources without deploying."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_compile(args: list[str]) -> None:
    jugglib.compile_call("compile")
