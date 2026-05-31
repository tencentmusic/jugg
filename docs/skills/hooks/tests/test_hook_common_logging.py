"""Tests for hook debug logging and payload logging switches."""

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock


def _load_hook_common():
    base = os.path.join(os.path.dirname(__file__), "..", "hook_common.py")
    spec = importlib.util.spec_from_file_location("jugg_hook_common", base)
    if spec is None or spec.loader is None:
        raise RuntimeError("failed to load hook common module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HookCommonLoggingTest(unittest.TestCase):
    def test_payload_logging_is_disabled_by_default(self):
        mod = _load_hook_common()
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual("", mod.payload_debug_suffix({"tool": "Edit"}))

    def test_payload_logging_can_be_enabled(self):
        mod = _load_hook_common()
        with mock.patch.dict(os.environ, {"JUGG_HOOK_DEBUG_PAYLOAD": "true"}, clear=True):
            suffix = mod.payload_debug_suffix({"tool": "Edit", "n": 1})
        self.assertIn("payload=", suffix)
        self.assertIn('"tool": "Edit"', suffix)
        self.assertIn('"n": 1', suffix)

    def test_debug_log_rotates_file_when_size_reaches_one_mb(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory(prefix="hook-debug-log-") as temp_dir:
            log_path = Path(temp_dir) / "jugg-hook-debug.log"
            log_path.write_bytes(b"x" * (1024 * 1024))
            with mock.patch.dict(os.environ, {"JUGG_HOOK_DEBUG_LOG": str(log_path)}, clear=True):
                mod.debug_log("JUGG-TEST", "hello")

            backup_path = Path(f"{log_path}.1")
            self.assertTrue(backup_path.exists())
            self.assertEqual(1024 * 1024, backup_path.stat().st_size)
            content = log_path.read_text(encoding="utf-8")
            self.assertIn("[JUGG-TEST]", content)
            self.assertIn("hello", content)

    def test_extract_session_id_from_payload(self):
        mod = _load_hook_common()
        payload = {
            "meta": {
                "session": {
                    "id": "session-123",
                },
            },
        }

        self.assertEqual("session-123", mod.extract_session_id(payload))

    def test_state_file_path_uses_session_id_when_present(self):
        mod = _load_hook_common()
        home = Path("/tmp")
        cwd = "/repo/android_demo_project"
        with_session = mod.state_file_path(home, cwd, "session-123")
        without_session = mod.state_file_path(home, cwd, None)

        self.assertEqual(home / ".jugg" / "skills" / "hooks" / ".state", with_session.parent)
        self.assertNotEqual(with_session, without_session)

    def test_remember_project_cwd_prefers_existing_state(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory() as project_cwd, tempfile.TemporaryDirectory() as hook_cwd:
            state = {"projectCwd": str(Path(project_cwd).resolve())}
            resolved, changed = mod.remember_project_cwd(state, {"cwd": hook_cwd}, hook_cwd)

        self.assertEqual(str(Path(project_cwd).resolve()), resolved)
        self.assertFalse(changed)

    def test_remember_project_cwd_uses_workspace_roots_without_state(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory() as project_cwd, tempfile.TemporaryDirectory() as hook_cwd:
            (Path(project_cwd) / "settings.gradle").write_text("", encoding="utf-8")
            state = {}
            payload = {"workspace_roots": [project_cwd], "cwd": ""}

            resolved, changed = mod.remember_project_cwd(state, payload, hook_cwd)

        self.assertEqual(str(Path(project_cwd).resolve()), resolved)
        self.assertTrue(changed)
        self.assertEqual(str(Path(project_cwd).resolve()), state.get("projectCwd"))

    def test_remember_project_cwd_uses_file_path_without_state(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory() as project_cwd, tempfile.TemporaryDirectory() as hook_cwd:
            project_path = Path(project_cwd)
            (project_path / "gradlew").write_text("", encoding="utf-8")
            source_path = project_path / "app/src/main/java/com/example/myapplication/HookEdit.kt"
            source_path.parent.mkdir(parents=True)
            source_path.write_text("class HookEdit", encoding="utf-8")
            state = {}
            payload = {"file_path": str(source_path)}

            resolved, changed = mod.remember_project_cwd(state, payload, hook_cwd)

        self.assertEqual(str(project_path.resolve()), resolved)
        self.assertTrue(changed)
        self.assertEqual(str(project_path.resolve()), state.get("projectCwd"))

    def test_remember_project_cwd_uses_payload_cwd(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory() as project_cwd, tempfile.TemporaryDirectory() as hook_cwd:
            state = {}
            resolved, changed = mod.remember_project_cwd(state, {"cwd": project_cwd}, hook_cwd)

        self.assertEqual(str(Path(project_cwd).resolve()), resolved)
        self.assertTrue(changed)
        self.assertEqual(str(Path(project_cwd).resolve()), state.get("projectCwd"))

    def test_is_codebuddy_ide_payload_detects_ide_runtime(self):
        mod = _load_hook_common()
        self.assertTrue(mod.is_codebuddy_ide_payload({"client": "CodeBuddyIDE"}))
        self.assertFalse(mod.is_codebuddy_ide_payload({"client": "CLI"}))
        self.assertFalse(mod.is_codebuddy_ide_payload({}))

    def test_is_hook_block_disabled_when_flag_missing(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory(prefix="hook-disable-flag-") as home:
            self.assertFalse(mod.is_hook_block_disabled(Path(home)))

    def test_is_hook_block_disabled_when_flag_present(self):
        mod = _load_hook_common()
        with tempfile.TemporaryDirectory(prefix="hook-disable-flag-") as home:
            flag = mod.resolve_hooks_dir(Path(home)) / mod.HOOK_BLOCK_DISABLED_FLAG_NAME
            flag.parent.mkdir(parents=True, exist_ok=True)
            flag.write_text("", encoding="utf-8")
            self.assertTrue(mod.is_hook_block_disabled(Path(home)))

    def test_format_status_summary_outputs_plain_key_value_lines(self):
        mod = _load_hook_common()
        summary = mod.format_status_summary(
            {
                "data": {
                    "hasDevice": True,
                    "needFallback": False,
                    "hasBeenFullCompiled": True,
                    "enabledAndroidTest": True,
                    "pendingModifiedFiles": {"total": 1, "SOURCE": 1},
                    "files": ["/repo/app/src/main/java/com/example/HookStopTrigger.kt"],
                    "lastCompileTime": "2026-05-14 10:20:30",
                }
            }
        )

        self.assertIn("Jugg status:", summary)
        self.assertIn("  hasDevice: true", summary)
        self.assertIn("  needFallback: false", summary)
        self.assertIn("  hasBeenFullCompiled: true", summary)
        self.assertIn("  enabledAndroidTest: true", summary)
        self.assertIn("  pendingModifiedFiles: HookStopTrigger.kt", summary)
        self.assertIn("  lastCompileTime: 2026-05-14 10:20:30", summary)
        self.assertNotIn('{"SOURCE":1,"total":1}', summary)


if __name__ == "__main__":
    unittest.main()
