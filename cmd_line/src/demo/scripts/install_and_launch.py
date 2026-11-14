#!/usr/bin/env python3
"""Install one explicitly selected build output and launch the demo application."""
import argparse
from pathlib import Path

from jugg_build import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-dir', type=Path, required=True)
    args = parser.parse_args()
    directory = args.apk_dir.resolve()
    apks = sorted(directory.glob('*.apk'))
    if not apks or any(p.stat().st_size == 0 for p in apks):
        parser.error(f'No nonempty APK set found: {directory}')
    # Pass every split together and keep artifact selection independent of build policy.
    run(['adb', 'install-multiple', '-r', *apks], directory)
    run(['adb', 'shell', 'am', 'start', '-n', 'com.example.myapplication/.MainActivity'], directory)


if __name__ == '__main__':
    main()
