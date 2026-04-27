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


if __name__ == "__main__":
    unittest.main()
