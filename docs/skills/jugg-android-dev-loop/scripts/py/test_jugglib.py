"""Tests for jugglib compile_call message selection logic."""

import io
import sys
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, "/Users/wormchen/IdeaProjects/jugg/jugg_f1/docs/skills/jugg-android-dev-loop/scripts/py")
import jugglib


def _make_compile_response(data_status: str, data_message: str) -> dict:
    """Build a structuredContent dict simulating compile/get-compile-status response."""
    return {
        "status": "OK",
        "message": "compile executed successfully.",
        "data": {
            "status": data_status,
            "message": data_message,
            "jobId": "job-123",
            "pollIntervalSuggestedMs": 100,
        },
    }


def _make_poll_response(data_status: str) -> dict:
    """Build a structuredContent dict simulating get-compile-status response."""
    return {
        "status": "OK",
        "message": "get-compile-status executed successfully.",
        "data": {
            "status": data_status,
            "message": "Compile succeeded.",
            "jobId": "job-123",
            "isCompileSuccess": True,
            "isDeploySuccess": False,
        },
    }


class TestCompileCallMessageOnSuccess(unittest.TestCase):
    """compile_call should print compile's own message on success, not the poll loop's message."""

    def _run_compile_call(self, initial: dict, poll_final: dict) -> str:
        """Helper: patch dependencies, run compile_call, return captured stdout."""
        with (
            patch.object(jugglib, "resolve_project_dir", return_value="/proj"),
            patch.object(jugglib, "resolve_port", return_value=12320),
            patch.object(jugglib, "raw_call") as mock_raw_call,
        ):
            # First call: compile tool; subsequent calls: get-compile-status
            def side_effect(port, tool, params):
                if tool == "compile":
                    return {"result": {"structuredContent": initial}}
                return {"result": {"structuredContent": poll_final}}

            mock_raw_call.side_effect = side_effect

            captured = io.StringIO()
            with patch("sys.stdout", captured):
                jugglib.compile_call("compile")

        return captured.getvalue()

    def test_success_message_comes_from_compile_not_poll(self):
        """On success, message must be from the compile response, not get-compile-status."""
        initial = _make_compile_response("running", "Compile started.")
        final_poll = _make_poll_response("success")

        output = self._run_compile_call(initial, final_poll)

        # Should NOT contain the poll loop's boilerplate message
        self.assertNotIn("get-compile-status executed successfully", output)

    def test_success_prints_compile_data_message(self):
        """On success, data.message from the final compile status is printed."""
        initial = _make_compile_response("running", "Compile started.")
        final_poll = _make_poll_response("success")

        output = self._run_compile_call(initial, final_poll)

        self.assertIn("Compile succeeded.", output)

    def test_already_final_on_first_response_uses_data_message(self):
        """When compile returns final status immediately (no polling needed), message is correct."""
        initial = _make_compile_response("success", "Compile succeeded immediately.")
        # poll should not be called, but provide a wrong response just in case
        wrong_poll = _make_poll_response("success")
        wrong_poll["message"] = "get-compile-status executed successfully."

        output = self._run_compile_call(initial, wrong_poll)

        self.assertNotIn("get-compile-status executed successfully", output)
        self.assertIn("Compile succeeded immediately.", output)

    def test_compile_success_hides_compile_and_deploy_result(self):
        """Human-readable compile output should not print isCompileSuccess or isDeploySuccess."""
        initial = _make_compile_response("running", "Compile started.")
        final_poll = _make_poll_response("success")

        output = self._run_compile_call(initial, final_poll)

        self.assertNotIn("isCompileSuccess", output)
        self.assertNotIn("isDeploySuccess", output)


class TestCompileCallMessageOnFailure(unittest.TestCase):
    """compile_call should print compile/deploy result flags on failure (except for compile-only command)."""

    def test_failure_prints_compile_and_deploy_result(self):
        structured = {
            "status": "ERROR",
            "message": "deploy failed.",
            "data": {
                "status": "failed",
                "message": "deploy failed.",
                "jobId": "job-123",
                "isCompileSuccess": True,
                "isDeploySuccess": False,
                "detail": "No device found. Stop deploying.",
                "logPath": "build/jugg/log/compile_latest.log",
            },
        }

        with (
            patch.object(jugglib, "resolve_project_dir", return_value="/proj"),
            patch.object(jugglib, "resolve_port", return_value=12320),
            patch.object(jugglib, "raw_call", return_value={"result": {"structuredContent": structured}}),
        ):
            captured = io.StringIO()
            with patch("sys.stderr", captured):
                with self.assertRaises(SystemExit):
                    jugglib.compile_call("deploy")

        output = captured.getvalue()
        self.assertIn("isCompileSuccess: true", output)
        self.assertIn("isDeploySuccess: false", output)

    def test_failure_prefers_data_message(self):
        structured = {
            "status": "ERROR",
            "message": "deploy failed. Reason: deploy failed.",
            "data": {
                "status": "failed",
                "message": "No device found. Stop deploying.",
                "jobId": "job-123",
                "isCompileSuccess": True,
                "isDeploySuccess": False,
                "detail": "No device found. Stop deploying.",
                "logPath": "build/jugg/log/compile_latest.log",
            },
        }

        with (
            patch.object(jugglib, "resolve_project_dir", return_value="/proj"),
            patch.object(jugglib, "resolve_port", return_value=12320),
            patch.object(jugglib, "raw_call", return_value={"result": {"structuredContent": structured}}),
        ):
            captured = io.StringIO()
            with patch("sys.stderr", captured):
                with self.assertRaises(SystemExit):
                    jugglib.compile_call("deploy")

        output = captured.getvalue()
        self.assertIn("message: No device found. Stop deploying.", output)
        self.assertNotIn("message: deploy failed. Reason: deploy failed.", output)

    def test_compile_failure_hides_deploy_result(self):
        structured = {
            "status": "ERROR",
            "message": "compile failed.",
            "data": {
                "status": "failed",
                "message": "compile failed.",
                "jobId": "job-123",
                "isCompileSuccess": False,
                "isDeploySuccess": False,
                "detail": "Compilation failed.",
                "logPath": "build/jugg/log/compile_latest.log",
            },
        }

        with (
            patch.object(jugglib, "resolve_project_dir", return_value="/proj"),
            patch.object(jugglib, "resolve_port", return_value=12320),
            patch.object(jugglib, "raw_call", return_value={"result": {"structuredContent": structured}}),
        ):
            captured = io.StringIO()
            with patch("sys.stderr", captured):
                with self.assertRaises(SystemExit):
                    jugglib.compile_call("compile")

        output = captured.getvalue()
        self.assertNotIn("isCompileSuccess", output)
        self.assertNotIn("isDeploySuccess", output)


class TestPollCompileWaitTimeout(unittest.TestCase):
    """poll_compile should call get-compile-status immediately with waitTimeoutMs."""

    def test_poll_compile_calls_get_status_with_wait_timeout(self):
        initial = _make_compile_response("running", "Compile started.")
        final = _make_poll_response("success")

        with (
            patch.object(jugglib, "raw_call", return_value={"result": {"structuredContent": final}}) as mock_raw_call,
            patch.object(jugglib.time, "sleep") as mock_sleep,
        ):
            structured = jugglib.poll_compile(12320, "/proj", initial)

        self.assertEqual("success", structured.get("data", {}).get("status"))
        mock_raw_call.assert_called_once_with(
            12320,
            "get-compile-status",
            {"projectDir": "/proj", "jobId": "job-123", "waitTimeoutMs": 3000},
        )
        mock_sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
