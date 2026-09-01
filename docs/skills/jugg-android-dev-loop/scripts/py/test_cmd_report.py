"""Tests for the two-phase report command."""

from __future__ import annotations

import os
import sys
import unittest
import io
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from cmd import cmd_report


class TestReportCommand(unittest.TestCase):
    """cmd_report must show the prepared archive before uploading it."""

    def setUp(self) -> None:
        self.prepared = {
            "status": "OK",
            "data": {
                "reportId": "a1b2c3d4",
                "filePath": "/tmp/a1b2c3d4.zip",
                "size": 1024,
                "sha256": "abc123",
                "uploadUrl": "https://jugg.sickworm.com/report_issue",
                "entries": [
                    {
                        "path": "diagnostics/environment.json",
                        "size": 128,
                        "sensitivity": "LOW",
                        "redaction": "completed",
                    },
                ],
            },
        }

    def _run(self, answer: str):
        upload = {"status": "OK", "data": {"reportId": "a1b2c3d4"}}
        responses = [
            {"result": {"structuredContent": self.prepared}},
            {"result": {"structuredContent": upload}},
        ]
        with \
            patch.object(cmd_report.jugglib, "resolve_project_dir", return_value="/proj"), \
            patch.object(cmd_report.jugglib, "resolve_port", return_value=12320), \
            patch.object(cmd_report.jugglib, "raw_call", side_effect=responses) as raw_call, \
            patch("builtins.input", return_value=answer):
            cmd_report.cmd_report([])
        return raw_call

    def test_declining_keeps_prepared_bundle_without_uploading(self):
        raw_call = self._run("n")

        self.assertEqual(1, raw_call.call_count)
        self.assertEqual("report-prepare", raw_call.call_args_list[0].args[1])

    def test_empty_confirmation_uploads_by_default(self):
        raw_call = self._run("")

        self.assertEqual(2, raw_call.call_count)
        self.assertEqual("report-upload", raw_call.call_args_list[1].args[1])

    def test_confirming_uploads_the_exact_prepared_bundle(self):
        raw_call = self._run("yes")

        self.assertEqual(2, raw_call.call_count)
        self.assertEqual(
            (12320, "report-upload", {
                "projectDir": "/proj",
                "reportId": "a1b2c3d4",
                "sha256": "abc123",
            }),
            raw_call.call_args_list[1].args,
        )

    def test_eof_keeps_prepared_bundle_without_uploading(self):
        with \
            patch.object(cmd_report.jugglib, "resolve_project_dir", return_value="/proj"), \
            patch.object(cmd_report.jugglib, "resolve_port", return_value=12320), \
            patch.object(
                cmd_report.jugglib,
                "raw_call",
                return_value={"result": {"structuredContent": self.prepared}},
            ) as raw_call, \
            patch("builtins.input", side_effect=EOFError):
            cmd_report.cmd_report([])

        self.assertEqual(1, raw_call.call_count)

    def test_keyboard_interrupt_keeps_prepared_bundle_without_uploading(self):
        with \
            patch.object(cmd_report.jugglib, "resolve_project_dir", return_value="/proj"), \
            patch.object(cmd_report.jugglib, "resolve_port", return_value=12320), \
            patch.object(
                cmd_report.jugglib,
                "raw_call",
                return_value={"result": {"structuredContent": self.prepared}},
            ) as raw_call, \
            patch("builtins.input", side_effect=KeyboardInterrupt):
            cmd_report.cmd_report([])

        self.assertEqual(1, raw_call.call_count)

    def test_json_mode_uses_the_same_confirmation_flow(self):
        upload = {"status": "OK", "data": {"reportId": "a1b2c3d4"}}
        with \
            patch.object(cmd_report.jugglib, "json_mode", True), \
            patch.object(cmd_report.jugglib, "resolve_project_dir", return_value="/proj"), \
            patch.object(cmd_report.jugglib, "resolve_port", return_value=12320), \
            patch.object(
                cmd_report.jugglib,
                "raw_call",
                side_effect=[
                    {"result": {"structuredContent": self.prepared}},
                    {"result": {"structuredContent": upload}},
                ],
            ) as raw_call, \
            patch("builtins.input", return_value=""), \
            patch("sys.stdout", new_callable=io.StringIO):
            cmd_report.cmd_report([])

        self.assertEqual(2, raw_call.call_count)

    def test_file_list_hides_metadata_and_orders_jugg_logs_first(self):
        prepared = dict(self.prepared)
        prepared["data"] = dict(self.prepared["data"])
        prepared["data"]["entries"] = [
            {"path": "diagnostics/environment.json", "size": 1, "sensitivity": "LOW", "redaction": "completed"},
            {"path": "diagnostics/logs/compile.log", "size": 2, "sensitivity": "MEDIUM", "redaction": "completed"},
            {"path": "diagnostics/manifest.json", "size": 3, "sensitivity": "LOW", "redaction": "completed"},
        ]
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            cmd_report._print_prepared(prepared)

        text = output.getvalue()
        self.assertLess(text.index("diagnostics/logs/compile.log"), text.index("diagnostics/environment.json"))
        self.assertLess(text.index("diagnostics/environment.json"), text.index("diagnostics/manifest.json"))
        self.assertNotIn("LOW", text)
        self.assertNotIn("MEDIUM", text)
        self.assertNotIn("redaction", text)


if __name__ == "__main__":
    unittest.main()
