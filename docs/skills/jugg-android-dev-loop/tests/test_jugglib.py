"""Tests for jugglib — port cache, projectDir resolution, record session, JSON output."""

import contextlib
import io
import json
import os
import sys
import tempfile
import types
import urllib.error
import unittest
from unittest.mock import patch

# Ensure jugglib is importable
SCRIPTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts")
sys.path.insert(0, SCRIPTS_DIR)
sys.path.insert(0, os.path.join(SCRIPTS_DIR, "py"))
import jugglib


class PortCacheTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["JUGG_CACHE_DIR"] = self.tmp
        os.environ["JUGG_PORT_CACHE"] = os.path.join(self.tmp, "port")
        os.environ["JUGG_RECORD_SESSION"] = os.path.join(self.tmp, "record_session")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_read_port_cache_returns_empty_when_no_file(self):
        self.assertEqual(jugglib.read_port_cache(), "")

    def test_write_and_read_port_cache(self):
        jugglib.write_port_cache(12321)
        self.assertEqual(jugglib.read_port_cache(), "12321")

    def test_write_port_cache_overwrites(self):
        jugglib.write_port_cache(12321)
        jugglib.write_port_cache(12323)
        self.assertEqual(jugglib.read_port_cache(), "12323")


class ResolvePortTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["JUGG_CACHE_DIR"] = self.tmp
        os.environ["JUGG_PORT_CACHE"] = os.path.join(self.tmp, "port")
        os.environ["JUGG_RECORD_SESSION"] = os.path.join(self.tmp, "record_session")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_resolve_port_prints_each_port_error_without_retry_for_closed_ports(self):
        refused = urllib.error.URLError(ConnectionRefusedError(61, "Connection refused"))
        stderr = io.StringIO()

        with patch.object(jugglib.urllib.request, "urlopen", side_effect=refused) as mock_urlopen, \
             patch.object(jugglib.time, "sleep") as mock_sleep, \
             contextlib.redirect_stderr(stderr), \
             self.assertRaises(SystemExit):
            jugglib.resolve_port()

        self.assertEqual(mock_urlopen.call_count, 10)
        mock_sleep.assert_not_called()
        output = stderr.getvalue()
        self.assertIn("12320: connection refused", output)
        self.assertIn("12329: connection refused", output)

    def test_resolve_port_retries_after_timeout_and_uses_recovered_port(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self):
                return b'{"jsonrpc":"2.0","result":{}}'

        timeout = TimeoutError("timed out")
        side_effects = [timeout] * 10 + [FakeResponse()]

        with patch.object(jugglib.urllib.request, "urlopen", side_effect=side_effects) as mock_urlopen, \
             patch.object(jugglib.time, "sleep") as mock_sleep:
            port = jugglib.resolve_port()

        self.assertEqual(port, 12320)
        self.assertEqual(mock_urlopen.call_count, 11)
        mock_sleep.assert_called_once()


class ProjectDirMatchTest(unittest.TestCase):

    def test_exact_prefix_match(self):
        projects = ["/project/alpha", "/project/beta"]
        result = jugglib.match_project_dir("/project/alpha/src", projects)
        self.assertEqual(result, "/project/alpha")

    def test_longest_prefix_match(self):
        projects = ["/project", "/project/sub"]
        result = jugglib.match_project_dir("/project/sub/src", projects)
        self.assertEqual(result, "/project/sub")

    def test_no_match(self):
        projects = ["/project/alpha", "/project/beta"]
        result = jugglib.match_project_dir("/other/dir", projects)
        self.assertEqual(result, "")

    def test_single_project(self):
        result = jugglib.match_project_dir("/my/project/module/src", ["/my/project"])
        self.assertEqual(result, "/my/project")

    def test_slash_boundary_no_partial_segment(self):
        result = jugglib.match_project_dir("/project_extra/src", ["/project"])
        self.assertEqual(result, "")

    def test_windows_backslash_paths(self):
        projects = ["C:\\Users\\dev\\project"]
        result = jugglib.match_project_dir("C:\\Users\\dev\\project\\src", projects)
        self.assertEqual(result, "C:\\Users\\dev\\project")


class ProjectDirOverrideTest(unittest.TestCase):

    def tearDown(self):
        jugglib.set_project_dir_override("")

    def test_resolve_project_dir_preserves_unmatched_explicit_value(self):
        jugglib.set_project_dir_override("/manual/project")

        with patch.object(jugglib, "resolve_port") as mock_resolve_port, \
             patch.object(jugglib, "raw_call", return_value={
                 "result": {"structuredContent": {"data": {"projects": []}}}
             }) as mock_raw_call:
            result = jugglib.resolve_project_dir()

        self.assertEqual(result, "/manual/project")
        mock_resolve_port.assert_called_once()
        mock_raw_call.assert_called_once_with(mock_resolve_port.return_value, "list-projects", {})

    def test_resolve_project_dir_uses_parent_project_for_explicit_nested_project(self):
        jugglib.set_project_dir_override("/manual/project/nested-project")

        with patch.object(jugglib, "resolve_port", return_value=12320), \
             patch.object(jugglib, "raw_call", return_value={
                 "result": {"structuredContent": {"data": {"projects": [
                     {"projectDir": "/manual/project"}
                 ]}}}
             }):
            result = jugglib.resolve_project_dir()

        self.assertEqual(result, "/manual/project")


class JuggGlobalProjectDirTest(unittest.TestCase):

    def tearDown(self):
        jugglib.set_project_dir_override("")

    def test_project_dir_global_flag_sets_override_and_does_not_reach_subcommand(self):
        import jugg

        captured_args = []
        fake_module = types.SimpleNamespace(
            cmd_status=lambda args: captured_args.append(args)
        )

        with patch.object(sys, "argv", ["jugg.py", "--project-dir", "/manual/project", "status"]), \
             patch("importlib.import_module", return_value=fake_module):
            jugg.main()

        self.assertEqual(jugglib.project_dir_override, "/manual/project")
        self.assertEqual(captured_args, [[]])

    def test_project_dir_global_flag_accepts_equals_form(self):
        import jugg

        fake_module = types.SimpleNamespace(cmd_status=lambda args: None)

        with patch.object(sys, "argv", ["jugg.py", "--project-dir=/manual/project", "status"]), \
             patch("importlib.import_module", return_value=fake_module):
            jugg.main()

        self.assertEqual(jugglib.project_dir_override, "/manual/project")


class JuggHelpTest(unittest.TestCase):

    def _run_main(self, argv):
        import io
        from contextlib import redirect_stderr, redirect_stdout
        import jugg

        stdout = io.StringIO()
        stderr = io.StringIO()
        with patch.object(sys, "argv", argv), \
             redirect_stdout(stdout), \
             redirect_stderr(stderr):
            try:
                jugg.main()
            except SystemExit as exc:
                return exc.code, stdout.getvalue(), stderr.getvalue()
        return 0, stdout.getvalue(), stderr.getvalue()

    def test_top_level_help_exits_zero(self):
        code, _, stderr = self._run_main(["jugg.py", "--help"])

        self.assertEqual(code, 0)
        self.assertIn("Usage: jugg", stderr)
        self.assertIn("jugg help <subcommand>", stderr)

    def test_help_subcommand_prints_options_without_importing_command(self):
        with patch("importlib.import_module") as mock_import:
            code, _, stderr = self._run_main(["jugg.py", "help", "instrument"])

        self.assertEqual(code, 0)
        mock_import.assert_not_called()
        self.assertIn("Usage: jugg", stderr)
        self.assertIn("instrument --source-path", stderr)
        self.assertIn("--source-path", stderr)
        self.assertIn("MCP: sourcePath", stderr)

    def test_subcommand_help_is_side_effect_free(self):
        with patch("importlib.import_module") as mock_import:
            code, _, stderr = self._run_main(["jugg.py", "compile", "--help"])

        self.assertEqual(code, 0)
        mock_import.assert_not_called()
        self.assertIn("Usage: jugg", stderr)
        self.assertIn("compile", stderr)

    def test_help_registry_covers_all_commands(self):
        import jugg
        from help_registry import COMMAND_HELP

        self.assertEqual(set(COMMAND_HELP.keys()), set(jugg.COMMANDS.keys()))


class RecordSessionTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["JUGG_CACHE_DIR"] = self.tmp
        os.environ["JUGG_PORT_CACHE"] = os.path.join(self.tmp, "port")
        os.environ["JUGG_RECORD_SESSION"] = os.path.join(self.tmp, "record_session")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_session_not_exists_initially(self):
        self.assertFalse(jugglib.record_session_exists())

    def test_save_and_read_session(self):
        jugglib.record_session_save("sess-abc-123")
        self.assertTrue(jugglib.record_session_exists())
        self.assertEqual(jugglib.record_session_read(), "sess-abc-123")

    def test_clear_session(self):
        jugglib.record_session_save("sess-to-delete")
        jugglib.record_session_clear()
        self.assertFalse(jugglib.record_session_exists())


class PrintKvTest(unittest.TestCase):

    def _capture(self, structured):
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            jugglib.print_kv(structured)
        return buf.getvalue()

    def test_status_and_data(self):
        out = self._capture({"status": "OK", "data": {"file": "/tmp/shot.jpg"}})
        self.assertIn("status: OK", out)
        self.assertIn("file: /tmp/shot.jpg", out)

    def test_error_with_message(self):
        out = self._capture({"status": "ERROR", "message": "No device found"})
        self.assertIn("status: ERROR", out)
        self.assertIn("message: No device found", out)

    def test_nested_data_as_json(self):
        out = self._capture({"status": "OK", "data": {"items": [1, 2, 3]}})
        self.assertIn("items: [1, 2, 3]", out)


class NormalizeArgsTest(unittest.TestCase):
    """normalize_args converts kebab-case flags to camelCase; non-flags pass through."""

    def test_single_word_unchanged(self):
        self.assertEqual(jugglib.normalize_args(["--text"]), ["--text"])

    def test_kebab_to_camel(self):
        self.assertEqual(jugglib.normalize_args(["--include-gone"]), ["--includeGone"])
        self.assertEqual(jugglib.normalize_args(["--all-windows"]), ["--allWindows"])
        self.assertEqual(jugglib.normalize_args(["--resource-id"]), ["--resourceId"])
        self.assertEqual(jugglib.normalize_args(["--content-desc"]), ["--contentDesc"])
        self.assertEqual(jugglib.normalize_args(["--class-name"]), ["--className"])
        self.assertEqual(jugglib.normalize_args(["--x-percent"]), ["--xPercent"])
        self.assertEqual(jugglib.normalize_args(["--end-x-percent"]), ["--endXPercent"])
        self.assertEqual(jugglib.normalize_args(["--root-layout"]), ["--rootLayout"])

    def test_camel_unchanged(self):
        self.assertEqual(jugglib.normalize_args(["--includeGone"]), ["--includeGone"])
        self.assertEqual(jugglib.normalize_args(["--resourceId"]), ["--resourceId"])

    def test_non_flag_tokens_unchanged(self):
        self.assertEqual(jugglib.normalize_args(["btn_login", "value"]), ["btn_login", "value"])

    def test_mixed_args(self):
        result = jugglib.normalize_args(["--resource-id", "btn_login", "--include-gone"])
        self.assertEqual(result, ["--resourceId", "btn_login", "--includeGone"])

class NormalizeArgsRoundTripTest(unittest.TestCase):
    """Verify kebab-case input reaches the same MCP params as camelCase input."""

    def setUp(self):
        from cmd.cmd_tap import build_params as tap_build
        from cmd.cmd_layout_dump import build_params as dump_build
        from cmd.cmd_view_locate import build_params as locate_build
        from cmd.cmd_view_inspect import build_params as inspect_build
        from cmd.cmd_ssh_info import build_params as ssh_build
        self.tap = tap_build
        self.dump = dump_build
        self.locate = locate_build
        self.inspect = inspect_build
        self.ssh = ssh_build

    def _norm(self, args):
        return jugglib.normalize_args(args)

    def test_tap_kebab_resource_id(self):
        result = self.tap(self._norm(["--resource-id", "btn_ok"]))
        self.assertEqual(result["resourceId"], "btn_ok")

    def test_tap_kebab_content_desc(self):
        result = self.tap(self._norm(["--content-desc", "Close"]))
        self.assertEqual(result["contentDesc"], "Close")

    def test_tap_kebab_class_name(self):
        result = self.tap(self._norm(["--text", "Login", "--class-name", "Button"]))
        self.assertEqual(result["className"], "Button")

    def test_view_locate_kebab_selector_and_budget(self):
        result = self.locate(self._norm([
            "--class-name", "AvatarView",
            "--visible-only", "false",
            "--max-results", "5",
        ]))
        self.assertEqual(result["target"]["className"], "AvatarView")
        self.assertFalse(result["visibleOnly"])
        self.assertEqual(result["maxResults"], 5)

    def test_tap_kebab_x_percent(self):
        result = self.tap(self._norm(["--x-percent", "50", "--y-percent", "80"]))
        self.assertEqual(result["xPercent"], 50.0)
        self.assertEqual(result["yPercent"], 80.0)

    def test_tap_kebab_end_x_percent(self):
        result = self.tap(self._norm([
            "--x-percent", "50", "--y-percent", "50",
            "--end-x-percent", "50", "--end-y-percent", "20",
            "--action", "swipe",
        ]))
        self.assertEqual(result["endXPercent"], 50.0)
        self.assertEqual(result["endYPercent"], 20.0)

    def test_tap_kebab_end_x(self):
        result = self.tap(self._norm([
            "--x", "100", "--y", "200",
            "--end-x", "300", "--end-y", "400",
            "--action", "swipe",
        ]))
        self.assertEqual(result["endX"], 300.0)
        self.assertEqual(result["endY"], 400.0)

    def test_dump_kebab_include_gone(self):
        result = self.dump(self._norm(["--include-gone"]))
        self.assertTrue(result["includeGone"])

    def test_dump_kebab_all_windows(self):
        result = self.dump(self._norm(["--all-windows"]))
        self.assertTrue(result["allWindows"])

    def test_dump_kebab_root_layout(self):
        result = self.dump(self._norm(["--root-layout", "content"]))
        self.assertEqual(result["rootLayout"], "content")

    def test_locate_kebab_resource_id(self):
        result = self.locate(self._norm(["--resource-id", "btn_confirm"]))
        self.assertEqual(result["target"]["resourceId"], "btn_confirm")

    def test_locate_kebab_content_desc(self):
        result = self.locate(self._norm(["--content-desc", "Back"]))
        self.assertEqual(result["target"]["contentDesc"], "Back")

    def test_inspect_kebab_resource_id(self):
        result = self.inspect(self._norm(["--resource-id", "btn_play", "getText()"]))
        self.assertEqual(result["target"]["resourceId"], "btn_play")

    def test_inspect_kebab_class_name(self):
        result = self.inspect(self._norm(["--text", "OK", "--class-name", "TextView", "getText()"]))
        self.assertEqual(result["target"]["className"], "TextView")

    def test_ssh_reason(self):
        result = self.ssh(self._norm(["--reason", "test"]))
        self.assertEqual(result["reason"], "test")


class CompileCallErrorDetailTest(unittest.TestCase):
    """compile_call should print data.detail when status is ERROR."""

    def setUp(self):
        self._original_if_compiling = jugglib.if_compiling
        jugglib.if_compiling = jugglib.IF_COMPILING_INTERRUPT

    def tearDown(self):
        jugglib.if_compiling = self._original_if_compiling

    def _run_compile_call(self, structured: dict):
        import io
        from contextlib import redirect_stderr
        from unittest.mock import patch, MagicMock

        buf = io.StringIO()
        with patch("jugglib.resolve_project_dir", return_value="/fake/project"), \
             patch("jugglib.resolve_port", return_value=12320), \
             patch("jugglib.raw_call", return_value={"result": {"structuredContent": structured}}), \
             redirect_stderr(buf):
            try:
                jugglib.compile_call("deploy", json_mode=False)
            except SystemExit:
                pass
        return buf.getvalue()

    def test_error_with_data_detail_is_printed(self):
        structured = {
            "status": "ERROR",
            "message": "deploy finished with status=failed.",
            "data": {"detail": "No connected device found (NO_DEVICE)"},
        }
        output = self._run_compile_call(structured)
        self.assertIn("No connected device found (NO_DEVICE)", output)

    def test_error_without_data_detail_still_works(self):
        structured = {
            "status": "ERROR",
            "message": "Unknown error occurred",
        }
        output = self._run_compile_call(structured)
        self.assertIn("Unknown error occurred", output)
        self.assertNotIn("detail:", output)

    def test_error_with_empty_data_detail_not_printed(self):
        structured = {
            "status": "ERROR",
            "message": "Some error",
            "data": {"detail": ""},
        }
        output = self._run_compile_call(structured)
        self.assertNotIn("detail:", output)


if __name__ == "__main__":
    unittest.main()
