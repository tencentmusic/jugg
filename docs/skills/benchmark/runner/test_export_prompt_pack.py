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


if __name__ == "__main__":
    unittest.main()
