#!/usr/bin/env python3
"""Build the repository's inclink source with pinned dependency archives."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def stable_content_sha256(archive):
    """Hash archive entries without Gitiles' changing tar timestamps."""
    digest = hashlib.sha256()
    with tarfile.open(archive, 'r:gz') as tar:
        for member in sorted(tar.getmembers(), key=lambda item: item.name):
            digest.update(member.name.encode('utf-8'))
            digest.update(b'\0' + member.type + b'\0')
            digest.update(str(member.mode).encode('ascii') + b'\0')
            digest.update(member.linkname.encode('utf-8') + b'\0')
            if member.isfile():
                source = tar.extractfile(member)
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
    return digest.hexdigest()


def prepare(deps):
    deps.mkdir(parents=True, exist_ok=True)
    for item in json.loads((ROOT / 'scripts/dependencies.json').read_text()):
        archive = deps / (item['name'] + '.tar.gz')
        if not archive.exists():
            temporary = archive.with_suffix('.download')
            subprocess.run(['curl', '-L', '--fail', '--silent', '--show-error',
                            item['url'], '-o', str(temporary)], check=True)
            temporary.rename(archive)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != item['sha256']:
            expected_content = item.get('content_sha256')
            if expected_content is None or stable_content_sha256(archive) != expected_content:
                raise RuntimeError(
                    f'Checksum mismatch: {archive}; remove it and rerun to download again')
            print(f'Accepted non-reproducible archive metadata after content verification: {archive}',
                  file=sys.stderr)
        destination = deps / item['directory']
        if (destination / '.inclink-ready').exists():
            continue
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir()
        command = ['tar', 'xf', str(archive), '-C', str(destination)]
        if item['name'] == 'protobuf':
            command += ['--strip-components=1']
        subprocess.run(command, check=True)
        (destination / '.inclink-ready').touch()
    include = deps / 'include'
    include.mkdir(exist_ok=True)
    if not (include / 'android').exists():
        (include / 'android').symlink_to('../native')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jobs', type=int, default=8)
    parser.add_argument('--mode', choices=('debug', 'release'), required=True,
                        help='Build mode; must be explicitly selected')
    parser.add_argument('--build-number',
                        help='Version metadata embedded in the executable')
    parser.add_argument('--architecture', choices=('x86_64', 'arm64', 'universal'), default='x86_64')
    parser.add_argument('--deps-root', type=pathlib.Path,
                        default=ROOT / 'build/deps14',
                        help='Dependency cache and extracted source directory')
    parser.add_argument('--build-root', type=pathlib.Path,
                        help='CMake build and output directory (defaults to build/macos-<mode>)')
    args = parser.parse_args()
    if args.build_root is None:
        args.build_root = ROOT / f'build/macos-{args.mode}'
    if sys.platform != 'darwin':
        parser.error('This build entry point requires macOS')
    deps = args.deps_root.resolve()
    prepare(deps)
    output = args.build_root.resolve()
    architectures = ('x86_64', 'arm64') if args.architecture == 'universal' else (args.architecture,)
    binaries = []
    for architecture in architectures:
        architecture_output = output / architecture if args.architecture == 'universal' else output
        configure = ['cmake', '-S', str(ROOT), '-B', str(architecture_output),
                     f'-DCMAKE_BUILD_TYPE={args.mode.capitalize()}', f'-DCMAKE_OSX_ARCHITECTURES={architecture}',
                     '-DCMAKE_POLICY_VERSION_MINIMUM=3.5', f'-DAAPT2_DEPS_ROOT={deps}']
        if args.build_number:
            configure.append(f'-DAAPT2_BUILD_NUMBER={args.build_number}')
        subprocess.run(configure, check=True)
        subprocess.run(['cmake', '--build', str(architecture_output), '--target', 'aapt2-inclink',
                        '-j', str(args.jobs)], check=True)
        binaries.append(architecture_output / 'aapt2-inclink')
    binary = binaries[0]
    if args.architecture == 'universal':
        binary = output / 'aapt2-inclink'
        subprocess.run(['lipo', '-create', *map(str, binaries), '-output', str(binary)], check=True)
    if args.mode == 'release':
        subprocess.run(['strip', '-u', '-r', str(binary)], check=True)
        print(f'Release executable: {binary}')
    else:
        subprocess.run(['dsymutil', str(binary)], check=True)
        print(f'Debug executable: {binary}')


if __name__ == '__main__':
    main()
