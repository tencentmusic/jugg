"""Tests for stop-hook guard decision logic."""

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path


def _load_stop_module():
    base = os.path.join(os.path.dirname(__file__), "..", "stop.py")
    spec = importlib.util.spec_from_file_location("jugg_stop_hook", base)
    if spec is None or spec.loader is None:
        raise RuntimeError("failed to load stop hook module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StopHookDecisionTest(unittest.TestCase):
    def test_should_block_when_android_edit_pending_even_without_status_change(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
        }
        state = {"androidEditPending": True}
        self.assertTrue(mod.should_block_stop(state, baseline, current))

    def test_should_not_block_when_no_android_edit_pending_even_with_status_change(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:05:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 3},
        }
        self.assertFalse(mod.should_block_stop({}, baseline, current))

    def test_should_block_without_current_status_when_android_edit_pending(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
        }
        self.assertTrue(mod.should_block_stop({"androidEditPending": True}, baseline, None))

    def test_should_not_block_when_last_compile_time_changed(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 0},
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:05:00",
            "lastCompileTime": "2026-04-25 10:04:00",
            "fileCounts": {"total": 3, "Java": 3},
        }
        self.assertFalse(mod.should_block_stop({"androidEditPending": True}, baseline, current))

    def test_should_block_when_compile_happened_before_recorded_edit(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:05:00",
            "lastCompileTime": "2026-04-25 10:04:00",
        }
        state = {
            "androidEditPending": True,
            "androidEditBaselineCompileTime": "2026-04-25 10:04:00",
        }
        self.assertTrue(mod.should_block_stop(state, baseline, current))

    def test_format_modified_file_summary_returns_file_names_and_omits_after_ten(self):
        mod = _load_stop_module()
        paths = [f"/repo/app/src/main/java/com/example/File{i}.kt" for i in range(12)]

        summary = mod.format_modified_file_summary(paths)

        self.assertEqual(
            "File0.kt, File1.kt, File2.kt, File3.kt, File4.kt, "
            "File5.kt, File6.kt, File7.kt, File8.kt, File9.kt, ...",
            summary,
        )

    def test_build_stop_block_message_includes_modified_file_names(self):
        mod = _load_stop_module()

        message = mod.build_stop_block_message(["/repo/app/src/main/java/com/example/Foo.kt"])

        self.assertIn("Modified files: Foo.kt", message)
        self.assertNotIn("/repo/app/src/main/java/com/example/Foo.kt", message)

    def test_parse_nested_state_round_trip(self):
        mod = _load_stop_module()
        raw = {
            "stopBlockCount": 1,
            "snapshot": {
                "cwd": "/tmp",
                "lastFileModifiedTime": "a",
                "lastCompileTime": "b",
                "fileCounts": {"total": 0},
            },
        }
        snapshot, count = mod.parse_stored_state(raw)
        self.assertEqual(count, 1)
        self.assertEqual(snapshot["lastFileModifiedTime"], "a")

    def test_parse_legacy_flat_state(self):
        mod = _load_stop_module()
        raw = {
            "lastFileModifiedTime": "x",
            "lastCompileTime": "y",
            "fileCounts": {"total": 1},
        }
        snapshot, count = mod.parse_stored_state(raw)
        self.assertEqual(count, 0)
        self.assertEqual(snapshot["lastFileModifiedTime"], "x")

    def test_compute_stop_hook_result_first_block_then_allow(self):
        mod = _load_stop_module()
        code, persist, msg = mod.compute_stop_hook_result(True, 0, ["Foo.kt"])
        self.assertEqual(code, 2)
        self.assertEqual(persist, 1)
        self.assertIn("Foo.kt", msg)
        code2, persist2, msg2 = mod.compute_stop_hook_result(True, 1, ["Foo.kt"])
        self.assertEqual(code2, 0)
        self.assertIsNone(persist2)
        self.assertIsNotNone(msg2)

    def test_compute_stop_hook_resets_count_when_no_longer_blocked(self):
        mod = _load_stop_module()
        code, persist, msg = mod.compute_stop_hook_result(False, 1, [])
        self.assertEqual(code, 0)
        self.assertEqual(persist, 0)
        self.assertIsNone(msg)

    def test_write_hook_state_preserves_edit_paths(self):
        mod = _load_stop_module()
        state = {
            "androidEditPending": True,
            "androidEditPaths": ["/repo/app/src/main/java/com/example/Foo.kt"],
            "snapshot": {"lastFileModifiedTime": "old"},
        }
        snapshot = {"lastFileModifiedTime": "new"}
        with tempfile.TemporaryDirectory() as tmp:
            state_file = Path(tmp) / "state.json"

            mod.write_hook_state(state_file, state, snapshot, 1)
            written = state_file.read_text(encoding="utf-8")

        self.assertIn("androidEditPending", written)
        self.assertIn("Foo.kt", written)
        self.assertIn('"stopBlockCount": 1', written)


if __name__ == "__main__":
    unittest.main()
