"""Tests for jugglib — port cache, projectDir resolution, record session, JSON output."""

import json
import os
import sys
import tempfile
import unittest

# Ensure jugglib is importable
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts", "py"))
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


class HasJsonFlagTest(unittest.TestCase):

    def test_with_json_flag(self):
        is_json, remaining = jugglib.has_json_flag(["--json", "--text", "hello"])
        self.assertTrue(is_json)
        self.assertEqual(remaining, ["--text", "hello"])

    def test_without_json_flag(self):
        is_json, remaining = jugglib.has_json_flag(["--text", "hello"])
        self.assertFalse(is_json)
        self.assertEqual(remaining, ["--text", "hello"])


if __name__ == "__main__":
    unittest.main()
