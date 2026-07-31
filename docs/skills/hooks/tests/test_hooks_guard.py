"""Tests for stop-hook stateful guard behavior."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SESSION_WRITE_FILE_NAMES_KEY = "sessionWriteFileNames"


def _write_fake_jugg_cli(
    home: str,
    total: int,
    files: list[str] | None = None,
    last_compile_time: str = "",
    enabled_android_test: bool = False,
    expected_cwd: str | None = None,
    expected_args: list[str] | None = None,
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
    args_assertion = ""
    if expected_args is not None:
        args_assertion = (
            "import sys\n"
            f"if sys.argv[1:] != {expected_args!r}:\n"
            "    print('wrong args: ' + repr(sys.argv[1:]), file=sys.stderr)\n"
            "    sys.exit(8)\n"
        )
    jugg_cli.write_text(
        "#!/usr/bin/env python3\n"
        "import json\n"
        f"{cwd_assertion}"
        f"{args_assertion}"
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


def _hook_env(home: str) -> dict[str, str]:
    return {**os.environ, "HOME": home, "USERPROFILE": home}


class StopHookGuardTest(unittest.TestCase):
    def test_stop_hook_requests_full_status_info(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-full-info"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(
                home,
                total=1,
                files=["/repo/app/src/main/java/com/example/HookStop.kt"],
                expected_args=[
                    "--console=json",
                    "status",
                    "--refresh-changes",
                    "true",
                    "--full-info",
                    "true",
                ],
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
        self.assertIn("STOP GATE", result.stderr)

    def test_stop_hook_skips_block_and_retry_when_disable_flag_present(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-disable-block"
        payload = {"session": {"id": session_id}}
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
        self.assertNotIn("stop gate", first.stderr.lower())
        self.assertNotIn("allowing session stop after a repeated stop attempt", second.stderr.lower())

    def test_stop_hook_blocks_first_and_allows_second_when_pending(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-1"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(
                home,
                total=2,
                files=[
                    "/repo/app/src/main/java/com/example/PendingOne.kt",
                    "/repo/app/src/main/java/com/example/PendingTwo.kt",
                ],
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
        self.assertIn("STOP GATE", first.stderr)
        self.assertIn("Jugg dev loop skipped: <reason>", first.stderr)
        self.assertIn("Jugg status:", first.stderr)
        self.assertIn("enabledAndroidTest: true", first.stderr)
        self.assertIn("pendingModifiedFiles: PendingOne.kt, PendingTwo.kt", first.stderr)
        self.assertEqual(0, second.returncode)
        self.assertIn("allowing session stop after a repeated stop attempt", second.stderr)
        self.assertEqual(1, state.get("stopBlockCount"))

    def test_stop_hook_uses_state_project_cwd_when_hook_cwd_is_global(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-global-cwd"
        payload = {"session": {"id": session_id}}
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
        self.assertIn("followup_message", response)
        self.assertIn("STOP GATE", response["followup_message"])

    def test_stop_hook_uses_cursor_workspace_roots_when_project_cwd_is_absent(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-workspace-roots"
        payload = {
            "session": {"id": session_id},
            "workspace_roots": [],
            "cwd": "",
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
        self.assertIn("followup_message", response)
        self.assertEqual(str(Path(project_cwd).resolve()), state.get("projectCwd"))

    def test_stop_hook_ignores_state_project_cwd_for_non_cursor_clients(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-non-cursor-cwd"
        payload = {"session": {"id": session_id}}
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
        self.assertIn("STOP GATE", result.stderr)

    def test_stop_hook_uses_codex_system_message_for_repeated_pending_warning(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-codex"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps({"sessionWriteSeen": True, "stopBlockCount": 1}),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1)
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codex"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            warning_payload = json.loads(result.stdout)

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertIn("systemMessage", warning_payload)
        self.assertIn("allowing session stop after a repeated stop attempt", warning_payload["systemMessage"])

    def test_stop_hook_uses_claude_system_message_for_repeated_pending_warning(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-claude"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps({"sessionWriteSeen": True, "stopBlockCount": 1}),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1)
            result = subprocess.run(
                [sys.executable, str(script), "--client", "claude"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            warning_payload = json.loads(result.stdout)

        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stderr)
        self.assertIn("systemMessage", warning_payload)
        self.assertIn("allowing session stop after a repeated stop attempt", warning_payload["systemMessage"])

    def test_stop_hook_emits_codebuddy_stdout_json_on_first_block(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-codebuddy"
        payload = {"session": {"id": session_id}, "client": "CLI"}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=1, files=["/repo/app/src/main/java/com/example/HookStop.kt"])
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )
            response = json.loads(result.stdout)

        self.assertEqual(2, result.returncode)
        self.assertFalse(response["continue"])
        self.assertIn("stopReason", response)
        self.assertIn("STOP GATE", response["stopReason"])
        self.assertIn("HookStop.kt", response["stopReason"])
        self.assertEqual("", result.stderr)

    def test_stop_hook_emits_codebuddy_stderr_on_first_block_for_ide(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-codebuddy-ide"
        payload = {"session": {"id": session_id}, "client": "CodeBuddyIDE"}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=1, files=["/repo/app/src/main/java/com/example/HookStop.kt"])
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env=_hook_env(home),
                check=False,
            )

        self.assertEqual(2, result.returncode)
        self.assertIn("STOP GATE", result.stderr)

    def test_stop_hook_emits_codebuddy_system_message_on_repeated_stop(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-codebuddy-repeat"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(
                json.dumps({"sessionWriteSeen": True, "stopBlockCount": 1}),
                encoding="utf-8",
            )
            _write_fake_jugg_cli(home, total=1)
            result = subprocess.run(
                [sys.executable, str(script), "--client", "codebuddy"],
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
        self.assertTrue(response["continue"])
        self.assertIn("systemMessage", response)
        self.assertIn("allowing session stop after a repeated stop attempt", response["systemMessage"])

    def test_stop_hook_uses_cursor_followup_for_first_block(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-cursor"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=1, files=["/repo/app/src/main/java/com/example/HookStop.kt"])
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
        self.assertIn("followup_message", response)
        self.assertIn("STOP GATE", response["followup_message"])
        self.assertIn("HookStop.kt", response["followup_message"])

    def test_stop_hook_silently_allows_repeated_cursor_stop(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-cursor-repeat"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True, "stopBlockCount": 1}), encoding="utf-8")
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

        self.assertEqual(0, result.returncode)
        self.assertEqual("{}", result.stdout.strip())
        self.assertEqual("", result.stderr)

    def test_stop_hook_allows_pending_files_without_session_write(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-pull"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            _write_fake_jugg_cli(home, total=2)
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

    def test_stop_hook_allows_pending_files_after_session_write_was_verified(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-verified-pending"
        payload = {"session": {"id": session_id}}
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

    def test_stop_hook_resets_count_when_no_pending_files(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-2"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"stopBlockCount": 1}), encoding="utf-8")
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
        self.assertEqual(0, state.get("stopBlockCount"))

    def test_stop_hook_first_block_includes_modified_file_names(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-3"
        payload = {"session": {"id": session_id}}
        files = [
            "/repo/hook_benchmark_scratch/app/src/main/java/com/example/StopHookTrigger.kt",
            "/repo/hook_benchmark_scratch/app/src/main/java/com/example/Another.kt",
        ]
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            state_file = _state_file(home, cwd, session_id)
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"sessionWriteSeen": True}), encoding="utf-8")
            _write_fake_jugg_cli(home, total=2, files=files)
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
        self.assertIn("pendingModifiedFiles: StopHookTrigger.kt, Another.kt", result.stderr)

    def test_stop_hook_allows_when_pending_file_name_does_not_match_recorded_write(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-name-mismatch"
        payload = {"session": {"id": session_id}}
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
            _write_fake_jugg_cli(home, total=1, files=["app/src/main/java/com/example/Another.kt"])
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

    def test_stop_hook_blocks_when_pending_file_name_matches_recorded_write(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-name-match"
        payload = {"session": {"id": session_id}}
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
            _write_fake_jugg_cli(home, total=1, files=["/repo/app/src/main/java/com/example/HookEdit.kt"])
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
        self.assertIn("STOP GATE", result.stderr)


if __name__ == "__main__":
    unittest.main()
