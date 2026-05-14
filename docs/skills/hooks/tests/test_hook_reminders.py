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
) -> None:
    jugg_bin = Path(home) / ".jugg" / "bin"
    jugg_bin.mkdir(parents=True, exist_ok=True)
    jugg_cli = jugg_bin / "jugg.py"
    files_json = json.dumps(files or [])
    enabled_android_test_python = "True" if enabled_android_test else "False"
    jugg_cli.write_text(
        "#!/usr/bin/env python3\n"
        "import json\n"
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
    return Path(home) / ".jugg" / "hooks" / ".state" / f"{digest}.json"


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
                env={**os.environ, "HOME": home, "JUGG_HOOK_DEBUG_LOG": str(debug_log)},
                check=False,
            )

            state_dir = Path(home) / ".jugg" / "hooks" / ".state"
            status_called = status_marker.exists()
            state_exists = state_dir.exists()
            log_text = debug_log.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertFalse(status_called)
        self.assertFalse(state_exists)
        self.assertIn("[JUGG-START]", log_text)
        self.assertIn("hook triggered", log_text)

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
                env={**os.environ, "HOME": home},
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
                env={**os.environ, "HOME": home},
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)

    def test_command_hook_is_raw_gradle_detection(self):
        mod = _load_hook_module("command.py")
        self.assertTrue(mod.is_raw_gradle_command("./gradlew :app:assembleDebug"))
        self.assertFalse(mod.is_raw_gradle_command("python3 ~/.jugg/bin/jugg.py gradle-build"))

    def test_command_hook_records_low_risk_shell_source_write_without_status_lookup(self):
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
                env={**os.environ, "HOME": home, "JUGG_HOOK_DEBUG_LOG": str(debug_log)},
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))
            log_text = debug_log.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode)
        self.assertFalse(status_marker.exists())
        self.assertTrue(state.get("sessionWriteSeen"))
        self.assertIsInstance(state.get("lastWriteTimeMs"), int)
        self.assertIn("shellCommand=", log_text)
        self.assertIn("\\n", log_text)

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
                env={**os.environ, "HOME": home},
                check=False,
            )
            state_path = _state_file(home, cwd, session_id)

        self.assertEqual(0, result.returncode)
        self.assertFalse(state_path.exists())

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
            _write_fake_jugg_cli(home, total=1, enabled_android_test=True)
            first = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env={**os.environ, "HOME": home},
                check=False,
            )
            second = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env={**os.environ, "HOME": home},
                check=False,
            )
            state = json.loads(_state_file(home, cwd, session_id).read_text(encoding="utf-8"))

        self.assertEqual(2, first.returncode)
        self.assertIn("Do not verify with raw Gradle here", first.stderr)
        self.assertIn("Jugg status:", first.stderr)
        self.assertIn("enabledAndroidTest: true", first.stderr)
        self.assertIn("pendingModifiedFiles: {\"total\":1}", first.stderr)
        self.assertEqual(0, second.returncode)
        self.assertIn("Allowing this repeated command attempt", second.stderr)
        self.assertEqual(1, state.get("gradleBlockCount"))
        self.assertTrue(state.get("gradleBlockedFingerprint"))

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
                env={**os.environ, "HOME": home},
                check=False,
            )
            state = json.loads(state_file.read_text(encoding="utf-8"))

        self.assertEqual(2, result.returncode)
        self.assertIn("Do not verify with raw Gradle here", result.stderr)
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
                env={**os.environ, "HOME": home},
                check=False,
            )
            second = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env={**os.environ, "HOME": home},
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
                env={**os.environ, "HOME": home},
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
                env={**os.environ, "HOME": home},
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
                env={**os.environ, "HOME": home},
                check=False,
            )
            state = json.loads(state_file.read_text(encoding="utf-8"))

        self.assertEqual(0, result.returncode)
        self.assertEqual(0, state.get("gradleBlockCount"))
        self.assertNotIn("gradleBlockedFingerprint", state)


if __name__ == "__main__":
    unittest.main()
