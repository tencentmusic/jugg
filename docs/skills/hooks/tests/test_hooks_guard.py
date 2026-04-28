"""Tests for stop-hook guard decision logic."""

import importlib.util
import os
import unittest


def _load_stop_module():
    base = os.path.join(os.path.dirname(__file__), "..", "stop.py")
    spec = importlib.util.spec_from_file_location("jugg_stop_hook", base)
    if spec is None or spec.loader is None:
        raise RuntimeError("failed to load stop hook module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StopHookDecisionTest(unittest.TestCase):
    def test_should_block_when_last_modified_changed_and_total_gt_zero(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 0},
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:05:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 2, "Java": 2},
        }
        self.assertTrue(mod.should_block_stop(baseline, current))

    def test_should_not_block_when_last_modified_unchanged(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 1},
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 3, "Java": 3},
        }
        self.assertFalse(mod.should_block_stop(baseline, current))

    def test_should_not_block_when_no_pending_files(self):
        mod = _load_stop_module()
        baseline = {
            "lastFileModifiedTime": "2026-04-25 10:00:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 0},
        }
        current = {
            "lastFileModifiedTime": "2026-04-25 10:05:00",
            "lastCompileTime": "2026-04-25 09:58:00",
            "fileCounts": {"total": 0},
        }
        self.assertFalse(mod.should_block_stop(baseline, current))

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
        self.assertFalse(mod.should_block_stop(baseline, current))

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
        code, persist, msg = mod.compute_stop_hook_result(True, 0)
        self.assertEqual(code, 2)
        self.assertEqual(persist, 1)
        self.assertIsNotNone(msg)
        code2, persist2, msg2 = mod.compute_stop_hook_result(True, 1)
        self.assertEqual(code2, 0)
        self.assertIsNone(persist2)
        self.assertIsNotNone(msg2)

    def test_compute_stop_hook_resets_count_when_no_longer_blocked(self):
        mod = _load_stop_module()
        code, persist, msg = mod.compute_stop_hook_result(False, 1)
        self.assertEqual(code, 0)
        self.assertEqual(persist, 0)
        self.assertIsNone(msg)


if __name__ == "__main__":
    unittest.main()
