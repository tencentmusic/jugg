from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'demo/jugg_ci'))
from build import check_changed_files_fallback, detect_repo_fallback_reason


class DemoPolicyTest(unittest.TestCase):
    def test_incremental_file_limit_includes_100_but_rejects_101(self):
        files = [{'type': 'Kotlin', 'file': __file__}] * 100
        self.assertIsNone(check_changed_files_fallback(files))
        self.assertIsNotNone(check_changed_files_fallback(files + files[:1]))

    def test_unknown_build_file_and_missing_inputs_reject_incremental(self):
        for change in ({'type': 'BuildFile', 'file': __file__},
                       {'type': 'Unknown', 'file': __file__},
                       {'type': 'Kotlin', 'file': '/missing/input.kt'}):
            with self.subTest(change=change):
                self.assertIsNotNone(check_changed_files_fallback([change]))

    def test_hilt_falls_back_after_editing_annotated_source(self):
        with TemporaryDirectory() as directory:
            source = Path(directory) / 'Fixture.kt'
            files = [{'type': 'Kotlin', 'file': str(source)}]
            for annotation in ('Inject', 'AndroidEntryPoint', 'HiltAndroidApp'):
                with self.subTest(annotation=annotation):
                    source.write_text(f'@{annotation}\nclass Fixture')
                    reason = detect_repo_fallback_reason('+class Fixture', files, 'debug')
                    self.assertIn(f'@{annotation}', reason or '')

    def test_keep_annotation_diff_requires_gradle_only_in_release(self):
        for patch in ('+@Keep', '-@Keep'):
            with self.subTest(patch=patch):
                self.assertIsNone(detect_repo_fallback_reason(patch, [], 'debug'))
                self.assertIn('@Keep', detect_repo_fallback_reason(patch, [], 'release') or '')
