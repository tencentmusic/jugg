"""Public settings syntax and output contracts."""
import contextlib
import io
import json
import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'scripts', 'py'))
from cmd.cmd_settings import build_params, cmd_settings
import jugglib


class SettingsCommandTest(unittest.TestCase):
    def test_preserves_key_and_raw_value(self):
        self.assertEqual({'action': 'set', 'key': 'maxCompileSourceModules', 'value': '30'},
                         build_params(['set', 'maxCompileSourceModules', '30']))
        self.assertEqual({'action': 'set', 'key': 'isEmbeddedToApk', 'value': 'true', 'confirm': True},
                         build_params(['set', 'isEmbeddedToApk', 'true', '--confirm', 'true']))

    def test_rejects_missing_or_implicit_confirmation_value(self):
        for args in (['set', 'x', 'true', '--confirm'], ['set', 'x', 'true', '--confirm', 'yes'],
                     ['get', 'x', '--confirm', 'true'], ['list', 'x']):
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    build_params(args)

    def test_json_confirmation_failure_has_nonzero_exit(self):
        failure = {'status': 'ERROR', 'errorCode': 'CONFIRMATION_REQUIRED', 'message': 'Continue?',
                   'data': {'confirmation': {'title': 'Confirm', 'message': 'Continue?'}}}
        with patch.object(jugglib, 'resolve_global_port', return_value=12320), \
                patch.object(jugglib, 'raw_call', return_value={'result': {'structuredContent': failure}}), \
                patch.object(jugglib, 'json_mode', True), contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(SystemExit) as raised:
                cmd_settings(['set', 'isEnableBackupClasspath', 'true'])
        self.assertNotEqual(0, raised.exception.code)
        self.assertEqual(failure, json.loads(output.getvalue()))

    def test_empty_action_lists_and_false_confirmation_stays_false(self):
        self.assertEqual({"action": "list"}, build_params([]))
        self.assertEqual({"action": "reset", "key": "isEnableBackupClasspath", "confirm": False},
                         build_params(["reset", "isEnableBackupClasspath", "--confirm", "false"]))

    def test_global_routing_prefers_supported_executor_without_registering_project(self):
        old = jugglib.RuntimeEndpoint(12320, "idea", ["/project"])
        supported = jugglib.RuntimeEndpoint(12321, "standalone", [], capabilities=("settings",))
        with patch.object(jugglib, "runtime_type_override", ""), \
                patch.object(jugglib, "candidate_project_dir", return_value="/project"):
            self.assertEqual(supported, jugglib._select_global_runtime([old, supported]))

    def test_global_startup_uses_no_project_and_never_registers_working_directory(self):
        from types import SimpleNamespace
        endpoint = jugglib.RuntimeEndpoint(12321, "standalone", [], capabilities=("settings",))
        launch = SimpleNamespace(process=SimpleNamespace(poll=lambda: None))
        response = {"result": {"structuredContent": {"status": "OK", "data": {"capabilities": ["settings"]}}}}
        with patch.object(jugglib, "runtime_type_override", ""), \
                patch.object(jugglib, "discover_runtime_endpoints", side_effect=[[], [], [endpoint]]), \
                patch.object(jugglib, "_standalone_launch_lock", return_value=contextlib.nullcontext()), \
                patch.object(jugglib, "candidate_project_dir", return_value="/unrelated"), \
                patch.object(jugglib, "launch_standalone", return_value=launch) as start, \
                patch.object(jugglib, "raw_call", return_value=response) as call:
            self.assertEqual(12321, jugglib.resolve_global_port())
            start.assert_called_once_with("")
            call.assert_called_once_with(12321, "version", {})

    def test_old_runtime_reports_capability_error_without_writing_locally(self):
        endpoint = jugglib.RuntimeEndpoint(12320, "idea", [])
        response = {"result": {"structuredContent": {"status": "OK", "data": {"capabilities": ["version"]}}}}
        with patch.object(jugglib, "discover_runtime_endpoints", return_value=[endpoint]), \
                patch.object(jugglib, "runtime_type_override", "idea"), \
                patch.object(jugglib, "raw_call", return_value=response), \
                patch.object(jugglib, "launch_standalone") as start, contextlib.redirect_stderr(io.StringIO()) as errors:
            with self.assertRaises(SystemExit):
                jugglib.resolve_global_port()
            start.assert_not_called()
        self.assertIn("does not support settings", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
