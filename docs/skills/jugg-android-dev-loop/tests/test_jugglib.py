"""Tests for jugglib — port cache, projectDir resolution, record session, JSON output."""

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import types
import urllib.error
import unittest
from pathlib import Path
from unittest.mock import patch

# Ensure jugglib is importable
SCRIPTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts")
sys.path.insert(0, SCRIPTS_DIR)
sys.path.insert(0, os.path.join(SCRIPTS_DIR, "py"))
import jugglib


_HOLD_LAUNCH_LOCK_SCRIPT = """
import sys
import time
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import jugglib
with jugglib._standalone_launch_lock(sys.argv[2]):
    Path(sys.argv[3]).touch()
    while not Path(sys.argv[4]).exists():
        time.sleep(0.05)
"""

_ACQUIRE_LAUNCH_LOCK_SCRIPT = """
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import jugglib
with jugglib._standalone_launch_lock(sys.argv[2]):
    Path(sys.argv[3]).touch()
"""


def _start_lock_process(script, *args):
    return subprocess.Popen([sys.executable, "-c", script, os.path.join(SCRIPTS_DIR, "py"), *args])


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
        jugglib.reset_runtime_selection()
        jugglib.set_runtime_type_override("")
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_launch_standalone_hides_the_windows_console(self):
        project_dir = os.path.join(self.tmp, "project")
        launcher = Path(self.tmp) / "jugg-standalone.bat"
        launcher.write_text("@echo off\n")
        process = object()

        with patch.object(jugglib, "_standalone_launcher_path", return_value=launcher), \
             patch.object(jugglib.sys, "platform", "win32"), \
             patch.object(jugglib.subprocess, "Popen", return_value=process) as mock_popen:
            launch = jugglib.launch_standalone(project_dir)

        self.assertEqual(
            jugglib._WINDOWS_CREATE_NEW_PROCESS_GROUP | jugglib._WINDOWS_CREATE_NO_WINDOW,
            mock_popen.call_args.kwargs["creationflags"],
        )
        self.assertIs(process, launch.process)
        self.assertTrue(launch.log_path.is_file())

    def test_hook_does_not_retry_or_launch_for_closed_ports_without_baseline(self):
        refused = urllib.error.URLError(ConnectionRefusedError(61, "Connection refused"))

        with patch.dict(os.environ, {"JUGG_CALLER": "hook"}), \
             patch.object(jugglib.urllib.request, "urlopen", side_effect=refused) as mock_urlopen, \
             patch.object(jugglib, "candidate_project_dir", return_value=self.tmp), \
             patch.object(jugglib.time, "sleep") as mock_sleep, \
             patch.object(jugglib, "launch_standalone") as mock_launch, \
             self.assertRaises(SystemExit) as cm:
            jugglib.resolve_port()

        self.assertEqual(0, cm.exception.code)
        self.assertEqual(mock_urlopen.call_count, 10)
        mock_sleep.assert_not_called()
        mock_launch.assert_not_called()

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
             patch.object(jugglib, "_read_runtime_endpoint", return_value=jugglib.RuntimeEndpoint(12320, "unknown", [], projects_known=False)), \
             patch.object(jugglib.time, "sleep") as mock_sleep:
            port = jugglib.resolve_port()

        self.assertEqual(port, 12320)
        self.assertGreaterEqual(mock_urlopen.call_count, 11)
        mock_sleep.assert_called_once()

    def test_resolve_port_launches_standalone_for_unowned_project(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)
        endpoint = jugglib.RuntimeEndpoint(12324, "standalone", [project_dir])

        class RunningProcess:
            def poll(self):
                return None

        launch = jugglib.StandaloneLaunch(RunningProcess(), Path(project_dir) / "startup.log")

        stderr = io.StringIO()

        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", side_effect=[[], [], [endpoint]]), \
             patch.object(jugglib, "launch_standalone", return_value=launch) as mock_launch, \
             contextlib.redirect_stderr(stderr):
            port = jugglib.resolve_port()

        self.assertEqual(12324, port)
        mock_launch.assert_called_once_with(project_dir)
        self.assertIn("Starting Jugg standalone runtime", stderr.getvalue())
        self.assertIn(" with ", stderr.getvalue())
        self.assertIn("Standalone runtime ready on port 12324", stderr.getvalue())

    def test_resolve_port_reuses_standalone_for_an_unregistered_project(self):
        project_dir = os.path.join(self.tmp, "project")
        other_project = os.path.join(self.tmp, "other-project")
        os.makedirs(project_dir)
        endpoint = jugglib.RuntimeEndpoint(12324, "standalone", [other_project])

        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", return_value=[endpoint]), \
             patch.object(
                 jugglib,
                 "launch_standalone",
                 side_effect=AssertionError("must reuse the running standalone"),
             ) as mock_launch:
            port = jugglib.resolve_port()

        self.assertEqual(12324, port)
        self.assertEqual(jugglib.normalize_project_dir(project_dir), jugglib._selected_project_dir)
        self.assertFalse(jugglib._selected_project_registered)
        mock_launch.assert_not_called()

    def test_resolve_port_waits_for_slow_standalone_and_reports_latest_runtime_log(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)
        runtime_log = Path(project_dir) / "build" / "jugg" / "log" / "standlone_cli" / "compile_latest.log"
        runtime_log.parent.mkdir(parents=True)
        long_details = "x" * 600
        runtime_log.write_text(
            "[2026-08-30 21:53:49.685] [FINE   ] [DeployDataDatabase] "
            f"SQLite run doInsertMethodRef cost 4182ms {long_details}\n"
            "non-structured continuation\n"
        )
        endpoint = jugglib.RuntimeEndpoint(12324, "standalone", [project_dir])

        class RunningProcess:
            def poll(self):
                return None

        class FakeClock:
            now = 0.0

            def monotonic(self):
                return self.now

            def sleep(self, seconds):
                self.now += seconds

        clock = FakeClock()
        launch = jugglib.StandaloneLaunch(RunningProcess(), Path(project_dir) / "startup.log")

        def discover_runtime_endpoints():
            return [endpoint] if clock.now >= 25 else []

        stderr = io.StringIO()
        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", side_effect=discover_runtime_endpoints), \
             patch.object(jugglib, "launch_standalone", return_value=launch), \
             patch.object(jugglib.time, "monotonic", side_effect=clock.monotonic), \
             patch.object(jugglib.time, "sleep", side_effect=clock.sleep), \
             contextlib.redirect_stderr(stderr):
            port = jugglib.resolve_port()

        output = stderr.getvalue()
        self.assertEqual(12324, port)
        self.assertIn("elapsed 10s", output)
        self.assertIn("elapsed 20s", output)
        self.assertIn("[DeployDataDatabase] SQLite run doInsertMethodRef cost 4182ms", output)
        self.assertNotIn(long_details, output)
        self.assertNotIn("non-structured continuation", output)

    def test_resolve_port_still_has_a_bounded_standalone_startup_timeout(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)

        class RunningProcess:
            def poll(self):
                return None

        class FakeClock:
            now = 0.0

            def monotonic(self):
                return self.now

            def sleep(self, seconds):
                self.now += seconds

        clock = FakeClock()
        launch = jugglib.StandaloneLaunch(RunningProcess(), Path(project_dir) / "startup.log")
        refused = {
            port: jugglib.PortProbeResult(False, "connection refused", False)
            for port in range(12320, 12330)
        }

        stderr = io.StringIO()
        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", return_value=[]), \
             patch.object(jugglib, "launch_standalone", return_value=launch), \
             patch.object(jugglib, "_scan_ports", return_value=refused), \
             patch.object(jugglib.time, "monotonic", side_effect=clock.monotonic), \
             patch.object(jugglib.time, "sleep", side_effect=clock.sleep), \
             contextlib.redirect_stderr(stderr), \
             self.assertRaises(SystemExit) as cm:
            jugglib.resolve_port()

        self.assertEqual(1, cm.exception.code)
        self.assertGreaterEqual(clock.now, jugglib._STANDALONE_STARTUP_TIMEOUT_SECONDS)
        self.assertLess(
            clock.now,
            jugglib._STANDALONE_STARTUP_TIMEOUT_SECONDS + jugglib._STANDALONE_STARTUP_POLL_INTERVAL_SECONDS,
        )
        output = stderr.getvalue()
        self.assertIn("elapsed 60s", output)
        self.assertIn("Runtime log is not available yet", output)

    def test_resolve_port_reports_standalone_process_startup_failure(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)
        startup_log = Path(project_dir) / "build" / "jugg" / "log" / "standlone_cli" / "standalone_startup.log"
        startup_log.parent.mkdir(parents=True)
        startup_log.write_text("IllegalStateException: standalone protocol mismatch\n")

        class FailedProcess:
            def poll(self):
                return 7

        launch = jugglib.StandaloneLaunch(FailedProcess(), startup_log)
        stderr = io.StringIO()
        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", side_effect=[[], [], []]), \
             patch.object(jugglib, "launch_standalone", return_value=launch), \
             contextlib.redirect_stderr(stderr), \
             self.assertRaises(SystemExit) as cm:
            jugglib.resolve_port()

        self.assertEqual(1, cm.exception.code)
        output = stderr.getvalue()
        self.assertIn("standalone failed to start (exit code 7)", output)
        self.assertIn("standalone protocol mismatch", output)
        self.assertIn(str(startup_log), output)

    def test_resolve_port_rechecks_under_launch_lock_before_starting_daemon(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)
        endpoint = jugglib.RuntimeEndpoint(12324, "standalone", [project_dir])

        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", side_effect=[[], [endpoint]]), \
             patch.object(jugglib, "launch_standalone") as mock_launch:
            port = jugglib.resolve_port()

        self.assertEqual(12324, port)
        mock_launch.assert_not_called()

    def test_standalone_launch_lock_serializes_processes(self):
        project_dir = os.path.join(self.tmp, "project")
        ready_file = os.path.join(self.tmp, "ready")
        release_file = os.path.join(self.tmp, "release")
        acquired_file = os.path.join(self.tmp, "acquired")
        os.makedirs(project_dir)
        first = _start_lock_process(_HOLD_LAUNCH_LOCK_SCRIPT, project_dir, ready_file, release_file)
        second = None
        try:
            for _ in range(50):
                if os.path.exists(ready_file):
                    break
                time.sleep(0.05)
            self.assertTrue(os.path.exists(ready_file))

            second = _start_lock_process(_ACQUIRE_LAUNCH_LOCK_SCRIPT, project_dir, acquired_file)
            time.sleep(0.2)
            self.assertFalse(os.path.exists(acquired_file))

            Path(release_file).touch()
            first.wait(timeout=5)
            second.wait(timeout=5)
            self.assertEqual(0, first.returncode)
            self.assertEqual(0, second.returncode)
            self.assertTrue(os.path.exists(acquired_file))
        finally:
            Path(release_file).touch()
            for process in (first, second):
                if process is not None and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=5)

    def test_standalone_launch_lock_is_shared_by_different_projects(self):
        project_a = os.path.join(self.tmp, "project-a")
        project_b = os.path.join(self.tmp, "project-b")
        lock_file = os.path.join(self.tmp, "standalone.launch.lock")

        with patch.dict(os.environ, {"JUGG_STANDALONE_LAUNCH_LOCK": lock_file}):
            self.assertEqual(
                jugglib._standalone_launch_lock_path(project_a),
                jugglib._standalone_launch_lock_path(project_b),
            )

    def test_hook_without_complete_flag_skips_daemon_start(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)

        with patch.dict(os.environ, {"JUGG_CALLER": "hook"}), \
             patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", return_value=[]), \
             patch.object(jugglib, "launch_standalone") as mock_launch, \
             self.assertRaises(SystemExit) as cm:
            jugglib.resolve_port()

        self.assertEqual(0, cm.exception.code)
        mock_launch.assert_not_called()

    def test_hook_without_complete_flag_does_not_register_in_an_existing_daemon(self):
        project_dir = os.path.join(self.tmp, "project")
        other_project = os.path.join(self.tmp, "other-project")
        os.makedirs(project_dir)
        endpoint = jugglib.RuntimeEndpoint(12324, "standalone", [other_project])

        with patch.dict(os.environ, {"JUGG_CALLER": "hook"}), \
             patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", return_value=[endpoint]), \
             patch.object(jugglib, "launch_standalone") as mock_launch, \
             self.assertRaises(SystemExit) as cm:
            jugglib.resolve_port()

        self.assertEqual(0, cm.exception.code)
        mock_launch.assert_not_called()

    def test_hook_with_complete_flag_can_launch_daemon(self):
        project_dir = os.path.join(self.tmp, "project")
        complete_flag = os.path.join(
            project_dir, "build", "jugg", "database", "compile_context.db", "complete_flag"
        )
        os.makedirs(os.path.dirname(complete_flag))
        open(complete_flag, "w").close()
        endpoint = jugglib.RuntimeEndpoint(12325, "standalone", [project_dir])

        with patch.dict(os.environ, {"JUGG_CALLER": "hook"}), \
             patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", side_effect=[[], [], [endpoint]]), \
             patch.object(jugglib, "launch_standalone") as mock_launch:
            port = jugglib.resolve_port()

        self.assertEqual(12325, port)
        mock_launch.assert_called_once_with(project_dir)

    def test_runtime_selection_matches_project_when_idea_and_standalone_coexist(self):
        project_a = os.path.join(self.tmp, "project-a")
        project_b = os.path.join(self.tmp, "project-b")
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_a]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_b]),
        ]

        with patch.object(jugglib, "candidate_project_dir", return_value=project_b), \
             patch.object(jugglib, "discover_runtime_endpoints", return_value=endpoints):
            port = jugglib.resolve_port()

        self.assertEqual(12321, port)

    def test_explicit_project_dir_selects_idea_parent_project(self):
        project_dir = os.path.join(self.tmp, "project")
        nested_project_dir = os.path.join(project_dir, "nested-project")
        jugglib.set_project_dir_override(nested_project_dir)

        selected = jugglib._select_runtime(
            [jugglib.RuntimeEndpoint(12320, "idea", [project_dir])],
            nested_project_dir,
        )

        self.assertEqual(12320, selected.port)

    def test_auto_detected_nested_project_starts_its_own_runtime(self):
        parent_project_dir = os.path.join(self.tmp, "project")
        nested_project_dir = os.path.join(parent_project_dir, "nested-project")
        os.makedirs(nested_project_dir)
        parent_endpoint = jugglib.RuntimeEndpoint(12320, "idea", [parent_project_dir])
        nested_endpoint = jugglib.RuntimeEndpoint(12321, "standalone", [nested_project_dir])

        class RunningProcess:
            def poll(self):
                return None

        launch = jugglib.StandaloneLaunch(RunningProcess(), Path(nested_project_dir) / "startup.log")

        with patch.object(jugglib, "candidate_project_dir", return_value=nested_project_dir), \
             patch.object(
                 jugglib,
                 "discover_runtime_endpoints",
                 side_effect=[[parent_endpoint], [parent_endpoint], [parent_endpoint, nested_endpoint]],
             ), \
             patch.object(jugglib, "launch_standalone", return_value=launch) as mock_launch:
            port = jugglib.resolve_port()

        self.assertEqual(12321, port)
        mock_launch.assert_called_once_with(nested_project_dir)

    def test_same_project_selection_prefers_idea_over_last_standalone_owner(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(os.path.join(project_dir, "build", "jugg"))
        owner_file = os.path.join(project_dir, "build", "jugg", "runtime.owner.json")
        with open(owner_file, "w") as output:
            json.dump({"runtimeType": "standalone"}, output)
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_dir]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_dir]),
        ]

        selected = jugglib._select_runtime(endpoints, project_dir)

        self.assertEqual(12320, selected.port)

    def test_same_project_selection_prefers_idea_over_current_standalone_lock_owner(self):
        project_dir = os.path.join(self.tmp, "project")
        jugg_dir = os.path.join(project_dir, "build", "jugg")
        os.makedirs(jugg_dir)
        with open(os.path.join(jugg_dir, "runtime.lock.owner.json"), "w") as output:
            json.dump({"runtimeType": "standalone"}, output)
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_dir]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_dir]),
        ]

        with patch.object(jugglib, "_is_project_lock_held", return_value=True):
            selected = jugglib._select_runtime(endpoints, project_dir)

        self.assertEqual(12320, selected.port)

    def test_same_project_selection_uses_verified_current_lock_owner(self):
        project_dir = os.path.join(self.tmp, "project")
        jugg_dir = os.path.join(project_dir, "build", "jugg")
        os.makedirs(jugg_dir)
        with open(os.path.join(jugg_dir, "runtime.owner.json"), "w") as output:
            json.dump({"runtimeType": "standalone"}, output)
        with open(os.path.join(jugg_dir, "runtime.lock.owner.json"), "w") as output:
            json.dump({"runtimeType": "idea"}, output)
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_dir]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_dir]),
        ]

        with patch.object(jugglib, "_is_project_lock_held", return_value=True):
            selected = jugglib._select_runtime(endpoints, project_dir)

        self.assertEqual(12320, selected.port)

    def test_same_project_selection_prefers_idea_when_current_owner_file_is_stale(self):
        project_dir = os.path.join(self.tmp, "project")
        jugg_dir = os.path.join(project_dir, "build", "jugg")
        os.makedirs(jugg_dir)
        with open(os.path.join(jugg_dir, "runtime.owner.json"), "w") as output:
            json.dump({"runtimeType": "standalone"}, output)
        with open(os.path.join(jugg_dir, "runtime.lock.owner.json"), "w") as output:
            json.dump({"runtimeType": "idea"}, output)
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_dir]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_dir]),
        ]

        with patch.object(jugglib, "_is_project_lock_held", return_value=False):
            selected = jugglib._select_runtime(endpoints, project_dir)

        self.assertEqual(12320, selected.port)

    def test_explicit_runtime_selection_overrides_owner(self):
        project_dir = os.path.join(self.tmp, "project")
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_dir]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_dir]),
        ]
        jugglib.set_runtime_type_override("idea")

        selected = jugglib._select_runtime(endpoints, project_dir)

        self.assertEqual(12320, selected.port)

    def test_explicit_standalone_runtime_overrides_idea_preference(self):
        project_dir = os.path.join(self.tmp, "project")
        endpoints = [
            jugglib.RuntimeEndpoint(12320, "idea", [project_dir]),
            jugglib.RuntimeEndpoint(12321, "standalone", [project_dir]),
        ]
        jugglib.set_runtime_type_override("standalone")

        selected = jugglib._select_runtime(endpoints, project_dir)

        self.assertEqual(12321, selected.port)

    def test_resolve_port_keeps_selected_standalone_for_current_command(self):
        project_dir = os.path.join(self.tmp, "project")
        standalone = jugglib.RuntimeEndpoint(12321, "standalone", [project_dir])
        idea = jugglib.RuntimeEndpoint(12320, "idea", [project_dir])

        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(
                 jugglib,
                 "discover_runtime_endpoints",
                 side_effect=[[standalone], [idea]],
             ) as discovery, \
             patch.object(jugglib, "ping_port", return_value=True):
            first_port = jugglib.resolve_port()
            second_port = jugglib.resolve_port()

        self.assertEqual(12321, first_port)
        self.assertEqual(12321, second_port)
        self.assertEqual(1, discovery.call_count)

    def test_explicit_idea_runtime_does_not_launch_standalone_when_missing(self):
        project_dir = os.path.join(self.tmp, "project")
        os.makedirs(project_dir)
        jugglib.set_runtime_type_override("idea")

        with patch.object(jugglib, "candidate_project_dir", return_value=project_dir), \
             patch.object(jugglib, "discover_runtime_endpoints", return_value=[]), \
             patch.object(jugglib, "launch_standalone") as mock_launch, \
             contextlib.redirect_stderr(io.StringIO()), \
             self.assertRaises(SystemExit) as cm:
            jugglib.resolve_port()

        self.assertEqual(1, cm.exception.code)
        mock_launch.assert_not_called()

    def test_known_legacy_runtime_for_other_project_does_not_block_standalone(self):
        project_dir = os.path.join(self.tmp, "project")
        other_project = os.path.join(self.tmp, "other")
        endpoint = jugglib.RuntimeEndpoint(12320, "unknown", [other_project])

        selected = jugglib._select_runtime([endpoint], project_dir)

        self.assertIsNone(selected)

    def test_legacy_runtime_with_unknown_projects_remains_backward_compatible(self):
        project_dir = os.path.join(self.tmp, "project")
        endpoint = jugglib.RuntimeEndpoint(12320, "unknown", [], projects_known=False)

        selected = jugglib._select_runtime([endpoint], project_dir)

        self.assertEqual(12320, selected.port)

    def test_list_projects_error_keeps_legacy_projects_unknown(self):
        version_response = {
            "result": {"structuredContent": {"status": "OK", "data": {"runtimeType": "unknown"}}}
        }
        projects_response = {
            "result": {"structuredContent": {"status": "ERROR", "data": {}}}
        }

        with patch.object(jugglib, "_discovery_call", side_effect=[version_response, projects_response]):
            endpoint = jugglib._read_runtime_endpoint(12320)

        self.assertFalse(endpoint.projects_known)


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
        jugglib.reset_runtime_selection()
        jugglib.set_project_dir_override("")

    def test_resolve_project_dir_uses_explicit_value_without_list_projects(self):
        jugglib.set_project_dir_override("/manual/project")

        with patch.object(jugglib, "resolve_port", return_value=12320) as mock_resolve_port, \
             patch.object(jugglib, "raw_call") as mock_raw_call:
            result = jugglib.resolve_project_dir()

        self.assertEqual(result, "/manual/project")
        mock_resolve_port.assert_called_once()
        mock_raw_call.assert_not_called()

    def test_resolve_project_dir_uses_selected_idea_parent_for_explicit_nested_project(self):
        parent_project_dir = "/manual/project"
        jugglib.set_project_dir_override(f"{parent_project_dir}/nested-project")
        jugglib._selected_project_dir = parent_project_dir

        with patch.object(jugglib, "resolve_port", return_value=12320):
            result = jugglib.resolve_project_dir()

        self.assertEqual(result, parent_project_dir)

    def test_resolve_project_dir_uses_pending_standalone_project_without_listing_it(self):
        project_dir = "/standalone/project"
        jugglib._selected_project_dir = project_dir
        jugglib._selected_project_registered = False

        with patch.object(jugglib, "resolve_port", return_value=12320), \
             patch.object(jugglib, "raw_call") as mock_raw_call:
            result = jugglib.resolve_project_dir()

        self.assertEqual(project_dir, result)
        mock_raw_call.assert_not_called()


class JuggGlobalProjectDirTest(unittest.TestCase):

    def tearDown(self):
        jugglib.set_project_dir_override("")
        jugglib.set_device_serial_override("")
        jugglib.set_runtime_type_override("")

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

    def test_serial_global_flag_sets_override_and_does_not_reach_subcommand(self):
        import jugg

        captured_args = []
        fake_module = types.SimpleNamespace(cmd_deploy=lambda args: captured_args.append(args))

        with patch.object(sys, "argv", ["jugg.py", "--serial", "emulator-5556", "deploy"]), \
             patch("importlib.import_module", return_value=fake_module):
            jugg.main()

        self.assertEqual(jugglib.device_serial_override, "emulator-5556")
        self.assertEqual(captured_args, [[]])

    def test_serial_global_flag_accepts_equals_form(self):
        import jugg

        fake_module = types.SimpleNamespace(cmd_status=lambda args: None)

        with patch.object(sys, "argv", ["jugg.py", "--serial=R58M123", "status"]), \
             patch("importlib.import_module", return_value=fake_module):
            jugg.main()

        self.assertEqual(jugglib.device_serial_override, "R58M123")

    def test_serial_global_flag_rejects_blank_value(self):
        import jugg

        with patch.object(sys, "argv", ["jugg.py", "--serial=  ", "status"]), \
             contextlib.redirect_stderr(io.StringIO()), \
             self.assertRaises(SystemExit) as cm:
            jugg.main()

        self.assertEqual(1, cm.exception.code)

    def test_runtime_global_flag_sets_explicit_runtime(self):
        import jugg

        fake_module = types.SimpleNamespace(cmd_status=lambda args: None)

        with patch.object(sys, "argv", ["jugg.py", "--runtime=standalone", "status"]), \
             patch("importlib.import_module", return_value=fake_module):
            jugg.main()

        self.assertEqual(jugglib.runtime_type_override, "standalone")

    def test_runtime_global_flag_rejects_unknown_value(self):
        import jugg

        with patch.object(sys, "argv", ["jugg.py", "--runtime=ci", "status"]), \
             contextlib.redirect_stderr(io.StringIO()), \
             self.assertRaises(SystemExit) as cm:
            jugg.main()

        self.assertEqual(1, cm.exception.code)


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
        self.assertIn("--serial SERIAL", stderr)

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

    def test_device_command_help_includes_global_serial(self):
        code, _, stderr = self._run_main(["jugg.py", "help", "view-locate"])

        self.assertEqual(code, 0)
        self.assertIn("--serial <adbSerial>", stderr)


class DeviceSerialInjectionTest(unittest.TestCase):

    def tearDown(self):
        jugglib.set_device_serial_override("")

    def test_raw_call_injects_serial_into_device_tool(self):
        jugglib.set_device_serial_override("emulator-5556")

        with patch.object(jugglib, "http_post", return_value={}) as mock_post:
            jugglib.raw_call(12320, "view-locate", {"projectDir": "/proj"})

        request = json.loads(mock_post.call_args[0][1])
        self.assertEqual("emulator-5556", request["params"]["arguments"]["serial"])

    def test_raw_call_does_not_inject_serial_into_non_device_tool(self):
        jugglib.set_device_serial_override("emulator-5556")

        with patch.object(jugglib, "http_post", return_value={}) as mock_post:
            jugglib.raw_call(12320, "compile", {"projectDir": "/proj"})

        request = json.loads(mock_post.call_args[0][1])
        self.assertNotIn("serial", request["params"]["arguments"])

    def test_raw_call_keeps_explicit_serial(self):
        jugglib.set_device_serial_override("emulator-5556")

        with patch.object(jugglib, "http_post", return_value={}) as mock_post:
            jugglib.raw_call(12320, "deploy", {"projectDir": "/proj", "serial": "device-2"})

        request = json.loads(mock_post.call_args[0][1])
        self.assertEqual("device-2", request["params"]["arguments"]["serial"])


class StandaloneProjectRegistrationHeartbeatTest(unittest.TestCase):

    def tearDown(self):
        jugglib.reset_runtime_selection()

    def test_first_project_request_reports_slow_automatic_registration(self):
        project_dir = "/pending/project"
        jugglib._selected_project_dir = project_dir
        jugglib._selected_project_registered = False

        def delayed_post(*_args, **_kwargs):
            time.sleep(0.04)
            return {"result": {"structuredContent": {"status": "OK"}}}

        with patch.object(jugglib, "http_post", side_effect=delayed_post), \
             patch.object(jugglib, "_STANDALONE_STARTUP_HEARTBEAT_SECONDS", 0.01), \
             patch.object(jugglib, "_print_standalone_startup_heartbeat") as heartbeat:
            jugglib.raw_call(12320, "status", {"projectDir": project_dir})

        heartbeat.assert_called()
        self.assertTrue(jugglib._selected_project_registered)

    def test_invalid_first_project_request_remains_pending(self):
        project_dir = "/pending/project"
        jugglib._selected_project_dir = project_dir
        jugglib._selected_project_registered = False
        response = {
            "result": {
                "structuredContent": {
                    "status": "ERROR",
                    "errorCode": "INVALID_PARAMS",
                },
            },
        }

        with patch.object(jugglib, "http_post", return_value=response):
            jugglib.raw_call(12320, "status", {"projectDir": project_dir})

        self.assertFalse(jugglib._selected_project_registered)


class InitCommandTest(unittest.TestCase):

    def tearDown(self):
        jugglib.set_runtime_type_override("")

    def test_init_selects_standalone_and_calls_project_action(self):
        from cmd.cmd_init import cmd_init

        response = {
            "result": {
                "structuredContent": {
                    "status": "OK",
                    "message": "Standalone project initialized successfully.",
                    "data": {"compileCommand": "./gradlew :app:assembleDebug"},
                }
            }
        }
        output = io.StringIO()
        with patch.object(jugglib, "resolve_project_dir", return_value="/project"), \
             patch.object(jugglib, "resolve_port", return_value=12321), \
             patch.object(jugglib, "raw_call", return_value=response) as mock_raw_call, \
             contextlib.redirect_stdout(output):
            cmd_init([])

        self.assertEqual("standalone", jugglib.runtime_type_override)
        mock_raw_call.assert_called_once_with(12321, "init", {"projectDir": "/project"})
        self.assertIn("./gradlew :app:assembleDebug", output.getvalue())


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
