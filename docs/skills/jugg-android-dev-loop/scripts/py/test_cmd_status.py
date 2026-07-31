"""Tests for status command argument forwarding."""

from __future__ import annotations

import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cmd import cmd_status


class TestStatusCommandParams(unittest.TestCase):
    """cmd_status should forward optional status arguments."""

    def _run_status(self, args: list[str]) -> dict:
        structured = {"status": "OK", "data": {"pendingModifiedFiles": {"total": 0}}}
        with \
            patch.object(cmd_status.jugglib, "resolve_project_dir", return_value="/proj"), \
            patch.object(cmd_status.jugglib, "resolve_port", return_value=12320), \
            patch.object(cmd_status.jugglib, "raw_call", return_value={"result": {"structuredContent": structured}}) as raw_call, \
            patch.object(cmd_status, "_print_status"):
            cmd_status.cmd_status(args)
        return raw_call.call_args[0][2]

    def test_status_does_not_send_refresh_changes_by_default(self):
        params = self._run_status([])

        self.assertEqual({"projectDir": "/proj"}, params)

    def test_status_sends_refresh_changes_when_true(self):
        params = self._run_status(["--refresh-changes", "true"])

        self.assertEqual({"projectDir": "/proj", "refreshChanges": True}, params)

    def test_status_sends_full_info_when_true(self):
        params = self._run_status(["--full-info", "true"])

        self.assertEqual({"projectDir": "/proj", "fullInfo": True}, params)


if __name__ == "__main__":
    unittest.main()
