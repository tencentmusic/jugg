import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import export_prompt_pack


class ExportPromptPackTest(unittest.TestCase):
    def test_cli_pack_includes_score_and_relative_path_rules(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            output_dir = export_prompt_pack.export_pack("cli", Path(tmp_dir))

            readme = (output_dir / "README.md").read_text(encoding="utf-8")
            prompt = (output_dir / "PROMPT.md").read_text(encoding="utf-8")
            report = (output_dir / "report.md").read_text(encoding="utf-8")

            self.assertIn("- Score: N / 5", readme)
            self.assertIn("不要在报告中写入本机绝对路径", readme)
            self.assertIn("Score", prompt)
            self.assertIn("- Score: N / 5", report)
            self.assertIn("| File | Case | Verdict | Score | Notes |", report)

    def test_ui_verify_pack_explains_expected_skip_scoring(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            output_dir = export_prompt_pack.export_pack("ui-verify", Path(tmp_dir))

            readme = (output_dir / "README.md").read_text(encoding="utf-8")
            prompt = (output_dir / "PROMPT.md").read_text(encoding="utf-8")

            self.assertIn("预期跳过", readme)
            self.assertIn("预期跳过", prompt)
            self.assertIn("可给满分", prompt)

    def test_hooks_pack_documents_sourceset_and_silent_allow_rules(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            output_dir = export_prompt_pack.export_pack("hooks", Path(tmp_dir))

            readme = (output_dir / "README.md").read_text(encoding="utf-8")
            prompt = (output_dir / "PROMPT.md").read_text(encoding="utf-8")
            cases = (output_dir / "cases.md").read_text(encoding="utf-8")
            report = (output_dir / "report.md").read_text(encoding="utf-8")

            self.assertIn("app/src/main/java/com/example/myapplication", readme)
            self.assertIn("hook_benchmark_scratch", readme)
            self.assertIn("预期静默放行", prompt)
            self.assertNotIn("Skipped:", readme)
            self.assertNotIn("Skipped:", report)
            self.assertIn("hook 反馈原文", readme)
            self.assertIn("绝对脚本路径", readme)
            self.assertIn("HOOKS-SOURCE", cases)
            self.assertIn("HookSourceTrigger.kt", cases)
            self.assertIn("HOOKS-NONSOURCE", cases)
            self.assertIn("HookShellTrigger.kt", cases)
            self.assertIn("不要执行任何命令、文件编辑", cases)
            self.assertIn("Cursor/Gemini", cases)
            self.assertIn("Codex/Claude", cases)
            self.assertIn("静默放行", cases)
            self.assertIn("stop hook 反馈不会出现在 shell/terminal/tool output", cases)
            self.assertIn("不要因为工具输出里没有 stop 文案就提前判 FAIL", cases)


if __name__ == "__main__":
    unittest.main()
