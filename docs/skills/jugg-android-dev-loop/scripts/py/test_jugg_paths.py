import tempfile
import unittest
from pathlib import Path
from jugg_paths import global_root


class GlobalRootTest(unittest.TestCase):
    def test_locator_and_invalid_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            self.assertEqual(home / ".jugg", global_root(home))
            locator = home / ".juggdir"
            for text in ("invalid", "juggdir: relative", "juggdir: ", "other: /tmp", "juggdir: /a\njuggdir: /b"):
                locator.write_text(text, encoding="utf-8")
                self.assertEqual(home / ".jugg", global_root(home))
            target = home / "space 中文"
            locator.write_text("juggdir: " + str(target) + "\n", encoding="utf-8")
            self.assertEqual(target, global_root(home))
            locator.write_bytes(b"\xff")
            self.assertEqual(home / ".jugg", global_root(home))


if __name__ == "__main__":
    unittest.main()
