"""Tests for edit and command hook reminder decisions."""

import importlib.util
import os
import unittest


def _load_hook_module(file_name):
    base = os.path.join(os.path.dirname(__file__), "..", file_name)
    spec = importlib.util.spec_from_file_location(f"jugg_{file_name}", base)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"failed to load hook module {file_name}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HookReminderDecisionTest(unittest.TestCase):
    def test_edit_hook_collects_android_source_paths(self):
        mod = _load_hook_module("edit.py")
        payload = {
            "tool_name": "Edit",
            "tool_input": {
                "file_path": "/repo/app/src/main/java/com/example/Foo.kt",
            },
        }

        paths = mod.collect_android_source_paths(payload)

        self.assertEqual(["/repo/app/src/main/java/com/example/Foo.kt"], paths)

    def test_edit_hook_ignores_docs_and_plain_text(self):
        mod = _load_hook_module("edit.py")
        payload = {
            "tool_input": {
                "file_path": "/repo/docs/notes.md",
                "other": "/repo/app/src/main/res/values/strings.txt",
            },
        }

        self.assertEqual([], mod.collect_android_source_paths(payload))

    def test_command_hook_blocks_raw_gradle_after_android_edit(self):
        mod = _load_hook_module("command.py")
        payload = {
            "tool_name": "Bash",
            "tool_input": {
                "command": "./gradlew :app:assembleDebug",
            },
        }

        self.assertTrue(mod.should_block_gradle_command(payload, {"androidEditReminderShown": True}))

    def test_command_hook_allows_jugg_gradle_build(self):
        mod = _load_hook_module("command.py")
        payload = {
            "tool_input": {
                "command": "python3 ~/.jugg/bin/jugg.py gradle-build",
            },
        }

        self.assertFalse(mod.should_block_gradle_command(payload, {"androidEditReminderShown": True}))

    def test_command_hook_allows_gradle_without_android_edit_marker(self):
        mod = _load_hook_module("command.py")
        payload = {
            "tool_input": {
                "command": "./gradlew :idea:compileKotlin",
            },
        }

        self.assertFalse(mod.should_block_gradle_command(payload, {}))


if __name__ == "__main__":
    unittest.main()
