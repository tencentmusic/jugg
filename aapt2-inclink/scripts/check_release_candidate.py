#!/usr/bin/env python3
"""Validate a macOS Universal Release binary before replacing the prebuilt."""
import argparse
import hashlib
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(*command):
    return subprocess.run(command, check=True, text=True, capture_output=True).stdout


def version(binary):
    executable = binary
    with tempfile.TemporaryDirectory(prefix='aapt2-version-') as temp:
        if not binary.stat().st_mode & 0o111:
            executable = pathlib.Path(temp) / binary.name
            executable.write_bytes(binary.read_bytes())
            executable.chmod(0o700)
        result = subprocess.run([str(executable), 'version'], check=True, text=True,
                                capture_output=True)
    return (result.stdout + result.stderr).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=pathlib.Path)
    parser.add_argument('--reference', type=pathlib.Path,
                        default=ROOT / 'src/main/resources/tools/darwin/aapt2-inclink-2.19.16')
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    reference = args.reference.resolve()
    if not candidate.is_file():
        parser.error(f'candidate does not exist: {candidate}')
    if not reference.is_file():
        parser.error(f'reference does not exist: {reference}')

    architectures = run('lipo', '-archs', str(candidate)).split()
    if architectures != ['x86_64', 'arm64']:
        raise SystemExit(f'candidate must be Universal x86_64 arm64, got: {" ".join(architectures)}')
    dependencies = [line.strip() for line in run('otool', '-L', str(candidate)).splitlines()
                    if line.startswith('\t')]
    allowed = ('/usr/lib/libSystem.B.dylib', '/usr/lib/libc++.1.dylib',
               '/usr/lib/libc++abi.dylib')
    unexpected = [line for line in dependencies if not line.startswith(allowed)]
    if unexpected:
        raise SystemExit('candidate has unsupported dynamic dependencies:\n' + '\n'.join(unexpected))
    if candidate.with_name(candidate.name + '.dSYM').exists():
        raise SystemExit(f'Release candidate must not have a dSYM: {candidate}.dSYM')
    candidate_version = version(candidate)
    reference_version = version(reference)
    if candidate_version != reference_version:
        raise SystemExit('candidate version metadata differs from reference:\n'
                         f'candidate: {candidate_version}\nreference: {reference_version}')

    candidate_hash = hashlib.sha256(candidate.read_bytes()).hexdigest()
    reference_hash = hashlib.sha256(reference.read_bytes()).hexdigest()
    print(f'candidate: {candidate}')
    print(f'candidate_sha256: {candidate_hash}')
    print(f'reference_sha256: {reference_hash}')
    print('architectures: x86_64 arm64')
    print('dynamic_dependencies: libSystem and platform libc++ runtime')
    print(f'version: {candidate_version}')
    print('release_candidate: PASS')


if __name__ == '__main__':
    main()
