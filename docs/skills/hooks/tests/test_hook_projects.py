from __future__ import annotations

import sys
import tempfile
import unittest
import importlib.util
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hook_common import (
    discover_available_projects,
    project_cwds_for_files,
    session_write_needs_verification,
)

_EDIT_SPEC = importlib.util.spec_from_file_location(
    "jugg_edit", Path(__file__).resolve().parents[1] / "edit.py"
)
_EDIT = importlib.util.module_from_spec(_EDIT_SPEC)
assert _EDIT_SPEC.loader is not None
_EDIT_SPEC.loader.exec_module(_EDIT)


def _complete_flag(directory: Path) -> None:
    path = directory / "build" / "jugg" / "database" / "compile_context.db" / "complete_flag"
    path.parent.mkdir(parents=True)
    path.write_text("", encoding="utf-8")


class HookProjectDiscoveryTest(unittest.TestCase):
    def test_stop_matches_full_session_path_against_relative_status_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            file_path = project / "wemusic" / "ConfigInfo.kt"
            state = {
                "sessionWriteSeen": True,
                "sessionWriteFiles": [str(file_path)],
                "lastWriteTimeMs": 2_000,
            }
            structured = {
                "data": {
                    "files": ["wemusic/ConfigInfo.kt"],
                    "lastCompileTime": "1970-01-01 00:00:00",
                }
            }
            self.assertTrue(session_write_needs_verification(state, structured, str(project)))

    def test_antigravity_path_accepts_json_encoded_tool_arguments(self):
        payload = {
            "toolCall": {
                "name": "replace_file_content",
                "args": '{"TargetFile":"/repo/app/src/main/java/Foo.kt","replacement":"\\n"}',
            }
        }
        self.assertEqual(
            "/repo/app/src/main/java/Foo.kt", _EDIT._antigravity_file_path(payload)
        )

    def test_available_root_prunes_nested_projects(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            nested = root / "android"
            _complete_flag(root)
            _complete_flag(nested)
            self.assertEqual([str(root.resolve())], discover_available_projects([str(root)]))

    def test_unavailable_root_searches_two_visible_levels(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "group"
            second = first / "android"
            too_deep = second / "feature"
            hidden = root / ".hidden" / "android"
            _complete_flag(second)
            _complete_flag(too_deep)
            _complete_flag(hidden)
            self.assertEqual([str(second.resolve())], discover_available_projects([str(root)]))

    def test_file_matching_uses_longest_project_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / "android"
            child = parent / "feature"
            file_path = child / "src" / "Foo.kt"
            file_path.parent.mkdir(parents=True)
            file_path.write_text("", encoding="utf-8")
            matched, unmatched = project_cwds_for_files(
                [str(file_path)], [str(parent), str(child)]
            )
            self.assertEqual([str(child.resolve())], matched)
            self.assertEqual([], unmatched)


if __name__ == "__main__":
    unittest.main()
