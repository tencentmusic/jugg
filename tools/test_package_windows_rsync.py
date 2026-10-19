"""Verify the archive and integrity contract consumed by the JVM runtime loader."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_windows_rsync import package


class RuntimePackageTest(unittest.TestCase):
    def test_native_package_uses_system_ssh_without_redistributing_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'staged'
            (source / 'licenses').mkdir(parents=True)
            (source / 'provenance.json').write_text('{}')
            (source / 'rsync.exe').write_bytes(b'fixture')
            archive = root / 'runtime.zip'
            package(source, archive, 'oc-rsync', 'rsync.exe', '', '', system_ssh=True)
            with zipfile.ZipFile(archive) as bundle:
                manifest = json.loads(bundle.read('runtime.json'))
                self.assertTrue(manifest['systemSsh'])
                self.assertNotIn('ssh', manifest)
                self.assertEqual({'provenance.json', 'rsync.exe'}, set(manifest['sha256']))

    def test_archive_contains_exact_hashes_and_selected_engine(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'staged'
            (source / 'bin').mkdir(parents=True)
            (source / 'licenses').mkdir()
            for name in ['rsync.exe', 'ssh.exe', 'cygpath.exe', 'cygwin1.dll']:
                (source / 'bin' / name).write_bytes(name.encode())
            (source / 'provenance.json').write_text('{}')
            (source / 'licenses' / 'COPYING').write_text('test fixture')
            archive = root / 'runtime.zip'
            package(source, archive, 'cygwin', 'bin/rsync.exe', 'bin/ssh.exe', 'bin/cygpath.exe')
            with zipfile.ZipFile(archive) as bundle:
                manifest = json.loads(bundle.read('runtime.json'))
                self.assertEqual('cygwin', manifest['engine'])
                self.assertEqual(set(bundle.namelist()) - {'runtime.json'}, set(manifest['sha256']))
                for name, expected in manifest['sha256'].items():
                    self.assertEqual(expected, hashlib.sha256(bundle.read(name)).hexdigest())

    def test_rejects_output_inside_staged_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / 'licenses').mkdir()
            (source / 'provenance.json').write_text('{}')
            (source / 'tool.exe').write_bytes(b'fixture')
            with self.assertRaises(ValueError):
                package(source, source / 'runtime.zip', 'oc-rsync', 'tool.exe', 'tool.exe', '')


if __name__ == '__main__':
    unittest.main()
