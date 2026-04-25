"""Tests for cmd_status output formatting."""

import io
import os
import sys
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts", "py"))

from cmd.cmd_status import _print_status


class CmdStatusPrintTest(unittest.TestCase):
    def test_print_status_includes_last_modified_fields(self):
        structured = {
            "data": {
                "hasDevice": True,
                "needFallback": False,
                "stateMessage": "ready",
                "fileCounts": {"total": 1, "Java": 1},
                "files": ["/tmp/A.java"],
                "detail": "",
                "lastFileModifiedTime": "2026-04-25 10:30:00",
            }
        }
        buf = io.StringIO()
        with redirect_stdout(buf):
            _print_status(structured)
        output = buf.getvalue()
        self.assertIn("lastFileModifiedTime: 2026-04-25 10:30:00", output)


if __name__ == "__main__":
    unittest.main()
