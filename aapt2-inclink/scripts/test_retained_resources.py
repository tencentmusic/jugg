#!/usr/bin/env python3
"""Verify retained binary references with real compile/link and daemon invocations."""
import argparse
import pathlib
import re
import struct
import subprocess
import tempfile
import unittest
import zipfile


class RetainedResourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='inclink-retained-')
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.manifest = self.root / 'AndroidManifest.xml'
        self.manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.retained"><uses-sdk android:minSdkVersion="21"/><application/></manifest>')
        values = self.root / 'res/values'
        values.mkdir(parents=True)
        (values / 'styles.xml').write_text('<resources><style name="Old" parent="@android:style/Theme.Material"/><string name="alias">@android:string/ok</string><array name="old_array"><item>@android:string/ok</item></array></resources>')
        self.run_tool('compile', '--dir', self.root / 'res', '-o', self.root / 'base.zip')
        original = self.root / 'original.apk'
        self.run_tool('link', '-I', ANDROID, '--manifest', self.manifest, '-o', original,
                      self.root / 'base.zip')
        dump = self.run_tool('dump', 'resources', original)
        alias = int(re.search(r'@(0x[0-9a-fA-F]+)', dump).group(1), 16)
        parent = int(re.search(r'parent=(?:[^\n]*?)(0x[0-9a-fA-F]+)', dump).group(1), 16)
        self.baseline = self.root / 'baseline.apk'
        with zipfile.ZipFile(original) as src, zipfile.ZipFile(self.baseline, 'w') as dst:
            for info in src.infolist():
                data = src.read(info.filename)
                if info.filename == 'resources.arsc':
                    self.assertIn(struct.pack('<I', parent), data)
                    data = data.replace(struct.pack('<I', parent), struct.pack('<I', 0x01830480))
                    self.assertIn(struct.pack('<I', alias), data)
                    data = data.replace(struct.pack('<I', alias), struct.pack('<I', 0x01049999))
                dst.writestr(info, data)

    def run_tool(self, *args):
        return subprocess.run([BINARY, *map(str, args)], check=True, capture_output=True,
                              text=True, timeout=60).stdout

    def compile_values(self, name, content):
        path = self.root / 'res/values' / (name + '.xml')
        path.write_text(content)
        output = self.root / (name + '.zip')
        self.run_tool('compile', path, '-o', output)
        return output

    def load(self, apk):
        return ['inclink', '--load', '-I', ANDROID, '--manifest', self.manifest,
                '-o', self.root / 'unused.apk', apk]

    def increment(self, flat, output):
        return ['inclink', '-I', ANDROID, '--manifest', self.manifest, '-o', output, flat]

    def daemon(self, commands, errors=0):
        protocol = ''.join('\n'.join(map(str, c)) + '\n\n' for c in commands) + 'quit\n\n'
        result = subprocess.run([BINARY, 'daemon'], input=protocol, capture_output=True,
                                text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(len(commands), result.stderr.splitlines().count('Done'), result.stderr)
        self.assertEqual(errors, result.stderr.splitlines().count('Error'), result.stderr)
        return result

    def test_load_accepts_dangling_references(self):
        self.daemon([self.load(self.baseline)])

    def test_unrelated_increments_and_reload_preserve_original_ids(self):
        flat = self.compile_values('strings', '<resources><string name="message">updated</string></resources>')
        layout_dir = self.root / 'res/layout'
        layout_dir.mkdir()
        layout = layout_dir / 'main.xml'
        layout.write_text('<View xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="7dp" android:layout_height="9dp"/>')
        layout_flat = self.root / 'layout.zip'
        self.run_tool('compile', layout, '-o', layout_flat)
        first, second, reloaded = [self.root / name for name in ('first.apk', 'second.apk', 'reloaded.apk')]
        self.daemon([self.load(self.baseline), self.increment(flat, first),
                     self.increment(layout_flat, second)])
        self.daemon([self.load(second), self.increment(flat, reloaded)])
        for apk in (first, second, reloaded):
            dump = self.run_tool('dump', 'resources', apk)
            self.assertIn('0x01830480', dump)
            self.assertIn('0x01049999', dump)
            self.assertIn('"updated"', dump)
        with zipfile.ZipFile(second) as archive:
            self.assertIn('res/layout/main.xml', archive.namelist())

    def test_redeclared_bad_parent_after_success_still_fails(self):
        good = self.compile_values('strings', '<resources><string name="message">updated</string></resources>')
        bad = self.compile_values('bad', '<resources><style name="Old" parent="@0x01830480"/></resources>')
        result = self.daemon([self.load(self.baseline), self.increment(good, self.root / 'good.apk'),
                              self.increment(bad, self.root / 'bad.apk')], errors=1)
        self.assertIn('failed linking references', result.stderr)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=pathlib.Path)
    parser.add_argument('android_jar', type=pathlib.Path)
    args = parser.parse_args()
    BINARY, ANDROID = str(args.binary.resolve()), str(args.android_jar.resolve())
    unittest.main(argv=[__file__])
