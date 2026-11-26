"""Tests for selected Runtime metadata in the version command."""

import contextlib
import io
import os
import sys
import unittest
from unittest.mock import patch

SCRIPTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts")
sys.path.insert(0, SCRIPTS_DIR)
sys.path.insert(0, os.path.join(SCRIPTS_DIR, "py"))

from cmd import cmd_version


class VersionCommandTest(unittest.TestCase):
    def test_plain_output_includes_runtime_metadata(self):
        structured = {
            "status": "OK",
            "data": {
                "pluginVersion": "3.2.2",
                "runtimeType": "standalone",
                "runtimeVersion": "3.2.2",
                "capabilities": ["version", "list-projects", "status"],
            },
        }
        output = io.StringIO()

        with patch.object(cmd_version.jugglib, "resolve_port", return_value=12321), \
             patch.object(cmd_version.jugglib, "raw_call", return_value={"result": {"structuredContent": structured}}), \
             contextlib.redirect_stdout(output):
            cmd_version.cmd_version([])

        text = output.getvalue()
        self.assertIn("runtime type: standalone", text)
        self.assertIn("runtime version: 3.2.2", text)
        self.assertIn("capabilities: version, list-projects, status", text)


if __name__ == "__main__":
    unittest.main()
