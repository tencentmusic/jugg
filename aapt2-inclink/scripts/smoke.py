#!/usr/bin/env python3
"""Exercise compile/link and retained inclink state without a device or Gradle."""
import argparse
import pathlib
import re
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=pathlib.Path)
    parser.add_argument('android_jar', type=pathlib.Path)
    args = parser.parse_args()
    binary, android = str(args.binary.resolve()), str(args.android_jar.resolve())

    def run(*cmd):
        return subprocess.run([binary, *map(str, cmd)], check=True, capture_output=True,
                              text=True, timeout=60).stdout

    with tempfile.TemporaryDirectory(prefix='inclink-smoke-') as temp:
        root = pathlib.Path(temp)
        values = root / 'res/values'
        values.mkdir(parents=True)
        layout = root / 'res/layout'
        layout.mkdir()
        (layout / 'main.xml').write_text('<View xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent"/>')
        manifest = root / 'AndroidManifest.xml'
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.smoke"><uses-sdk android:minSdkVersion="21"/><application/></manifest>')
        strings = values / 'strings.xml'
        strings.write_text('<resources><string name="message">before</string></resources>')
        baseline = root / 'baseline.apk'
        run('compile', '--dir', root / 'res', '-o', root / 'base.zip')
        run('link', '-I', android, '--manifest', manifest, '-o', baseline, root / 'base.zip')
        before = run('dump', 'resources', baseline)
        resource_id = re.search(r'resource (0x[0-9a-f]+) string/message', before).group(1)
        commands = [['inclink', '--load', '-I', android, '--manifest', manifest,
                     '-o', root / 'unused.apk', baseline]]
        for index, text in enumerate(['after', 'again'], 1):
            strings.write_text(f'<resources><string name="message">{text}</string><string name="added">new</string></resources>')
            run('compile', strings, '-o', root / f'update{index}.zip')
            commands.append(['inclink', '-I', android, '--manifest', manifest,
                             '-o', root / f'out{index}.apk', root / f'update{index}.zip'])
        protocol = ''.join('\n'.join(map(str, c)) + '\n\n' for c in commands) + 'quit\n\n'
        result = subprocess.run([binary, 'daemon'], input=protocol, capture_output=True,
                                text=True, timeout=60)
        assert result.returncode == 0, result.stderr
        assert 'Ready' in result.stdout, result.stdout
        assert 'Error' not in result.stderr and result.stderr.splitlines().count('Done') == 3, result.stderr
        for index, text in enumerate(['after', 'again'], 1):
            apk = root / f'out{index}.apk'
            dump = run('dump', 'resources', apk)
            assert f'resource {resource_id} string/message' in dump, dump
            assert f'"{text}"' in dump and 'string/added' in dump, dump
            with zipfile.ZipFile(apk) as archive:
                assert 'AndroidManifest.xml' in archive.namelist()
                assert 'resources.arsc' in archive.namelist()
                assert 'res/layout/main.xml' not in archive.namelist(), archive.namelist()
        failed = subprocess.run([binary, 'inclink', '-o', str(root / 'fail.apk'),
                                 '--manifest', str(manifest)], capture_output=True, text=True, timeout=60)
        assert failed.returncode != 0 and 'no cache data found' in failed.stderr, failed.stderr
    print('PASS: compile/link; daemon load + two increments; stable IDs; added resource; changed-file output; missing-cache failure')


if __name__ == '__main__':
    main()
