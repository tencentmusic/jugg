#!/usr/bin/env python3
"""Tests for the pull request task plan check."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import check_pr_plan


class CheckPrPlanTest(unittest.TestCase):
    def check(self, head_ref, base_ref="main", head_repo="tencentmusic/jugg",
              plans=None, body="## Summary\n\nRelease branch merge.\n"):
        event = {
            "pull_request": {
                "base": {"ref": base_ref, "sha": "base", "repo": {"full_name": "tencentmusic/jugg"}},
                "head": {"ref": head_ref, "sha": "head", "repo": {"full_name": head_repo}},
                "body": body,
            }
        }
        with tempfile.TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            event_path.write_text(json.dumps(event), encoding="utf-8")
            with patch.object(check_pr_plan, "changed_plans", return_value=plans or []):
                return check_pr_plan.main(event_path)

    def test_release_branch_merge_does_not_need_plan(self):
        self.assertEqual(0, self.check("develop/3.6"))

    def test_ordinary_pr_still_needs_plan(self):
        self.assertEqual(1, self.check("feature/new-behavior"))

    def test_release_branch_into_other_target_still_needs_plan(self):
        self.assertEqual(1, self.check("develop/3.6", base_ref="develop/4.0"))

    def test_forked_release_branch_still_needs_plan(self):
        self.assertEqual(1, self.check("develop/3.6", head_repo="someone/jugg"))

    def test_ordinary_pr_with_linked_changed_plan_passes(self):
        plan = "docs/task/2026-09/example.md"
        body = ("## Task plan\n\n[Task plan](https://github.com/tencentmusic/jugg/blob/"
                "feature/new-behavior/" + plan + ")\n")
        self.assertEqual(0, self.check("feature/new-behavior", plans=[plan], body=body))


if __name__ == "__main__":
    unittest.main()
