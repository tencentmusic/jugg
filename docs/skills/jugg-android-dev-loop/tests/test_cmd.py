"""Tests for subcommand argument parsing and local CLI control commands."""

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

# Ensure modules are importable
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts", "py"))


class StopCommandTest(unittest.TestCase):

    def setUp(self):
        from cmd import cmd_stop
        self.command = cmd_stop
        self.original_runtime_type = cmd_stop.jugglib.runtime_type_override
        self.original_json_mode = cmd_stop.jugglib.json_mode

    def tearDown(self):
        self.command.jugglib.runtime_type_override = self.original_runtime_type
        self.command.jugglib.json_mode = self.original_json_mode

    def test_invokes_launcher_without_resolving_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            launcher = Path(temp, "jugg-standalone")
            launcher.touch()
            completed = subprocess.CompletedProcess([], 0, "Stopped all Jugg standalone runtimes.\n", "")
            output = io.StringIO()

            with patch.object(self.command.jugglib, "_standalone_launcher_path", return_value=launcher), \
                 patch.object(self.command.jugglib, "resolve_port", side_effect=AssertionError("must not resolve Runtime")), \
                 patch.object(self.command.subprocess, "run", return_value=completed) as run, \
                 contextlib.redirect_stdout(output):
                self.command.cmd_stop([])

            self.assertEqual([str(launcher), "--stop-all"], run.call_args.args[0])
            self.assertIn("Stopped all Jugg standalone runtimes.", output.getvalue())

    def test_rejects_idea_runtime(self):
        self.command.jugglib.runtime_type_override = "idea"
        error = io.StringIO()

        with contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as raised:
            self.command.cmd_stop([])

        self.assertEqual(1, raised.exception.code)
        self.assertIn("only available for the standalone Runtime", error.getvalue())

    def test_launcher_failure_is_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            launcher = Path(temp, "jugg-standalone")
            launcher.touch()
            completed = subprocess.CompletedProcess([], 7, "", "Unable to stop runtime.\n")
            error = io.StringIO()

            with patch.object(self.command.jugglib, "_standalone_launcher_path", return_value=launcher), \
                 patch.object(self.command.subprocess, "run", return_value=completed), \
                 contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as raised:
                self.command.cmd_stop([])

            self.assertEqual(1, raised.exception.code)
            self.assertIn("Unable to stop runtime.", error.getvalue())

    def test_json_failure_prefers_bootstrap_error_over_launcher_output(self):
        with tempfile.TemporaryDirectory() as temp:
            launcher = Path(temp, "jugg-standalone")
            launcher.touch()
            completed = subprocess.CompletedProcess(
                [],
                1,
                "Jugg standalone max open files: 65536\n",
                "IllegalStateException: Failed to stop standalone Runtime processes: [123]\n",
            )
            output = io.StringIO()
            self.command.jugglib.json_mode = True

            with patch.object(self.command.jugglib, "_standalone_launcher_path", return_value=launcher), \
                 patch.object(self.command.subprocess, "run", return_value=completed), \
                 contextlib.redirect_stdout(output), self.assertRaises(SystemExit):
                self.command.cmd_stop([])

            result = json.loads(output.getvalue())
            self.assertEqual("ERROR", result["status"])
            self.assertIn("Failed to stop standalone Runtime processes", result["message"])
            self.assertNotIn("max open files", result["message"])


class TapBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_tap import build_params
        self.build = build_params

    def test_text_selector(self):
        result = self.build(["--text", "Login"])
        self.assertEqual(result["action"], "tap")
        self.assertEqual(result["text"], "Login")

    def test_coordinate_mode(self):
        result = self.build(["--x", "540", "--y", "960"])
        self.assertEqual(result["x"], 540.0)
        self.assertEqual(result["y"], 960.0)

    def test_percent_mode(self):
        result = self.build(["--xPercent", "50", "--yPercent", "80"])
        self.assertEqual(result["xPercent"], 50.0)
        self.assertEqual(result["yPercent"], 80.0)

    def test_swipe_without_end_coords_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--x", "540", "--y", "960", "--action", "swipe"])

    def test_swipe_with_full_coords(self):
        result = self.build([
            "--x", "540", "--y", "960",
            "--endX", "540", "--endY", "200",
            "--action", "swipe",
        ])
        self.assertEqual(result["action"], "swipe")
        self.assertEqual(result["endX"], 540.0)
        self.assertEqual(result["endY"], 200.0)

    def test_no_selector_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_missing_x_value_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--x"])


class ViewLocateBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_view_locate import build_params
        self.build = build_params

    def test_no_selector_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_missing_text_value_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--text"])

    def test_text_selector(self):
        result = self.build(["--text", "Avatar"])
        self.assertEqual(result["target"]["text"], "Avatar")

    def test_id_selector(self):
        result = self.build(["--resourceId", "btn_login"])
        self.assertEqual(result["target"]["resourceId"], "btn_login")

    def test_combined_selector_and_budget(self):
        result = self.build([
            "--text", "Avatar",
            "--resourceId", "avatar",
            "--className", "com.example.AvatarView",
            "--visibleOnly", "false",
            "--maxResults", "3",
        ])
        self.assertEqual(result["target"]["text"], "Avatar")
        self.assertEqual(result["target"]["resourceId"], "avatar")
        self.assertEqual(result["target"]["className"], "com.example.AvatarView")
        self.assertFalse(result["visibleOnly"])
        self.assertEqual(result["maxResults"], 3)

    def test_invalid_max_results_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--text", "Avatar", "--maxResults", "101"])


class ViewInspectBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_view_inspect import build_params
        self.build = build_params

    def test_no_selector_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_missing_text_value_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--text"])

    def test_no_expression_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--text", "Label"])

    def test_text_with_expressions(self):
        result = self.build(["--text", "Label", "getTextSize()", "isEnabled()"])
        self.assertEqual(result["target"]["text"], "Label")
        self.assertIn("getTextSize()", result["expressions"])
        self.assertIn("isEnabled()", result["expressions"])


class LayoutDumpBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_layout_dump import build_params
        self.build = build_params

    def test_no_flags(self):
        result = self.build([])
        self.assertEqual(result, {})

    def test_root_flag(self):
        result = self.build(["--rootLayout", "my_root_id"])
        self.assertEqual(result["rootLayout"], "my_root_id")

    def test_include_gone(self):
        result = self.build(["--includeGone"])
        self.assertTrue(result["includeGone"])

    def test_all_windows(self):
        result = self.build(["--allWindows"])
        self.assertTrue(result["allWindows"])


class SshInfoBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_ssh_info import build_params
        self.build = build_params

    def test_no_reason_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_reason(self):
        result = self.build(["--reason", "investigating crash"])
        self.assertEqual(result["reason"], "investigating crash")


class InstrumentBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_instrument import build_params
        self.build = build_params

    def test_empty_args_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_source_class_method_runner(self):
        result = self.build([
            "--sourcePath", "library1/src/androidTest/kotlin/com/example/FooTest.kt",
            "--class", "com.example.FooTest",
            "--method", "bar",
            "--runner", "androidx.test.runner.AndroidJUnitRunner",
        ])
        self.assertEqual(result["sourcePath"], "library1/src/androidTest/kotlin/com/example/FooTest.kt")
        self.assertEqual(result["class"], "com.example.FooTest")
        self.assertEqual(result["method"], "bar")
        self.assertEqual(result["runner"], "androidx.test.runner.AndroidJUnitRunner")

    def test_package_and_regex_are_rejected(self):
        with self.assertRaises(SystemExit):
            self.build(["--package", "com.example.pkg"])
        with self.assertRaises(SystemExit):
            self.build(["--testsRegex", "Login.*"])

    def test_legacy_aliases_are_rejected(self):
        with self.assertRaises(SystemExit):
            self.build(["--sourcePath", "FooTest.kt", "--clazz", "com.example.FooTest"])
        with self.assertRaises(SystemExit):
            self.build(["--sourcePath", "FooTest.kt", "--instrumentationRunner", "Runner"])
        with self.assertRaises(SystemExit):
            self.build(["--sourcePath", "FooTest.kt", "-e", "size=large"])
        with self.assertRaises(SystemExit):
            self.build(["--sourcePath", "FooTest.kt", "--e", "size=large"])

    def test_extras_semicolon_pairs(self):
        result = self.build([
            "--sourcePath", "library1/src/androidTest/kotlin/com/example/FooTest.kt",
            "--extras", "size=medium;clearPackageData=true",
        ])
        self.assertEqual(result["extras"]["size"], "medium")
        self.assertEqual(result["extras"]["clearPackageData"], "true")

    def test_missing_extras_value_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["--sourcePath", "FooTest.kt", "--extras"])


class WaitLogsBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_wait_logs import build_params
        self.build = build_params

    def test_no_marker_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_marker(self):
        result = self.build(["--marker", "DONE"])
        self.assertEqual(result["marker"], "DONE")


if __name__ == "__main__":
    unittest.main()
