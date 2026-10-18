"""Protect the acceptance runner's credential-safe failure reporting."""

from pathlib import Path
import sys
import tempfile
import unittest

from windows_rsync_acceptance import SafeProbe, failure


class AcceptancePrivacyTest(unittest.TestCase):
    def test_diagnostics_and_argv_never_escape_failed_process(self):
        with tempfile.TemporaryDirectory() as directory:
            probe = SafeProbe({'engine': 'cygwin', 'rsync': sys.executable}, Path(directory))
            with self.assertRaises(RuntimeError) as caught:
                probe.run([sys.executable, '-c',
                           'import sys; sys.stderr.write("private-account private-key"); sys.exit(23)'])
            report = str(failure('transfer', caught.exception))
            self.assertIn('exit_23', report)
            self.assertNotIn('private-account', report)
            self.assertNotIn('private-key', report)
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_unexpected_errors_report_type_without_sensitive_message(self):
        report = failure('config', ValueError('private-account'))
        self.assertEqual({'phase': 'config', 'error_type': 'ValueError'}, report)
        self.assertEqual({'phase': 'config', 'error_type': 'RuntimeError'},
                         failure('config', RuntimeError('private-account')))


if __name__ == '__main__':
    unittest.main()
