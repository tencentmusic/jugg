"""Tests for subcommand argument parsing (tap, view-locate, view-inspect, etc.)."""

import json
import os
import sys
import unittest

# Ensure modules are importable
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts", "py"))


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


class ViewLocateBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_view_locate import build_params
        self.build = build_params

    def test_no_selector_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_text_selector(self):
        result = self.build(["--text", "Avatar"])
        self.assertEqual(result["target"]["text"], "Avatar")

    def test_id_selector(self):
        result = self.build(["--resourceId", "btn_login"])
        self.assertEqual(result["target"]["resourceId"], "btn_login")


class ViewInspectBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_view_inspect import build_params
        self.build = build_params

    def test_no_selector_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

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

    def test_reason_with_consent(self):
        result = self.build(["--reason", "investigating crash", "--consent"])
        self.assertEqual(result["reason"], "investigating crash")
        self.assertTrue(result["consent"])


class InstrumentBuildParamsTest(unittest.TestCase):

    def setUp(self):
        from cmd.cmd_instrument import build_params
        self.build = build_params

    def test_empty_args_fails(self):
        with self.assertRaises(SystemExit):
            self.build([])

    def test_source_class_method_runner(self):
        result = self.build([
            "--source-path", "library1/src/androidTest/kotlin/com/example/FooTest.kt",
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

    def test_e_pairs(self):
        result = self.build([
            "--source-path", "library1/src/androidTest/kotlin/com/example/FooTest.kt",
            "-e", "size=large",
            "-e", "clearPackageData=true",
        ])
        self.assertEqual(result["extras"]["size"], "large")
        self.assertEqual(result["extras"]["clearPackageData"], "true")

    def test_extras_semicolon_pairs(self):
        result = self.build([
            "--source-path", "library1/src/androidTest/kotlin/com/example/FooTest.kt",
            "--extras", "size=medium;clearPackageData=true",
        ])
        self.assertEqual(result["extras"]["size"], "medium")
        self.assertEqual(result["extras"]["clearPackageData"], "true")

    def test_missing_e_value_fails(self):
        with self.assertRaises(SystemExit):
            self.build(["-e"])


if __name__ == "__main__":
    unittest.main()
