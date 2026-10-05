import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import export_prompt_pack


class ExportPromptPackTest(unittest.TestCase):
    def test_english_masters_export_every_case_without_answer_sections(self):
        expected_counts = {"cli": 47, "hooks": 7, "instrument": 30, "ui-verify": 43}
        with tempfile.TemporaryDirectory() as tmp_dir:
            for kind, count in expected_counts.items():
                with self.subTest(kind=kind):
                    output = export_prompt_pack.export_pack(kind, Path(tmp_dir))
                    cases = (output / "cases.md").read_text(encoding="utf-8")
                    readme = (output / "README.md").read_text(encoding="utf-8")
                    prompt = (output / "PROMPT.md").read_text(encoding="utf-8")
                    report = (output / "report.md").read_text(encoding="utf-8")
                    self.assertEqual(cases.count("\n### "), count)
                    self.assertEqual(report.count("\n### "), count)
                    self.assertNotIn("Expected:", cases)
                    self.assertNotIn("Prompt:", cases)
                    for content in (cases, readme, prompt, report):
                        self.assertFalse(any("\u4e00" <= char <= "\u9fff" for char in content))

    def test_ui_skip_and_hooks_feedback_rules_survive_export(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            ui = export_prompt_pack.export_pack("ui-verify", Path(tmp_dir))
            hooks = export_prompt_pack.export_pack("hooks", Path(tmp_dir))
            ui_prompt = (ui / "PROMPT.md").read_text(encoding="utf-8")
            hooks_prompt = (hooks / "PROMPT.md").read_text(encoding="utf-8")
            hooks_cases = (hooks / "cases.md").read_text(encoding="utf-8")
            self.assertIn("expected safety-gate skip may earn full credit", ui_prompt)
            self.assertIn("mark `FAIL`, not `SKIP`", hooks_prompt)
            self.assertIn("verbatim feedback", hooks_prompt)
            self.assertIn("Do not run commands, edit files, compile, deploy", hooks_cases)
            self.assertIn("Cursor/Gemini", hooks_cases)
            self.assertIn("Codex/Claude", hooks_cases)

    def test_chinese_prompt_remains_readable_during_transition(self):
        lines = ["Prompt：执行一次检查。", "期望：应当成功。"]
        self.assertEqual(export_prompt_pack.extract_prompt(lines, 0), "执行一次检查。")


if __name__ == "__main__":
    unittest.main()
