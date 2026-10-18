"""Offline checks for the probe's observable process and report contract."""

import json
from pathlib import Path
import sys
import tempfile
import unittest

from windows_rsync_probe import Probe


class ProbeProcessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.probe = Probe({'engine': 'cygwin', 'rsync': sys.executable,
                            'timeout': 3}, self.output)

    def report(self):
        return json.loads((self.output / 'report.json').read_text(encoding='utf-8'))

    def test_nonzero_exit_is_failure_even_without_output(self):
        with self.assertRaises(AssertionError):
            self.probe.run([sys.executable, '-c', 'raise SystemExit(23)'])
        event = self.report()['events'][-1]
        self.assertEqual(23, event['exit_code'])
        self.assertEqual('', event['stdout'])

    def test_argv_is_not_interpreted_by_shell(self):
        value = '中文 space & (test) --exclude=*.iml'
        output = self.probe.run([sys.executable, '-X', 'utf8', '-c',
                                 'import sys; print(sys.argv[1])', value])
        self.assertEqual(value, output.strip())

    def test_start_failure_is_preserved_in_report(self):
        with self.assertRaises(OSError):
            self.probe.run([str(self.output / 'missing.exe')])
        self.assertIn('error', self.report()['events'][-1])

    def test_expected_failure_rejects_success(self):
        with self.assertRaises(AssertionError):
            self.probe.run([sys.executable, '-c', 'pass'], expected='nonzero')


if __name__ == '__main__':
    unittest.main()
