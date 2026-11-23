#!/usr/bin/env python3
"""Install the APKs from one build and launch the demo."""
import argparse
from pathlib import Path

from flow_common import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-dir', type=Path, required=True)
    args = parser.parse_args()
    directory = args.apk_dir.resolve()
    apks = sorted(directory.glob('*.apk'))
    if not apks or any(p.stat().st_size == 0 for p in apks):
        parser.error(f'No nonempty APK set found: {directory}')
    # Pass all APKs together to support split installs.
    run(['adb', 'install-multiple', '-r', *apks], directory)
    run(['adb', 'shell', 'am', 'start', '-n', 'com.example.myapplication/.MainActivity'], directory)


if __name__ == '__main__':
    main()
