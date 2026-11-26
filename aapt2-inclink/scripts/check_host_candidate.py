#!/usr/bin/env python3
"""Check x86-64 host artifacts against the pre-cross-build compatibility contract."""
import argparse
import json
import pathlib
import re
import subprocess

BASELINE = pathlib.Path(__file__).with_name('host_compatibility.json')


def windows_imports(output):
    imports = {}
    current = None
    for line in output.splitlines():
        match = re.search(r'DLL Name: (\S+)', line)
        if match:
            current = match[1].lower()
            imports[current] = []
        elif current:
            match = re.fullmatch(r'\s+\d+\s+(\S+)\s*', line)
            if match:
                imports[current].append(match[1])
    return imports


def check_linux(output, baseline):
    errors = []
    if 'file format elf64-x86-64' not in output:
        errors.append('expected an x86-64 ELF')
    versions = re.findall(r'GLIBC_(\d+(?:\.\d+)+)', output)
    if not versions:
        errors.append('missing GLIBC version requirements')
    maximum = tuple(map(int, baseline['max_glibc'].split('.')))
    if any(tuple(map(int, version.split('.'))) > maximum for version in versions):
        errors.append('GLIBC requirements exceed ' + baseline['max_glibc'])
    dependencies = set(re.findall(r'NEEDED\s+(\S+)', output))
    unexpected = dependencies - set(baseline['libraries'])
    if unexpected:
        errors.append('new ELF dependencies: ' + ', '.join(sorted(unexpected)))
    return errors


def check_windows(output, baseline):
    errors = []
    if 'file format coff-x86-64' not in output:
        errors.append('expected an x86-64 PE')
    imports = windows_imports(output)
    if not imports or not all(imports.values()):
        errors.append('missing DLL imports or unrecognized import table')
    for dll, symbols in imports.items():
        allowed = baseline['imports'].get(dll, [])
        allowed = allowed + baseline.get('reviewed_additions', {}).get(dll, [])
        unexpected = set(symbols) - set(allowed)
        if unexpected:
            errors.append(f'new imports from {dll}: ' + ', '.join(sorted(unexpected)))
    for field in ('MajorOSystemVersion', 'MajorSubsystemVersion'):
        match = re.search(rf'{field}\s+(\d+)', output)
        if not match or int(match[1]) > 6:
            errors.append(f'{field} exceeds the old PE baseline')
    for field in ('MinorOSystemVersion', 'MinorSubsystemVersion'):
        match = re.search(rf'{field}\s+(\d+)', output)
        if not match or int(match[1]) != 0:
            errors.append(f'{field} differs from the old PE baseline')
    return errors


def check(binary, target, objdump='objdump', build_number=None):
    baseline = json.loads(BASELINE.read_text())
    output = subprocess.run([objdump, '-p', str(binary)], check=True,
                            text=True, capture_output=True).stdout
    errors = []
    if build_number and build_number.encode('ascii') not in pathlib.Path(binary).read_bytes():
        errors.append('build number differs from the packaged compatibility baseline')
    validate = check_linux if target == 'linux' else check_windows
    errors += validate(output, baseline[target])
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'{target} compatibility: PASS ({binary})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=pathlib.Path)
    parser.add_argument('--target', choices=('linux', 'windows'), required=True)
    parser.add_argument('--objdump', default='objdump')
    parser.add_argument('--build-number', help='Expected embedded build number')
    args = parser.parse_args()
    check(args.binary, args.target, args.objdump, args.build_number)


if __name__ == '__main__':
    main()
