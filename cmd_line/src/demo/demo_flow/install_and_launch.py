#!/usr/bin/env python3
"""安装指定构建轮次的 APK 并启动演示应用。"""
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
    # 将本轮 APK 一起传给 adb，兼容 split APK。
    run(['adb', 'install-multiple', '-r', *apks], directory)
    run(['adb', 'shell', 'am', 'start', '-n', 'com.example.myapplication/.MainActivity'], directory)


if __name__ == '__main__':
    main()
