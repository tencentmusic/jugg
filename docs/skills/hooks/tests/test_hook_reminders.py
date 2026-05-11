"""Tests for edit and command hook reminder decisions."""

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


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

    def test_edit_hook_collects_android_path_from_apply_patch_payload(self):
        mod = _load_hook_module("edit.py")
        payload = {
            "tool_name": "apply_patch",
            "tool_input": {
                "command": (
                    "*** Begin Patch\n"
                    "*** Update File: /repo/android_demo_project/app/src/main/java/com/example/HookTrigger.kt\n"
                    "@@\n"
                    " object HookTrigger {\n"
                    "+    const val marker = \"x\"\n"
                    " }\n"
                    "*** End Patch\n"
                ),
            },
        }

        paths = mod.collect_android_source_paths(payload)

        self.assertEqual(
            ["/repo/android_demo_project/app/src/main/java/com/example/HookTrigger.kt"],
            paths,
        )

    def test_edit_hook_collects_android_path_from_git_status_short_line(self):
        mod = _load_hook_module("edit.py")
        payload = {
            "tool_name": "Bash",
            "tool_response": "?? hook_benchmark_scratch/app/src/main/java/com/example/HookTrigger.kt\n",
        }

        paths = mod.collect_android_source_paths(payload)

        self.assertEqual(
            ["hook_benchmark_scratch/app/src/main/java/com/example/HookTrigger.kt"],
            paths,
        )

    def test_edit_hook_extracts_status_compile_time(self):
        mod = _load_hook_module("edit.py")
        structured = {
            "data": {
                "lastCompileTime": "2026-04-25 10:04:00",
            },
        }

        self.assertEqual("2026-04-25 10:04:00", mod.extract_last_compile_time(structured))

    def test_command_hook_blocks_raw_gradle_after_android_edit(self):
        mod = _load_hook_module("command.py")
        payload = {
            "tool_name": "Bash",
            "tool_input": {
                "command": "./gradlew :app:assembleDebug",
            },
        }

        self.assertTrue(mod.should_block_gradle_command(payload, {"androidEditPending": True}))

    def test_command_hook_ignores_legacy_edit_reminder_marker(self):
        mod = _load_hook_module("command.py")
        payload = {
            "tool_name": "Bash",
            "tool_input": {
                "command": "./gradlew :app:assembleDebug",
            },
        }

        legacy_state = {"androidEditReminderShown": True}

        self.assertFalse(mod.should_block_gradle_command(payload, legacy_state))

    def test_edit_hook_records_pending_state_without_soft_reminder(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        payload = {
            "tool_name": "Edit",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/Foo.kt",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            jugg_bin = Path(home) / ".jugg" / "bin"
            jugg_bin.mkdir(parents=True)
            jugg_cli = jugg_bin / "jugg.py"
            jugg_cli.write_text(
                "#!/usr/bin/env python3\n"
                "import json\n"
                "print(json.dumps({'status': 'OK', 'data': {'hasBeenFullCompiled': True, "
                "'lastCompileTime': '2026-04-25 10:04:00'}}))\n",
                encoding="utf-8",
            )
            jugg_cli.chmod(0o755)

            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env={**os.environ, "HOME": home, "JUGG_HOOK_DEBUG_LOG": str(Path(home) / "debug.log")},
                check=False,
            )

            state_files = list((Path(home) / ".jugg" / "hooks" / ".state").glob("*.json"))
            self.assertEqual(1, len(state_files))
            state = json.loads(state_files[0].read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertTrue(state.get("androidEditPending"))
        self.assertNotIn("androidEditReminderShown", state)
        self.assertEqual("2026-04-25 10:04:00", state.get("androidEditBaselineCompileTime"))

    def test_start_hook_only_logs_without_status_or_state(self):
        script = Path(__file__).resolve().parent.parent / "start.py"
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            debug_log = Path(home) / "debug.log"
            status_marker = Path(home) / "status-called"
            jugg_bin = Path(home) / ".jugg" / "bin"
            jugg_bin.mkdir(parents=True)
            jugg_cli = jugg_bin / "jugg.py"
            jugg_cli.write_text(
                "#!/usr/bin/env python3\n"
                "from pathlib import Path\n"
                f"Path({str(status_marker)!r}).write_text('called', encoding='utf-8')\n"
                "print('{\"status\":\"OK\",\"data\":{\"hasBeenFullCompiled\":true}}')\n",
                encoding="utf-8",
            )
            jugg_cli.chmod(0o755)

            result = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                capture_output=True,
                text=True,
                cwd=cwd,
                env={**os.environ, "HOME": home, "JUGG_HOOK_DEBUG_LOG": str(debug_log)},
                check=False,
            )

            state_dir = Path(home) / ".jugg" / "hooks" / ".state"
            log_text = debug_log.read_text(encoding="utf-8")
            status_called = status_marker.exists()
            state_exists = state_dir.exists()

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertFalse(status_called)
        self.assertFalse(state_exists)
        self.assertIn("[JUGG-START]", log_text)
        self.assertIn("hook triggered", log_text)

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

    def test_hook_common_skips_project_without_full_compile_baseline(self):
        mod = _load_hook_module("hook_common.py")

        self.assertFalse(mod.project_allows_hooks({"hasBeenFullCompiled": False}))

    def test_hook_common_allows_project_with_full_compile_baseline(self):
        mod = _load_hook_module("hook_common.py")

        self.assertTrue(mod.project_allows_hooks({"hasBeenFullCompiled": True}))

    def test_hook_common_allows_legacy_project_when_flag_is_missing(self):
        mod = _load_hook_module("hook_common.py")

        self.assertTrue(mod.project_allows_hooks({"projectDir": "/repo"}))

    def test_hook_common_matches_longest_project_dir(self):
        mod = _load_hook_module("hook_common.py")
        projects = [
            {"projectDir": "/repo"},
            {"projectDir": "/repo/app"},
        ]

        matched = mod.match_project_info("/repo/app/module", projects)

        self.assertEqual("/repo/app", matched["projectDir"])


if __name__ == "__main__":
    unittest.main()
