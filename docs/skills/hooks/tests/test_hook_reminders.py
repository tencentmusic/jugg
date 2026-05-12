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


def _write_fake_jugg_cli(home: str, total: int) -> None:
    jugg_bin = Path(home) / ".jugg" / "bin"
    jugg_bin.mkdir(parents=True, exist_ok=True)
    jugg_cli = jugg_bin / "jugg.py"
    jugg_cli.write_text(
        "#!/usr/bin/env python3\n"
        "import json\n"
        f"print(json.dumps({{'status': 'OK', 'data': {{'hasBeenFullCompiled': True, 'fileCounts': {{'total': {total}}}}}}}))\n",
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

    def test_edit_hook_is_noop(self):
        script = Path(__file__).resolve().parent.parent / "edit.py"
        payload = {"session": {"id": "s-1"}, "tool_name": "Edit"}
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
            state_files = list((Path(home) / ".jugg" / "hooks" / ".state").glob("*.json"))

        self.assertEqual(0, result.returncode)
        self.assertEqual([], state_files)

    def test_command_hook_is_raw_gradle_detection(self):
        mod = _load_hook_module("command.py")
        self.assertTrue(mod.is_raw_gradle_command("./gradlew :app:assembleDebug"))
        self.assertFalse(mod.is_raw_gradle_command("python3 ~/.jugg/bin/jugg.py gradle-build"))

    def test_command_hook_blocks_first_and_allows_second_when_pending(self):
        script = Path(__file__).resolve().parent.parent / "command.py"
        session_id = "session-123"
        payload = {
            "session": {"id": session_id},
            "tool_name": "Bash",
            "tool_input": {"command": "./gradlew :app:assembleDebug"},
        }
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            _write_fake_jugg_cli(home, total=1)
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
        self.assertEqual(0, second.returncode)
        self.assertIn("Allowing this repeated command attempt", second.stderr)
        self.assertEqual(1, state.get("gradleBlockCount"))

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
            state_file.write_text(json.dumps({"gradleBlockCount": 1}), encoding="utf-8")
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


if __name__ == "__main__":
    unittest.main()
