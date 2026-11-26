#!/usr/bin/env python3
"""Build a Linux or Windows AAPT2 inclink target from pinned source archives."""
import argparse
import pathlib
import platform
import shlex
import shutil
import subprocess

from build_macos import ROOT, prepare
from check_host_candidate import check


def zig_toolchain(zig, target, output):
    zig = str(pathlib.Path(shutil.which(zig) or zig).resolve())
    version = subprocess.run([zig, 'version'], check=True, text=True,
                             capture_output=True).stdout.strip()
    if version != '0.16.0':
        raise SystemExit(f'Expected Zig 0.16.0, got {version}')
    # The OS/libc target must also apply when Zig builds its own C++ runtime.
    triple = {'linux': 'x86_64-linux-gnu.2.14',
              'windows': 'x86_64-windows.vista-gnu'}[target]
    wrappers = output / 'toolchain'
    wrappers.mkdir(parents=True, exist_ok=True)
    result = {}
    for name, command in [('cc', 'cc'), ('cxx', 'c++'), ('ar', 'ar'), ('ranlib', 'ranlib')]:
        arguments = [zig, command]
        if name in ('cc', 'cxx'):
            arguments += ['-target', triple, '-mcpu=baseline']
        wrapper = wrappers / name
        wrapper.write_text('#!/bin/sh\nexec ' + shlex.join(arguments) + ' "$@"\n')
        wrapper.chmod(0o755)
        result[name] = str(wrapper)
    print(f'Toolchain: Zig {version}, {triple}, CPU baseline', flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=('linux', 'windows'), required=True)
    parser.add_argument('--jobs', type=int, default=8)
    parser.add_argument('--mode', choices=('debug', 'release'), required=True)
    parser.add_argument('--build-number',
                        help='Version metadata embedded in the executable')
    parser.add_argument('--deps-root', type=pathlib.Path,
                        default=ROOT / 'build/deps14',
                        help='Dependency cache and extracted source directory')
    parser.add_argument('--build-root', type=pathlib.Path,
                        help='CMake build and output directory')
    parser.add_argument('--cc', help='Target C compiler or compiler wrapper')
    parser.add_argument('--cxx', help='Target C++ compiler or compiler wrapper')
    parser.add_argument('--ar', help='Target archiver or archiver wrapper')
    parser.add_argument('--ranlib', help='Target ranlib or ranlib wrapper')
    parser.add_argument('--zig', help='Zig 0.16.0 executable for pinned x86-64 cross targets')
    parser.add_argument('--objdump', default='objdump',
                        help='LLVM-compatible objdump for the release compatibility gate')
    parser.add_argument('--strip',
                        help='Target-compatible strip command; required for cross release builds')
    parser.add_argument('--protoc',
                        help='Host protoc used to generate sources during cross compilation')
    args = parser.parse_args()

    if args.build_root is None:
        args.build_root = ROOT / f'build/{args.target}-{args.mode}'
    output = args.build_root.resolve()
    if args.zig:
        if any((args.cc, args.cxx, args.ar, args.ranlib)):
            parser.error('--zig cannot be combined with explicit compiler tools')
        for name, value in zig_toolchain(args.zig, args.target, output).items():
            setattr(args, name, value)

    cross_build = platform.system().lower() != args.target
    if cross_build and not all((args.cc, args.cxx, args.ar, args.ranlib, args.protoc)):
        parser.error('--cc, --cxx, --ar, --ranlib and --protoc are required for a cross build')
    strip = args.strip
    if args.mode == 'release' and strip is None and not cross_build:
        strip = 'strip'
    if args.mode == 'release' and strip is None:
        parser.error('--strip is required for a cross release build')

    deps = args.deps_root.resolve()
    prepare(deps)
    configure = [
        'cmake', '-S', str(ROOT), '-B', str(output),
        f'-DCMAKE_SYSTEM_NAME={args.target.capitalize()}',
        f'-DCMAKE_BUILD_TYPE={args.mode.capitalize()}',
        '-DCMAKE_POLICY_VERSION_MINIMUM=3.5',
        f'-DAAPT2_DEPS_ROOT={deps}',
    ]
    for name, value in (
            ('CMAKE_C_COMPILER', args.cc),
            ('CMAKE_CXX_COMPILER', args.cxx),
            ('CMAKE_AR', args.ar),
            ('CMAKE_RANLIB', args.ranlib),
            ('CMAKE_C_COMPILER_AR', args.ar),
            ('CMAKE_CXX_COMPILER_AR', args.ar),
            ('CMAKE_C_COMPILER_RANLIB', args.ranlib),
            ('CMAKE_CXX_COMPILER_RANLIB', args.ranlib)):
        if value:
            configure.append(f'-D{name}={value}')
    if args.build_number:
        configure.append(f'-DAAPT2_BUILD_NUMBER={args.build_number}')
    if args.protoc:
        configure.append(f'-DAAPT2_PROTOC_EXECUTABLE={pathlib.Path(args.protoc).resolve()}')
    subprocess.run(configure, check=True)
    subprocess.run([
        'cmake', '--build', str(output), '--target', 'aapt2-inclink',
        '-j', str(args.jobs),
    ], check=True)
    binary = output / ('aapt2-inclink.exe' if args.target == 'windows' else 'aapt2-inclink')
    if args.mode == 'release':
        subprocess.run([strip, str(binary)], check=True)
        check(binary, args.target, args.objdump, args.build_number)
    print(f'Target executable: {binary}')


if __name__ == '__main__':
    main()
