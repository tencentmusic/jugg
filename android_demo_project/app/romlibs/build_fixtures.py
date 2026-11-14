#!/usr/bin/env python3
"""Build separate framework and ROM helper jars using a JDK, without an Android SDK."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parent
java_home = os.environ.get('JAVA_HOME')
bin_dir = Path(java_home) / 'bin' if java_home else Path('/usr/bin')
with tempfile.TemporaryDirectory(prefix='jugg-rom-fixtures-') as temporary:
    classes = Path(temporary) / 'classes'
    classes.mkdir()
    subprocess.run([str(bin_dir / 'javac'), '--release', '8', '-d', str(classes),
                    *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
    for name, package in [('rom_framework.jar', 'android'), ('rom_sdk.jar', 'com')]:
        subprocess.run([str(bin_dir / 'jar'), 'cf', str(root / name), '-C', str(classes), package], check=True)
