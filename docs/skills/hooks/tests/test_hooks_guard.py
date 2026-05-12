"""Tests for stop-hook stateful guard behavior."""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


def _write_fake_jugg_cli(home: str, total: int, files: list[str] | None = None) -> None:
    jugg_bin = Path(home) / ".jugg" / "bin"
    jugg_bin.mkdir(parents=True, exist_ok=True)
    jugg_cli = jugg_bin / "jugg.py"
    files_json = json.dumps(files or [])
    jugg_cli.write_text(
        "#!/usr/bin/env python3\n"
        "import json\n"
        "payload = {\n"
        "    'status': 'OK',\n"
        "    'data': {\n"
        "        'hasBeenFullCompiled': True,\n"
        f"        'fileCounts': {{'total': {total}}},\n"
        f"        'files': {files_json},\n"
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


class StopHookGuardTest(unittest.TestCase):
    def test_stop_hook_blocks_first_and_allows_second_when_pending(self):
        script = Path(__file__).resolve().parent.parent / "stop.py"
        session_id = "session-stop-1"
        payload = {"session": {"id": session_id}}
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
            _write_fake_jugg_cli(home, total=2)
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
        self.assertIn("Before stopping, you must enable the jugg-android-dev-loop skill", first.stderr)
        self.assertEqual(0, second.returncode)
        self.assertIn("allowing session stop after a repeated stop attempt", second.stderr)
        self.assertEqual(1, state.get("stopBlockCount"))

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
                env={**os.environ, "HOME": home},
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
            _write_fake_jugg_cli(home, total=2, files=files)
            result = subprocess.run(
                [sys.executable, str(script)],
                input=json.dumps(payload),
                capture_output=True,
                text=True,
                cwd=cwd,
                env={**os.environ, "HOME": home},
                check=False,
            )

        self.assertEqual(2, result.returncode)
        self.assertIn("Modified files: StopHookTrigger.kt, Another.kt", result.stderr)


if __name__ == "__main__":
    unittest.main()
