"""Tests for command and edit hook behaviors."""

import hashlib
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


def _write_fake_jugg_cli(
    home: str,
    total: int,
    files: list[str] | None = None,
    last_compile_time: str = "",
    enabled_android_test: bool = False,
    expected_cwd: str | None = None,
) -> None:
    jugg_bin = Path(home) / ".jugg" / "bin"
    jugg_bin.mkdir(parents=True, exist_ok=True)
    jugg_cli = jugg_bin / "jugg.py"
    files_json = json.dumps(files or [])
    enabled_android_test_python = "True" if enabled_android_test else "False"
    cwd_assertion = ""
    if expected_cwd:
        cwd_assertion = (
            "import os, sys\n"
            f"if os.getcwd() != {expected_cwd!r}:\n"
            "    print('wrong cwd: ' + os.getcwd(), file=sys.stderr)\n"
            "    sys.exit(7)\n"
        )
    jugg_cli.write_text(
        "#!/usr/bin/env python3\n"
        "import json\n"
        f"{cwd_assertion}"
        "payload = {\n"
        "    'status': 'OK',\n"
        "    'data': {\n"
        "        'hasDevice': True,\n"
        "        'needFallback': False,\n"
        "        'hasBeenFullCompiled': True,\n"
        f"        'enabledAndroidTest': {enabled_android_test_python},\n"
        f"        'pendingModifiedFiles': {{'total': {total}}},\n"
        f"        'files': {files_json},\n"
        f"        'lastCompileTime': {last_compile_time!r},\n"
        "    },\n"
        "}\n"
        "print(json.dumps(payload))\n",
        encoding="utf-8",
    )
    jugg_cli.chmod(0o755)


def _state_file(home: str, cwd: str, session_id: str) -> Path:
    resolved_cwd = str(Path(cwd).resolve())
    digest = hashlib.sha1(f"{resolved_cwd}\n{session_id}".encode("utf-8")).hexdigest()
    return Path(home) / ".jugg" / "skills" / "hooks" / ".state" / f"{digest}.json"


def _hook_env(home: str, **extra: str) -> dict[str, str]:
    env = {**os.environ, "HOME": home, "USERPROFILE": home}
    env.update(extra)
    return env


SESSION_WRITE_FILE_NAMES_KEY = "sessionWriteFileNames"


class HookReminderDecisionTest(unittest.TestCase):
    def test_start_hook_only_logs_without_status_or_state(self):
        script = Path(__file__).resolve().parent.parent / "start.py"
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            debug_log = Path(home) / "debug.log"
            status_marker = Path(home) / "status-called"
            jugg_bin = Path(home) / ".jugg" / "bin"
            jugg_bin.mkdir(parents=True, exist_ok=True)
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
                env=_hook_env(home, JUGG_HOOK_DEBUG_LOG=str(debug_log)),
                check=False,
            )

            state_dir = Path(home) / ".jugg" / "skills" / "hooks" / ".state"
            status_called = status_marker.exists()
            state_exists = state_dir.exists()
            log_text = debug_log.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertFalse(status_called)
        self.assertFalse(state_exists)
        self.assertIn("[JUGG-START]", log_text)
        self.assertIn("hook triggered", log_text)

    def test_start_hook_clears_previous_turn_state_without_status_lookup(self):
        script = Path(__file__).resolve().parent.parent / "start.py"
        session_id = "s-start-clear"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            status_marker = Path(home) / "status-called"
            jugg_bin = Path(home) / ".jugg" / "bin"
            jugg_bin.mkdir(parents=True, exist_ok=True)
            jugg_cli = jugg_bin / "jugg.py"
            jugg_cli.write_text(
                "#!/usr/bin/env python3\n"
                "from pathlib import Path\n"
                f"Path({str(status_marker)!r}).write_text('called', encoding='utf-8')\n"
                "print('{\"status\":\"OK\",\"data\":{\"hasBeenFullCompiled\":true}}')\n",
                encoding="utf-8",
            )
            jugg_cli.chmod(0o755)
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps(
                    {
                        "sessionWriteSeen": True,
                        "lastWriteTimeMs": 1778668800000,
                        SESSION_WRITE_FILE_NAMES_KEY: ["Previous.kt"],
                        "stopBlockCount": 1,
                        "gradleBlockCount": 1,
                        "gradleBlockedFingerprint": "previous",
                        "projectCwd": str(Path(cwd).resolve()),
                    }
                ),
                encoding="utf-8",
            )

            result = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(state_file.read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertFalse(status_marker.exists())
        self.assertNotIn("sessionWriteSeen", state)
        self.assertNotIn("lastWriteTimeMs", state)
        self.assertNotIn(SESSION_WRITE_FILE_NAMES_KEY, state)
        self.assertNotIn("stopBlockCount", state)
        self.assertNotIn("gradleBlockCount", state)
        self.assertNotIn("gradleBlockedFingerprint", state)
        self.assertEqual(str(Path(cwd).resolve()), state.get("projectCwd"))

    def test_edit_hook_records_session_write_without_status_lookup(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-1"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Edit",
            "tool_input": {"command": "sed -n '1,20p' app/src/main/java/Example.kt"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            status_marker = Path(home) / "status-called"
            jugg_bin = Path(home) / ".jugg" / "bin"
            jugg_bin.mkdir(parents=True, exist_ok=True)
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
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertFalse(status_marker.exists())
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertNotIn("androidEditPaths", state)

    def test_edit_hook_records_last_write_time(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-edit-time"
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            payload = {
                "session": {"id": session_id},
                "tool_name": "Edit",
                "tool_input": {"file_path": str(Path(cwd) / "Any.kt")},
            }
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["Any.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_ignores_codex_apply_patch_for_docs(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codex-docs-patch"
        payload = {
            "session_id": session_id,
            "tool_name": "apply_patch",
            "tool_input": {
                "command": (
                    "*** Begin Patch\n"
                    "*** Update File: docs/ai_knowledge/00_overview.md\n"
                    "@@\n"
                    "-old\n"
                    "+new\n"
                    "*** End Patch\n"
                )
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_exists = _state_file(home, cwd, session_id).exists()

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_exists)

    def test_edit_hook_records_codex_apply_patch_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codex-source-patch"
        payload = {
            "session_id": session_id,
            "tool_name": "apply_patch",
            "tool_input": {
                "command": (
                    "*** Begin Patch\n"
                    "*** Update File: app/src/main/java/com/example/myapplication/HookEdit.kt\n"
                    "@@\n"
                    "-old\n"
                    "+new\n"
                    "*** End Patch\n"
                )
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookEdit.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_ignores_codex_apply_patch_delete_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codex-source-delete-patch"
        payload = {
            "session_id": session_id,
            "tool_name": "apply_patch",
            "tool_input": {
                "command": (
                    "*** Begin Patch\n"
                    "*** Delete File: app/src/main/java/com/example/myapplication/HookDelete.kt\n"
                    "*** End Patch\n"
                )
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_exists = _state_file(home, cwd, session_id).exists()

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_exists)

    def test_edit_hook_ignores_claude_edit_for_docs(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-claude-docs-edit"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Edit",
            "tool_input": {
                "file_path": "/tmp/fake/docs/readme.md",
                "old_string": "old",
                "new_string": "new",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "claude"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_exists = _state_file(home, cwd, session_id).exists()

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_exists)

    def test_edit_hook_records_claude_edit_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-claude-source-edit"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Edit",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/myapplication/HookEdit.kt",
                "old_string": "old",
                "new_string": "new",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "claude"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookEdit.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_ignores_cursor_edit_for_docs(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-cursor-docs-edit"
        payload = {
            "session_id": session_id,
            "hook_event_name": "afterFileEdit",
            "file_path": "docs/ai_knowledge/00_overview.md",
            "edits": [{"old_string": "old", "new_string": "new"}],
            "workspace_roots": ["/tmp/android-demo"],
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "cursor"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_path = _state_file(home, cwd, session_id)
            if state_path.exists():
                state = json.loads(state_path.read_text(encoding="utf-8"))
                session_write_seen = state.get("sessionWriteSeen")
            else:
                session_write_seen = None

        self.assertEqual(0, result.returncode)
        self.assertNotEqual(True, session_write_seen)

    def test_edit_hook_records_cursor_edit_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-cursor-source-edit"
        payload = {
            "session_id": session_id,
            "hook_event_name": "afterFileEdit",
            "file_path": "app/src/main/java/com/example/myapplication/HookEdit.kt",
            "edits": [{"old_string": "old", "new_string": "new"}],
            "workspace_roots": ["/tmp/android-demo"],
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "cursor"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookEdit.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_records_claude_write_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-claude-source-write"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Write",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/myapplication/HookWrite.kt",
                "content": "class HookWrite",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "claude"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookWrite.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_records_project_cwd_from_absolute_file_path(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-edit-project-cwd"
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            project_path = Path(project_cwd)
            (project_path / "settings.gradle").write_text("", encoding="utf-8")
            source_path = project_path / "app/src/main/java/com/example/myapplication/HookEdit.kt"
            source_path.parent.mkdir(parents=True)
            source_path.write_text("class HookEdit", encoding="utf-8")
            payload = {
                "session_id": session_id,
                "hook_event_name": "afterFileEdit",
                "file_path": str(source_path),
                "edits": [{"old_string": "class HookEdit", "new_string": "class HookEditUpdated"}],
                "workspace_roots": [str(project_path.resolve())],
            }
            with tempfile.TemporaryDirectory() as hook_cwd:
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "cursor"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )
                state = json.loads(_state_file(home, hook_cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertEqual(str(project_path.resolve()), state.get("projectCwd"))

    def test_edit_hook_ignores_codebuddy_edit_for_docs(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codebuddy-docs-edit"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Edit",
            "tool_input": {
                "file_path": "docs/ai_knowledge/00_overview.md",
                "old_string": "old",
                "new_string": "new",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_exists = _state_file(home, cwd, session_id).exists()

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_exists)

    def test_edit_hook_records_codebuddy_edit_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codebuddy-source-edit"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Edit",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/myapplication/HookEdit.kt",
                "old_string": "old",
                "new_string": "new",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookEdit.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_records_codebuddy_write_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codebuddy-source-write"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Write",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/myapplication/HookWrite.kt",
                "content": "class HookWrite",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookWrite.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_records_codebuddy_edit_with_tool_input_file_path_camel_case(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codebuddy-ide-edit"
        source_path = (
            "/tmp/android_demo/app/src/main/java/com/example/myapplication/HookSmokeTrigger.kt"
        )
        payload = {
            "session": {"id": session_id},
            "tool_name": "Edit",
            "tool_input": {
                "filePath": source_path,
                "old_str": '"smoke trigger v1"',
                "new_str": '"smoke trigger v2"',
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertEqual(["HookSmokeTrigger.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_records_codebuddy_write_with_tool_input_file_path_camel_case(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-codebuddy-ide-write"
        source_path = (
            "/tmp/android_demo/app/src/main/java/com/example/myapplication/HookPayload.kt"
        )
        payload = {
            "session": {"id": session_id},
            "tool_name": "Write",
            "tool_input": {
                "filePath": source_path,
                "content": "object HookPayload",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertEqual(["HookPayload.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_edit_hook_ignores_gemini_write_file_for_docs(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-gemini-docs-write"
        payload = {
            "session_id": session_id,
            "tool_name": "write_file",
            "tool_input": {
                "file_path": "docs/ai_knowledge/00_overview.md",
                "content": "new content",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "gemini"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_exists = _state_file(home, cwd, session_id).exists()

        self.assertEqual(0, result.returncode)
        # Current behavior: it DOES record it (fuzzy match or fallthrough)
        # Target behavior: it should NOT record it.
        # This test will FAIL until we implement precise matching.
        self.assertFalse(state_exists)

    def test_edit_hook_records_gemini_replace_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-gemini-source-replace"
        payload = {
            "session_id": session_id,
            "tool_name": "replace",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/myapplication/HookEdit.kt",
                "old_string": "old",
                "new_string": "new",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "gemini"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookEdit.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))

    def test_command_hook_is_raw_gradle_detection(self):
        mod = _load_hook_module("command.py")
        self.assertTrue(mod.is_raw_gradle_command("./gradlew :app:assembleDebug"))
        self.assertTrue(mod.is_raw_gradle_command(".\\gradlew :app:assembleDebug"))
        self.assertTrue(mod.is_raw_gradle_command(".\\gradlew.bat :app:assembleDebug"))
        self.assertTrue(mod.is_raw_gradle_command("gradlew.bat :app:assembleDebug"))
        self.assertFalse(mod.is_raw_gradle_command("python3 ~/.jugg/bin/jugg.py gradle-build"))

    def test_command_hook_does_not_record_shell_source_write_without_status_lookup(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-shell-write"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {
                "command": "cat <<'EOF' > app/src/main/java/com/example/myapplication/HookShellTrigger.kt\nclass HookShellTrigger\nEOF"
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            debug_log = Path(home) / "debug.log"
            status_marker = Path(home) / "status-called"
            jugg_bin = Path(home) / ".jugg" / "bin"
            jugg_bin.mkdir(parents=True, exist_ok=True)
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
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home, JUGG_HOOK_DEBUG_LOG=str(debug_log)),
                check=False,
            )
            state_path = _state_file(home, cwd, session_id)
            state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
            log_text = debug_log.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode)
        self.assertFalse(status_marker.exists())
        self.assertFalse(state.get("sessionWriteSeen"))
        self.assertNotIn(SESSION_WRITE_FILE_NAMES_KEY, state)
        self.assertIn("shellCommand=", log_text)
        self.assertIn("\\n", log_text)

    def test_command_hook_does_not_record_shell_source_write_through_variable_redirect(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-shell-var-write"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {
                "command": (
                    "out=\"app/src/main/java/com/example/myapplication/HookShellTrigger.kt\"\n"
                    "cat > \"$out\" <<'EOF'\n"
                    "class HookShellTrigger\n"
                    "EOF"
                )
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "cursor"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertFalse(state.get("sessionWriteSeen"))
        self.assertNotIn(SESSION_WRITE_FILE_NAMES_KEY, state)

    def test_command_hook_ignores_vcs_commands_for_shell_write_tracking(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-vcs"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "git pull && echo x > app/src/main/java/com/example/myapplication/Pulled.kt"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_path = _state_file(home, cwd, session_id)

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_path.exists())

    def test_command_hook_skips_block_and_retry_when_disable_flag_present(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-disable-block"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            hooks_dir = Path(home) / ".jugg" / "skills" / "hooks"
            hooks_dir.mkdir(parents=True, exist_ok=True)
            (hooks_dir / "DISABLE_BLOCK").write_text("", encoding="utf-8")
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=1)
            first = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            second = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )

        self.assertEqual(0, first.returncode)
        self.assertEqual(0, second.returncode)
        self.assertNotIn("COMMAND GATE", first.stderr)
        self.assertNotIn("Allowing this repeated command attempt", second.stderr)

    def test_command_hook_blocks_first_and_allows_second_when_pending(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-123"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(
                home,
                total=1,
                files=["/repo/app/src/main/java/com/example/Pending.kt"],
                enabled_android_test=True,
            )
            first = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            second = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(2, first.returncode)
        self.assertIn("COMMAND GATE", first.stderr)
        self.assertIn("Jugg CLI verification skipped: <reason>", first.stderr)
        self.assertIn("Jugg status:", first.stderr)
        self.assertIn("enabledAndroidTest: true", first.stderr)
        self.assertIn("pendingModifiedFiles: Pending.kt", first.stderr)
        self.assertEqual(0, second.returncode)
        self.assertIn("Allowing this repeated command attempt", second.stderr)
        self.assertEqual(1, state.get("gradleBlockCount"))
        self.assertTrue(state.get("gradleBlockedFingerprint"))

    def test_command_hook_uses_cursor_permission_json_for_raw_gradle_block(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-cursor-block"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=1)
            result = subprocess.run(
                [sys.executable, str(script), "--client", "cursor"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            response = json.loads(result.stdout)

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertEqual("deny", response.get("permission"))
        self.assertIn("COMMAND GATE", response.get("agent_message", ""))

    def test_command_hook_uses_cursor_permission_json_for_repeated_warning(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-cursor-warning"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps(
                    {
                        "sessionWriteSeen": True,
                        "gradleBlockCount": 1,
                        "gradleBlockedFingerprint": "old",
                    }
                ),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1)
            first = subprocess.run(
                [sys.executable, str(script), "--client", "cursor"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            second = subprocess.run(
                [sys.executable, str(script), "--client", "cursor"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            warning = json.loads(second.stdout)

        self.assertEqual(0, first.returncode)
        self.assertEqual("deny", json.loads(first.stdout).get("permission"))
        self.assertEqual(0, second.returncode)
        self.assertEqual("allow", warning.get("permission"))
        self.assertIn("Allowing this repeated command attempt", warning.get("agent_message", ""))

    def test_command_hook_uses_state_project_cwd_when_hook_cwd_is_global(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-global-cwd"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            with tempfile.TemporaryDirectory() as hook_cwd:
                state_file = _state_file(home, hook_cwd, session_id)
                state_file.parent.mkdir(parents=True, exist_ok=True)
                state_file.write_text(
                    json.dumps({"sessionWriteSeen": True, "projectCwd": str(Path(project_cwd).resolve())}),
                    encoding="utf-8",
                )
                _write_fake_jugg_cli(home, total=1, expected_cwd=str(Path(project_cwd).resolve()))
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "cursor"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )

        response = json.loads(result.stdout)
        self.assertEqual(0, result.returncode)
        self.assertEqual("deny", response.get("permission"))
        self.assertIn("COMMAND GATE", response.get("agent_message", ""))

    def test_command_hook_uses_cursor_workspace_roots_when_project_cwd_is_absent(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-cursor-workspace-roots"
        payload = {
            "session": {"id": session_id},
            "cwd": "",
            "workspace_roots": [],
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            Path(project_cwd, "settings.gradle").write_text("", encoding="utf-8")
            payload["workspace_roots"] = [project_cwd]
            with tempfile.TemporaryDirectory() as hook_cwd:
                state_file = _state_file(home, hook_cwd, session_id)
                state_file.parent.mkdir(parents=True, exist_ok=True)
                state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
                _write_fake_jugg_cli(home, total=1, expected_cwd=str(Path(project_cwd).resolve()))
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "cursor"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )
                state = json.loads(state_file.read_text(encoding="utf-8"))

        response = json.loads(result.stdout)
        self.assertEqual(0, result.returncode)
        self.assertEqual("deny", response.get("permission"))
        self.assertEqual(str(Path(project_cwd).resolve()), state.get("projectCwd"))

    def test_command_hook_blocks_windows_cursor_root_command_payload(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-cursor-windows-command"
        payload = {
            "conversation_id": session_id,
            "session_id": session_id,
            "hook_event_name": "beforeShellExecution",
            "command": 'Set-Location "D:\\GitHub\\jugg\\android_demo_project"; .\\gradlew.bat :app:assembleDebug 2>&1',
            "cwd": "",
            "workspace_roots": [],
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            project_path = Path(project_cwd)
            (project_path / "settings.gradle").write_text("", encoding="utf-8")
            payload["workspace_roots"] = [str(project_path.resolve())]
            with tempfile.TemporaryDirectory() as hook_cwd:
                state_file = _state_file(home, hook_cwd, session_id)
                state_file.parent.mkdir(parents=True, exist_ok=True)
                state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
                _write_fake_jugg_cli(home, total=1, expected_cwd=str(project_path.resolve()))
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "cursor"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )

        response = json.loads(result.stdout)
        self.assertEqual(0, result.returncode)
        self.assertEqual("deny", response.get("permission"))
        self.assertIn("COMMAND GATE", response.get("agent_message", ""))

    def test_command_hook_blocks_windows_cursor_utf16_command_payload(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-cursor-windows-utf16-command"
        payload = {
            "conversation_id": session_id,
            "session_id": session_id,
            "hook_event_name": "beforeShellExecution",
            "command": 'Set-Location "D:\\GitHub\\jugg\\android_demo_project"; .\\gradlew :app:assembleDebug 2>&1',
            "cwd": "",
            "workspace_roots": [],
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            project_path = Path(project_cwd)
            (project_path / "settings.gradle").write_text("", encoding="utf-8")
            payload["workspace_roots"] = [str(project_path.resolve())]
            with tempfile.TemporaryDirectory() as hook_cwd:
                state_file = _state_file(home, hook_cwd, session_id)
                state_file.parent.mkdir(parents=True, exist_ok=True)
                state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
                _write_fake_jugg_cli(home, total=1, expected_cwd=str(project_path.resolve()))
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "cursor"],
                    input=json.dumps(payload).encode("utf-16"),
                    capture_output=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )

        response = json.loads(result.stdout.decode("utf-8"))
        self.assertEqual(0, result.returncode)
        self.assertEqual("deny", response.get("permission"))
        self.assertIn("COMMAND GATE", response.get("agent_message", ""))

    def test_command_hook_ignores_state_project_cwd_for_non_cursor_clients(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-non-cursor-cwd"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            with tempfile.TemporaryDirectory() as hook_cwd:
                state_file = _state_file(home, hook_cwd, session_id)
                state_file.parent.mkdir(parents=True, exist_ok=True)
                state_file.write_text(
                    json.dumps({"sessionWriteSeen": True, "projectCwd": str(Path(project_cwd).resolve())}),
                    encoding="utf-8",
                )
                _write_fake_jugg_cli(home, total=1, expected_cwd=str(Path(hook_cwd).resolve()))
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "claude"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )

        self.assertEqual(2, result.returncode)
        self.assertIn("COMMAND GATE", result.stderr)

    def test_command_hook_records_project_cwd_from_payload_cwd_for_shell_write(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-shell-project-cwd"
        payload = {
            "session": {"id": session_id},
            "cwd": "",
            "tool_name": "Bash",
            "tool_input": {
                "cwd": "",
                "command": "cat > app/src/main/java/com/example/myapplication/HookShellTrigger.kt <<'EOF'\nclass HookShellTrigger\nEOF",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            payload["cwd"] = project_cwd
            payload["tool_input"]["cwd"] = project_cwd
            with tempfile.TemporaryDirectory() as hook_cwd:
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "cursor"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )
                state = json.loads(_state_file(home, hook_cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertEqual(str(Path(project_cwd).resolve()), state.get("projectCwd"))

    def test_command_hook_does_not_record_project_cwd_from_payload_for_non_cursor_clients(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-shell-non-cursor"
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as project_cwd:
            payload = {
                "session": {"id": session_id},
                "cwd": project_cwd,
                "tool_name": "Bash",
                "tool_input": {
                    "cwd": project_cwd,
                    "command": "cat > app/src/main/java/com/example/myapplication/HookShellTrigger.kt <<'EOF'\nclass HookShellTrigger\nEOF",
                },
            }
            with tempfile.TemporaryDirectory() as hook_cwd:
                result = subprocess.run(
                    [sys.executable, str(script), "--client", "claude"],
                    input=json.dumps(payload),
                    capture_output=True,
                    text=True,
                    cwd=hook_cwd,
                    env=_hook_env(home),
                    check=False,
                )
                state_path = _state_file(home, hook_cwd, session_id)

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_path.exists())

    def test_command_hook_blocks_again_when_pending_fingerprint_changes(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-fingerprint"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            old_fingerprint = "old-pending-file"
            state_file.write_text(
                json.dumps(
                    {
                        "sessionWriteSeen": True,
                        "gradleBlockCount": 1,
                        "gradleBlockedFingerprint": old_fingerprint,
                    }
                ),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1, files=["app/src/main/java/com/example/myapplication/NewHook.kt"])
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(state_file.read_text(encoding="utf-8"))

        self.assertEqual(2, result.returncode)
        self.assertIn("COMMAND GATE", result.stderr)
        self.assertEqual(1, state.get("gradleBlockCount"))
        self.assertNotEqual(old_fingerprint, state.get("gradleBlockedFingerprint"))

    def test_command_hook_uses_codex_system_message_for_repeated_raw_gradle_warning(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-codex-warning"
        files = ["app/src/main/java/com/example/myapplication/HookCodex.kt"]
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=1, files=files, enabled_android_test=True)
            first = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            second = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            warning_payload = json.loads(second.stdout)

        self.assertEqual(0, first.returncode)
        self.assertEqual("", first.stderr)
        first_payload = json.loads(first.stdout)
        reason = first_payload["hookSpecificOutput"]["permissionDecisionReason"]
        self.assertIn("permissionDecision", first.stdout)
        self.assertIn("Jugg status:", reason)
        self.assertIn("enabledAndroidTest: true", reason)
        self.assertEqual(0, second.returncode)
        self.assertEqual("", second.stderr)
        self.assertIn("systemMessage", warning_payload)
        self.assertIn("Allowing this repeated command attempt", warning_payload["systemMessage"])

    def test_command_hook_uses_claude_system_message_for_repeated_raw_gradle_warning(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-claude-warning"
        files = ["app/src/main/java/com/example/myapplication/HookClaude.kt"]
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps(
                    {
                        "sessionWriteSeen": True,
                        "gradleBlockCount": 1,
                        "gradleBlockedFingerprint": "old",
                    }
                ),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1, files=files, enabled_android_test=True)
            blocked = subprocess.run(
                [sys.executable, str(script), "--client", "claude"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            allowed = subprocess.run(
                [sys.executable, str(script), "--client", "claude"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            warning_payload = json.loads(allowed.stdout)

        self.assertEqual(2, blocked.returncode)
        self.assertIn("COMMAND GATE", blocked.stderr)
        self.assertEqual(0, allowed.returncode)
        self.assertEqual("", allowed.stderr)
        self.assertIn("systemMessage", warning_payload)
        self.assertIn("Allowing this repeated command attempt", warning_payload["systemMessage"])

    def test_command_hook_allows_pending_files_without_session_write(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-pull-1"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            _write_fake_jugg_cli(home, total=1)
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)

    def test_command_hook_allows_pending_files_after_session_write_was_verified(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-verified-pending"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps({"sessionWriteSeen": True, "lastWriteTimeMs": 1778668800000}),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1, last_compile_time="2026-05-13 19:15:03")
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)

    def test_command_hook_resets_block_count_when_no_pending_files(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-456"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True, "gradleBlockCount": 1}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=0)
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(state_file.read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertEqual(0, state.get("gradleBlockCount"))
        self.assertNotIn("gradleBlockedFingerprint", state)

    def test_command_hook_allows_when_pending_file_name_does_not_match_recorded_write(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-name-mismatch"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps(
                    {
                        "sessionWriteSeen": True,
                        SESSION_WRITE_FILE_NAMES_KEY: ["HookEdit.kt"],
                    }
                ),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(
                home,
                total=1,
                files=["/repo/app/src/main/java/com/example/myapplication/Another.kt"],
            )
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)

    def test_command_hook_blocks_when_pending_file_name_matches_recorded_write(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-name-match"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps(
                    {
                        "sessionWriteSeen": True,
                        SESSION_WRITE_FILE_NAMES_KEY: ["HookEdit.kt"],
                    }
                ),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(
                home,
                total=1,
                files=["app/src/main/java/com/example/myapplication/HookEdit.kt"],
            )
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )

        self.assertEqual(2, result.returncode)
        self.assertIn("COMMAND GATE", result.stderr)

    def test_edit_hook_ignores_gemini_write_file_for_docs(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-gemini-docs-write"
        payload = {
            "session_id": session_id,
            "tool_name": "write_file",
            "tool_input": {
                "file_path": "docs/ai_knowledge/00_overview.md",
                "content": "new content",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "gemini"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state_exists = _state_file(home, cwd, session_id).exists()

        self.assertEqual(0, result.returncode)
        # Current behavior: it DOES record it (fuzzy match or fallthrough)
        # Target behavior: it should NOT record it.
        self.assertFalse(state_exists)

    def test_edit_hook_records_gemini_replace_for_android_source(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        session_id = "s-gemini-source-replace"
        payload = {
            "session_id": session_id,
            "tool_name": "replace",
            "tool_input": {
                "file_path": "app/src/main/java/com/example/myapplication/HookEdit.kt",
                "old_string": "old",
                "new_string": "new",
            },
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, str(script), "--client", "gemini"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertEqual(["HookEdit.kt"], state.get(SESSION_WRITE_FILE_NAMES_KEY))


if __name__ == "__main__":
    unittest.main()
